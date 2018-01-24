package hobby.wei.c.tool

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.Breaks._

/**
  * 用于多个线程竞争去做某些事情，但这些事情只需要一个线程做即可（即：只要有人做就ok），其它线程不必等待或阻塞。
  * 但同时也要避免遗漏：{{{
  * 当做事的线程A认为做完了准备收工的时候，又来的新任务，但此时A还没切换标识（即：A说我正做着呢），
  * 导致其它线程认为有人在做而略过的情况。
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
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 24/01/2018
  */
class Snatcher {
  private val scheduling = new AtomicBoolean(false)
  private val signature = new AtomicBoolean(false)

  /** 线程尝试抢占执行权并做某事。 */
  def tryOn[T](doSomething: => T): Option[T] = {
    var result: Option[T] = None
    if (snatch()) {
      breakable {
        while (true) {
          result = Option(doSomething)
          if (!glance()) break
        }
      }
    }
    result
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
      // continue
      true
    } else false
  }
}
