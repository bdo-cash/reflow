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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.locks.{Condition, ReentrantLock}
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Dependency._
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.reflow.State._
import hobby.wei.c.reflow.Tracker.Runner
import hobby.wei.c.tool.{Locker, Snatcher}

import scala.collection._
import scala.collection.mutable.ListBuffer

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016;
  *          1.1, 31/01/2018
  */
private[reflow] abstract class Tracker(val outer: Option[Env], _cache: Cache = null, var inited: Boolean = false) {
  // 这两个变量，在浏览运行阶段会根据需要自行创建（任务可能需要缓存临时参数到cache中）；
  // 而在Reinforce阶段，会从外部传入。
  // 因此有这样的设计。
  //  private var inited = _inited
  private lazy val cache = if (_cache.isNull) new Cache else _cache
  private[reflow] lazy val reinforceRequired = new AtomicBoolean(false)

  def getCache(trat: String, sub: Option[Cache] = None): Cache = {
    if (!inited) {
      if (!synchronized(inited)) {
        outer.foreach { env =>
          env.tracker.getCache(env.trat.name$, Option(cache))
        }
        synchronized(inited = true)
      }
    }
    sub.foreach(cache.subs.putIfAbsent(trat, _))
    cache
  }

  def getState: State.Tpe

  def isSubReflow: Boolean = outer.isDefined

  def isReinforceRequired: Boolean = outer.fold(reinforceRequired.get)(_.isReinforceRequired)

  def isReinforcing: Boolean = outer.fold(getState == REINFORCING)(_.isReinforcing)

  def requireReinforce(): Boolean = outer.fold {
    if (!reinforceRequired.getAndSet(true)) {
      onRequireReinforce()
      false
    } else true
  }(_.requireReinforce())

  protected def onRequireReinforce(): Unit

  private[reflow] def onTaskStart(trat: Trait[_]): Unit

  private[reflow] def onTaskProgress(name: String, trat: Trait[_], progress: Float, out: Out, desc: String): Unit

  private[reflow] def onTaskComplete(trat: Trait[_], out: Out, flow: Out): Unit

  private[reflow] def performAbort(trigger: Runner, forError: Boolean, trat: Trait[_], e: Exception): Unit
}

private[reflow] class Cache {
  // 用`trat.name$`作为`key`, 同一个`Reflow`中，`name$`是不能重名的，因此该方法可靠。
  /** 子`Trait`的`Task`缓存用到的`Out`。 **/
  lazy val caches = new concurrent.TrieMap[String, Out]
  /** 子`Trait`的`Task`启动的`Reflow`对应的`Tracker`的`Cache`。 **/
  lazy val subs = new concurrent.TrieMap[String, Cache]
}

private[reflow] class SubReflowFeedback(env: Env, runner: Runner) extends Feedback {
  override def onStart(): Unit = env.tracker.onTaskStart(env.trat)

  override def onProgress(name: String, out: Out, count: Int, sum: Int, sub: Float, desc: String): Unit =
    env.tracker.onTaskProgress(name, env.trat, (count + sub) / sum, out, desc)

  override def onComplete(out: Out): Unit = env.tracker.onTaskComplete(env.trat, out, out)

  override def onUpdate(out: Out): Unit = env.tracker.onTaskComplete(env.trat, out, out)

  override def onAbort(): Unit = env.tracker.performAbort(runner, forError = false, env.trat, null)

  override def onFailed(name: String, e: Exception): Unit = env.tracker.performAbort(runner, forError = true, env.trat, e)
}

private[reflow] object SubReflowFeedback {
  def join(env: Env, runner: Runner, fb: Feedback): Feedback = {
    val feedback = new Feedback.Observable
    feedback.addObservers(new SubReflowFeedback(env, runner), fb)
    feedback
  }
}

private[reflow] object Tracker {
  private[reflow] final class Impl(val basis: Dependency.Basis, traitIn: Trait[_ <: Task], inputTrans: immutable.Set[Transformer[_, _]],
                                   state: Scheduler.State$, feedback: Feedback,
                                   outer: Option[Env], cache: Cache = null, inited: Boolean = false)
    extends Tracker(outer: Option[Env], cache: Cache, inited: Boolean) with Scheduler {
    private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)
    private lazy val buffer4Reports = new ListBuffer[() => Unit]
    private lazy val snatcher = new Snatcher()

    // 本数据结构是同步操作的, 不需要ConcurrentLinkedQueue。
    private val remaining = {
      val seq = new mutable.ListBuffer[Trait[_ <: Task]]
      copy(basis.traits, seq)
      seq
    }

    private val sum = remaining.length
    private lazy val reinforce = new mutable.ListBuffer[Trait[_ <: Task]]
    private lazy val runnersParallel = new concurrent.TrieMap[Runner, Any]
    private lazy val reinforceMode = new AtomicBoolean(false)
    private lazy val reinforceCaches = new concurrent.TrieMap[String, Out]
    private lazy val progress = new concurrent.TrieMap[String, Float]
    private lazy val sumParRunning = new AtomicInteger
    private lazy val reporter = new Reporter(feedback, sum)
    @volatile private var normalDone, reinforceDone: Boolean = _
    @volatile private var outFlowTrimmed = new Out(Map.empty[String, Key$[_]])
    @volatile private[reflow] var prevOutFlow, reinforceInput: Out = _

    private[reflow] def start(): Boolean = {
      // TODO: 应该先处理这个
      traitIn
      inputTrans
      if (tryScheduleNext(true)) {
        Worker.scheduleBuckets()
        true
      } else false
    }

    /**
      * 先于{@link #endRunner(Runner)}执行。
      */
    private def innerError(runner: Runner, e: Exception): Unit = {
      log.e("innerError")(runner.trat.name$)
      // 正常情况下是不会走的，仅用于测试。
      performAbort(runner, forError = true, runner.trat, e)
    }

    private def endRunner(runner: Runner): Unit = {
      log.w("endRunner")(runner.trat.name$)
      runnersParallel -= runner
      if (runnersParallel.isEmpty && state.get$ != ABORTED && state.get$ != FAILED) {
        assert(sumParRunning.get == 0)
        progress.clear()
        if (basis.stepOf(runner.trat) < 0) {
          doTransform(basis.transGlobal.get(runner.trat.name$),,, global = true)
        } else Locker.sync {
          remaining.remove(0)
        }
        tryScheduleNext(false)
      }
    }

    // 必须在进度反馈完毕之后再下一个, 否则可能由于线程优先级问题, 导致低优先级的进度没有反馈完,
    // 而新进入的高优先级任务又要争用同步锁, 造成死锁的问题。
    private def tryScheduleNext(veryBeginning: Boolean): Boolean = Locker.sync {
      if (!veryBeginning) {
        if (remaining.isEmpty) {
          if (reinforceRequired.get()) {
            copy(reinforce /*注意写的时候没有synchronized*/ , remaining)
            outFlowTrimmed = reinforceInput
            reinforceMode.set(true)
            reinforce.clear()
          } else return false // 全部运行完毕
        }
      }
      assert(state.get == PENDING /*start()的时候已经PENDING了*/
        || state.forward(PENDING)
        || state.forward(REINFORCE_PENDING))

      val trat = if (veryBeginning) traitIn else remaining.head // 不poll(), 以备requireReinforce()的copy().
      if (trat.isParallel) {
        val parallel = trat.asParallel
        val runners = new ListBuffer[Runner]
        parallel.traits().foreach { t =>
          runners += new Runner(this, t)
          // 把并行的任务put进去，不然计算子进度会有问题。
          progress.put(t.name$, 0f)
        }
        runners.foreach(r => runnersParallel += ((r, 0)))
      } else {
        //progress.put(trat.name$, 0f)
        runnersParallel += ((new Runner(this, trat), 0))
      }
      sumParRunning.set(runnersParallel.size)
      resetOutFlow(new Out(basis.outsFlowTrimmed(trat.name$)))
      // 在调度之前获得结果比较保险
      val hasMore = sumParRunning.get() > 0
      runnersParallel.foreach { kv =>
        val runner = kv._1

        fdsfdsfdsfsdfsaf
        // TODO: 看 runner 是不是一个新的 Reflow。当然也可以不在这里处理，而是
        // 重构 runner 不立即回调 endMe，等到 runner 所在的整个 Reflow 结束才 endMe。
        // 但这种方式应该在前面 new Runner(this, t) 时使用不同的 Runner。
        import Period._
        runner.trat.period$ match {
          case INFINITE => Worker.sPreparedBuckets.sInfinite.offer(runner)
          case LONG => Worker.sPreparedBuckets.sLong.offer(runner)
          case SHORT => Worker.sPreparedBuckets.sShort.offer(runner)
          case TRANSIENT => Worker.sPreparedBuckets.sTransient.offer(runner)
        }
      }
      // 不写在这里，原因见下面方法本身：写在这里几乎无效。
      // scheduleBuckets()
      hasMore
    }.get

    override protected def onRequireReinforce(): Unit = Locker.sync {
      copy(remaining, reinforce)
      reinforceInput = prevOutFlow
    }

    private[reflow] def cache(trat: Trait[_], create: Boolean): Out = {
      Option(reinforceCaches.get(trat.name$)).getOrElse[Out] {
        if (create) {
          val cache = new Out(Helper.Keys.empty())
          reinforceCaches.put(trat.name$, cache)
          cache
        } else null
      }
    }

    private def resetOutFlow(flow: Out): Unit = {
      Locker.sync {
        prevOutFlow = outFlowTrimmed
        outFlowTrimmed = flow
      }
      joinOutFlow(prevOutFlow)
    }

    private def joinOutFlow(flow: Out): Unit = Locker.sync(outFlowTrimmed).get
      .putWith(flow._map, flow._nullValueKeys, ignoreDiffType = true, fullVerify = false)

    private def verifyOutFlow(): Unit = if (debugMode) Locker.sync(outFlowTrimmed).get.verify()

    private def performAbort(trigger: Runner, forError: Boolean, trat: Trait[_], e: Exception) {
      if (state.forward(if (forError) FAILED else ABORTED)) {
        runnersParallel.foreach { r =>
          val runner = r._1
          runner.abort()
          Monitor.abortion(if (trigger.isNull) null else trigger.trat.name$, runner.trat.name$, forError)
        }
        if (forError) postReport(reporter.reportOnFailed(trat.name$, e))
        else postReport(reporter.reportOnAbort())
      } else if (state.abort()) {
        // 已经到达COMPLETED/REINFORCE阶段了
      } else {
        // 如果本方法被多次被调用，则会进入本case. 虽然逻辑上并不存在本case, 但没有影响。
        // Throws.abortForwardError();
      }
      interruptSync(true /*既然是中断，应该让reinforce级别的sync请求也终止*/)
    }

    @deprecated(message = "已在{Impl}中实现, 本方法不会被调用。", since = "0.0.1")
    override def sync(): Out = ???

    @throws[InterruptedException]
    override def sync(reinforce: Boolean, milliseconds: Long): Out = {
      val start = System.currentTimeMillis
      Locker.sync(new Locker.CodeC[Out](1) {
        @throws[InterruptedException]
        override protected def exec(cons: Array[Condition]) = {
          // 不去判断mState是因为任务流可能会失败
          while (!(if (reinforce) reinforceDone else normalDone)) {
            if (milliseconds == -1) {
              cons(0).await()
            } else {
              val delta = milliseconds - (System.currentTimeMillis() - start)
              if (delta <= 0 || !cons(0).await(delta, TimeUnit.MILLISECONDS)) {
                throw new InterruptedException()
              }
            }
          }
          outFlowTrimmed
        }
      }, lock)
    }.get

    private def interruptSync(reinforce: Boolean) {
      normalDone = true
      if (reinforce) reinforceDone = true
      try {
        Locker.sync(new Locker.CodeC[Unit](1) {
          @throws[InterruptedException]
          override protected def exec(cons: Array[Condition]): Unit = {
            cons(0).signalAll()
          }
        }, lock)
      } catch {
        case _: Exception => // 不可能抛异常
      }
    }

    override def abort(): Unit = performAbort(null, forError = false, null, null)

    override def getState = state.get

    @deprecated(message = "不要调用。", since = "0.0.1")
    override def isDone = ???

    /**
      * 每个Task都执行。
      */
    override private[reflow] def onTaskStart(trat: Trait[_]): Unit = {
      if (reinforceMode.get) state.forward(REINFORCING)
      else {
        val step = basis.stepOf(trat)
        if (step >= 0) {
          if (state.forward(EXECUTING))
          // 但反馈有且只有一次（上面forward方法只会成功一次）
            if (step == 0) {
              postReport(reporter.reportOnStart())
            } else {
              // progress会在任务开始、进行中及结束时report，这里do nothing.
            }
        }
      }
    }

    override private[reflow] def onTaskProgress(name: String, trat: Trait[_], progress: Float, out: Out, desc: String): Unit = {
      // 因为对于REINFORCING, Task还是会进行反馈，但是这里需要过滤掉。
      if (!reinforceMode.get) {
        val step = basis.stepOf(trat)
        if (step >= 0) { // 过滤掉input任务
          Locker.syncr { // 为了保证并行的不同任务间进度的顺序性，这里还必须得同步。
            val subPogres = subProgress(trat, progress)
            buffer4Reports += (() => reporter.reportOnProgress(name, step, subPogres, out, desc))
          }
          postReport() // 注意就这一个地方写法不同
        }
      }
    }

    // 注意：本方法为单线程操作。
    private def subProgress(trat: Trait[_], pogres: Float): Float = {
      def result: Float = progress.values.sum /*reduce(_ + _)*/ / progress.size

      val value = between(0, pogres, 1)
      // 如果只有一个任务，单线程，不需要同步；
      // 而如果是多个任务，那对于同一个trat，总是递增的（即单线程递增），所以
      // 总体上，即使是并行，那也是发生在不同的trat之间（即不同的trat并行递增），推导出
      // progress.values.sum也是递增的，因此也不需要全局同步。
      if (progress.size <= 1) value
      else {
        if (pogres > 0) {
          assert(progress.contains(trat.name$))
          progress.put(trat.name$, value)
          result
        } else result
      }
    }

    override private[reflow] def onTaskComplete(trat: Trait[_], out: Out, flow: Out): Unit = {
      val step = basis.stepOf(trat)
      if (step < 0) resetOutFlow(flow)
      else joinOutFlow(flow)
      // 由于不是在这里移除(runnersParallel.remove(runner)), 所以不能用这个判断条件：
      //(runnersParallel.size == 1 && runnersParallel.contains(runner))
      if (sumParRunning.decrementAndGet == 0) {
        不应该在这里
        ， 而应该在 endrunner
          verifyOutFlow()
        Monitor.complete(step, out, flow, outFlowTrimmed)
        if (reinforceMode.get) {
          if (Locker.syncr(remaining.length).get == 1) { // 当前是最后一个
            Monitor.assertStateOverride(state.get, UPDATED, state.forward(UPDATED))
            interruptSync(true)
            // 即使放在interruptSync()的前面，也不能保证事件的到达会在sync()返回结果的前面，因此干脆放在后面算了。
            postReport(reporter.reportOnUpdate(outFlowTrimmed))
          }
        } else if (step == sum - 1) {
          Monitor.assertStateOverride(state.get, COMPLETED, state.forward(COMPLETED))
          interruptSync(!reinforceRequired.get())
          postReport(reporter.reportOnComplete(outFlowTrimmed)) // 注意参数，因为这里是complete.
        } else {
          // 单步任务的完成仅走进度(已反馈，不在这里反馈), 而不反馈完成事件。
        }
      }
    }

    private def postReport(action: => Unit): Unit = {
      Locker.syncr(buffer4Reports += (() => action))
      snatcher.tryOn {
        while (Locker.syncr(buffer4Reports.nonEmpty).get) {
          val f = Locker.syncr(buffer4Reports.remove(0)).get
          f()
        }
      }
    }
  }

  class Runner(tracker: Tracker, override val trat: Trait[_ <: Task]) extends Worker.Runner(trat: Trait[_ <: Task], null) with Equals {
    private implicit lazy val TAG: LogTag = new LogTag(trat.name$)

    @volatile private var task: Task = _
    @volatile private var _abort: Boolean = _
    private var timeBegin: Long = _

    override def equals(any: scala.Any) = super.equals(any)

    override def canEqual(that: Any) = super.equals(that)

    override def hashCode() = super.hashCode()

    // 这个场景没有使用synchronized, 跟Task.abort()场景不同。
    def abort(): Unit = {
      // 防止循环调用
      if (_abort) return
      _abort = true
      if (task.nonNull) task.abort()
    }

    override def run(): Unit = {
      var working = false
      try {
        task = trat.newTask()
        // 判断放在mTask的创建后面, 配合abort()中的顺序。
        if (_abort) onAbort(completed = false)
        else {
          onStart()
          val input = new Out(trat.requires$)
          log.i("input: %s", input)
          input.fillWith(tracker.prevOutFlow)
          val cached = tracker.cache(trat, create = false)
          if (cached.nonNull) input.cache(cached)
          val out = new Out(trat.outs$)
          task.env(tracker, trat, input, out)
          working = true
          log.i("111111111111")
          task.exec()
          log.i("222222222222")
          log.i("out: %s", out)
          val map = out._map.concurrent
          val nulls = out._nullValueKeys.concurrent
          log.w("doTransform, prepared:")
          doTransform(tracker.basis.transformers(trat.name$), map, nulls, global = false)
          log.w("doTransform, done.")
          val dps = tracker.basis.dependencies.get(trat.name$)
          log.i("dps: %s", dps.get)
          val flow = new Out(dps.getOrElse(Map.empty[String, Key$[_]]))
          log.i("flow prepared: %s", flow)
          flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
          log.w("flow done: %s", flow)
          working = false
          onComplete(out, flow)
          if (_abort) onAbort(completed = true)
        }
      } catch {
        case e: Exception =>
          log.i("exception:%s", e)
          if (working) {
            e match {
              case _: AbortException => // 框架抛出的, 表示成功中断
                onAbort(completed = false)
              case e: FailedException =>
                onFailed(e.getCause.as[Exception])
              case e: CodeException => // 客户代码问题
                onException(e)
              case _ =>
                onException(new CodeException(e))
            }
          } else {
            innerError(e)
          }
      } finally {
        endMe()
      }
    }

    private def onStart() {
      tracker.onTaskStart(trat)
      timeBegin = System.currentTimeMillis
    }

    private def onComplete(out: Out, flow: Out) {
      Monitor.duration(trat.name$, timeBegin, System.currentTimeMillis, trat.period$)
      tracker.onTaskComplete(trat, out, flow)
    }

    // 人为触发，表示任务失败
    private def onFailed(e: Exception) {
      log.i(e)
      abortAction(e).run()
    }

    // 客户代码异常
    private def onException(e: CodeException) {
      log.w(e)
      abortAction(e).run()
    }

    private def onAbort(completed: Boolean) {
      tracker.performAbort(this, forError = false, trat, null)
    }

    private def abortAction(e: Exception): Runnable = new Runnable {
      override def run(): Unit = {
        if (!_abort) _abort = true
        tracker.performAbort(Runner.this, forError = true, trat, e)
      }
    }

    private def innerError(e: Exception) {
      log.e(e)
      tracker.innerError(this, e)
      throw new InnerError(e)
    }

    private def endMe() {
      tracker.endRunner(this)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //************************************ Reporter ************************************//

  /**
    * 该结构的目标是保证进度反馈的递增性。同时保留关键点，丢弃密集冗余。
    * 注意：事件到达本类，已经是单线程操作了。
    */
  private class Reporter(feedback: Feedback, sum: Int) {
    private var _step: Int = _
    private var _subProgress: Float = _
    private var _stateResetted = true

    private[Tracker] def reportOnStart(): Unit = {
      assert(_stateResetted)
      _stateResetted = false
      eatExceptions(feedback.onStart())
    }

    private[Tracker] def reportOnProgress(name: String, step: Int, subProgress: Float, out: Out, desc: String): Unit = {
      assert(subProgress >= _subProgress, s"调用没有同步？`$name`:`$desc`")
      if (_stateResetted) {
        assert(step == _step + 1)
        _step = step
        _subProgress = 0
      } else assert(step == _step)
      if (subProgress > _subProgress) {
        _subProgress = subProgress
        // 一定会有1的, Task#exec()里有progress(1), 会使单/并行任务到达1.
        if (subProgress == 1) {
          _stateResetted = true
        }
      }
      eatExceptions(feedback.onProgress(name, out, step, sum, subProgress, desc))
    }

    private[Tracker] def reportOnComplete(out: Out): Unit = {
      assert(_step == sum - 1 && _subProgress == 1)
      eatExceptions(feedback.onComplete(out))
    }

    private[Tracker] def reportOnUpdate(out: Out): Unit = eatExceptions(feedback.onUpdate(out))

    private[Tracker] def reportOnAbort(): Unit = eatExceptions(feedback.onAbort())

    private[Tracker] def reportOnFailed(name: String, e: Exception): Unit = eatExceptions(feedback.onFailed(name, e))
  }
}
