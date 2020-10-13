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
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Feedback.Progress
import hobby.wei.c.reflow.Feedback.Progress.Strategy.{Depth, Fluent}
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
    * @param progress  进度对象。
    * @param out       进度的时刻已经获得的输出。
    * @param fromDepth 触发当前进度反馈的`子任务流（SubReflow）`的嵌套深度（顶层为`0`，并按照`SubReflow`的嵌套层级依次递增）。
    */
  def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit

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
    * @param sum     当前进度的总步数。
    * @param step    当前进度走到了第几步。
    * @param trat    当前`step`对应的 top level `Trait`。分为3种情况：
    *                1. 若`subs`为`None`，则`trat`一定也为`None`，表示某`Task.progress(step, sum)`出来的进度；
    *                2. 若当前是一个具体的（顺序依赖的）任务触发出的进度，则`trat`表示该任务，同时`subs`中会有唯一一个进度与之对应，该进度其性质同 1；
    *                3. 若`trat`是并行的（`_.isPar == true`），则`subs`代表了所有当前正在并行执行的任务。
    *                注意：`subs`中的进度同样分为以上3种情况。
    * @param trigger 表示触发进度反馈的那个进度，以便知道哪个任务是开始还是完成。注意：不能再关注递归的[[trigger]]属性（13/10/2020 增加）。
    * @param subs    子任务。可以是并行的，所以用了`Seq`。
    */
  final case class Progress(sum: Int, step: Int, trat: Option[Trait] = None, trigger: Progress = null, subs: Option[Seq[Progress]] = None) {
    require(step < sum || (step == sum && subs.isEmpty))
    require(subs.fold(true)(_.forall(_.nonNull)))

    lazy val main: Float = (step + sub) / sum
    lazy val sub: Float = subs.fold[Float](0) { p => p.map(_ ()).sum / p.size }

    @inline def apply(): Float = main
    override def toString = s"Progress(sum:$sum, step:$step, p:$main, name:${trat.map(_.name$).orNull}, trigger:${trigger.trat.map(_.name$).orNull}(${trigger.main}))"
  }

  object Progress {
    /** 进度反馈的优化策略。 */
    trait Strategy extends Ordering[Strategy] {
      outer =>
      val priority: Int
      def genDelegator(feedback: Feedback): Feedback = feedback
      def ->(strategy: Strategy): Strategy = new Multiply(this, strategy)
      final def isFluentMode: Boolean = this <= Fluent
      def base = this

      /** 生成用于传递到`SubReflow`的`Policy`。 */
      def toSub = this match {
        case Depth(level) => Depth(level - 1) // 每加深一层即递减
        case p => p
      }

      final def revise(strategy: Strategy): Strategy = if (strategy.base equiv this.base) { // 如果相等，其中一个必然是`Depth`。
        strategy match {
          case _: Depth => strategy
          case _ => this
        }
      } else if (strategy.base > this.base) strategy else this

      // 优先级越高，数值越小。
      override def compare(x: Strategy, y: Strategy) = if (x.priority > y.priority) -1 else if (x.priority < y.priority) 1 else 0
    }

    class Multiply(val before: Strategy, val after: Strategy) extends Strategy {
      override val priority = (before min after).priority

      override def base = before.base
      override def toSub = base.toSub
      override def genDelegator(feedback: Feedback) = before.genDelegator(after.genDelegator(feedback))
    }

    object Strategy {
      /** 全量。不错过任何进度细节。 */
      object FullDose extends Strategy {
        override val priority = 0
      }

      /** 流畅的。即：丢弃拥挤的消息。（注意：仅适用于`Poster`之类有队列的）。 */
      object Fluent extends Strategy {
        override val priority = 1

        // 虽然`Tracker`内部已经实现，但仍需增强。
        override def genDelegator(feedback: Feedback) = new Delegator(feedback) with TAG.ClassName {
          @volatile private var main = -1f

          override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = {
            if (debugMode) log.i("~~~~~~~~~~~~~~~~~~~~~~~~[Fluent]fromDepth:%s, progress:%s.", fromDepth, progress)
            if ((progress.main > main).obiter {
              main = progress.main
            }) {
              super.onProgress(progress, out, fromDepth)
            }
          }
        }
      }

      /**
        * 基于子进度的深度。
        *
        * @param level 子进度的深度水平。`0`表示放弃顶层进度；`1`表示放弃子层进度；`2`表示放弃次子层进度。以此类推。
        */
      case class Depth(level: Int) extends Strategy {
        override val priority = Fluent.priority - (level max 0)

        final def isMind(level: Int) = this.level > level

        override def genDelegator(feedback: Feedback) = if (isFluentMode) Fluent.genDelegator(feedback)
        else new Delegator(feedback) with TAG.ClassName {
          @volatile private var step: Int = -1

          override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = {
            if (debugMode) log.i("[Depth(%s)]progress:%s, out:%s, fromDepth:%s.", level, progress, out, fromDepth)
            if (isMind(0)) { // 关注当前层
              if (isMind(1) /*关注子层*/
                || (progress.step > step).obiter {
                step = progress.step
              }) {
                super.onProgress(progress, out, fromDepth)
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
      case class Interval(minGap: Int) extends Strategy {
        override val priority = 2

        override def genDelegator(feedback: Feedback) = if (minGap <= 0) super.genDelegator(feedback)
        else new Delegator(feedback) {
          @volatile private var time = 0L

          override def onStart(): Unit = {
            super.onStart()
            time = System.currentTimeMillis
          }

          override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = {
            if (minGap > 0) {
              val curr = System.currentTimeMillis
              if (curr - time >= minGap) {
                time = curr
                super.onProgress(progress, out, fromDepth)
              }
            } else super.onProgress(progress, out, fromDepth)
          }
        }
      }
    }
  }

  implicit class Join(fb: Feedback = null) {
    def join(that: Feedback): Feedback = {
      // 把 that 放在最前面
      if (fb.nonNull && fb.isInstanceOf[Feedback.Observable]) {
        val obs = fb.as[Feedback.Observable]
        val old = obs.obs.toList
        obs.removeAll()
        obs.addObservers(that :: old: _*)
        obs
      } else {
        val obs = new Feedback.Observable
        obs.addObservers(that :: (if (fb.nonNull) fb :: Nil else Nil): _*)
        obs
      }
    }

    @inline def reverse(): Feedback = {
      if (fb.nonNull && fb.isInstanceOf[Feedback.Observable]) fb.as[Feedback.Observable].reverse()
      fb
    }
  }

  implicit class WithPoster(feedback: Feedback) {
    def wizh(poster: Poster): Feedback = if (poster.isNull) feedback else if (feedback.isNull) feedback else new Delegator(feedback) {
      require(poster.nonNull)

      override def onPending(): Unit = poster.post(super.onPending())
      override def onStart(): Unit = poster.post(super.onStart())
      override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = poster.post(super.onProgress(progress, out, fromDepth))
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
    override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = feedback.onProgress(progress, out, fromDepth)
    override def onComplete(out: Out): Unit = feedback.onComplete(out)
    override def onUpdate(out: Out): Unit = feedback.onUpdate(out)
    override def onAbort(trigger: Option[Trait]): Unit = feedback.onAbort(trigger)
    override def onFailed(trat: Trait, e: Exception): Unit = feedback.onFailed(trat, e)
  }

  class Adapter extends Feedback {
    override def onPending(): Unit = {}
    override def onStart(): Unit = {}
    override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = {}
    override def onComplete(out: Out): Unit = {}
    override def onUpdate(out: Out): Unit = {}
    override def onAbort(trigger: Option[Trait]): Unit = {}
    override def onFailed(trat: Trait, e: Exception): Unit = Log.onFailed(trat, e)
  }

  /**
    * 仅关注需要的值的`Feedback`，以方便客户代码的编写。
    * 用法示例：{{{
    * val buttStr = new Feedback.Butt(new Kce[String]("str") {}, watchProgressDepth = 1) {
    *   override def onValueGotOnProgress(value: Option[String], progress: Progress): Unit = ???
    *   override def onValueGot(value: Option[String]): Unit = ???
    *   override def onValueGotOnUpdate(value: Option[String]): Unit = ???
    *   override def onFailed(trat: Trait, e: Exception): Unit = ???
    * }
    * val buttInt = new Feedback.Butt[Integer](new Kce[Integer]("int") {}) {
    *   override def onValueGot(value: Option[Integer]): Unit = ???
    * }
    * // 可将多个值用`join`连接，将返回的`Feedback`传给`Reflow.start()`。
    * val feedback = buttStr.join(buttInt)
    * }}}
    * 注意：如果需要监听`Feedback`的更多事件，只需要在 join 的第一个`butt`下重写需要的回调即可（第一个`butt`在排序上是放在最后的）。
    *
    * @param kce                所关注值的`Kce[T]`信息。
    * @param watchProgressDepth 如果同时关注进度中的反馈值的话，会涉及到 Reflow 嵌套深度的问题。
    *                           本参数表示关注第几层的进度（即：是第几层的哪个任务会输出`kce`值，Reflow 要求不同层任务的`kce`可以相同）。
    **/
  abstract class Butt[T >: Null <: AnyRef](kce: KvTpe[T], watchProgressDepth: Int = 0) extends Adapter {
    override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = {
      super.onProgress(progress, out, fromDepth)
      if (fromDepth == watchProgressDepth && out.keysDef().contains(kce))
        onValueGotOnProgress(out.get(kce), progress)
    }

    override def onComplete(out: Out): Unit = {
      super.onComplete(out)
      onValueGotOnComplete(out.get(kce))
    }

    override def onUpdate(out: Out): Unit = {
      super.onUpdate(out)
      onValueGotOnUpdate(out.get(kce))
    }

    def onValueGotOnProgress(value: Option[T], progress: Progress): Unit = {}
    def onValueGotOnComplete(value: Option[T]): Unit
    def onValueGotOnUpdate(value: Option[T]): Unit = {}
  }

  abstract class Lite[-T <: AnyRef](watchProgressDepth: Int = 0) extends Butt(lite.Task.defKeyVType, watchProgressDepth) {
    @deprecated
    override final def onValueGotOnProgress(value: Option[AnyRef], progress: Progress): Unit =
      liteValueGotOnProgress(value.as[Option[T]], progress)
    @deprecated
    override final def onValueGotOnComplete(value: Option[AnyRef]): Unit = liteOnComplete(value.as[Option[T]])
    @deprecated
    override final def onValueGotOnUpdate(value: Option[AnyRef]): Unit = liteOnUpdate(value.as[Option[T]])

    def liteValueGotOnProgress(value: Option[T], progress: Progress): Unit = {}
    def liteOnComplete(value: Option[T]): Unit
    def liteOnUpdate(value: Option[T]): Unit = {}
  }

  object Lite {
    implicit final object Log extends Feedback.Lite[AnyRef] with TAG.ClassName {
      import Reflow.{logger => log}

      override def onPending(): Unit = log.i("[onPending]")
      override def onStart(): Unit = log.i("[onStart]")
      override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = log.i("[onProgress]fromDepth:%s, progress:%s, value:%s.", fromDepth, progress, out/*.get(lite.Task.defKeyVType)*/)
      override def onComplete(out: Out): Unit = super.onComplete(out)
      override def onUpdate(out: Out): Unit = super.onUpdate(out)
      override def onAbort(trigger: Option[Trait]): Unit = log.w("[onAbort]trigger:%s.", trigger)
      override def onFailed(trat: Trait, e: Exception): Unit = log.e(e, "[onFailed]trat:%s.", trat)

      override def liteOnComplete(value: Option[AnyRef]): Unit = log.w("[liteOnComplete]value:%s.", value)
      override def liteOnUpdate(value: Option[AnyRef]): Unit = log.w("[liteOnUpdate]value:%s.", value)
    }

  }

  class Observable extends Adapter with TAG.ClassName {
    import Assist.eatExceptions
    implicit private lazy val lock: ReentrantLock = Locker.getLockr(this)

    @volatile
    private[Feedback] var obs: Seq[Feedback] = Nil //scala.collection.concurrent.TrieMap[Feedback, Unit] //CopyOnWriteArraySet[Feedback]

    private[Feedback] def removeAll(): Unit = Locker.syncr(obs = Nil)
    private[Feedback] def reverse(): Unit = Locker.syncr(obs = obs.reverse)

    def addObservers(fbs: Feedback*): Unit = Locker.syncr {
      obs = (obs.to[mutable.LinkedHashSet] ++= fbs.map(_.ensuring(_.nonNull))).toSeq
    }

    def removeObservers(fbs: Feedback*): Unit = Locker.syncr {
      obs = (obs.to[mutable.LinkedHashSet] --= fbs.map(_.ensuring(_.nonNull))).toSeq
    }

    override def onPending(): Unit = obs.foreach { fb => eatExceptions(fb.onPending()) }
    override def onStart(): Unit = obs.foreach { fb => eatExceptions(fb.onStart()) }
    override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = obs.foreach { fb => eatExceptions(fb.onProgress(progress, out, fromDepth)) }
    override def onComplete(out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onComplete(out)) }
    override def onUpdate(out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onUpdate(out)) }
    override def onAbort(trigger: Option[Trait]): Unit = obs.foreach { fb => eatExceptions(fb.onAbort(trigger)) }
    override def onFailed(trat: Trait, e: Exception): Unit = obs.foreach { fb => eatExceptions(fb.onFailed(trat, e)) }
  }

  implicit final object Log extends Feedback with TAG.ClassName {
    import Reflow.{logger => log}

    override def onPending(): Unit = log.i("[onPending]")
    override def onStart(): Unit = log.i("[onStart]")
    override def onProgress(progress: Progress, out: Out, fromDepth: Int): Unit = log.i("[onProgress]fromDepth:%s, progress:%s, out:%s.", fromDepth, progress, out)
    override def onComplete(out: Out): Unit = log.w("[onComplete]out:%s.", out)
    override def onUpdate(out: Out): Unit = log.w("[onUpdate]out:%s.", out)
    override def onAbort(trigger: Option[Trait]): Unit = log.w("[onAbort]trigger:%s.", trigger)
    override def onFailed(trat: Trait, e: Exception): Unit = log.e(e, "[onFailed]trat:%s.", trat)
  }
}
