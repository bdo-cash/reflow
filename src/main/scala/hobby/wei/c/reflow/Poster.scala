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
  * 用于将{Feedback}传送到目标线程(如UI线程)去运行的时光机。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 23/07/2016
  */
trait Poster {
  def post(f: => Unit): Unit = post(new Runnable {
    override def run(): Unit = f
  })

  def post(run: Runnable): Unit

  // TODO: 16/7/24 增加三种策略（Policy）：1. 丢弃拥挤的消息（默认。参见 EasyCache 项目）；2. 基于子进度的深度；3. 基于反馈时间间隔（这个构建于策略1之上）。
  // 但应该仅针对于 feedback.onProgress(name, out, step, sum, subProgress, desc) 的 subProgress。
  // 写在哪里，怎么写？
}
