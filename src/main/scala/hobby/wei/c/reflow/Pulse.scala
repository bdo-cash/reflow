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

import hobby.chenai.nakam.basis.TAG.ThrowMsg
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.tool
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Feedback.Progress

import scala.util.DynamicVariable

/**
  * @param reflow           每个`流处理器`都`Base`在一个主任务流上。
  * @param interruptIfError 当有一次输入出现错误时，是否中断。默认为`false`。
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 01/07/2018
  */
// feedback: Feedback 这里应该用一个全新的 feedback。
class Pulse(reflow: Reflow, interruptIfError: Boolean = false /*, feedback*/)(implicit policy: Policy, poster: Poster) {
  private lazy val snatcher = new tool.Snatcher.ActionQueue()

  //  class X {
  //    val queue: LinkedTransferQueue[Trait] = new LinkedTransferQueue[Trait]()
  //  }

  @volatile var prev: Option[Tactic] = None

  def input(in: In): Unit = Reflow.submit {
    // 先进行调度
    snatcher.queueAction {
      // 无论我是不是第一个
      prev = Option(new Tactic(prev))
      prev.get.start(in)
    }
  }(SHORT)

  class Tactic(private var prev: Option[Tactic]) {
    private lazy val snatcher = new tool.Snatcher.ActionQueue()
    @volatile private var follower: Tactic = _

    val xxx = new DynamicVariable[Option[Tactic]](prev)

    private lazy val feedback: Feedback = new Feedback.Adapter {
      override def onProgress(progress: Feedback.Progress, out: Out, depth: Int): Unit = {
        super.onProgress(progress, out, depth)
        snatcher.queueAction {
          if (follower.isNull) 状态存起来
          else follower.onEvolve(progress, cache)
        }
      }

      override def onComplete(out: Out): Unit = {
        super.onComplete(out)

        // 本流程结束。
        // TODO: 解决`prev`的释放问题。由于总是会引用前一个，会造成内存泄露。
        prev = null
      }

      override def onUpdate(out: Out): Unit = {
        super.onUpdate(out)
        assert(false, "对于`Pulse`中的`Reflow`，不应该走到`onUpdate`这里".tag)
      }
    }

    // `in`参数放在这里以缩小作用域。
    def start(in: In): Unit = {
      val scheduler = reflow.as[Reflow.Impl].start(in, feedback)(
        Policy.FullDose /*全量进度，以便下一个输入可以在任何深度确认是否可以运行某任务了。*/ ,
        null /*不使用外部客户代码提供的`poster`，以确保`feedback`和`cacheBack`反馈顺序问题。*/)
    }.as[Scheduler.Impl]

    //    scheduler.

    // TODO: 在每一步开始之前，需要镜像复制 ReinforceCache.caches 的内容。


    def setFollower(tactic: Tactic): Unit = snatcher.queueAction {
      follower = tactic
    }

    def onEvolve(progress: Progress, cache: Out): Unit = snatcher.queueAction {
      // TODO: 前一个的状态来了，存起来，并看自己有没有挂起，恢复之；并在当自己询问是否前进的时候，给于回答。
    }
  }
}

object Pulse {
  private[reflow] trait Interact {
    def onRoadmap(depth: Int, cache: Out): Unit
  }
}
