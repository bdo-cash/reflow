/*
 * Copyright (C) 2018-present, Chenai Nakam(chenai.nakam@gmail.com)
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

package reflow.test

import hobby.wei.c.reflow._
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Reflow.GlobalTrack.GlobalTrackObserver
import org.scalatest._

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    // Reflow.setDebugMode(false)
    // Reflow.setConfig(SINGLE_THREAD) // 线程数设为1，便是单线程模式。

    Reflow.GlobalTrack.registerObserver(new GlobalTrackObserver {
      override def onUpdate(current: GlobalTrack, items: All): Unit = {
        println(s"++++++++++[[[current.state:${current.scheduler.getState}, ${current.reflow.name}")
        items().foreach(println(_))
        println("----------]]]")
      }
    })
  }

  info("------------------------- 简介 -------------------------")
  info("Reflow 是一个`任务串并联`组合调度框架。")

  info("`数据`就是`电流`，而`任务（Task）`可以看做电路元器件；")

  info("`任务`在组装`提交（submit）`之后，会获得一个`Reflow`对象，它可以启动运行，[同/异]步返回结果。")

  info("任务需要被包装在`Trait`对象里，包含任务执行时需要的特征信息；")

  info("任务的`组装`使用`Dependency`对象。")

  info("Reflow 还可以进行嵌套组装：作为一个整体，可以看做一个单一`任务`，可以再次被包装在`Trait`里。")

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
        override protected def doWork(): Unit = {
          output(kces.outputstr.key, outputStr)
          output(kces.int.key, 66666)
        }
      }
      info("任务的作用是获得`输入`并产生`输出`")
      Then("用特征`Trait`来包装这个任务")
      val trat = new Trait.Adapter {
        override protected def name() = "test4outputstr"

        override protected def period() = TRANSIENT // Period.TRANSIENT

        override protected def outs() = kces.str + new Kce[String]("outputstr") {} + kces.int

        override def newTask() = task
      }
      info("`特征`的作用是向客户代码展示该任务的输入、输出、优先级和大概耗时等信息")
      Then("为任务创建依赖`Dependency`")
      val dependency = Reflow.create(trat).next(new Trait.Adapter {
        override protected def period() = SHORT

        override def newTask() = new Task {
          override protected def doWork(): Unit = {
            // do sth ...
          }
        }
      })
      Then("提交这个依赖，获得任务流对象`Reflow`")
      val reflow = dependency.submit("reflow test 1", kces.outputstr)
      When("启动运行任务流")
      val scheduler = reflow.start(In.empty(), implicitly)
      info("代码被异步执行")
      Then("等待执行结果")
      info("输出：" + scheduler.sync())
      val as = assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))
      info("输出符合预期，Done。")
      as
    }

    Scenario("[Trait 定义]也可以简写") {
      val trat = Trait("t0", SHORT, new Kce[String]("outputstr") {}) { ctx =>
        ctx.output(kces.outputstr.key, outputStr)
      }
      val scheduler = Reflow.create(trat).next(Trait("t1", SHORT) { _ => }).submit("简写", kces.outputstr)
        .start(none, implicitly)
      info("输出：" + scheduler.sync())
      val as = assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))
      info("输出符合预期，Done。")
      as
    }
  }

  info("【进阶】高级用法")
  info("在一个大型系统中，往往存在大量的业务逻辑，这些业务包含着数以百计的`工作`需要处理，那么可以把这些工作构造为任务。")
  info("这些任务之间通常具有顺序性，即：`依赖`关系。从整体上看，往往错综复杂。")
  info("但可以将两两之间的关系归纳为两类：有依赖和无依赖，即：`串行`和`并行`。本框架的设计便是围绕这两种关系而展开。")
  info("在`Reflow`里，对于关系复杂的任务集，应该使用`Dependency`构建依赖关系。")

  Feature("组装复杂业务逻辑") {
    val token4JobA = new Kce[Integer]("token4JobA") {}
    val token4JobB = new Kce[Integer]("token4JobB") {}
    val key4JobADone = new Kce[Integer]("key4JobADone") {}
    val key4JobBDone = new Kce[Integer]("key4JobBDone") {}
    val outkeyA = new Kce[Integer]("outkeyA") {}
    val outkeyB = new Kce[Integer]("outkeyB") {}

    info("首先应该将复杂业务拆分为多个[功能单一]的没有[阻塞]等待的[单线程]结构的任务。")
    Given("一个业务需求：")
    info("Master 给 slave A 分派一件工作，并等待工作结果。")
    Then("将该业务拆分为3个任务：")
    info("任务1：master 发出指令；")
    val trat4MasterBegin = Trait("MasterBegin", TRANSIENT, token4JobA + token4JobB) { ctx =>
      ctx.output(token4JobA.key, 555)
      ctx.output(token4JobB.key, 999)
    }
    info("任务2：slave 接受指令并执行；")
    val trat4SlaveAsJob = Trait("SlaveAsJob", SHORT, key4JobADone, token4JobA) { ctx =>
      // 1. 接受指令
      val input = ctx.input(token4JobA).get
      // 2. 执行指令
      Thread.sleep(1000)
      // 3. 输出
      ctx.output(key4JobADone, input)
    }
    info("任务3：Master 发出指令；")
    val trat4MasterEnd = Trait("MasterEnd", TRANSIENT, outkeyA, key4JobADone) { ctx =>
      val jobADone = ctx.input(key4JobADone).get
      assertResult(555)(jobADone)
      ctx.output(outkeyA, jobADone)
    }

    Scenario("串行任务") {
      Then("组装任务")
      val reflow = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).next(trat4MasterEnd)
        .submit("master&slave", outkeyA)
      Then("启动执行")
      val scheduler = reflow.start(none, implicitly)
      info("输出：" + scheduler.sync())
      val as = assertResult(555)(scheduler.sync()(outkeyA))
      info("输出符合预期，Done。")
      as
    }
    Scenario("并行任务") {
      Given("业务需求变更：")
      info("Master 分别给 slave A 和 slave B 各分派一件工作，并等待工作结果。")
      Then("新增任务")
      info("任务2B：slave B 接受指令并执行；")
      val trat4SlaveBsJob = Trait("SlaveBsJob", SHORT, key4JobBDone, token4JobB) { ctx =>
        // 1. 接受指令
        val input = ctx.input(token4JobB).get
        // 2. 执行指令
        // do something ...
        // 3. 输出
        ctx.output(key4JobBDone, input)
      }
      val trat4MasterEndx = Trait("MasterEnd", TRANSIENT, outkeyA + outkeyB, key4JobADone + key4JobBDone) { ctx =>
        val jobADone = ctx.input(key4JobADone).get
        val jobBDone = ctx.input(key4JobBDone).get
        assertResult(555)(jobADone)
        assertResult(999)(jobBDone)
        ctx.output(outkeyA, jobADone)
        ctx.output(outkeyB, jobBDone)
      }
      Then("组装任务")
      val reflow = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).and(trat4SlaveBsJob).next(trat4MasterEndx)
        .submit("master&slave", outkeyA + outkeyB)
      Then("启动执行")
      val scheduler = reflow.start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(555)(scheduler.sync()(outkeyA))
      val as = assertResult(999)(scheduler.sync()(outkeyB))
      info("输出符合预期，Done。")
      as
    }
    Scenario("混合及嵌套") {
      Given("")

      assert(true)
    }
  }

  //  Feature("便捷的[同/异]步调用切换") {
  //    Scenario("异步执行任务") {
  //      Given("一个Reflow")
  //      val reflow = Reflow.create(trats.int2str0).submit("reflow test 2", kces.str)
  //      Given("一个反馈接口")
  //      val feedback = new Feedback.Adapter {
  //        override def onComplete(out: Out): Unit = info(s"反馈接口输出`out`对象：onComplete->out:$out")
  //      }
  //      Then("启动它")
  //      reflow.start((kces.str, "66666") + trans.str2int, feedback)
  //      info("现在它就在异步执行了")
  //
  //      assert(true)
  //    }
  //
  //    Scenario("将异步转换为同步") {
  //      info("通常情况下，等待反馈接口的回调及")
  //      Given("一个Reflow")
  //      val reflow = Reflow.create(trats.int2str0).submit("reflow test 3", kces.str)
  //      Then("启动它")
  //      reflow.start((kces.str, "12345") + trans.str2int, implicitly)
  //
  //      // TODO: 从这里继续
  //
  //
  //      info("Reflow 是异步调用的，但也支持同步。不过`不推荐`这样写，仅为了方便测试。")
  //      assert(true)
  //    }

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
  //  }

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
