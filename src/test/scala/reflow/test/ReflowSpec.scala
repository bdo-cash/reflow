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

import java.util.concurrent.{Callable, FutureTask}
import hobby.chenai.nakam.lang.J2S.future2Scala
import hobby.wei.c.reflow._
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Feedback.Progress.Strategy
import hobby.wei.c.reflow.Reflow.GlobalTrack.GlobalTrackObserver
import org.scalatest._
import reflow.test.enum.EnumTest

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class ReflowSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    Reflow.setDebugMode(false)
    // Reflow.setConfig(SINGLE_THREAD) // 线程数设为1，便是单线程模式。

    Reflow.GlobalTrack.registerObserver(new GlobalTrackObserver {
      override def onUpdate(current: GlobalTrack, items: All): Unit = {
        if (!current.isSubReflow && current.scheduler.getState == State.EXECUTING) {
          println(s"++++++++++[[[current.state:${current.scheduler.getState}")
          items().foreach(println)
          println("----------]]]")
        }
      }
    })(null)
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
  implicit lazy val strategy: Strategy = Strategy.Fluent
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
      assertResult(outputStr)(future.get)
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
      assertResult(outputStr)(future.get)
    }

    Scenario("【概览】框架标准用法") {
      Given("一个任务`Task`")
      val task = new Task {
        override protected def doWork(): Unit = {
          output(kvTpes.outputstr.key, outputStr)
          output(kvTpes.int.key, 66666)
        }
      }
      info("任务的作用是获得`输入`并产生`输出`")
      Then("用特征`Trait`来包装这个任务")
      val trat = new Trait.Adapter {
        override protected def name() = "test4outputstr"

        override protected def period() = TRANSIENT // Period.TRANSIENT

        override protected def outs() = kvTpes.str + new KvTpe[String]("outputstr") {} + kvTpes.int

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
      val reflow = dependency.submit(kvTpes.outputstr)
      When("启动运行任务流")
      val scheduler = reflow.start(In.empty(), implicitly)
      info("代码被异步执行")
      Then("等待执行结果")
      info("输出：" + scheduler.sync())
      assertResult(outputStr)(scheduler.sync()(kvTpes.outputstr.key))
    }

    Scenario("[Trait 定义]也可以简写") {
      val trat = Trait("t0", SHORT, new KvTpe[String]("outputstr") {}) { ctx =>
        ctx.output(kvTpes.outputstr.key, outputStr)
      }
      val trat1 = Trait("t1", SHORT, kvTpes.outputstr) { ctx =>
        ctx.output(kvTpes.outputstr.key, "abcd")
      }
      val scheduler = Reflow.create(trat).next(Trait("t2", SHORT) { _ => })
        .and(trat1, new Transformer[String, Integer](kvTpes.outputstr, new KvTpe[Integer]("kkk") {}) {
          override def transform(in: Option[String]) = Option(666)
        }).next(kvTpes.outputstr.re)
        .submit(/*kces.outputstr*/) // 默认用最后的输出作为prefer输出
        .start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(outputStr)(scheduler.sync()(kvTpes.outputstr.key))
    }

    Scenario("[Scala 枚举]在`In`中的 Bug") {
      val trat = Trait("t1", SHORT, none, kvTpes.enum) { _ => }
      val scheduler = Reflow.create(trat).submit(kvTpes.enum)
        .start(kvTpes.enum -> EnumTest.A, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(EnumTest.A)(scheduler.sync()(kvTpes.enum))
    }
  }

  info("【进阶】高级用法")
  info("在一个大型系统中，往往存在着大量的业务逻辑和控制逻辑，它们是数以百计的“工作”的具体化。这些逻辑交织在一起，从整体上看，往往错综复杂。")
  info("我们可以将业务逻辑和控制逻辑分开，把控制逻辑抽象为框架（本`Reflow`框架），把业务逻辑构造为任务（Task）。而任务之间的关系也可进一步" +
    "归纳为两类：有依赖和无依赖，即：串行和并行。")
  info("用户程序员只需要专注于编写任务集（即：拆分业务逻辑），其它交给框架。本框架的设计便是围绕着处理这些任务的控制逻辑而展开。")
  info("在`Reflow`里，对于关系复杂的任务集，应该使用`Dependency`构建依赖关系。")

  Feature("组装复杂业务逻辑") {
    val token4JobA = new KvTpe[Integer]("token4JobA") {}
    val token4JobB = new KvTpe[Integer]("token4JobB") {}
    val key4JobADone = new KvTpe[Integer]("key4JobADone") {}
    val key4JobBDone = new KvTpe[Integer]("key4JobBDone") {}
    val outkeyA = new KvTpe[Integer]("outkeyA") {}
    val outkeyB = new KvTpe[Integer]("outkeyB") {}

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
        .submit(outkeyA)
      Then("启动执行")
      val scheduler = reflow.start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(555)(scheduler.sync()(outkeyA))
    }

    Given("业务需求变更：")
    info("Master 分别给 slave A 和 slave B 各分派一件工作，并等待工作结果。")
    val trat4MasterEndx = Trait("MasterEnd", TRANSIENT, outkeyA + outkeyB, key4JobADone + key4JobBDone) { ctx =>
      val jobADone = ctx.input(key4JobADone).get
      val jobBDone = ctx.input(key4JobBDone).get
      assertResult(555)(jobADone)
      assertResult(999)(jobBDone)
      ctx.output(outkeyA, jobADone)
      ctx.output(outkeyB, jobBDone)
    }
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
    Scenario("并行任务") {
      Then("组装任务：见`and`方法。")
      val reflow = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).and(trat4SlaveBsJob).next(trat4MasterEndx)
        .submit(outkeyA + outkeyB)
      Then("启动执行")
      val scheduler = reflow.start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(555)(scheduler.sync()(outkeyA))
      assertResult(999)(scheduler.sync()(outkeyB))
    }
    Scenario("混合及嵌套") {
      Given("一个已经提交的reflow对象")
      val reflow0 = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).and(trat4SlaveBsJob).next(trat4MasterEndx)
        .submit(outkeyA + outkeyB)
      Then("将该reflow转换为`Trait`")
      val reflowTrat = reflow0.toSub("master&slave")
      Then("对任务进行依赖组装")
      val reflow = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).and(reflowTrat)
        .submit(outkeyA + outkeyB)
      Then("启动执行")
      val scheduler = reflow.start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(555)(scheduler.sync()(outkeyA))
      assertResult(999)(scheduler.sync()(outkeyB))
    }
  }

  Feature("便捷的[同/异]步调用切换") {
    Scenario("异步执行任务") {
      Given("一个Reflow")
      val reflow = Reflow.create(trats.int2str0).submit(kvTpes.str)
      Given("一个反馈接口")
      info("通常情况下，等待反馈接口的回调即可。")
      @volatile var callableOut: Out = null
      val future = new FutureTask[Out](new Callable[Out] {
        override def call() = callableOut
      })
      val feedback = new Feedback.Adapter {
        override def onComplete(out: Out): Unit = {
          callableOut = out
          future.run()
        }
      }
      Then("启动执行")
      reflow.start((kvTpes.str, "66666") + trans.str2int, feedback)
      info("现在它就在异步执行了")
      future map { out => assertResult("66666")(out(kvTpes.str)) }
    }

    Scenario("将异步转换为同步") {
      info("Reflow 是异步调用的，但也支持同步。")
      info("要想转换为同步执行，只需在启动执行的`start()`方法后面跟`sync()`即可。")
      Given("一个Reflow")
      val reflow = Reflow.create(trats.int2str0).submit(kvTpes.str)
      Given("一个反馈接口")
      info("通常情况下，等待反馈接口的回调即可。")
      @volatile var syncOut: Out = null
      val feedback = new Feedback.Adapter {
        override def onComplete(out: Out): Unit = {
          syncOut = out
        }
      }
      Then("启动执行并后跟`sync()`")
      reflow.start((kvTpes.str, "66666") + trans.str2int, feedback).sync()
      info(s"出现本行内容时已经同步执行完毕。syncOut:$syncOut。")
      val as = assertResult("66666")(syncOut(kvTpes.str))
      info("不过`不推荐`这样写，仅为了方便测试。")
      info("如果真有此需求，请考虑使用本框架的`顺序依赖`结构进行重构。")
      as
    }
  }

  Feature("`Transformer`输出转换器") {
    Given("一个`Integer -> String`的转换器")
    val transformer = new Transformer[Integer, String](kvTpes.int, kvTpes.str) {
      override def transform(in: Option[Integer]) = in.map(String.valueOf)
    }
    Scenario("[局部]转换") {
      val scheduler = Reflow.create(
        Trait("int2str", TRANSIENT, kvTpes.int, kvTpes.str) { ctx =>
          ctx.output(kvTpes.int, Integer.valueOf(ctx.input(kvTpes.str).getOrElse("-1")))
        }, transformer)
        .submit(kvTpes.str)
        .start(kvTpes.str -> "00000", implicitly)
      assertResult("00000")(scheduler.sync()(kvTpes.str))
    }
    Scenario("[全局]转换 1") {
      val scheduler = Reflow.create(
        Trait("int2str 1", TRANSIENT, kvTpes.int, kvTpes.str) { ctx =>
          ctx.output(kvTpes.int, Integer.valueOf(ctx.input(kvTpes.str).get))
        }).next(transformer)
        .submit(kvTpes.str)
        .start(kvTpes.str -> "11111", implicitly)
      assertResult("11111")(scheduler.sync()(kvTpes.str))
    }
    Scenario("[不]转换 2") {
      val scheduler = Reflow.create(
        Trait("int2str 2", TRANSIENT, kvTpes.int, kvTpes.str) { ctx =>
          ctx.output(kvTpes.int, Integer.valueOf(ctx.input(kvTpes.str).get))
        }, transformer).next(transformer)
        .submit(kvTpes.int)
        .start(kvTpes.str -> "22222", implicitly)
      assertResult(22222)(scheduler.sync()(kvTpes.int))
    }
  }

  Feature("`Reinforce`强化运行模式") {
    Scenario("申请运行强化模式") {
      info("申请强化模式后，会分为两个阶段：`浏览`和`强化`。")
      info("也会分别有两次反馈：`onComplete()`和`onUpdate()`，结果根据实际情况而不同。")
      val feedback = new Feedback.Adapter {
        override def onComplete(out: Out): Unit = {
          assertResult(11111)(out(kvTpes.int))
        }

        override def onUpdate(out: Out): Unit = {
          assertResult(12345)(out(kvTpes.int))
        }
      }
      val scheduler = Reflow.create(
        Trait("int2str", TRANSIENT, kvTpes.int, kvTpes.str) { ctx =>
          if (ctx.isReinforcing) {
            // do something ...
            Thread.sleep(1000)
            ctx.output[Integer](kvTpes.int, ctx.input(kvTpes.int).orNull)
          } else {
            ctx.output(kvTpes.int, Integer.valueOf(ctx.input(kvTpes.str).getOrElse("-1")))
            // 申请强化运行
            ctx.requireReinforce()
            ctx.cache[Integer](kvTpes.int, 12345)
          }
        })
        .submit(kvTpes.int)
        .start(kvTpes.str -> "11111", feedback)
      // info(s"强化运行后的最终输出。out:${scheduler.sync()}")
      assertResult(null)(scheduler.sync(reinforce = true, 600).map(_(kvTpes.int)).orNull)
      assertResult(12345)(scheduler.sync(reinforce = true, 60 * 1000).map(_(kvTpes.int)).orNull)
      assertResult(12345)(scheduler.sync(reinforce = true)(kvTpes.int))
    }
  }

  Feature("跨线程回调反馈") {
    Scenario("使用`Poster`令`Feedback`在指定线程被调用") {
      Given("一个`Reflow`")
      val reflow = Reflow.create(trats.int2str0).submit(kvTpes.str)
      Given("一个反馈接口")
      @volatile var threadA: Thread = null
      @volatile var threadB: Thread = null
      val feedback = new Feedback.Adapter {
        override def onPending(): Unit = super.onPending()

        override def onProgress(progress: Feedback.Progress, out: Out, depth: Int): Unit = super.onProgress(progress, out, depth)

        override def onComplete(out: Out): Unit = {
          // do something with `out`.
          // threadB = Thread.currentThread
        }
      }
      Given("一个`Poster`")
      // threadA = 目标线程
      implicit val poster = new Poster {
        override def post(run: Runnable): Unit = {
          // 将`runnable`发送到指定线程的任务队列。适用于移动端，如：`Android`平台。
        }
      }
      Then("在启动执行时传入`poster`参数")
      info("这样，所有`feedback`的回调将在指定线程执行。")
      reflow.start((kvTpes.str, "66666") + trans.str2int, feedback)(implicitly, poster).sync()
      Then("等待异步执行结束")
      assertResult(threadA)(threadB)
    }
  }

  Feature("`Progress`进度反馈策略") {
    info("有四种策略（Strategy）")
    val trat4Progress = Trait("trat 4 progress", TRANSIENT) { ctx =>
      ctx.progress(1, 10)
      ctx.progress(2, 10)
      ctx.progress(3, 10)
      ctx.progress(4, 10)
      ctx.progress(5, 10)
      ctx.progress(6, 10)
      ctx.progress(7, 10)
      ctx.progress(8, 10)
      ctx.progress(9, 10)
    }
    val interval = Strategy.Interval(6)

    val reflow0 = Reflow.create(trat4Progress).submit(none)
    val reflow1 = Reflow.create(trat4Progress).next(reflow0.toSub("reflow0")).submit(none)
    val reflow2 = Reflow.create(trat4Progress).next(reflow1.toSub("reflow1")).submit(none)
    val reflow3 = Reflow.create(trat4Progress).next(reflow2.toSub("reflow2")).submit(none)
    val reflow4 = Reflow.create(trat4Progress).next(reflow3.toSub("reflow3")).submit(none)
    val reflow5 = Reflow.create(trat4Progress).next(reflow4.toSub("reflow4")).submit(none)
    val reflow6 = Reflow.create(trat4Progress).next(reflow5.toSub("reflow5")).submit(none)
    val reflow7 = Reflow.create(trat4Progress).next(reflow6.toSub("reflow6")).submit(none)
    val reflow8 = Reflow.create(trat4Progress).next(reflow7.toSub("reflow7")).submit(none)
    val reflow9 = Reflow.create(trat4Progress).next(reflow8.toSub("reflow8")).submit(none)
    info("串行任务进度测试")
    Scenario("1.全量（串行）") {
      Given("传入参数`Strategy.FullDose`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(Strategy.FullDose, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("2.丢弃拥挤的消息（串行）") {
      Given("传入参数`Strategy.Fluent`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(Strategy.Fluent, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3.基于子进度的深度（串行）") {
      Given("传入参数`Strategy.Depth(2)`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(Strategy.Depth(2), poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("4.基于反馈时间间隔（串行）") {
      Given("传入参数`interval`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(interval, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }

    val reflow0x = Reflow.create(trat4Progress).submit(none)
    val reflow1x = Reflow.create(trat4Progress).and(reflow0x.toSub("reflow0x")).submit(none)
    val reflow2x = Reflow.create(trat4Progress).and(reflow1x.toSub("reflow1x")).submit(none)
    val reflow3x = Reflow.create(trat4Progress).and(reflow2x.toSub("reflow2x")).submit(none)
    val reflow4x = Reflow.create(trat4Progress).and(reflow3x.toSub("reflow3x")).submit(none)
    val reflow5x = Reflow.create(trat4Progress).and(reflow4x.toSub("reflow4x")).submit(none)
    val reflow6x = Reflow.create(trat4Progress).and(reflow5x.toSub("reflow5x")).submit(none)
    val reflow7x = Reflow.create(trat4Progress).and(reflow6x.toSub("reflow6x")).submit(none)
    val reflow8x = Reflow.create(trat4Progress).and(reflow7x.toSub("reflow7x")).submit(none)
    val reflow9x = Reflow.create(trat4Progress).and(reflow8x.toSub("reflow8x")).submit(none)
    info("并行任务进度测试")
    Scenario("1.全量（并行）") {
      Given("传入参数`Strategy.FullDose`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(Strategy.FullDose, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("2.丢弃拥挤的消息（并行）") {
      Given("传入参数`Strategy.Fluent`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(Strategy.Fluent, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3.基于子进度的深度（并行）") {
      Given("传入参数`Strategy.Depth(2)`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(Strategy.Depth(2), poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("4.基于反馈时间间隔（并行）") {
      Given("传入参数`interval`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(interval, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    info("还可以进行多Strategy叠加")
    Scenario("1. 叠加方案: Strategy.Fluent -> Strategy.Depth(2)") {
      val strategy = Strategy.Fluent -> Strategy.Depth(2)
      val scheduler = reflow9x.start(none, implicitly)(strategy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("2. 叠加方案: Strategy.Depth(2) -> Strategy.Fluent") {
      val strategy = Strategy.Depth(2) -> Strategy.Fluent
      val scheduler = reflow9x.start(none, implicitly)(strategy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3. 叠加方案: Strategy.Interval(6) -> Strategy.Fluent") {
      val strategy = Strategy.Interval(6) -> Strategy.Fluent
      val scheduler = reflow9x.start(none, implicitly)(strategy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("4. 叠加方案: Strategy.Depth(3) -> Strategy.Interval(6)") {
      val strategy = Strategy.Depth(3) -> Strategy.Interval(6)
      val scheduler = reflow9x.start(none, implicitly)(strategy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
  }

  Feature("`fork()`") {
    val dependency = Reflow.create(trats.int2str0).next(trats.str2int)
    info("`dependency`可以`fork`")
    val reflow = dependency.fork().submit(kvTpes.str)
    info("`reflow`也可以`fork`")
    reflow.fork().start(kvTpes.int -> Integer.valueOf(66666), implicitly)
    assert(true)
  }

  Feature("全局任务管理器——状态跟踪") {
    Reflow.GlobalTrack.getAllItems
    Reflow.GlobalTrack.registerObserver(new GlobalTrackObserver {
      override def onUpdate(current: GlobalTrack, items: All): Unit = {
        items().foreach(_.progress)
      }
    })
  }

  Feature("线程状态重置") {
    Reflow.setThreadResetor(new ThreadResetor {
      override def reset(thread: Thread, beforeOrAfterWork: Boolean, runOnCurrentThread: Boolean): Unit = {
        // 对于`Android`平台，线程的优先级是通过`Process`来调用的。
        if (beforeOrAfterWork && runOnCurrentThread) {
          // Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        }
      }
    })
  }

  Feature("可配置的线程池") {
    Reflow.setConfig(Config.DEF)
  }

  Feature("DebugMode") {
    info("在需要调试依赖构建错误时，应将`DebugMode`打开；release时关闭。")
    Reflow.setDebugMode(true)
  }

  Feature("可配置的`Logger`日志输出") {
    // Reflow.setLogger() // 可适应`Android`平台的`Log`工具。
  }

  override protected def afterAll(): Unit = {
    info("All test done!!!~")
    Reflow.shutdown()
  }

  before {
    info("++++++++++>>>")
  }

  after {
    info("<<<----------")
  }
}
