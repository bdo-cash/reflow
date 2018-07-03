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

import hobby.wei.c.tool
import hobby.wei.c.reflow.Feedback.Progress.Policy
import implicits._

/**
  * @param reflow           每个`流处理器`都`Base`在一个主任务流上。
  * @param interruptIfError 当有一次输入出现错误时，是否中断。默认为`false`。
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 01/07/2018
  */
// feedback: Feedback 这里应该用一个全新的 feedback。
class Stream(reflow: Reflow, interruptIfError: Boolean = false/*, feedback*/)(implicit policy: Policy, poster: Poster) {
  private lazy val snatcher = new tool.Snatcher.ActionQueue()

  //  class X {
  //    val queue: LinkedTransferQueue[Trait] = new LinkedTransferQueue[Trait]()
  //  }

  @volatile var prev: Option[Tactic] = None

  def input(in: In): Unit = Reflow.submit {
    // 先进行调度
    snatcher.queueAction {
      // 无论我是不是第一个
      prev = Option(new Tactic(prev, in))
    }
  }(SHORT)

  class Tactic(prev: Option[Tactic], in: In) {
    val feedback: Feedback = ???
    reflow.start(in, feedback)(
      Policy.FullDose /*全量进度，以便下一个输入可以在任何深度确认是否可以运行某任务了。*/ , poster)
  }
}

object Stream {
}
