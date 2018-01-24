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

package hobby.wei.c.tool

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.Breaks._

/**
  * 用于多个线程竞争去执行某个任务，但这个任务只需要任意一个线程执行即可（即：主要是做事，只要有人做就ok），其它线程不必等待或阻塞。
  * 但同时也要避免遗漏：{{{
  * 当执行任务的线程A认为做完了准备收工的时候，又来了新任务，但此时A还没切换标识（即：A说我正做着呢），
  * 导致其它线程认为有人在做而略过，而A接下来又收工了的情况。
  * }}}
  * 用法示例：{{{
  * val snatcher = new Snatcher()
  * if (snatcher.snatch()) {
  *   breakable {
  *     while (true) {
  *       // do something ...
  *       if (!snatcher.glance()) break
  *     }
  *   }
  * }
  * }}}
  * 或：{{{
  * val snatcher = new Snatcher()
  * snatcher.tryOn {
  *   // do something ...
  * }
  * }}}
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 24/01/2018
  */
class Snatcher {
  private val scheduling = new AtomicBoolean(false)
  private val signature = new AtomicBoolean(false)

  /**
    * 线程尝试抢占执行权并执行某任务。
    *
    * @return `true` 抢占成功并执行任务，`false`抢占失败，未执行任务。
    */
  def tryOn(doSomething: => Unit): Boolean = {
    if (snatch()) {
      breakable {
        while (true) {
          doSomething
          if (!glance()) break
        }
      }
      true
    } else false
  }

  /**
    * 线程尝试抢占执行权。
    *
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def snatch(): Boolean = {
    signature.set(true) // 必须放在前面。标识新的调度请求，防止遗漏。
    if (scheduling.compareAndSet(false, true)) {
      signature.set(false)
      true
    } else false
  }

  /**
    * 之前抢占成功（`snatch()`返回`true`）的线程，释放（重置）标识，并再次尝试抢占执行权。
    *
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def glance(): Boolean = {
    // 必须放在sSignature前面，确保不会有某个瞬间丢失调度(外部线程拿不到锁，而本线程认为没有任务了)。
    scheduling.set(false)
    // 再看看是不是又插入了新任务，并重新竞争锁定。
    // 如果不要sSignature的判断而简单再来一次是不是就解决了问题呢？
    // 不能。这个再来一次的问题会递归。
    if (signature.get() && scheduling.compareAndSet(false, true)) {
      signature.set(false) // 等竞争到了再置为false.
      true // continue
    } else false // break
  }
}
