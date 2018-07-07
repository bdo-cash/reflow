/*
 * Copyright (C) 2018-present, Chenai Nakam(chenai.nakam@gmail.com)
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

import java.util.concurrent.atomic.AtomicLong
import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.ThrowMsg
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.chenai.nakam.tool.pool.S._2S
import hobby.wei.c.tool
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Assist.eatExceptions
import hobby.wei.c.reflow.Feedback.Progress
import hobby.wei.c.reflow.Pulse.Reporter
import hobby.wei.c.reflow.Reflow.{P_HIGH, logger => log}
import hobby.wei.c.reflow.State._

import scala.collection.concurrent.TrieMap

/**
  * 脉冲步进流式数据处理器。
  * <p>
  * 数据流经`大规模集成任务集（Reflow）`，能够始终保持输入时的先后顺序，会排队进入各个任务，每个任务可保留前一个数据在处理时
  * 特意留下的标记。无论在任何深度的子任务中，也无论前一个数据在某子任务中停留的时间是否远大于后一个。
  *
  * @param reflow       每个`流处理器`都`Base`在一个主`Reflow`上。
  * @param abortIfError 当有一次输入出现错误时，是否中断。默认为`false`。
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 01/07/2018
  */
class Pulse(val reflow: Reflow, feedback: Pulse.Feedback, abortIfError: Boolean = false)(implicit poster: Poster) extends Scheduler {
  private lazy val snatcher = new tool.Snatcher.ActionQueue()
  private lazy val reporter = new Reporter(feedback.wizh(poster))
  private lazy val state = new Scheduler.State$()
  private lazy val counter = new AtomicLong(0)
  @volatile var head: Option[Tactic] = None

  def input(in: In): Unit = Reflow.submit {
    snatcher.queAc {
      val s = getState
      if (s == FAILED && abortIfError || s == ABORTED) return
      if (isDone) state.reset()
      val pending = state.forward(PENDING)
      // 无论我是不是第一个，原子切换。
      head = Option(new Tactic(head, this, counter.incrementAndGet))
      val tac = head
      Reflow.submit {
        if (pending) reporter.reportOnPending()
        tac.get.start(in)
      }(TRANSIENT, P_HIGH)
      ()
    }
  }(SHORT, P_NORMAL)

  @deprecated
  override def sync() = head.get.scheduler.sync()

  @deprecated
  override def sync(reinforce: Boolean, milliseconds: Long) = head.get.scheduler.sync(reinforce, milliseconds)

  override def abort(): Unit = snatcher.queAc {
    if (state.forward(PENDING)) { // 说明还没有开始
      state.forward(ABORTED)
      reporter.reportOnAbort(None)
    }
    abortHead(head)
    head = None
  }

  override def getState = state.get

  override def isDone = {
    val s = getState
    s == COMPLETED /*不应该存在*/ || s == FAILED || s == ABORTED || s == UPDATED
  }

  private[reflow] def onFailed(): Unit = snatcher.queAc {
    if (abortIfError) {
      state.forward(FAILED)
      // 后面的`state.forward()`是不起作用的。
      abort()
    }
  }

  private[reflow] def onAbort(trigger: Option[Trait]): Unit = snatcher.queAc {
    if (state.forward(ABORTED)) {
      reporter.reportOnAbort(trigger)
    }
    abort()
  }

  private[reflow] def abortHead(head: Option[Tactic]): Unit = head.foreach { tac =>
    val thd = tac.head
    // 顺序很重要，由于`abort()`事件会触发tac重置 head, 所以这里先引用，后执行，确保万无一失。
    tac.scheduler.abort()
    abortHead(thd) // 尾递归
  }

  private[reflow] class Tactic(@volatile var head: Option[Tactic], pulse: Pulse, count: Long) extends TAG.ClassName {
    private lazy val snatcher = new tool.Snatcher.ActionQueue()
    private lazy val roadmap = new TrieMap[(Int, String), Out]
    private lazy val suspend = new TrieMap[(Int, String), () => Unit]
    @volatile private var follower: Tactic = _
    @volatile var scheduler: Scheduler = _

    // `in`参数放在这里以缩小作用域。
    def start(in: In): Unit = {
      head.foreach(follow)
      scheduler = pulse.reflow.as[Reflow.Impl].start(in, feedback,
        /*全量进度，以便下一个输入可以在任何深度确认是否可以运行某`Task`了。*/
        Policy.FullDose,
        /*不使用外部客户代码提供的`poster`，以确保`feedback`和`cacheBack`反馈顺序问题。*/
        null, null, interact)
    }

    def follow(tac: Tactic): Unit = tac.snatcher.queAc {
      tac.follower = this
    }

    def onEvolve(depth: Int, trat: Trait): Unit = suspend.remove((depth, trat.name$)).foreach(_ ())

    def followerGetCache(depth: Int, trat: String): Out = roadmap.get((depth, trat)).orNull

    private lazy val feedback = new Feedback.Adapter {
      override def onStart(): Unit = {
        super.onStart()
        pulse.snatcher.queAc {
          if (pulse.state.forward(EXECUTING) && head.isEmpty) pulse.reporter.reportOnStart()
        }
      }

      override def onProgress(progress: Progress, out: Out, depth: Int): Unit = {
        super.onProgress(progress, out, depth)
        pulse.snatcher.queAc {
          if (pulse.state.get == EXECUTING) pulse.reporter.reportOnEvolve(count, progress, out, depth)
        }
      }

      override def onComplete(out: Out): Unit = {
        super.onComplete(out)
        pulse.snatcher.queAc {
          if (pulse.state.get == EXECUTING) pulse.reporter.reportOnOutput(count, out)
        }
        // 释放`head`。由于总是会引用前一个，会造成内存泄露。
        head = None
        // 本次脉冲走完所有`Task`，结束。
      }

      override def onUpdate(out: Out): Unit = {
        super.onUpdate(out)
        assert(assertion = false, "对于`Pulse`中的`Reflow`，不应该走到`onUpdate`这里。".tag)
      }

      override def onAbort(trigger: Option[Trait]): Unit = {
        super.onAbort(trigger)
        head = None
        pulse.onAbort(trigger)
      }

      override def onFailed(trat: Trait, e: Exception): Unit = {
        super.onFailed(trat, e)
        head = None
        pulse.onFailed()
      }
    }

    private lazy val interact = new Pulse.Interact {
      override def evolve(depth: Int, trat: Trait, cache: Out): Unit = snatcher.queAc {
        log.i("[interact.evolve](%s, %s):", depth, trat.name$.s)
        roadmap.put((depth, trat.name$), cache)
        if (follower.nonNull) follower.onEvolve(depth, trat)
      }

      override def forward(depth: Int, trat: Trait, go: () => Unit): Unit = head.fold(go()) { tac =>
        tac.snatcher.queAc {
          if (tac.roadmap.contains((depth, trat.name$))) go()
          else suspend.put((depth, trat.name$), go)
          () // 返回 Unit
        }
      }

      override def getCache(depth: Int, trat: Trait): Out = head.fold[Out](null) {
        log.i("[interact.getCache](%s, %s).", depth, trat.name$.s)
        // 不需要也不能使用`(tac.)snatcher`：
        // 因为此时数据一定是就绪的（而且存储结构支持并发，不需要同步）；
        // 而由于需要直接返回，所以不能（使用`(tac.)snatcher`）。
        _.followerGetCache(depth, trat.name$)
      }
    }
  }
}

object Pulse {
  trait Feedback extends hobby.wei.c.reflow.Feedback {
    override def onPending(): Unit
    override def onStart(): Unit
    @deprecated
    override final def onProgress(progress: hobby.wei.c.reflow.Feedback.Progress, out: Out, depth: Int): Unit = {}
    @deprecated
    override final def onComplete(out: Out): Unit = {}
    @deprecated
    override final def onUpdate(out: Out): Unit = {}
    def onEvolve(count: Long, progress: hobby.wei.c.reflow.Feedback.Progress, out: Out, depth: Int): Unit
    def onOutput(count: Long, out: Out)
    override def onAbort(trigger: Option[Trait]): Unit
    override def onFailed(trat: Trait, e: Exception): Unit
  }

  object Feedback {
    trait Adapter extends Feedback {
      override def onPending(): Unit = {}
      override def onStart(): Unit = {}
      override def onEvolve(count: Long, progress: Progress, out: Out, depth: Int): Unit = {}
      override def onAbort(trigger: Option[Trait]): Unit = {}
      override def onFailed(trat: Trait, e: Exception): Unit = {}
    }
  }

  private[reflow] trait Interact {
    /**
      * 进展，完成某个小`Task`。
      * <p>
      * 表示上一个`Tactic`反馈的最新进展，或者当前`Tactic`要反馈给下一个`Tactic`的进展。
      *
      * @param depth `SubReflow`的嵌套深度，顶层为`0`。在同一深度下的`trat`名称不会重复。
      * @param trat  完成的`Task`。
      * @param cache 留给下一个路过`Task`的数据。
      */
    def evolve(depth: Int, trat: Trait, cache: Out): Unit
    //********** 以上为上一个`Tactic`的输出 **********//
    //********** 以下为当前`Tactic`的询问和交互 *******//
    /**
      * 当前`Tactic`的某`Task`询问是否可以继续。
      *
      * @param depth 同上。
      * @param trat  同上。
      * @param go    一个函数，调用以推进询问的`Task`继续。如果在询问时，前一个`Tactic`进展落后了，则这里应该
      *              将本参数缓存起来，以备在`evolve()`调用满足条件时，再执行本函数以推进`Task`继续。
      */
    def forward(depth: Int, trat: Trait, go: () => Unit)
    /**
      * 当前`Tactic`的某`Task`执行的时候，需要获得上一个`Tactic`留下的数据。
      *
      * @param depth
      * @param trat
      * @return
      */
    def getCache(depth: Int, trat: Trait): Out
  }

  implicit class WithPoster(feedback: Feedback) {
    def wizh(poster: Poster): Feedback = if (poster.isNull) feedback else if (feedback.isNull) feedback else new Feedback {
      require(poster.nonNull)

      override def onPending(): Unit = poster.post(feedback.onPending())

      override def onStart(): Unit = poster.post(feedback.onStart())

      override def onEvolve(count: Long, progress: Progress, out: Out, depth: Int): Unit = poster.post(feedback.onEvolve(count, progress, out, depth))

      override def onOutput(count: Long, out: Out): Unit = poster.post(feedback.onOutput(count, out))

      override def onAbort(trigger: Option[Trait]): Unit = poster.post(feedback.onAbort(trigger))

      override def onFailed(trat: Trait, e: Exception): Unit = poster.post(feedback.onFailed(trat, e))
    }
  }

  private[reflow] class Reporter(feedback: Pulse.Feedback) extends Tracker.Reporter(feedback) {
    @deprecated
    private[reflow] override final def reportOnProgress(progress: Progress, out: Out, depth: Int): Unit = ???

    @deprecated
    private[reflow] override final def reportOnComplete(out: Out): Unit = ???

    @deprecated
    private[reflow] override final def reportOnUpdate(out: Out): Unit = ???

    def reportOnEvolve(count: Long, progress: Progress, out: Out, depth: Int): Unit = eatExceptions(feedback.onEvolve(count, progress, out, depth))

    def reportOnOutput(count: Long, out: Out): Unit = eatExceptions(feedback.onOutput(count, out))
  }
}
