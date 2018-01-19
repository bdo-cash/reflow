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
import java.util.concurrent.locks.Condition
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Dependency._
import hobby.wei.c.reflow.Reflow.{logger => log}
import hobby.wei.c.tool.Locker
import Reflow._
import hobby.wei.c.reflow.Tracker.{Reporter, Runner}

import scala.collection.{mutable, _}
import scala.collection.mutable.ListBuffer
import scala.collection.parallel.mutable.{ParHashMap, ParHashSet}

private[reflow] final class Tracker(val basis:Dependency.Basis, traitIn:Trait[_], inputTrans:immutable.Set[Transformer[_, _]],
                        state:Scheduler.State$, feedback:Feedback, poster:Poster) extends Scheduler {
      // 本数据结构是同步操作的, 不需要ConcurrentLinkedQueue
      private val remaining = {
        val seq = new mutable.ListBuffer[Trait[_<:Task]]
        copy(basis.traits, seq)
        seq
      }

      private val runnersParallel = new ParHashSet[Runner]
      @volatile // 用volatile而不在赋值的时候用synchronized是因为读写操作不会同时发生。
      private var reinforce: Seq[Trait[_]] = _
      private[reflow] val reinforceRequired = new AtomicBoolean(false)
      private val reinforceCaches = new ParHashMap[String, Out]
      private val progress = new ParHashMap[String, java.lang.Float]
      private val step = new AtomicInteger(-1 /*第一个任务是系统input任务*/)
      private val sum = remaining.length
      private val sumParallel = new AtomicInteger
      @volatile private var sumReinforce: Int = _
      @volatile private var normalDone, reinforceDone: Boolean = _
      @volatile private var outFlowTrimmed = new Out(mutable.AnyRefMap.empty[String, Key$[_]])
      @volatile private[reflow] var prevOutFlow, reinforceInput: Out = _
      private val reporter = new Reporter(feedback, poster, sum)

      private[reflow] def start(): Boolean = {
        if (tryScheduleNext()) {
          Worker.scheduleBuckets()
          true
        } else false
      }

      /**
        * 先于{@link #endRunner(Runner)}执行。
        */
      private def innerError(runner: Runner, e: Exception) {
        log.i("innerError")(runner.trat.name$)
        // 正常情况下是不会走的，仅用于测试。
        performAbort(runner, true, runner.trat, e)
      }

      private def endRunner(runner: Runner): Unit = synchronized {
        log.i("endRunner")(runner.trat.name$)
        runnersParallel.-=(runner)
        if (runnersParallel.isEmpty && state.get$ != State.ABORTED && state.get$ != State.FAILED) {
          assertx(sumParallel.get == 0)
          progress.clear
          step.incrementAndGet
          doTransform()
          tryScheduleNext()
        }
      }

      // 必须在进度反馈完毕之后再下一个, 否则可能由于线程优先级问题, 导致低优先级的进度没有反馈完,
      // 而新进入的高优先级任务又要争用同步锁, 造成死锁的问题。
      private def tryScheduleNext(): Boolean = synchronized {
        if (step.get() >= 0) remaining.remove(0) // 原因见下文的peek().
        if (remaining.isEmpty) {
          if (reinforceRequired.get()) {
            Dependency.copy(reinforce /*注意写的时候没有synchronized*/, remaining)
            outFlowTrimmed = reinforceInput
            reinforce = _ // clear()
          } else return false // 中止
        }
        assert(state.get() == State.PENDING /*start()的时候已经PENDING了*/
          || state.forward(State.PENDING)
          || state.forward(State.REINFORCE_PENDING))
        // 不poll(), 以备requireReinforce()的copy().
        val trat = remaining.head
        if (trat.isParallel) {
          val parallel = trat.asParallel
          val runners = new ListBuffer[Runner]
          parallel.traits().foreach{t =>
            runners += new Runner(this, t)
            // 把并行的任务put进去，不然计算子进度会有问题。
            progress.put(t.name$, 0f)
          }
          runners.foreach(runnersParallel.+=)
        } else {
          //progress.put(trat.name$, 0f)
          runnersParallel += new Runner(this, trat)
        }
        sumParallel.set(runnersParallel.size)
        resetOutFlow(new Out(basis.outsFlowTrimmed(trat.name$)))
        // 在调度之前获得结果比较保险
        val hasMore = sumParallel.get() > 0
        runnersParallel.toSeq.foreach{runner =>
          import Period._
          runner.trat.period$ match {
            case INFINITE => Worker.sPreparedBuckets.sInfinite.offer(runner)
            case LONG => Worker.sPreparedBuckets.sLong.offer(runner)
            case SHORT => Worker.sPreparedBuckets.sShort.offer(runner)
            case TRANSIENT=>Worker.sPreparedBuckets.sTransient.offer(runner)
          }
        }
        // 不写在这里，原因见下面方法本身：写在这里几乎无效。
        // scheduleBuckets()
        hasMore
      }

      private[reflow] def requireReinforce(): Boolean = {
        if (!reinforceRequired.getAndSet(true)) {
          reinforce = new mutable.ListBuffer[Trait[_]]
          copy(remaining, reinforce)
          sumReinforce = reinforce.length
          reinforceInput = prevOutFlow
          false
        } else true
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

      private def resetOutFlow(out: Out): Unit = {
        Locker.syncr({
          prevOutFlow = outFlowTrimmed;
          outFlowTrimmed = out;
          joinOutFlow(prevOutFlow);
          return null;
        }, this)
      }

      private def joinOutFlow(out: Out) {
        Locker.syncr({
          outFlowTrimmed.putWith(out.map, out.nullValueKeys, true, false);
          return null;
        }, this)
      }

      private def verifyOutFlow() {
        if (debugMode) outFlowTrimmed.verify()
      }

      private def performAbort(trigger: Runner, forError: Boolean, trat: Trait[_], e: Exception) {
        if (state.forward(if (forError) State.FAILED else State.ABORTED)) {
          for (runner <- runnersParallel) {
            runner.abort()
            Monitor.abortion(if (trigger.isNull) null else trigger.trat.name$, runner.trat.name$, forError)
          }
          if (forError) {
            reporter.reportOnFailed(trat, e)
          } else {
            reporter.reportOnAbort()
          }
        } else if (state.abort()) {
          // 已经到达COMPLETED/REINFORCE阶段了
        } else {
          // 如果本方法被多次被调用，则会进入本case. 虽然逻辑上并不存在本case, 但没有影响。
          // Throws.abortForwardError();
        }
        interruptSync(true /*既然是中断，应该让reinforce级别的sync请求也终止*/)
      }

  @deprecated(message = "已在{Impl}中实现, 本方法不会被调用。", since = "0.0.1")
  override def sync():Out = ???

  @throws[InterruptedException]
  override def sync(reinforce: Boolean, milliseconds: Long):Out = {
        val start = System.currentTimeMillis
        Locker.sync(new Locker.CodeC[Out](1) {
          @throws[InterruptedException]
          override protected def exec(cons: Array[Condition]) = {
            // 不去判断mState是因为任务流可能会失败
            while (!(if(reinforce) reinforceDone else normalDone)) {
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
        }, this)
      }

      private def interruptSync(reinforce:Boolean) {
        normalDone = true
        if (reinforce)  reinforceDone = true
        try {
          Locker.sync(new Locker.CodeC[Unit](1) {
            @throws[InterruptedException]
            override protected def exec(cons: Array[Condition]) = {
              cons(0).signalAll()
            }
          }, this)
        } catch {
          case e:Exception => // 不可能抛异常
        }
      }

  override def abort(): Unit = performAbort(null, false, null, null)

  override def getState() = state.get()

  @deprecated(message = "不要调用。", since = "0.0.1")
  override def isDone() = ???

      /**
        * 每个Task都执行。
        */
      private def onTaskStart (trat:Trait[_]) {
        Locker.syncr(() -> {
          final int step = basis.stepOf(
          trait);
          if (step >= sum) { // reinforce阶段(最后一个任务的step是sum - 1)
            state.forward(State.REINFORCING);
          } else if (step >= 0) {
            if (state.forward(State.EXECUTING))
            // 但反馈有且只有一次
              if (step == 0) {
                reporter.reportOnStart();
              } else {
                // progress会在任务开始、进行中及结束时report，这里do nothing.
              }
          }
          return null;
        }, this);
      }

      private[reflow] def onTaskProgress(trat:Trait[_],  progress:Float, out:Out) {
        Locker.syncr(() -> {
          final int step = basis.stepOf(
          trait);
          // 不用assertx(step >= 0 && step < sum),
          // 因为对于State.REINFORCING, Task还是会进行反馈，但是这里需要过滤掉。
          if (step >= 0 && step < sum) {
            reporter.reportOnProgress(
            trait, step
            , subProgress(
            trait, progress
            ), out
            );
          }
          return null;
        }, this);
      }

      private def subProgress(trat:Trait[_], pogres:Float):Float =
        if (progress.size <= 1) between(0, pogres, 1)
        else {
          if (pogres > 0) {
          assertx(progress.contains(trat.name$))
          progress.synchronized { // put操作和下面for求值应互斥
            progress.put(trat.name$, between(0, pogres, 1))
          }
        }
        progress.synchronized {
          progress.values.reduce(_ + _) / progress.size
        }
      }

      private def onTaskComplete (trat:Trait[_], out:Out, flow:Out) {
        Locker.syncr(() -> {
          final int step = basis.stepOf(
          trait);
          if (step < 0) resetOutFlow(flow);
          else joinOutFlow(flow);
          // 由于不是在这里移除(runnersParallel.remove(runner)), 所以不能用这个判断条件：
          // (runnersParallel.size() == 1 && runnersParallel.contains(runner))
          if (sumParallel.decrementAndGet() == 0) {
            verifyOutFlow();
            Monitor.complete(step, out, flow, outFlowTrimmed);
            if (step == sum - 1) {
              Monitor.assertStateOverride(state.get(),
                State.COMPLETED, state.forward(State.COMPLETED));
              reporter.reportOnComplete(outFlowTrimmed); // 注意参数，因为这里是complete.
              interruptSync(!reinforceRequired.get());
            } else if (step == sum + sumReinforce - 1) {
              Monitor.assertStateOverride(state.get(),
                State.UPDATED, state.forward(State.UPDATED));
              reporter.reportOnUpdate(outFlowTrimmed);
              interruptSync(true);
            } else {
              // 单步任务的完成仅走进度(已反馈，不在这里反馈), 而不反馈完成事件。
            }
          }
          return null;
        }, this)
      }
    }

private[reflow] object Tracker extends Scheduler {
  class Runner(tracker: Tracker, override val trat: Trait[_ <: Task]) extends Worker.Runner(trat: Trait[_ <: Task], null) with Equals {
    implicit val TAG = new LogTag(trat.name$)

    @volatile
    private var task: Task = _
    @volatile
    private var abort: Boolean = _
    private var timeBegin: Long = _

    override def equals(any: scala.Any) = super.equals(any)

    override def canEqual(that: Any) = super.equals(that)

    override def hashCode() = super.hashCode()

    // 这个场景没有使用synchronized, 跟Task.abort()场景不同。
    def abort(): Unit = {
      // 防止循环调用
      if (abort) return
      abort = true
      if (task.nonNull) task.abort()
    }

    override def run(): Unit = {
      var working = false
      try {
        task = trat.newTask()
        // 判断放在mTask的创建后面, 配合abort()中的顺序。
        if (abort) onAbort(false)
        else {
          onStart()
          val input = new Out(trat.requires$)
          log.i("input:%s", input)
          input.fillWith(tracker.prevOutFlow)
          val cached = tracker.cache(trat, false)
          if (cached.nonNull) input.cache(cached)
          val out = new Out(trat.outs$)
          task.env(tracker, trat, input, out)
          working = true
          log.i("111111111111")
          task.exec()
          log.i("222222222222")
          log.i("out:%s", out)
          val dps = tracker.basis.dependencies.get(trat.name$)
          log.i("dps:%s", dps.get)
          val flow = new Out(dps.getOrElse(Map.empty[String, Key$[_]]))
          log.i("flow0:%s", flow)
          val map = out.map
          val nulls = out.nullValueKeys
          doTransform(tracker.basis.transformers(trat.name$), map, nulls, false)
          flow.putWith(map, nulls, false, true)
          log.i("flow1:%s", flow)
          working = false
          onComplete(out, flow)
          if (abort) onAbort(true)
        }
      } catch {
        case e: Exception =>
          log.i("exception:%s", e)
          if (working) {
            // TODO:
            if (e.isInstanceOf[AbortException]) { // 框架抛出的, 表示成功中断
              onAbort(false)
            } else {
              if (e.isInstanceOf[FailedException]) {
                onFailed(e.getCause.as[Exception])
              } else if (e.isInstanceOf[CodeException]) { // 客户代码问题
                onException(e.as[CodeException])
              } else {
                onException(new CodeException(e))
              }
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
      tracker.performAbort(this, false, trat, null)
    }

    private def abortAction(e: Exception): Runnable = new Runnable {
      override def run(): Unit = {
        if (!abort) abort = true
        tracker.performAbort(this, true, trat, e)
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
    */
  private class Reporter {
    private final Feedback feedback;
    private final Poster poster;
    private final int sum;
    private int step;
    private float subProgress;
    private boolean stateResetted = true;

    Reporter(Feedback feedback, Poster poster, int sum) {
      this.feedback = feedback;
      this.poster = poster;
      this.sum = sum;
    }

    private void reportOnStart() {
      synchronized (this) {
        assertx(stateResetted);
        stateResetted = false;
      }
      wrapReport(Feedback::onStart);
    }

    private void reportOnProgress(Trait trait, int step, float subProgress, Out out) {
      synchronized (this) {
        if (stateResetted) {
          assertx(step == this.step + 1);
          this.step = step;
          this.subProgress = 0;
        } else {
          assertx(step == this.step);
        }
        if (subProgress <= this.subProgress) {
          return;
        }
        this.subProgress = subProgress;
        // 一定会有1的, Task#exec()里有progress(1), 会使单/并行任务到达1.
        if (subProgress == 1) {
          stateResetted = true;
        }
      }
      wrapReport((Feedback feedback) -> feedback.onProgress(trat.name$,
        out, step, sum, subProgress, trait.desc$()));
    }

    private void reportOnComplete(Out out) {
      assertx(step == sum - 1 && subProgress == 1);
      wrapReport((Feedback feedback) -> feedback.onComplete(out));
    }

    private void reportOnUpdate(Out out) {
      wrapReport((Feedback feedback) -> feedback.onUpdate(out));
    }

    private void reportOnAbort() {
      wrapReport(Feedback::onAbort);
    }

    private void reportOnFailed(Trait trait, Exception e) {
      wrapReport((Feedback feedback) -> feedback.onFailed(trat.name$, e));
    }

    private void wrapReport(Exec<Feedback> r) {
      final Feedback feedback = this.feedback;
      if (feedback != null) {
        final Poster poster = this.poster;
        if (poster != null) {
          // 我们认为poster已经按规格实现了, 那么runner将在目标线程串行运行,
          // 不存在可见性问题, 不需要synchronized同步。
          eatExceptions(() -> poster.post(() -> r.exec(feedback)), null);
        } else {
          eatExceptions(() -> {
            // 这个同步是为了保证可见性
            synchronized (this) {
              r.exec(feedback);
            }
          }, null);
        }
      }
    }

    private interface Exec<T> {
      void exec(T args);
    }
  }
}
