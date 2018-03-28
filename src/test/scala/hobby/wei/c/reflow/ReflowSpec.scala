package hobby.wei.c.reflow

import hobby.wei.c.reflow.Reflow.Period
import hobby.wei.c.reflow.implicits.InAddK._
import hobby.wei.c.reflow.implicits.KceAdd._
import org.scalatest._

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    Reflow.setDebugMode(true)
    Reflow.setConfig(Config(1, 1))
  }

  info("------------------------- 简介 -------------------------")
  info("Reflow 是一个`任务串并联`组合调度框架。")

  info("`数据`就是`电流`，而`任务（Task）`可以看做电路元器件；")

  info("`任务`在组装`提交（submit）`之后，会获得一个`Reflow`对象，它可以启动执行，[同/异]步返回结果。")

  info("任务需要被包装在`Trait`对象里，包含任务执行时需要的特征信息；")

  info("任务的`组装`使用`Dependency`对象。")

  info("Reflow 作为一个整体，也可以看做一个`电路`单位，一个电路元器件，即：任务。因此可以进行嵌套组装。")

  info("------------------------- 测试 -------------------------")

  lazy val outputStr = "---------->执行输出，done."
  implicit val poster: Poster = null
  implicit val feedback: Feedback = Feedback.Log

  Feature("异步执行一段代码") {
    Scenario("简写") {
      When("把代码体作为参数提交")
      val future = Reflow.submit {
        // do something ...
        Thread.sleep(1000)
        outputStr
      }(Period.SHORT)
      Then("代码被异步执行")
      And("输出：" + future.get)
      assertResult(outputStr)(future.get)
    }

    Scenario("也可以这样写") {
      Given("一段代码")
      lazy val someCodes: () => String = () => {
        // do something ...
        Thread.sleep(1000)
        outputStr
      }
      When("提交")
      val future = Reflow.submit(someCodes())(Period.SHORT)
      Then("代码被异步执行")
      And("输出：" + future.get)
      assertResult(outputStr)(future.get)
    }

    Scenario("框架标准写法") {
      Given("a Trait")
      val trat = new Trait.Adapter {
        override protected def name() = "test4outputstr"

        override protected def period() = Period.SHORT

        override protected def outs() = kces.outputstr

        override def newTask() = new Task {
          override protected def doWork(): Unit = {
            // do something ...
            Thread.sleep(1000)
            output(kces.outputstr.key, outputStr)
          }
        }
      }
      Then("create a Dependency")
      val dependency = Reflow.create(trat)
      Then("submit the Dependency to got an Reflow")
      val reflow = dependency.submit(kces.outputstr)
      //      When("start the Reflow")
      //      val scheduler = reflow.start(In.empty(), implicitly)
      //      Then("代码被异步执行")
      //      And("输出：" + scheduler.sync())
      //      assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))

      val reflow1 = Reflow.create(reflow.toTrait).and(trats.int2str0).submit(kces.str + kces.outputstr)
      val scheduler1 = reflow1.start((kces.int, Integer.valueOf(567)), implicitly)
      Then("代码被异步执行")
      And("输出：" + scheduler1.sync())
      assertResult("567")(scheduler1.sync()(kces.str))
      assertResult(outputStr)(scheduler1.sync()(kces.outputstr))
    }
  }

  info("Reflow 是异步调用的，但也支持同步（`不推荐`这样写，仅为了方便测试）：")


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

  before {}

  after {}
}
