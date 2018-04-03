/*
 * Copyright (C) 2018-present, Wei Chou(weichou2010@gmail.com)
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

import hobby.wei.c.reflow.Feedback.Progress

/**
  * 全局跟踪器。跟踪当前正在运行的任务流。可用于构建全局`任务管理器`。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 03/04/2018
  */
class GlobalTrack(val reflow: Reflow, val scheduler: Scheduler, val isSubReflow: Boolean) extends Equals {
  @volatile private var _progress: Progress = Progress(reflow.basis.traits.size, 0)

  private[reflow] def progress(progress: Progress): GlobalTrack = {
    _progress = progress
    this
  }

  def progress = _progress

  /** 取得[正在]和[将要]任务的任务列表。 */
  def remaining = reflow.basis.traits.drop(_progress.step).ensuring(r => _progress.trat.fold(true)(_ == r.head))

  override def equals(any: Any) = any match {
    case that: GlobalTrack if that.canEqual(this) =>
      (this.reflow eq that.reflow) && (this.scheduler eq that.scheduler) && (this.isSubReflow == that.isSubReflow)
    case _ => false
  }

  override def canEqual(that: Any) = that.isInstanceOf[GlobalTrack]

  override def toString = s"[GlobalTrack]reflow:$reflow, scheduler:$scheduler, isSubReflow:$isSubReflow, progress:$progress."
}
