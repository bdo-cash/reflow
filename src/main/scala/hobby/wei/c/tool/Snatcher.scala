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

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.lang.J2S.NonNull
import java.util.concurrent
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
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
  * @version 1.0, 24/01/2018;
  *          2.0, 07/07/2018, 增加可重入（`reentrant`）能力；
  *          2.1, 08/04/2019, 重构。
  */
class Snatcher {
  private val scheduling = new AtomicBoolean(false)
  private val signature  = new AtomicBoolean(false)
  private val thread     = new AtomicReference[Thread](null)

  /**
    * 线程尝试抢占执行权并执行某任务。
    * <p>
    * 2.0 版本新增了可重入（`reentrant`）能力，使得本方法可以嵌套使用。*** 但这似乎是个伪命题：<br>
    * 多重甚至不可预知的嵌套，意味着有多个不同的任务需要抢占执行权，这是危险的：抢不到执行权的任务会被忽略不执行。***
    * <p>
    * 对于 1.0 版本或者把当前`needReentrant`参数设为`false`时，由于对于同一个线程没有`reentrant`能力，嵌套的`tryOn()`无法执行。
    * 但请注意：本方法仍然仅限用于抢占执行同一个（其它抢占到的线程做的是同样的事情）任务，而不适用于不可预知的嵌套情况。如果有
    * 多个不同的任务，请换用`ActionQueue.queAc()`让其排队执行。
    * <p>
    * 可重入（`reentrant`）能力可能的用法是与`queAc()`结合，并明确知晓嵌套状况，但细节仍需仔细斟酌。2.0 实现仅提供一种选择，但不一定适用。
    *
    * @param doSomething  要执行的任务。
    * @param forReentrant 是否需要可重入（`reentrant`）能力。`true`表示需要，`false`拒绝（默认值）。
    *                     用于执行权已经被抢占而当前可能正处于该线程（当前调用嵌套于正在执行的另一个`doSomething`内）的情况。
    * @return `true`抢占成功并执行任务，`false`抢占失败，未执行任务。
    */
  def tryOn(doSomething: => Unit, forReentrant: Boolean = false): Boolean = {
    // 同一个线程，说明重入（`reentrant`）了。这种情况下，如果本方法的当前调用未结束，则必然处于抢占而未释放的状态中。
    if (forReentrant && tryReentrant()) {
      doSomething
      true
    } else if (snatch()) {
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
    tryLock()
  }

  /**
    * 之前抢占成功（`snatch()`返回`true`）的线程，释放（重置）标识，并再次尝试抢占执行权。
    *
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def glance(): Boolean = {
    // 必须放在sSignature前面，确保不会有某个瞬间丢失调度(外部线程拿不到锁，而本线程认为没有任务了)。
    scheduling.set(false)
    thread.set(null)
    // 再看看是不是又插入了新任务，并重新竞争锁定。
    // 如果不要sSignature的判断而简单再来一次是不是就解决了问题呢？
    // 不能。这个再来一次的问题会递归。
    signature.get && tryLock()
  }

  private def tryLock() = if (scheduling.compareAndSet(false, true)) {
    signature.set(false) // 等竞争到了再置为false.
    thread.set(Thread.currentThread)
    true // continue
  } else false // break

  /**
    * @return 当前是否可重入。
    */
  private def tryReentrant(): Boolean = {
    val t = thread.get
    if (t eq Thread.currentThread) true
    else if (t.isNull) false
    // 情况复杂，不能这样处理。见`Tracker.snatcher4Init`用法。
    // 针对于并行情况，存在多线程同时调用，而有需要`可重入`能力，不能简单的抛出异常。
    // else if (debugMode) throw new ReentrantLockError("非当前线程[`tryOn()`的嵌套情况]，不可启用`可重入`功能。")
    else false
  }
}

object Snatcher {
  class ReentrantLockError(msg: String) extends Error(msg)

  /**
    * 为避免多线程的阻塞，提高运行效率，可使用本组件将`action`队列化（非`顺序化`）。
    * <p>
    * 如果没有`顺序化`需求，可略过后面的说明。但如果有，务必请注意：<br>
    * 本组件并不能保证入队后的[`action`s]的顺序与入队前想要的一致，这不是本组件的缺陷，而是同步锁固有的性质导致了
    * 必然存在这样的问题：`任何同步锁的互斥范围都不可能超过其能够包裹的代码块的范围`。即使本组件的入队操作使用了`公平锁`，也
    * 无法保证外层的顺序需求。要实现顺序性，客户代码有两个选择：<br>
    * 1. 外层代码必须根据具体的业务逻辑另行实现能够保证顺序的`互斥同步`逻辑，并在同步块内执行`queueAction()`操作；
    * 2. 在`queueAction()`的参数`action`所表示的函数体内实现[让输出具有`顺序性`]逻辑。
    * <p>
    * 重申：本组件`能且只能`实现：`避免多线程阻塞，提高执行效率`。
    *
    * @param fluentMode 流畅模式。启用后，在拥挤（队列不空）的情况下，设置了`flag`的`action`将会被丢弃而不执行（除非是最后一个）。默认`不启用`。
    */
  class ActionQueue(val fluentMode: Boolean = false) extends Snatcher with TAG.ClassName {
    private lazy val queue = new concurrent.LinkedTransferQueue[Action[_]]

    private case class Action[T](necessity: () => T, action: T => Unit, canAbandon: Boolean) {
      type A = T
      def execN(): A           = necessity()
      def execA(args: A): Unit = action(args)
    }

    /** queueAction()的简写。 */
    def queAc(action: => Unit): Unit = queAc()(action) { _ => }

    /**
      * 执行`action`或将其放进队列。
      *
      * @param canAbandon 是否可以被丢弃（需配合`fluentMode`使用）。默认为`false`。
      * @param necessity  必须要执行的，不可以`abandon`的。本函数的返回值将作为`action`的输入。
      * @param action     要执行的代码。
      */
    def queAc[T](canAbandon: Boolean = false)(necessity: => T)(action: T => Unit): Unit = {
      def hasMore = !queue.isEmpty
      def newAc   = Action(() => necessity, action, canAbandon)
      def quelem() {
        val elem = newAc
        while (!(queue offer elem)) Thread.`yield`()
      }
      def execAc(elem: Action[_]) {
        val p: elem.A = elem.execN()
        if (fluentMode && elem.canAbandon) { // 设置了`abandon`标识
          if (hasMore) {                     // 可以抛弃
          } else elem.execA(p)
        } else elem.execA(p)
      }
//      if (
//        !tryOn({
//          if (hasMore) quelem()
//          else execAc(newAc)
//          while (hasMore) {
//            execAc(queue.remove())
//          }
//        }, false)
//      ) {
//        quelem() // 放在最后，很可能得不到执行，也会乱序，当然本实现并不解决乱序问题（当前也有乱序问题）。
//      }
      quelem()
      tryOn({
        // 第一次也要检查，虽然前面入队了。因为很可能在当前线程抢占到的时候，自己入队的已经被前一个线程消化掉而退出了。
        while (hasMore) {
          execAc(queue.remove())
        }
      }, false /*必须为`false`，否则调用会嵌套，方法栈会加深。*/)
    }
  }
}
