package hobby.wei.c.reflow

import org.scalatest.Spec

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends Spec {

  Reflow.setDebugMode(false)

  object `Run Reflow` {
    //    def `execute runnable` {
    //      Reflow.execute(
    //        println("试一试 Reflow.execute(new Runnable {...}。")
    //      )(Reflow.Period.TRANSIENT)
    //    }
    //
    //    def `print Kce[Int] ("abcd")`: Unit = {
    //      println(anyRef)
    //      println(integer)
    //      println(string)
    //      println(trans_int2str)
    //    }

    def `execute created flow`: Unit = {
      val reflow = Reflow.create(trats.int2str0)
        .next(trans.int2str)
        .next(trats.int2str1)
        .next(trans.str2int)
        .submit(Helper.Kces.add(kces.int).add(kces.str).ok())
      val out = reflow.start(In.map(Map(kces.int.key -> 666), trans.str2int, trans.int2str), Feedback.Log, null)
        .sync()
      Reflow.shutdown()
    }
  }
}
