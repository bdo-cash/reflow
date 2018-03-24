package hobby.wei.c.reflow

import hobby.chenai.nakam.lang.J2S._
import hobby.wei.c.reflow.Reflow.Period
import org.scalatest.AsyncFlatSpec

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends AsyncFlatSpec {
  info("Reflow 的异步调用支持简写：")

  behavior of "call submit {...}"

  it should "异步执行, 并返回预期结果。" in {
    lazy val value: String = "一个预期的结果"
    Reflow.submit {
      Thread.sleep(1000)
      value // 返回
    }(Period.SHORT) map { v =>
      assertResult(value)(v)
    }
  }

  info("但对于关系复杂任务集，应该使用 Dependency 构建依赖/并行关系：")

  //  {
  //    //    def `execute runnable` {
  //    //      Reflow.execute(
  //    //      )(Reflow.Period.TRANSIENT)
  //    //    }
  //    //
  //    //    def `print Kce[Int] ("abcd")`: Unit = {
  //    //      println(anyRef)
  //    //      println(integer)
  //    //      println(string)
  //    //      println(trans_int2str)
  //    //    }
  //
  //    def `execute created flow`: Unit = {
  //      Reflow.setDebugMode(false)
  //      val reflow = Reflow.create(trats.int2str0)
  //        .next(trans.int2str)
  //        .next(trats.int2str1)
  //        .next(trans.str2int)
  //        .submit(Helper.Kces.add(kces.int).add(kces.str).ok())
  //      val out = reflow.start(In.map(Map(kces.int.key -> 666), trans.str2int, trans.int2str), Feedback.Log, null)
  //        .sync()
  //      Reflow.shutdown()
  //    }
  //  }
}
