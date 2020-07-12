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
  * 用于将`Feedback`传送到目标线程（如：UI 线程）去运行的时光机。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 23/07/2016
  */
trait Poster {
  def post(f: => Unit): Unit = post(() => f)

  def post(runner: Runnable): Unit // 可这样作默认实现 = runner.run()
}
