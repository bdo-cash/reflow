package hobby.wei.c.reflow

import org.scalatest.Spec

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends Spec {
  Reflow.setDebugMode(true)
  object `Run Reflow` {
//    def `execute runnable` {
//      Reflow.execute(new Runnable {
//        override def run(): Unit = println("试一试 Reflow.execute(new Runnable {...}。")
//      })
//    }

    def `print Kce[Int] ("abcd")`: Unit = {
      println(new Kce[AnyRef]("anyref") {})
      println(new Kce[Integer]("integer") {})
      println(new Kce[String]("string") {})
      println(new Transformer[Integer, String]("int", "str") {
        override protected def transform(in: Integer) = String.valueOf(in)
      })
    }

    def `execute created flow`: Unit = {
      val reflow = Reflow.create(Traits.trait0)
        .submit(Helper.Keys.add(new Kce[Integer]("int0") {}).ok())
      println(reflow)
      val out = reflow.start(In.empty(), Feedback.Log, null)
        .sync()
      println(out)
    }
  }
}
