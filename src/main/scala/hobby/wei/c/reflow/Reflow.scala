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

import java.util.concurrent.locks.ReentrantLock
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.log.Logger
import hobby.wei.c.reflow.Dependency._
import hobby.wei.c.reflow.Reflow.{P_NORMAL, Period}
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool.Locker

import scala.collection.{mutable, _}

/**
  * 任务[串并联]组合调度框架。
  * <p><b><i>
  * 概述
  * </i></b>
  * <p>
  * 本框架为简化[多{@link Task 任务}之间的数据流转和事件处理]的编码复杂度而生，通过[要求{@link Trait
 * 显式定义}任务的{@link Trait#requires() I}/{@link Trait#outs() O}]、基于{@link Key$
 * [关键字]和[数据类型]}的[预先&运行时输出]检查(深层泛型解析)策略、一致的{@link Feedback
 * 事件反馈}及错误处理接口的设计，实现了预期目标。
  * <p>
  * 此外还有优化的{@link Config 可配置} {@link Worker 线程池}、基于{@link #P_NORMAL 优先级}和{@link Period
 * 预估时长}的[按需的]任务装载机制、便捷的[{@link Scheduler#sync() 同}/异(默认)步]调用接口、巧妙的{@link Scheduler#abort()
 * 中断}策略、[浏览/{@link Task#requireReinforce() 强化}]运行模式、无依赖输出丢弃等设计，可实现线程的无阻塞高效利用、
  * 全局精准的任务管理、任务的自由串并联组合、内存的有效利用(丢弃)、以及数据的快速加载(浏览模式)，以满足实际项目需求。
  * <p><b><i>
  * 其它
  * </i></b>
  * <p>
  * 本框架的主要功能类似<a href="http://github.com/BoltsFramework/Bolts-Android">Facebook
  * Bolts</a>和<a href="https://github.com/ReactiveX/RxJava">RxJava</a>的部分功能，
  * 可以视为对它们[任务组合能力的]细粒度的扩展。
  * <p>
  * 本框架基于{@link java.util.concurrent.ThreadPoolExecutor
 * 线程池}实现而非{@link java.util.concurrent.ForkJoinPool
 * Fork/Join框架(JDK 1.7)}，并对前者作了改进以符合[先增加线程数到{@link Config#maxPoolSize()
 * 最大}，再入队列，空闲释放线程]这个基本逻辑；
  * 后者适用于计算密集型任务，但不适用于本框架的调度策略，也不适用于资源受限的设备(如：手机等)。
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
    case class Period(weight: Int) extends Val {
      private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)
      private var average = 0L
      private var count = 0L

      def average(duration: Long): Long = Locker.sync {
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
    * @param trat 打头的{Trait}。
    * @return 新的任务流。
    */
  def create(trat: Trait[_ <: Task]): Dependency = builder.next(trat)

  /**
    * 复制参数到新的任务流。
    *
    * @param dependency
    * @return
    */
  def create(dependency: Dependency): Dependency = builder.next(dependency)

//  def execute(_period: Period.Tpe, _priority: Int = P_NORMAL, _desc: String = null, _name: String = null)(_runner: => Unit): Unit = execute(
//    new Runnable {
//      override def run(): Unit = _runner
//    }, _period, _priority, _desc, _name)

  /**
    * 为{@link Runnable}提供运行入口，以便将其纳入框架的调度管理。
    *
    * @param _runner   包装要运行代码的{ @link Runnable}.
    * @param _period   同{ @link Trait#period()}.
    * @param _priority 同{ @link Trait#priority()}.
    * @param _desc     同{ @link Trait#description()}.
    * @param _name     同{ @link Trait#name()}.
    */
  def execute(_runner: Runnable, _period: Period.Tpe, _priority: Int = P_NORMAL, _desc: String = null, _name: String = null): Unit = {
    Worker.sPreparedBuckets.queue4(_period).offer(new Worker.Runner(new Trait.Adapter() {
      override protected def name() = if (_name.isNull || _name.isEmpty) super.name() else _name

      override protected def priority() = _priority

      override protected def period() = _period

      override protected def desc() = if (_desc.isNull) name$ else _desc

      override def newTask() = null
    }, _runner))
    Worker.scheduleBuckets()
  }

  private def builder = new Dependency()

  //////////////////////////////////////////////////////////////////////////////////////
  //********************************** Reflow  Impl **********************************//

  class Impl private[reflow](basis: Dependency.Basis, inputRequired: immutable.Map[String, Kce[_ <: AnyRef]]) extends Reflow {
    override def start(inputs: In, feedback: Feedback = new Feedback.Adapter, poster: Poster = null, outer: Env = null): Scheduler = {
      // requireInputsEnough(inputs, inputRequired) // 有下面的方法组合，不再需要这个。
      val required = inputRequired.mutable
      consumeTranSet(inputs.trans, required, check = true)
      val reqSet = required.values.toSet
      requireRealInEnough(reqSet, putAll(new mutable.AnyRefMap[String, Kce[_ <: AnyRef]], inputs.keys))
      val traitIn = new Trait.Input(inputs, reqSet, basis.first(true).get.priority$)
      // 第一个任务是不需要trim的，至少从第二个以后。
      // 不可以将参数放进basis的任何数据结构里，因为basis需要被反复重用。
      new Scheduler.Impl(basis, traitIn, inputs.trans, feedback, poster, outer).start$()
    }

    override def toTrait(_period: Period.Tpe, _priority: Int, _desc: String, _name: String = null, feedback: Feedback = null, poster: Poster = null) =
      new ReflowTrait(this, feedback, poster) {
        override protected def name() = if (_name.isNull || _name.isEmpty) super.name() else _name

        override protected def requires() = inputRequired.values.toSet

        override protected def outs() = basis.outs

        override protected def priority() = _priority

        override protected def period() = _period

        override protected def desc() = if (_desc.isNull || _desc.isEmpty) name$ else _desc
      }
  }
}

trait Reflow {
  /**
    * 启动任务。可并行启动多个。
    *
    * @param inputs   输入内容的加载器。
    * @param feedback 事件反馈回调接口。
    * @param poster   转移<code>feedback</code>的调用线程, 可为null.
    * @return true 启动成功, false 正在运行。
    */
  final def start(inputs: In, feedback: Feedback, poster: Poster): Scheduler = start(inputs, feedback, poster, null)
  private[reflow] def start(inputs: In, feedback: Feedback, poster: Poster, outer: Env = null): Scheduler

  @deprecated(message = "应该尽量使用{#toTrait(Period, int, String)}。", since = "0.0.1")
  def toTrait: ReflowTrait = toTrait(Period.SHORT, P_NORMAL, null)

  def toTrait(period: Period.Tpe, priority: Int, desc: String, name: String = null, feedback: Feedback = null, poster: Poster = null): ReflowTrait
}
