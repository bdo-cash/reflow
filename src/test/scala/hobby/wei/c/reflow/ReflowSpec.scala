package hobby.wei.c.reflow

import org.scalatest.Spec

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends Spec {
  import Traits._

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
      val reflow = Reflow.create(trait0_int2str)
        .next(trans_str2int)
        .next(trait1_int2str)
        .submit(Helper.Kces.add(integer).add(string).ok())
      val out = reflow.start(In.map(Map(integer.key -> 666), trans_int2str), Feedback.Log, null)
        .sync()
      Reflow.shutdown()
    }
  }
}

object Traits {
  lazy val anyRef = new Kce[AnyRef]("anyr") {}
  lazy val integer = new Kce[Integer]("int") {}
  lazy val string = new Kce[String]("str") {}
  lazy val trans_int2str = new Transformer[Integer, String]("int", "str") {
    override def transform(in: Option[Integer]) = in.map(String.valueOf(_))
  }
  lazy val trans_str2int = new Transformer[String, Integer]("str", "int") {
    override def transform(in: Option[String]) = in.map(Integer.valueOf(_))
  }

  lazy val trait0_int2str = new Trait.Adapter {
    override protected def period() = Reflow.Period.TRANSIENT

    override protected def requires() = Helper.Kces.add(integer).ok()

    override protected def outs() = Helper.Kces.add(string).ok()

    override protected def name() = "trait0_int2str"

    override def newTask() = new Task() {
      override protected def doWork(): Unit = {
        output(string.key, String.valueOf(input(integer.key).getOrElse(-1)))
      }
    }
  }

  lazy val trait1_int2str = new Trait.Adapter {
    override protected def period() = Reflow.Period.TRANSIENT

    override protected def requires() = Helper.Kces.add(integer).ok()

    override protected def outs() = Helper.Kces.add(string).ok()

    override protected def name() = "trait1_int2str"

    override def newTask() = new Task() {
      override protected def doWork(): Unit = {
        output(string.key, String.valueOf(input(integer.key).getOrElse(-1)))
      }
    }
  }
}
