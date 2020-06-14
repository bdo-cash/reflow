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
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Feedback.Progress
import hobby.wei.c.reflow.Tracker.Runner
import hobby.wei.c.tool.Locker
import hobby.wei.c.tool.Locker.CodeZ

import scala.collection._

/**
  * 这里用于编写客户任务代码（重写`doWork()`方法）。注意不可以写异步任务和线程挂起等操作。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016
  */
abstract class Task protected() {
  private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)

  @volatile private var env$: Env = _
  @volatile private var thread: Thread = _
  @volatile private var aborted: Boolean = _
  @volatile private var working: Boolean = _

  /**
    * @return 与本任务相关的执行环境对象。
    */
  final def env = env$

  /**
    * @return 与本任务相关的接口及调度参数信息对象。
    */
  final def trat: Trait = env$.trat

  /**
    * 取得输入的value。
    *
    * @param key value对应的key。
    * @tparam T value的类型参数。
    * @return `Option[T]`.
    */
  final def input[T >: Null](key: String): Option[T] = env$.input.get(key)

  final def input[T >: Null <: AnyRef](kce: Kce[T]): Option[T] = input(kce.key)

  /**
    * 输出结果。
    *
    * @param key
    * @param value
    * @tparam T
    */
  final def output[T](key: String, value: T): Unit = env$.out.put(key, value)

  final def output[T <: AnyRef](kce: Kce[T], value: T): Unit = output(kce.key, value)

  final def output(map: Map[String, Any]): Unit = map.foreach { m: (String, Any) => output(m._1, m._2) }

  /**
    * 如果 {@link #isReinforceRequired()}为true, 则缓存一些参数用于再次执行时使用。
    * 问: 为何不直接用全局变量来保存?
    * 答: 下次并不重用本对象。
    *
    * @param key
    * @param value
    */
  final def cache[T](key: String, value: T): Unit = {
    require(env$.isPulseMode || env$.isReinforceRequired, "`cache()`操作必须在`requireReinforce()`之后。")
    env$.input.cache(key, null) // 仅用来测试key是否重复，null值不会被放进去。
    env$.cache(key, value)
  }

  final def cache[T <: AnyRef](kce: Kce[T], value: T): Unit = cache(kce.key, value)

  /**
    * 发送一个进度。
    *
    * @param _progress 进度百分比，取值区间[0, 1]，必须是递增的。
    */
  final def progress(_progress: Float): Unit = {
    val s = String.valueOf(_progress)
    val unit = math.pow(10, s.length - (s.indexOf('.') + 1)).toInt
    progress((_progress * unit).round, unit)
  }

  /**
    * 发送一个进度。
    *
    * @param step 进度的分子。必须是递增的。
    * @param sum  进度的分母。必须大于等于`step`且不可变。
    */
  final def progress(step: Int, sum: Int): Unit = env$.tracker.onTaskProgress(
    trat, Progress(sum, step.ensuring(_ <= /*这里必须可以等于*/ sum)), env$.out, env$.subDepth)

  /**
    * 请求强化运行。
    *
    * @return （在本任务或者本次调用）之前是否已经请求过, 同`isReinforceRequired()`。
    */
  final def requireReinforce() = env$.requireReinforce()

  /**
    * @return 当前是否已经请求过强化运行。
    */
  final def isReinforceRequired: Boolean = env$.isReinforceRequired

  /**
    * @return 当前是否处于强化运行阶段。
    */
  final def isReinforcing: Boolean = env$.isReinforcing

  /**
    * @return 当前任务所在的沙盒`Reflow`是否是`子``Reflow`（即：被包装在一个`Trait`里面被再次组装运行）。
    */
  final def isSubReflow: Boolean = env$.tracker.isSubReflow

  final def isAborted: Boolean = aborted

  /**
    * 如果认为任务失败, 则应该主动调用本方法来强制结束任务。
    * 不设计成直接声明{@link #doWork()}方法throws异常, 是为了让客户代码尽量自己处理好异常, 以防疏忽。
    *
    * @param e 自定义异常，可以为`null`。
    */
  final def failed(e: Exception = null) = {
    // 简单粗暴的抛出去, 由Runner统一处理。
    // 这里不抛出Exception的子类, 是为了防止被客户代码错误的给catch住。
    // 但是exec()方法catch了本Error并转换为正常的Assist.FailedException。
    throw new FailedError(e)
  }

  /**
    * 如果子类在{@link #doWork()}中检测到了中断请求(如: 在循环里判断{@link #isAborted()}),
    * 应该在处理好了当前事宜、准备好中断的时候调用本方法以中断整个任务。
    */
  final def abortionDone = throw new AbortError()

  @throws[CodeException]
  @throws[AbortException]
  @throws[FailedException]
  private[reflow] final def exec(_env: Env, _runner: Runner): Boolean = {
    env$ = _env
    Locker.syncr {
      if (aborted) return false
      thread = Thread.currentThread()
      working = true
    }
    try {
      exec$(_env, _runner)
    } catch {
      case e: FailedError =>
        throw new FailedException(e.getCause)
      case e: AbortError =>
        throw new AbortException(e.getCause)
      case e: Exception =>
        // 各种非正常崩溃的RuntimeException, 如NullPointerException等。
        throw new CodeException(e)
    } finally {
      // 不能置为false, 不然异步执行`exec$()`时，obort()请求传不到onAbort().
      // working = false // 节省一个`async`变量
      thread = null
    }
  }

  /**
    * @return 同步还是异步执行。
    */
  private[reflow] def exec$(_env: Env, _runner: Runner): Boolean = {
    // 这里反馈进度有两个用途: 1.Feedback subProgress; 2.并行任务进度统计。
    progress(0)
    doWork()
    progress(1)
    true
  }

  private[reflow] final def abort(): Unit = {
    Locker.syncr {
      aborted = true
    }
    if (working) { // || async
      try {
        onAbort()
      } finally {
        val t = thread
        // 这个中断信号对框架毫无影响：
        // 1. 对于外部对`sync()`的调用，只有外部调用`sync()`的线程对象发出的中断信号才对它起作用；
        // 2. 框架内部没有监听这个信号，`Tracker.interruptSync()`的实现也没有。
        if (t.nonNull) t.interrupt()
      }
    }
  }

  /**
    * 客户代码的扩展位置。<br>
    * 重写本方法应该在必要时监听`isAborted`或`Thread.interrupt()`信号从而及时中断。<br>
    * 注意：必须仅有同步代码，不可以执行异步操作（如果有异步需求，应该运用本`Reflow`框架的思想去实现并行化）。
    */
  protected def doWork(): Unit

  /**
    * 一般不需要重写本方法，只需在`doWork()`中检测`isAborted`标识即可。
    * 重写本方法以获得中断通知，并处理中断后的收尾工作。<br>
    * 注意：本方法的执行与`doWork()`并不在同一线程，需谨慎处理。<br>
    */
  protected def onAbort(): Unit = {}

  /**
    * 当同一个任务被放到多个{@link Reflow}中运行, 而某些代码段需要Class范围的串行化时, 应该使用本方法包裹。
    * 注意: 不要使用synchronized关键字, 因为它在等待获取锁的时候不能被中断, 而本方法使用{@link ReentrantLock}锁机制,
    * 当{@link #abort()}请求到达时, 如果还处于等待获取锁状态, 则可以立即中断。
    *
    * @param codes 要执行的代码段, 应包裹在{Locker.CodeZ#exec()}中。
    * @param scope 锁的作用范围。通常应该写某Task子类的ClassName.class, 而不要去{#getClass()},
    *              因为如果该类再有一个子类, 本方法在不同的实例返回不同的Class对象, scope不同, 同步目的将失效。
    * @tparam T 返回值类型。
    * @return {Locker.CodeZ#exec()}的返回值。
    */
  final def sync[T](scope: Class[_ <: Task])(codes: => T): T = sync(new CodeZ[T] {
    override def exec() = codes
  }, scope)

  final def sync[T](codes: Locker.CodeZ[T], scope: Class[_ <: Task]): T = {
    try {
      Locker.sync(codes, scope.ensuring(_.nonNull))
    } catch {
      case e: InterruptedException =>
        assert(isAborted)
        throw new AbortError(e)
    }
  }
}

object Task {
  private[reflow] def apply(f: Context => Unit): Task = new Context {
    override protected def doWork(): Unit = f(this)
  }

  abstract class Context private[reflow]() extends Task
}
