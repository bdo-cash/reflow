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

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.reflow.Feedback.Progress

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
trait Feedback extends Equals {
  /**
    * 开始执行。
    */
  def onStart(): Unit

  /**
    * 进度反馈。
    *
    * @param progress 进度对象。
    * @param out      进度的时刻已经获得的输出。
    */
  def onProgress(progress: Progress, out: Out): Unit

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
    * @param trigger 触发失败的`Trait`。
    */
  def onAbort(trigger: Trait): Unit

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
    * @param trat 当前`step`对应的`Trait`。可能为`None`，表示某`Task.progress(step, sum)`出来的进度。
    * @param subs 子任务。可以是并行的，所以用了`Seq`。
    */
  case class Progress(sum: Int, step: Int, trat: Option[Trait] = None, subs: Option[Seq[Progress]] = None) {
    require(step < sum || (step == sum && subs.isEmpty))
    require(subs.fold(true)(_.forall(_.nonNull)))

    @inline def progress: Float = (step + subProgress) / sum

    def subProgress: Float = subs.fold[Float](0) { p => p.map(_ ()).sum / p.size }

    @inline def apply(): Float = progress

    override def toString = s"sum:$sum, step:$step, p-main:$progress, p-sub:$subProgress${trat.fold("") { t => s", name:${t.name$}, desc:${t.desc$}" }}."
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
    def wizh(poster: Poster): Feedback = if (poster.isNull) feedback else if (feedback.isNull) feedback else new Feedback {
      require(feedback.nonNull)
      require(poster.nonNull)

      override def onStart(): Unit = poster.post(feedback.onStart())

      override def onProgress(progress: Progress, out: Out): Unit = poster.post(
        feedback.onProgress(progress, out)
      )

      override def onComplete(out: Out): Unit = poster.post(feedback.onComplete(out))

      override def onUpdate(out: Out): Unit = poster.post(feedback.onUpdate(out))

      override def onAbort(trigger: Trait): Unit = poster.post(feedback.onAbort(trigger))

      override def onFailed(trat: Trait, e: Exception): Unit = poster.post(feedback.onFailed(trat, e))
    }
  }

  class Adapter extends Feedback {
    override def onStart(): Unit = {}

    override def onProgress(progress: Progress, out: Out): Unit = {}

    override def onComplete(out: Out): Unit = {}

    override def onUpdate(out: Out): Unit = {}

    override def onAbort(trigger: Trait): Unit = {}

    override def onFailed(trat: Trait, e: Exception): Unit = {}
  }

  class Observable extends Adapter {
    import Assist.eatExceptions

    @volatile
    private var obs: Seq[Feedback] = Nil //scala.collection.concurrent.TrieMap[Feedback, Unit] //CopyOnWriteArraySet[Feedback]

    def addObservers(fbs: Feedback*): Unit = obs = (obs.to[mutable.LinkedHashSet] ++= fbs.map(_.ensuring(_.nonNull))).toSeq

    def removeObservers(fbs: Feedback*): Unit = obs = (obs.to[mutable.LinkedHashSet] --= fbs.map(_.ensuring(_.nonNull))).toSeq

    override def onStart(): Unit = obs.foreach { fb => eatExceptions(fb.onStart()) }

    override def onProgress(progress: Progress, out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onProgress(progress, out)) }

    override def onComplete(out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onComplete(out)) }

    override def onUpdate(out: Out): Unit = obs.foreach { fb => eatExceptions(fb.onUpdate(out)) }

    override def onAbort(trigger: Trait): Unit = obs.foreach { fb => eatExceptions(fb.onAbort(trigger)) }

    override def onFailed(trat: Trait, e: Exception): Unit = obs.foreach { fb => eatExceptions(fb.onFailed(trat, e)) }
  }

  implicit object Log extends Feedback with TAG.ClassName {
    import Reflow.{logger => log}

    override def onStart(): Unit = log.i("[onStart]")

    override def onProgress(progress: Progress, out: Out): Unit = log.i("[onProgress]progress:%s, out:%s.", progress, out)

    override def onComplete(out: Out): Unit = log.w("[onComplete]out:%s.", out)

    override def onUpdate(out: Out): Unit = log.w("[onUpdate]out:%s.", out)

    override def onAbort(trigger: Trait): Unit = log.w("[onAbort]trigger:%s.", trigger)

    override def onFailed(trat: Trait, e: Exception): Unit = log.e(e, "[onFailed]trat:%s.", trat)
  }
}
