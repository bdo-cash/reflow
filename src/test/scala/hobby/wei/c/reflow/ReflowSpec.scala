package hobby.wei.c.reflow

import hobby.wei.c.reflow.implicits._
import org.scalatest._

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    Reflow.setDebugMode(false)
    // Reflow.setConfig(SINGLE_THREAD) // 线程数设为1，便是单线程模式。
  }

  info("------------------------- 简介 -------------------------")
  info("Reflow 是一个`任务串并联`组合调度框架。")

  info("`数据`就是`电流`，而`任务（Task）`可以看做电路元器件；")

  info("`任务`在组装`提交（submit）`之后，会获得一个`Reflow`对象，它可以启动运行，[同/异]步返回结果。")

  info("任务需要被包装在`Trait`对象里，包含任务执行时需要的特征信息；")

  info("任务的`组装`使用`Dependency`对象。")

  info("Reflow 作为一个整体，也可以看做一个`电路`单位或元器件，即：单一任务。因此可以进行嵌套组装。")

  info("-------------------- Features & 测试 --------------------")

  lazy val outputStr = "<<<这是输出>>>，Done。"
  implicit lazy val poster: Poster = null

  info("【入门】基本功能")

  Feature("异步执行代码") {
    Scenario("简写") {
      When("把`代码体`作为参数提交 -> Reflow.submit{...}")
      val future = Reflow.submit {
        // do something ...
        Thread.sleep(10)
        outputStr
      }(SHORT)
      Then("框架将自动`异步`执行这段代码")
      info("输出：" + future.get)
      val as = assertResult(outputStr)(future.get)
      info("输出符合预期，Done。")
      as
    }

    Scenario("也可以这样写") {
      Given("一段代码")
      lazy val someCodes: () => String = () => {
        // do something ...
        Thread.sleep(10)
        outputStr
      }
      When("提交")
      val future = Reflow.submit(someCodes())(SHORT)
      Then("代码被异步执行")
      info("输出：" + future.get)
      val as = assertResult(outputStr)(future.get)
      info("输出符合预期，Done。")
      as
    }

    Scenario("【概览】框架标准用法") {
      Given("一个任务`Task`")
      val task = new Task {
        override protected def doWork(): Unit = output(kces.outputstr.key, outputStr)
      }
      info("任务的作用是获得`输入`并产生`输出`")
      Then("用特征`Trait`来包装这个任务")
      val trat = new Trait.Adapter {
        override protected def name() = "test4outputstr"

        override protected def period() = TRANSIENT

        override protected def outs() = kces.outputstr

        override def newTask() = task
      }
      info("`特征`的作用是向客户代码展示该任务的输入、输出、优先级和大概耗时等信息")
      Then("为任务创建依赖`Dependency`")
      val dependency = Reflow.create(trat)
      Then("提交这个依赖，获得任务流对象`Reflow`")
      val reflow = dependency.submit(kces.outputstr)
      When("启动运行任务流")
      val scheduler = reflow.start(In.empty(), implicitly)
      info("代码被异步执行")
      Then("等待执行结果")
      info("输出：" + scheduler.sync())
      val as = assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))
      info("输出符合预期，Done。")
      as
    }
  }

  info("【进阶】高级用法")
  info("在一个大型系统中，往往有大量业务逻辑，这些业务包含着数以百计的事件需要处理，那么可以把这些事件构造为任务。")
  info("这些任务之间有的有依赖关系，有的没有，xxx")
  info("对于关系复杂的任务集，应该使用`Dependency`构建依赖关系。")

  Feature("便捷的[同/异]步调用切换") {
    Scenario("异步执行任务") {
      Given("一个Reflow")
      val reflow = Reflow.create(trats.int2str0).submit(kces.str)
      Given("一个反馈接口")
      val feedback = new Feedback.Adapter {
        override def onComplete(out: Out): Unit = info(s"反馈接口输出`out`对象：onComplete->out:$out")
      }
      Then("启动它")
      reflow.start((kces.str, "66666") + trans.str2int, feedback)
      info("现在它就在异步执行了")

      assert(true)
    }

    Scenario("异步将异步转换为同步") {
      info("通常情况下，等待反馈接口的回调及")
      Given("一个Reflow")
      val reflow = Reflow.create(trats.int2str0).submit(kces.str)
      Then("启动它")
      reflow.start((kces.str, "12345") + trans.str2int, implicitly)


      info("Reflow 是异步调用的，但也支持同步。但`不推荐`这样写，仅为了方便测试。")
      assert(true)
    }

//    Scenario("异步将异步转换为同步") {
//      info("通常情况下，等待反馈接口的回调及")
//      Given("一个Reflow")
//      val reflow = Reflow.create(trats.int2str0).submit(kces.str)
//      Then("启动它")
//      reflow.start((kces.str, "12345") + trans.str2int, implicitly)
//
//
//      info("Reflow 是异步调用的，但也支持同步。但`不推荐`这样写，仅为了方便测试。")
//      assert(true)
//    }

    //    Scenario("简写") {
    //      val reflow1 = Reflow.create(reflow.torat, trans.str2int).and(trats.int2str0).submit(kces.outputstr)
    //      val scheduler1 = Reflow.create(reflow1.torat(TRANSIENT, P_NORMAL, "", "外层")).and(trats.int2str1, trans.str2int).submit(kces.int + kces.outputstr)
    //        .start((kces.str, "567") + trans.str2int, implicitly)
    //      Then("代码被异步执行")
    //      And("输出：" + scheduler1.sync(reinforce = true))
    //      //      assertResult("567")(scheduler1.sync()(kces.str))
    //      //      assertResult(outputStr)(scheduler1.sync()(kces.outputstr))
    //      assert(true)
    //    }
  }

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
