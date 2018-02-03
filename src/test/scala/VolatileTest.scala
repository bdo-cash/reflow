import java.util.concurrent.{Callable, Executors}
import java.util.concurrent.atomic.AtomicBoolean

/**
  * 一个用于证明`AtomicXxx.get`在多线程中可靠性的验证方法。
  *
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 03/02/2018
  */
object VolatileTest extends App {
  val executor = Executors.newFixedThreadPool(2)

  var count = 0

  while (true) {
    val a = new AtomicBoolean(false)
    val b = new AtomicBoolean(false)

    def test(): Boolean = a.get() && b.get()

    val callA = new Callable[Boolean] {
      override def call() = {
        Thread.sleep(0, (math.random * 10).toInt)
        a.set(true)
        test()
      }
    }
    val callB = new Callable[Boolean] {
      override def call() = {
        Thread.sleep(0, (math.random * 5).toInt)
        b.set(true)
        test()
      }
    }
    val fa = executor.submit(callA)
    val fb = executor.submit(callB)

    if (fa.get() || fb.get()) {
      // 符合预期。
    } else throw new Exception("AtomicXxx.get 不可靠。")

    count += 1
    println(s"done. count:$count, fa:${fa.get}, fb:${fb.get}.")
  }
}
