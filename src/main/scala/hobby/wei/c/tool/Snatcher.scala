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
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.log.Logger._
import hobby.wei.c.reflow.Reflow.{logger => log}
import java.util.concurrent
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.annotation.tailrec
import scala.util.control.Breaks._

/**
  * 本组件是一个无锁（lock-free）线程同步工具。用于处理如下场景：
  * 用于多个线程竞争去执行某个任务，但这个任务只需要任意一个线程执行即可（只要有人做就 ok，无论是谁），其它线程不必等待或阻塞。
  * 但同时也要避免遗漏（信号标志对任意线程要`可见`）：{{{
  *   当执行任务的线程 a 认为做完了准备收工的时候，又来了新任务，但此时 a 还没切换标识，导致其它线程认为有人在做而略过，而 a 接下来又收工了的情况。
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
  *          2.1, 08/04/2019, 重构；
  *          2.1, 30/12/2021, 重构，并加入`tryOns()`。
  */
class Snatcher extends TAG.ClassName {
  private val scheduling = new AtomicBoolean(false)
  private val signature  = new AtomicBoolean(false)

  private lazy val thread = new AtomicReference[Thread]
  private lazy val serial = new AtomicLong(0)

  /**
    * 线程尝试抢占执行权并执行某任务。
    * <p>
    * 2.0 版本新增了可重入（`reentrant`）能力，使得本方法可以嵌套使用。但这似乎是个伪命题：<br>
    * 多重嵌套代码块（往往是不同且不可预知的），意味着有多个不同的任务需要抢占执行权，这是危险的：抢不到执行权的任务会被忽略不执行。
    * 所以仅用于有需求的殊设计的代码块。
    * <p>
    * 对于 1.0 版本或当前`reentrant = false`时，嵌套的`tryOn()`无法执行。
    * 但请注意：本方法仍然仅限用于抢占执行同一个（其它抢占到的线程做的是同样的事情）任务，而不适用于不可预知的嵌套情况。如有
    * 多个不同的任务，请换用`ActionQueue.queAc()`让其排队执行。
    * <p>
    * @param doWork    要执行的任务。本代码块[需要能够被]执行任意多次而不会对逻辑有未预期的影响，而且不应该携带参数，如有需要，可使用[[tryOns]]。
    * @param reentrant 是否需要可重入能力。`true`表示需要，`false`拒绝（默认值）。
    *                  用于执行权已经被抢占而当前可能正处于该线程（当前调用嵌套于正在执行的另一个`doSomething`内）的情况。
    * @return `true`抢占成功并执行任务完成，`false`抢占失败，未执行任务。
    */
  def tryOn(doWork: => Unit, reentrant: Boolean = false): Boolean = {
    if (reentrant && tryReentrant()) {
      doWork; true
    } else if (snatch()) {
      breakable { while (true) { doWork; if (!glance()) break } }
      true
    } else false
  }

  /** 本方法是[[tryOn]]的健壮版本。
    * <p>
    * 在多线程环境中，常常面临时序和可见性问题，如：a 线程尝试调用[[tryOn]](doWork)，而此时 b 线程占据执行权，a 并未抢占成功。
    * 在这种情况下，a 携带的对 a 可见的数据`x`，对 b 而言不一定可见，因此 b 线程有可能错过处理`x`而导致`x`一直得不到处理。因此，对于
    * 代码块`{doWork}`中需要从其它集合中遍历或读取数据的，建议使用本方法。
    * <p>
    * 用法示例：{{{
    * tryOns(serial(x)) {
    *   // do sth officially …
    *   (serial(a), serial(b), …, serial(y)).max
    * }
    * }}}
    * @param incr 可见性(visible)标志，任意希望被其它线程`看见`的最新值[数字表示(`serial(x)`)]。
    *             竞争到执行权的线程在集合中看见该值，则表示达到线程同步目标（处理如否看需求，看见即可）；
    *             取值范围：`<= 0`无效，会被忽略（或认为不需要同步），`> 0`时才进行判断。
    * @param reentrant 同[[tryOn]]，不作可见性同步操作。
    * @param doWork 要执行的代码块，返回值表示在该次运行中`看见`的所有值的[数字表示]的最大值。隐含的逻辑是：
    *               `serial(n)`要能够被比较大小，只要返回值`> incr`就表示同步完成。取值范围同`incr`。
    *               重申：本代码块[需要能够被]执行任意多次而不会对逻辑有未预期的影响，而且不应该携带参数，如有需要，可先插入集合，再运用本机制进行可见性同步。
    * @return 同[[tryOn]]，`true`抢占成功并执行任务完成，`false`抢占失败，未执行任务。
    */
  def tryOns(incr: Long = -1, reentrant: Boolean = false)(doWork: => Long): Boolean = {
    if (incr > 0) serial.set(incr)
    if (reentrant && tryReentrant()) {
      doWork; true
    } else if (snatch()) {
      breakable {
        var i = 0; var n = 0L
        while (true) {
          val r = doWork
          val b =
            if (r > 0) { n = serial.get; if (n > 0) r >= n else true }
            else true
          debug.set((r, serial.get))
          if (/*b &&*/ !glance()) break
          if (b) { i = 0 }
          else {
            if (!signature.get) {
              log.w("[tryOns] WAIT !!! | %s | r:%s, n:%s.", i, r, n)
              Thread.`yield`()
            }
            i += 1
          }
        }
      }
      true
    } else false
  }

  private lazy val debug = new AtomicReference[(Long, Long)]((0, 0))

  override def finalize(): Unit = {
    val (r, n) = debug.get
    if (r > 0 && r < n) {
      log.w("========== ========== ========== ========== ========== ========== ========== ========== ========== ==========")
      log.e("[tryOns] 没有达到最终一致性 !!! | r:%s, n:%s.", r, n)
      log.i("========== ========== ========== ========== ========== ========== ========== ========== ========== ==========")
    }
    super.finalize()
  }

//  private lazy val volatile = new AtomicReference[(AnyRef, WeakHashSet[AnyRef])]((null, new WeakHashSet[AnyRef]))
//  def tryOns(x: AnyRef)(doWork: => Set[AnyRef]): Boolean = {
//    // 这一版实现过于复杂，且并没有解决问题：如果不用`WeakHashSet`，会导致内存很容易超限，但使用了，又会导致对象
//    // 过早（先于 x 被设置进 volatile）被 GC。然后，解决该问题其实很简单：调整执行顺序即可，但又势必会造成`可见`时间延长，循环会执行更多次。
//    // 前者的设计是基于[先把对象放进集合，再调用本方法]，从而引发了本方法的复杂实现；现改为[在放入集合前，先调用本方法]，按照本方法的要求实现客户代码即可。
//    // 实测，瓶颈在于`doWork`返回的 Set 元素对象创建次数过多，所以不能使用本模型。
//    volatile.updateAndGet { case (_, set) => if ((x ne null) && set.contains(x)) (null, set - x) else (x, set) }
//    //volatile.set(x.as); toColl(x)
//    if (snatch()) {
//      breakable {
//        var i   = 0
//        var b   = true
//        var x   = null.asInstanceOf[AnyRef]
//        val set = new WeakHashSet[AnyRef]
//        while (true) {
//          set ++= doWork
//          // 存在[前一轮已经消化了但`glance()`信号还在]，所以又重来了一遍，就有`vol eq null`了。
//          // 存在[多次看见已经被消化过的 vol]，即 set 多次出现上一轮已经出现的值，亦即 set 可能总是`nonEmpty`，这是正常的：何时处理这些值取决于具体业务逻辑。
//          // 由于 vol 在时间顺序上比 set 晚（而且每多一遍`cas()`就更晚一次），所以本`cas()`方法总是倾向于返回`false`，这是符合预期的。
//          // 如果 vol 已经被消化了，需要置为 null，但可能同时又被其它线程设置了新值，so… 而如果没有被消化，当然要保留该值不要抹去。
//          var vol = null.asInstanceOf[AnyRef]
//          val (stable, _) = visWait {
//            val p = volatile.get; vol = p._1; if (b) { set ++= p._2; b = false }
//            ((vol eq null) || set.contains(vol), p) // 不要用`.exists(_ eq vol)`，原因见使用案例。
//          } { case (cons, p) =>
//            !cons || volatile.compareAndSet(p, (null, set))
//          }(i => if (i < 7 && !(i > 3 && set.size > 64)) "" else s"set.size:${set.size}, x:$vol")
//
//          // stable 是，set 在包含 vol 的同时也可能包含刚刚设置进 volatile 的最新的 vol（但还未被读取到而清除），所以需要留给下一次（抢占到执行权的线程）。
//          //   深层次的原因是：方法开头的`x`还没被设置进 volatile 之前，就已经被放入`doWork`所操作的集合中，而此时另一个线程正在执行`doWork`且
//          //   恰好被`看见`了，所以就处理并返回了 set。
//          // !stable 时，表明所有旧的（和当前的）set 里都没有 vol，那 vol 一定是最新的，因此是时候安全地删除所有旧的 set 了。
//          //if (stable) { /*prevSet.set(set)*/ } else { set.clear } // 挪到下边了
//
//          if (stable && !glance()) break
//          if (stable) { i = 0; x = null; b = true }
//          else {
//            signature.set(false)
//            if (x == vol || i > 0) log.w("[tryOns] WAIT !!! | %s | set.size:%s, x:%s.", i, set.size, vol.toString.s) // vol 有可能本身就是 String。
//            i += 1; x = vol; set.clear
//            Thread.`yield`()
//          }
//        }
//      }
//      true
//    } else false
//  }

  /** 线程尝试抢占执行权。
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def snatch(): Boolean = {
    signature.set(true) // 必须放在前面。标识新的调度请求，防止遗漏。
    tryLock()
  }

  /** 之前抢占成功（`snatch()`返回`true`）的线程，释放（重置）标识，并再次尝试抢占执行权。
    * @return `true` 抢占成功，`false`抢占失败。
    */
  def glance(): Boolean =
    // 如果返回 false，说明`signature`本来就是 false。加这个判断的目的是减少不必要的原子操作（也是个不便宜的操作，好几个变量）。
    if (signature.compareAndSet(true, false)) {
      true.ensuring(scheduling.get, "调用顺序不对？请参照示例。")
    } else {
      thread.set(null)
      // 必须放在`signature`前面，确保不会有某个瞬间丢失调度（外部线程拿不到锁，而本线程认为没有任务了）。
      scheduling.set(false)
      // 再看看是不是又插入了新任务，并重新竞争锁定。
      // 如果不要`signature`的判断而简单再来一次是不是就解决了问题呢？
      // 不能。这个再来一次的问题会递归。
      signature.get && tryLock()
    }

  private def tryLock() =
    if (scheduling.compareAndSet(false, true)) {
      signature.set(false) // 等竞争到了再置为false.
      thread.set(Thread.currentThread)
      true       // continue
    } else false // break

  /** @return 当前是否可重入。 */
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

  def visWait[T](waitFor: => T)(cond: T => Boolean)(msg: Int => String)(implicit tag: LogTag): T = {
    @tailrec def cas(i: Int = 0): T = {
      val o = waitFor
      if (cond(o)) o
      else {
        val m = msg(i); if (m.nonEmpty) log.w("[visWait] WAIT !!! | %s | %s.", i, m.s)
        Thread.`yield`(); cas(i + 1)
      }
    }
    cas()
  }

  /**
    * 为避免多线程的阻塞，提高运行效率，可使用本组件将`action`队列化（非`顺序化`）。
    * <p>
    * 如果没有`顺序化`需求，可略过后面的说明。但如果有，务必请注意：<br>
    * 本组件并不能保证入队后的[`action`s]的顺序与入队前想要的一致，这不是本组件的缺陷，而是同步锁固有的性质导致了
    * 必然存在这样的问题：`任何同步锁的互斥范围都不可能超过其能够包裹的代码块的范围`。即使本组件的入队操作使用了`公平锁`，也
    * 无法保证外层的顺序需求。要实现顺序性，客户代码有两个选择：<br>
    * 1. 外层代码必须根据具体的业务逻辑另行实现能够保证顺序的`互斥同步`逻辑，并在同步块内执行`queAc()`操作；
    * 2. 在`queAc()`的参数`action`所表示的函数体内实现[让输出具有`顺序性`]逻辑。
    * <p>
    * 重申：本组件`能且只能`实现：`避免多线程阻塞，提高执行效率`。
    *
    * @param fluentMode 流畅模式。启用后，在拥挤（队列不空）的情况下，设置了`flag`的`action`将会被丢弃而不执行（除非是最后一个）。默认`不启用`。
    */
  class ActionQueue(val fluentMode: Boolean = false) extends Snatcher {
    private val queue = new concurrent.LinkedTransferQueue[Action[_]]
    private val incr  = new AtomicLong(0)

    private case class Action[T](necessity: () => T, action: T => Unit, canAbandon: Boolean, serial: Long = incr.incrementAndGet) {
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
      def quelem(): Action[_] = {
        val elem = newAc
        while (!(queue offer elem)) Thread.`yield`()
        elem
      }
      def execAc(elem: Action[_]): Action[_] = {
        val p: elem.A = elem.execN()
        if (fluentMode && elem.canAbandon) { // 设置了`abandon`标识
          if (hasMore) {                     // 可以抛弃
          } else elem.execA(p)
        } else elem.execA(p)
        elem
      }
      def serial(elem: Action[_]): Long = elem.serial

      tryOns(serial(quelem())) {
        var n = incr.get
        while (hasMore) {
          n = n max serial(execAc(queue.remove()))
        }; n
      }

//      quelem() // format: off
//      tryOn({
//        // 第一次也要检查，虽然前面入队了。因为很可能在当前线程抢占到的时候，自己入队的已经被前一个线程消化掉而退出了。
//        while (hasMore) {
//          execAc(queue.remove())
//        }
//      }, false /*必须为`false`，否则调用会嵌套，方法栈会加深。*/)

//      if (!tryOn({
//        if (hasMore) quelem()
//        else execAc(newAc)
//        while (hasMore) {
//          execAc(queue.remove())
//        }
//      }, false)) {
//        quelem() // 放在最后，很可能得不到执行，也会乱序，当然本实现并不解决乱序问题（需自行从逻辑设计角度解决）。
//      }
      // format: on
    }
  }
}
