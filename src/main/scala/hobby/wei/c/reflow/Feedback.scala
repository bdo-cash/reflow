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
import hobby.chenai.nakam.tool.pool.S._2S

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
trait Feedback {
  def onStart(): Unit

  /**
    *
    * @param name 正在执行(中途会更新进度)或完成的任务名称。来源于`Trait#name()`。
    * @param out  进度的时刻已经获得的输出。
    * @param step 任务计数。
    * @param sum  任务流总数。用于计算主进度(%): `step * 1f / sum`, 和总进度(%): `(step + sub) / sum`。
    * @param sub  任务内部进度值。
    * @param desc 任务描述`Trait#desc()`。
    */
  def onProgress(name: String, out: Out, step: Int, sum: Int, sub: Float, desc: String): Unit

  def onComplete(out: Out): Unit

  /**
    * 强化运行完毕之后的最终结果。
    *
    * @see Task#requireReinforce()
    */
  def onUpdate(out: Out): Unit

  def onAbort(): Unit

  /**
    * 任务失败。
    *
    * @param name 见`Trait#name()`。
    * @param e    分为两类:
    *             第一类是客户代码自定义的Exception, 即显式传给`Task#failed(Exception)`方法的参数, 可能为null;
    *             第二类是由客户代码质量问题导致的RuntimeException, 如`NullPointerException`等,
    *             这些异常被包装在`CodeException`里, 可以通过`CodeException#getCause()`方法取出具体异对象。
    */
  def onFailed(name: String, e: Exception): Unit
}

object Feedback {
  implicit class WithPoster(feedback: Feedback) {
    @inline def withPoster(poster: Poster): Feedback = if (poster.isNull) feedback else if (feedback.isNull) feedback else new Feedback {
      require(feedback.nonNull)
      require(poster.nonNull)

      override def onStart(): Unit = poster.post(feedback.onStart())

      override def onProgress(name: String, out: Out, step: Int, sum: Int, sub: Float, desc: String): Unit = poster.post(
        feedback.onProgress(name, out, step, sum, sub, desc)
      )

      override def onComplete(out: Out): Unit = poster.post(feedback.onComplete(out))

      override def onUpdate(out: Out): Unit = poster.post(feedback.onUpdate(out))

      override def onAbort(): Unit = poster.post(feedback.onAbort())

      override def onFailed(name: String, e: Exception): Unit = poster.post(feedback.onFailed(name, e))
    }
  }

  class Adapter extends Feedback {
    override def onStart(): Unit = {}

    override def onProgress(name: String, out: Out, step: Int, sum: Int, sub: Float, desc: String): Unit = {}

    override def onComplete(out: Out): Unit = {}

    override def onUpdate(out: Out): Unit = {}

    override def onAbort(): Unit = {}

    override def onFailed(name: String, e: Exception): Unit = {}
  }

  class Observable extends Adapter {
    @volatile
    private var obs: Seq[Feedback] = Nil //scala.collection.concurrent.TrieMap[Feedback, Unit] //CopyOnWriteArraySet[Feedback]

    def addObservers(fbs: Feedback*): Unit = obs = (obs.to[mutable.LinkedHashSet] ++= fbs.map(_.ensuring(_.nonNull))).toSeq

    def removeObservers(fbs: Feedback*): Unit = obs = (obs.to[mutable.LinkedHashSet] --= fbs.map(_.ensuring(_.nonNull))).toSeq

    override def onStart(): Unit = obs.foreach(_.onStart())

    override def onProgress(name: String, out: Out, step: Int, sum: Int, sub: Float, desc: String): Unit =
      obs.foreach(_.onProgress(name, out, step, sum, sub, desc))

    override def onComplete(out: Out): Unit = obs.foreach(_.onComplete(out))

    override def onUpdate(out: Out): Unit = obs.foreach(_.onUpdate(out))

    override def onAbort(): Unit = obs.foreach(_.onAbort())

    override def onFailed(name: String, e: Exception): Unit = obs.foreach(_.onFailed(name, e))
  }

  object Log extends Feedback with TAG.ClassName {
    import Reflow.{logger => log}

    override def onStart(): Unit = log.i("[onStart]")

    override def onProgress(name: String, out: Out, step: Int, sum: Int, sub: Float, desc: String): Unit =
      log.i("[onProgress]step:%s, sub:%s, sum:%s, name:%s, desc:%s, out:%s.", step, sub, sum, name.s, desc.s, out)

    override def onComplete(out: Out): Unit = log.w("[onComplete]out:%s.", out)

    override def onUpdate(out: Out): Unit = log.w("[onUpdate]out:%s.", out)

    override def onAbort(): Unit = log.w("[onAbort]")

    override def onFailed(name: String, e: Exception): Unit = log.e(e, "[onFailed]name:%s.", name.s)
  }
}
