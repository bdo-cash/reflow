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

/**
  * For Android:
  * <code>
  * android.os.Process.setThreadPriority(thread.getId(), android.os.Process.THREAD_PRIORITY_BACKGROUND);
  * </code>
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 05/01/2018
  */
trait ThreadResetor {
  /**
    * @param thread             需要被重置的线程。
    * @param beforeOrOfterWork  在运行任务之前还是之后。`true`表示之前。
    * @param runOnCurrentThread 都是本调用在`thread`指定的线程内。
    */
  def reset(thread: Thread, beforeOrOfterWork: Boolean, runOnCurrentThread: Boolean): Unit = {
    if (thread.getPriority != Thread.NORM_PRIORITY) thread.setPriority(Thread.NORM_PRIORITY)
  }
}
