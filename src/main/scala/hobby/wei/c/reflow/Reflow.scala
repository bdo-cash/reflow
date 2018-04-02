/*
 * Copyright (C) 2016-present, Wei Chou(weichou2010@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hobby.wei.c.reflow

import java.util.concurrent._
import java.util.concurrent.locks.ReentrantLock
import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.log.Logger
import hobby.wei.c.reflow.Dependency._
import hobby.wei.c.reflow.Reflow.Period
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool.Locker

import scala.collection.{mutable, _}

/**
  * 任务[串/并联]组合调度框架。
  * <p>
  * 概述
  * <p>
  * 本框架为`简化复杂业务逻辑中`<i>多任务之间的数据流转和事件处理</i>的`编码复杂度`而生。通过`要求显式定义`任务的I/O、
  * 基于`关键字`和`值类型`分析的智能化`依赖管理`、一致的`运行调度`、`事件反馈`及`错误处理`接口等设计，实现了既定目标：`任务
  * 串/并联组合调度`。 数据即`电流`， 任务即`元件`。在简化编码复杂度的同时，确定的框架可以将原本杂乱无章、错综复杂的写法
  * 规范化，编码失误也极易被检测，这将大大增强程序的`易读性`、`健壮性`和`可扩展性`。
  * <p>
  * 此外还有优化的`可配置`线程池、基于`优先级`和`预估时长`的`按需的`任务装载机制、便捷的`同/异（默认）步`切换调度、巧妙的`中断
  * 策略`、任务的`无限`嵌套组装、`浏览/强化`运行模式、无依赖输出`丢弃`、事件反馈可`指定到线程`和对异常事件的`确定性分类`等设计，
  * 实现了线程的`无`阻塞高效利用、全局`精准`的任务管理、内存的`有效利用（垃圾丢弃）`、以及数据的`快速加载（浏览模式）`和进度的`策略化
  * 反馈`，极大地满足了大型项目的需求。
  * <p><i>
  * 相关
  * </i>
  * <p>
  * 本框架的主要功能类似<a href="http://github.com/BoltsFramework/Bolts-Android">Facebook
  * Bolts</a>和<a href="https://github.com/ReactiveX/RxJava">RxJava</a>，可以视为对它们[任务组合能力]的细粒度扩展，
  * 但更加严谨、高规格和`贴近实际项目需求`。
  * <p>
  * 本框架基于{@link java.util.concurrent.ThreadPoolExecutor
  * 线程池}实现而非{@link java.util.concurrent.ForkJoinPool
  * Fork/Join框架（JDK 1.7）}，并对前者作了改进以符合[先增加线程数到{@link Config#maxPoolSize
  * 最大}，再入队列，空闲释放线程]这个基本逻辑；
  * 后者适用于计算密集型任务，但不适用于本框架的设计目标，也不适用于资源受限的设备(如：手机等)。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 12/04/2015
  */
object Reflow {
  /**
    * 高优先级的任务将被优先执行(注意: 不是线程优先级)。在{@link Period}相同的情况下。
    *
    * @see #P_NORMAL
    */
  val P_HIGH = -10
  /**
    * 相同优先级的任务将根据提交顺序依次执行(注意: 不是线程优先级)。在{@link Period}相同的情况下。
    * <p>
    * 注意：并不是优先级高就必然能获得优先调度权，还取决于{@link Period}以及系统资源占用情况。
    *
    * @see Period#INFINITE
    * @see Config#maxPoolSize()
    */
  val P_NORMAL = 0
  /**
    * 低优先级的任务将被延后执行(注意: 不是线程优先级)。在{@link Period}相同的情况下。
    *
    * @see #P_NORMAL
    */
  val P_LOW = 10

  /**
    * 任务大概时长。
    */
  object Period extends Enumeration {
    type Tpe = Period
    /**
      * @param weight 辅助任务{Trait#priority() 优先级}的调度策略参考。
      */
    private[reflow] case class Period(weight: Int) extends Val with Ordering[Period] {
      private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)
      private var average = 0L
      private var count = 0L

      override def compare(x: Period, y: Period) = if (x.weight < y.weight) -1 else if (x.weight == y.weight) 0 else 1

      def average(duration: Long): Long = Locker.syncr {
        if (duration > 0) {
          val prevAvg = average
          val prevCnt = count
          average = (prevAvg * prevCnt + duration) / {
            count += 1
            count
          }
        }
        average
      }.get
    }
    /**
      * 任务执行时间：瞬间。
      */
    val TRANSIENT = Period(1)
    /**
      * 任务执行时间：很短。
      */
    val SHORT = Period(2)
    /**
      * 任务执行时间：很长。
      */
    val LONG = Period(5)
    /**
      * 任务执行时间：无限长。
      * <p>
      * 注意：只有在当前时长任务的优先级{@link #P_HIGH 最高}而{@link
      * #TRANSIENT}任务的优先级{@link #P_LOW 最低}时，才能真正实现优先于{@link
      * #TRANSIENT}执行。
      *
      * @see #weight
      */
    val INFINITE = Period(20)
  }

  private var _debugMode = true
  private var _config: Config = Config.DEF
  private var _logger: Logger = new Logger()

  /**
    * 设置自定义的{@link Config}. 注意: 只能设置一次。
    */
  def setConfig(conf: Config): Unit = {
    if (_config == Config.DEF && conf != Config.DEF) {
      _config = conf
      Worker.updateConfig(_config)
    }
  }

  def config = _config

  def setThreadResetor(resetor: ThreadResetor): Unit = Worker.setThreadResetor(resetor)

  def setLogger(logr: Logger): Unit = _logger = logr

  def logger = _logger

  def setDebugMode(b: Boolean): Unit = _debugMode = b

  def debugMode = _debugMode

  def shutdown(): Unit = Worker.sThreadPoolExecutor.shutdown()

  /**
    * 创建以参数开始的新任务流。
    *
    * @param trat  打头的`Trait`。
    * @param trans 转换器列表。
    * @return 新的任务流。
    */
  def create(trat: Trait[_ <: Task], trans: Transformer[_ <: AnyRef, _ <: AnyRef]*): Dependency = builder.next(trat)

  /**
    * 复制参数到新的任务流。
    *
    * @param dependency
    * @return
    */
  def create(dependency: Dependency): Dependency = builder.next(dependency)

  /**
    * 为简单代码段或`Runnable`提供运行入口，以便将其纳入框架的调度管理。
    *
    * @tparam V 执行的结果类型。
    * @param _runner   包装要运行代码。如果是`Runnable`，可以写`runnable.run()`。
    * @param _period   同`Trait#period()`。
    * @param _priority 同`Trait#priority()`。
    * @param _desc     同`Trait#description()`。
    * @param _name     同`Trait#name()`。
    */
  def submit[V](_runner: => V)(_period: Period.Tpe, _priority: Int = P_NORMAL, _desc: String = null, _name: String = null): Future[V] = {
    val future = new FutureTask[V](new Callable[V] {
      override def call() = _runner
    })
    Worker.sPreparedBuckets.queue4(_period).offer(new Worker.Runner(new Trait.Adapter() {
      override protected def name() = if (_name.isNull || _name.isEmpty) super.name() else _name

      override protected def priority() = _priority

      override protected def period() = _period

      override protected def desc() = if (_desc.isNull) name$ else _desc

      override def newTask() = null
    }, future))
    Worker.scheduleBuckets()
    future
  }

  private def builder = new Dependency()

  //////////////////////////////////////////////////////////////////////////////////////
  //********************************** Reflow  Impl **********************************//

  private[reflow] class Impl private[reflow](override val name: String, override val basis: Dependency.Basis, inputRequired: immutable.Map[String,
    Kce[_ <: AnyRef]], override val desc: String = null) extends Reflow(name: String, basis: Dependency.Basis, desc: String) with TAG.ClassName {
    override def start(inputs: In, feedback: Feedback = new Feedback.Adapter, poster: Poster = null, outer: Env = null): Scheduler = {
      // requireInputsEnough(inputs, inputRequired) // 有下面的方法组合，不再需要这个。
      val required = inputRequired.mutable
      val tranSet = inputs.trans.mutable
      val realIn = putAll(new mutable.AnyRefMap[String, Kce[_ <: AnyRef]], inputs.keys)
      consumeTranSet(tranSet, required, realIn, check = true, trim = true)
      val reqSet = required.values.toSet
      requireRealInEnough(reqSet, realIn)
      if (debugMode) logger.w("[start]required:%s, inputTrans:%s.", reqSet, tranSet)
      val traitIn = new Trait.Input(this, inputs, reqSet)
      // TODO: 需要增加全局记录功能。
      new Scheduler.Impl(this, traitIn, tranSet.toSet, feedback.wizh(poster), outer).start$()
    }

    override def torat(_period: Period.Tpe = basis.maxPeriod(), feedback: Feedback = null)(implicit poster: Poster = null) =
      new ReflowTrait(this, feedback.wizh(poster)) {
        override protected def name() = reflow.name

        override protected def requires() = inputRequired.values.toSet

        override protected def outs() = reflow.basis.outs

        override protected def period() = _period.ensuring(_ >= reflow.basis.maxPeriod())

        override protected def desc() = if (reflow.desc.isNull || reflow.desc.isEmpty) name$ else reflow.desc
      }
  }
}

abstract class Reflow(val name: String, val basis: Dependency.Basis, val desc: String = null) {
  require(name.nonEmpty)

  /**
    * 启动任务。可并行启动多个。
    *
    * @param inputs   输入内容的加载器。
    * @param feedback 事件反馈回调接口。
    * @param poster   转移<code>feedback</code>的调用线程, 可为null.
    * @return true 启动成功, false 正在运行。
    */
  final def start(inputs: In, feedback: Feedback)(implicit poster: Poster): Scheduler = start(inputs, feedback, poster, null)

  private[reflow] def start(inputs: In, feedback: Feedback, poster: Poster, outer: Env = null): Scheduler

  /**
    * 转换为一个`Trait`（用`Trait`将本`Reflow`打包）以便嵌套构建任务流。
    */
  def torat(period: Period.Tpe = basis.maxPeriod(), feedback: Feedback = null)(implicit poster: Poster = null): ReflowTrait
}
