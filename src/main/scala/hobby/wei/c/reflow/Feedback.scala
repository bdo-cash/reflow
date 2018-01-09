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

import java.util.concurrent.CopyOnWriteArraySet
import hobby.chenai.nakam.lang.J2S.NonNull

import scala.collection.JavaConversions.asScalaSet

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
trait Feedback {
  def onStart(): Unit

  /**
    *
    * @param name        正在执行(中途会更新进度)或完成的任务名称。来源于`Trait#name()`。
    * @param out         进度的时刻已经获得的输出。
    * @param count       任务计数。
    * @param sum         任务流总数。用于计算主进度(%): `count * 1f / sum`, 和总进度(%): `(count + sub) / sum`。
    * @param sub         任务内部进度值。
    * @param description 任务描述`Trait#description()`。
    */
  def onProgress(name: String, out: Out, count: Int, sum: Int, sub: Float, description: String): Unit

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
  class Adapter extends Feedback {
    override def onStart(): Unit = {}

    override def onProgress(name: String, out: Out, count: Int, sum: Int, sub: Float, description: String): Unit = {}

    override def onComplete(out: Out): Unit = {}

    override def onUpdate(out: Out): Unit = {}

    override def onAbort(): Unit = {}

    override def onFailed(name: String, e: Exception): Unit = {}
  }

  class Observable extends Adapter {
    private val obs = new CopyOnWriteArraySet[Feedback]

    def addObserver(fb: Feedback) = obs.add(fb.ensuring(_.nonNull))

    def removeObserver(fb: Feedback) = obs.remove(fb.ensuring(_.nonNull))

    override def onStart(): Unit = obs.toSeq.foreach(_.onStart())

    override def onProgress(name: String, out: Out, count: Int, sum: Int, sub: Float, description: String): Unit =
      obs.toSeq.foreach(_.onProgress(name, out, count, sum, sub, description))

    override def onComplete(out: Out): Unit = obs.toSeq.foreach(_.onComplete(out))

    override def onUpdate(out: Out): Unit = obs.toSeq.foreach(_.onUpdate(out))

    override def onAbort(): Unit = obs.toSeq.foreach(_.onAbort())

    override def onFailed(name: String, e: Exception): Unit = obs.toSeq.foreach(_.onFailed(name, e))
  }
}
