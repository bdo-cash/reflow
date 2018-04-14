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
import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.ShortMsg
import hobby.chenai.nakam.lang.J2S.{NonNull, Obiter}
import hobby.wei.c.reflow.Feedback.Progress
import hobby.wei.c.reflow.Feedback.Progress.Policy.{Depth, Fluent}
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.tool.Locker

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
trait Feedback extends Equals {
  /**
    * 任务已提交到线程池，等待被执行。
    */
  def onPending(): Unit

  /**
    * 第一个任务开始执行。
    */
  def onStart(): Unit

  /**
    * 进度反馈。
    *
    * @param progress 进度对象。
    * @param out      进度的时刻已经获得的输出。
    * @param depth    触发当前进度反馈的子任务深度。
    */
  def onProgress(progress: Progress, out: Out, depth: Int): Unit

  /**
    * 任务流执行完成。
    *
    * @param out 任务的输出结果。
    */
  def onComplete(out: Out): Unit

  /**
    * 强化运行完毕之后的最终结果。
    *
    * @see Task#requireReinforce()
    */
  def onUpdate(out: Out): Unit

  /**
    * 任务流中断。
    *
    * @param trigger 触发失败的`Trait`，为`None`表示客户代码通过`scheduler`主动触发。
    */
  def onAbort(trigger: Option[Trait]): Unit

  /**
    * 任务失败。
    *
    * @param trat 触发失败的`Trait`。
    * @param e    分为两类:
    *             第一类是客户代码自定义的 Exception, 即显式传给`Task#failed(Exception)`方法的参数, 可能为`null`;
    *             第二类是由客户代码质量问题导致的 RuntimeException, 如`NullPointerException`等,
    *             这些异常被包装在`CodeException`里, 可以通过`CodeException#getCause()`方法取出具体异对象。
    */
  def onFailed(trat: Trait, e: Exception): Unit

  override def equals(any: Any) = super.equals(any)

  override def canEqual(that: Any) = false
}

object Feedback {
  /**
    * 表示任务的进度。由于任务可以嵌套，所以进度也需要嵌套，以便实现更精确的管理。
    *
    * @param sum  当前进度的总步数。
    * @param step 当前进度走到了第几步。
    * @param trat 当前`step`对应的 top level `Trait`。可能为`None`，表示某`Task.progress(step, sum)`出来的进度；也可能为并行（`_.isPar`）。
    * @param subs 子任务。可以是并行的，所以用了`Seq`。
    */
  case class Progress(sum: Int, step: Int, trat: Option[Trait] = None, subs: Option[Seq[Progress]] = None) {
    require(step < sum || (step == sum && subs.isEmpty))
    require(subs.fold(true)(_.forall(_.nonNull)))

    lazy val main: Float = (step + sub) / sum

    lazy val sub: Float = subs.fold[Float](0) { p => p.map(_ ()).sum / p.size }

    @inline def apply(): Float = main

    override def toString = s"sum:$sum, step:$step, p-main:$main, p-sub:$sub${trat.fold("") { t => s", name:${t.name$.tag}" }}."
  }

  object Progress {
    /** 进度反馈的优化策略。 */
    trait Policy extends Ordering[Policy] {
      outer =>
      val priority: Int

      def genDelegator(feedback: Feedback): Feedback = feedback

      def ->(policy: Policy): Policy = new Multiply(this, policy)

      final def isFluentMode: Boolean = this <= Fluent

      def base = this

      /** 生成用于传递到`SubReflow`的`Policy`。 */
      def toSub = this match {
        case Depth(level) => Depth(level - 1) // 每加深一层即递减
        case p => p
      }

      final def revise(policy: Policy): Policy = if (policy.base equiv this.base) { // 如果相等，其中一个必然是`Depth`。
        policy match {
          case _: Depth => policy
          case _ => this
        }
      } else if (policy.base > this.base) policy else this

      // 优先级越高，数值越小。
      override def compare(x: Policy, y: Policy) = if (x.priority > y.priority) -1 else if (x.priority < y.priority) 1 else 0
    }

    class Multiply(val before: Policy, val after: Policy) extends Policy {
      override val priority = (before min after).priority

      override def base = before.base

      override def toSub = base.toSub

      override def genDelegator(feedback: Feedback) = before.genDelegator(after.genDelegator(feedback))
    }

    object Policy {
      /** 全量。不错过任何进度细节。 */
      object FullDose extends Policy {
        override val priority = 0
      }

      /** 流畅的。即：丢弃拥挤的消息。（注意：仅适用于`Poster`之类有队列的）。 */
      object Fluent extends Policy {
        override val priority = 1

        // 虽然`Tracker`内部已经实现，但仍需增强。
        override def genDelegator(feedback: Feedback) = new Delegator(feedback) with TAG.ClassName {
          @volatile private var main = -1f

          override def onProgress(progress: Progress, out: Out, depth: Int): Unit = {
            if (debugMode) log.i("~~~~~~~~~~~~~~~~~~~~~~~~[Fluent]depth:%s, progress:%s.", depth, progress)
            if ((progress.main > main).obiter {
              main = progress.main
            }) {
              super.onProgress(progress, out, depth)
            }
          }
        }
      }

      /**
        * 基于子进度的深度。
        *
        * @param level 子进度的深度水平。`0`表示放弃顶层进度；`1`表示放弃子层进度；`2`表示放弃次子层进度。以此类推。
        */
      case class Depth(level: Int) extends Policy {
        override val priority = Fluent.priority - (level max 0)

        final def isMind(level: Int) = this.level > level

        override def genDelegator(feedback: Feedback) = if (isFluentMode) Fluent.genDelegator(feedback)
        else new Delegator(feedback) with TAG.ClassName {
          @volatile private var step: Int = -1

          override def onProgress(progress: Progress, out: Out, depth: Int): Unit = {
            if (debugMode) log.i("[Depth(%s)]progress:%s, out:%s, depth:%s.", level, progress, out, depth)
            if (isMind(0)) { // 关注当前层
              if (isMind(1) /*关注子层*/
                || (progress.step > step).obiter {
                step = progress.step
              }) {
                super.onProgress(progress, out, depth)
              }
            }
          }
        }
      }

      /**
        * 基于反馈时间间隔（构建于`Fluent`之上）。
        *
        * @param minGap 最小时间间隔，单位：毫秒。
        */
      case class Interval(minGap: Int) extends Policy {
        override val priority = 2

        override def genDelegator(feedback: Feedback) = if (minGap <= 0) super.genDelegator(feedback)
        else new Delegator(feedback) {
          @volatile private var time = 0L

          override def onStart(): Unit = {
            super.onStart()
            time = System.currentTimeMillis
          }

          override def onProgress(progress: Progress, out: Out, depth: Int): Unit = {
            if (minGap > 0) {
              val curr = System.currentTimeMillis
              if (curr - time >= minGap) {
                time = curr
                super.onProgress(progress, out, depth)
              }
            } else super.onProgress(progress, out, depth)
          }
        }
      }
    }
  }

  implicit class Join(fb: Feedback = null) {
    def join(that: Feedback): Feedback = {
      val feedback = new Feedback.Observable
      feedback.addObservers(that)
      if (fb.nonNull) feedback.addObservers(fb)
      feedback
    }
  }

  implicit class WithPoster(feedback: Feedback) {
    def wizh(poster: Poster): Feedback = if (poster.isNull) feedback else if (feedback.isNull) feedback else new Delegator(feedback) {
      require(poster.nonNull)

      override def onPending(): Unit = poster.post(super.onPending())

      override def onStart(): Unit = poster.post(super.onStart())

      override def onProgress(progress: Progress, out: Out, depth: Int): Unit = poster.post(super.onProgress(progress, out, depth))

      override def onComplete(out: Out): Unit = poster.post(super.onComplete(out))

      override def onUpdate(out: Out): Unit = poster.post(super.onUpdate(out))

      override def onAbort(trigger: Option[Trait]): Unit = poster.post(super.onAbort(trigger))

      override def onFailed(trat: Trait, e: Exception): Unit = poster.post(super.onFailed(trat, e))
    }
  }

  private[reflow] class Delegator(feedback: Feedback) extends Feedback {
    require(feedback.nonNull)

    override def onPending(): Unit = feedback.onPending()

    override def onStart(): Unit = feedback.onStart()

    override def onProgress(progress: Progress, out: Out, depth: Int): Unit = feedback.onProgress(progress, out, depth)

    override def onComplete(out: Out): Unit = feedback.onComplete(out)

    override def onUpdate(out: Out): Unit = feedback.onUpdate(out)

    override def onAbort(trigger: Option[Trait]): Unit = feedback.onAbort(trigger)

    override def onFailed(trat: Trait, e: Exception): Unit = feedback.onFailed(trat, e)
  }

  class Adapter extends Feedback {
    override def onPending(): Unit = {}

    override def onStart(): Unit = {}

    override def onProgress(progress: Progress, out: Out, depth: Int): Unit = {}

    override def onComplete(out: Out): Unit = {}

    override def onUpdate(out: Out): Unit = {}

    override def onAbort(trigger: Option[Trait]): Unit = {}

    override def onFailed(trat: Trait, e: Exception): Unit = {}
  }

  class Observable extends Adapter {
    import Assist.eatExceptions
    implicit private lazy val lock: ReentrantLock = Locker.getLockr(this)

    @volatile
    private var obs: Seq[Feedback] = Nil //scala.collection.concurrent.TrieMap[Feedback, Unit] //CopyOnWriteArraySet[Feedback]

    def addObservers(fbs: Feedback*): Unit = Locker.syncr {
      obs = (obs.to[mutable.LinkedHashSet] ++= fbs.map(_.ensuring(_.nonNull))).toSeq
    }

    def removeObservers(fbs: Feedback*): Unit = Locker.syncr {
      obs = (obs.to[mutable.LinkedHashSet] --= fbs.map(_.ensuring(_.nonNull))).toSeq
    }

    override def onPending(): Unit = obs.foreach { fb => eatExceptions(fb.onPending()) }

    override def onStart(): Unit = obs.foreach { fb => eatExceptions(fb.onStart()) }

    override def onProgress(progress: Progress, out: Out, depth: Int): Unit = obs.foreach { fb => eatExceptions(fb.onProgress(progress, out, depth)) }

    override def onComplete(out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onComplete(out)) }

    override def onUpdate(out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onUpdate(out)) }

    override def onAbort(trigger: Option[Trait]): Unit = obs.foreach { fb => eatExceptions(fb.onAbort(trigger)) }

    override def onFailed(trat: Trait, e: Exception): Unit = obs.foreach { fb => eatExceptions(fb.onFailed(trat, e)) }
  }

  implicit object Log extends Feedback with TAG.ClassName {
    import Reflow.{logger => log}

    override def onPending(): Unit = log.i("[onPending]")

    override def onStart(): Unit = log.i("[onStart]")

    override def onProgress(progress: Progress, out: Out, depth: Int): Unit = log.i("[onProgress]depth:%s, progress:%s, out:%s.", depth, progress, out)

    override def onComplete(out: Out): Unit = log.w("[onComplete]out:%s.", out)

    override def onUpdate(out: Out): Unit = log.w("[onUpdate]out:%s.", out)

    override def onAbort(trigger: Option[Trait]): Unit = log.w("[onAbort]trigger:%s.", trigger)

    override def onFailed(trat: Trait, e: Exception): Unit = log.e(e, "[onFailed]trat:%s.", trat)
  }
}
