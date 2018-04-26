## Reflow: 任务 _`串/并联`_ 组合调度框架


#### 一、概述

本框架为 _简化复杂业务逻辑中 **多[任务](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L28)之间的数据流转和事件处理**_ 的 _编码复杂度_ 而生。通过 **_要求[显式定义](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L27)_ 任务的[ I ](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L48)/[O ](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L53)**、基于 [_**关键字**_ 和 _**值类型**_](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Kce.scala#L26) 分析的智能化 **[依赖管理](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L31)**、一致的 **[运行调度](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L26)**、**[事件反馈](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L25)** 及 **[错误处理](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L124)** 接口等设计，实现了既定目标：**任务 _[串](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L78) /[并](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L52)联_ 组合调度**。 _数据_ 即 **电流**， _任务_ 即 **元件**。在简化编码复杂度的同时，确定的框架可以将原本杂乱无章、错综复杂的写法规范化，编码失误也极易被检测，这将大大增强程序的 **易读性**、**健壮性** 和 **可扩展性**。

此外还有优化的 _[可配置](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Config.scala#L19)_ [线程池](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Worker.scala#L71)、基于 _[优先级](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L58)_ 和 _[预估时长](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L63)_ 的 **按需的** 任务装载机制、便捷的 **[同](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L34)/异（默认）步** 切换调度、巧妙的 _[中断](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L51)策略_ 、任务的 _无限_ **嵌套** 组装、**浏览/[强化](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L100)** 运行模式、 _无依赖输出_ **丢弃**、事件反馈可 **[指定到线程](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Poster.scala#L20)** 和对异常事件的 _[确定性分类](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L56)_ 等设计，实现了线程的 **无** _阻塞_ 高效利用、全局 **精准** 的任务管理、内存的 _有效利用（垃圾丢弃）_ 、以及数据的 _快速加载（**浏览** 模式）_ 和进度的 _[策略化](https://github.com/dedge-space/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L154)反馈_ ，极大地满足了大型项目的需求。


##### _1.1 相关_

本框架的主要功能类似 [Facebook Bolts](http://github.com/BoltsFramework/Bolts-Android) 和 [RxJava](https://github.com/ReactiveX/RxJava)，可以视为对它们 _任务组合能力_ 的细粒度扩展，但更加严谨、高规格和 **贴近实际项目需求**。

本框架基于 **线程池**（`java.util.concurrent.ThreadPoolExecutor`）实现而非 **Fork/Join 框架（JDK 1.7）**（`java.util.concurrent.ForkJoinPool`），并对前者作了 [改进](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Worker.scala#L59) 以符合 **先增加线程数到 [最大](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Config.scala#L28)，再入队列，空闲释放线程** 这个基本逻辑；后者适用于计算密集型任务，但不适用于本框架的设计目标，也不适用于资源受限的设备（如：手机等）。


##### _1.2 说明_

本框架完全采用 Scala 语言编写，参数都支持 **[简写](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala#L130)**，会自动 **按需** 转义（[implicit](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/implicits.scala#L40) 隐式转换）。可用于采用 jvm 的任何平台。

本框架衍生了一个特别的 **抗阻塞**_线程同步_ 工具 [`Snatcher`](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/tool/Snatcher.scala#L25)（详见代码文档）。

* 特别说明：本框架没有采用 `java.util.concurrent.Future<V>` 工具来处理并行任务，因为它是基于 **线程阻塞** 模型实现的，不符合本框架的设计目标。


#### 二、Reflow 运行原理
![Reflow 运行原理示意图](https://github.com/WeiChou/Reflow/blob/master/Reflow%20%E8%BF%90%E8%A1%8C%E5%8E%9F%E7%90%86%E7%A4%BA%E6%84%8F%E5%9B%BE.png "Reflow 运行原理示意图")


#### 三、开始使用 Reflow

##### _3.1 配置依赖_

请戳这里 [![](https://jitpack.io/v/dedge-space/reflow.svg)](https://jitpack.io/#dedge-space/reflow/bcab2d691b)

##### _3.2 应用示例_

见 _下文_ 或 特性测试 [`ReflowSpec`](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala)。

* 如果在 Android 平台上使用，请先作如下设置。

```Scala
class App extends AbsApp {
  override def onCreate(): Unit = {
    App.reflow.init()
    super.onCreate()
  }
}

object App {
  object implicits {
    implicit lazy val policy: Policy = Policy.Depth(3) -> Policy.Fluent -> Policy.Interval(600)
    implicit lazy val poster: Poster = new Poster {
      override def post(runner: Runnable): Unit = getApp.mainHandler.post(runner)
    }
  }

  object reflow {
    private[App] def init(): Unit = {
      Reflow.setThreadResetor(new ThreadResetor {
        override def reset(thread: Thread, runOnCurrentThread: Boolean): Unit = {
          if (runOnCurrentThread) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        }
      })
    }
  }
}

```

* 以下为 [`ReflowSpec`](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala) 原文。
```Scala
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
import hobby.chenai.nakam.lang.J2S.toScala
import hobby.wei.c.reflow._
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.Reflow.GlobalTrack.GlobalTrackObserver
import org.scalatest._

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
          println(s"++++++++++[[[current.state:${current.scheduler.getState}, ${current.reflow.name}")
          items().foreach(println)
          println("----------]]]")
        }
      }
    })(Policy.Interval(600), null)
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
  implicit lazy val policy: Policy = Policy.Fluent
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
      assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))
    }

    Scenario("[Trait 定义]也可以简写") {
      val trat = Trait("t0", SHORT, new Kce[String]("outputstr") {}) { ctx =>
        ctx.output(kces.outputstr.key, outputStr)
      }
      val scheduler = Reflow.create(trat).next(Trait("t1", SHORT) { _ => }).submit("简写", kces.outputstr)
        .start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))
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
        .submit("master&slave", outkeyA + outkeyB)
      Then("启动执行")
      val scheduler = reflow.start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(555)(scheduler.sync()(outkeyA))
      assertResult(999)(scheduler.sync()(outkeyB))
    }
    Scenario("混合及嵌套") {
      Given("一个已经提交的reflow对象")
      val reflow0 = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).and(trat4SlaveBsJob).next(trat4MasterEndx)
        .submit("master&slave", outkeyA + outkeyB)
      Then("将该reflow转换为`Trait`")
      val reflowTrat = reflow0.torat()
      Then("对任务进行依赖组装")
      val reflow = Reflow.create(trat4MasterBegin).next(trat4SlaveAsJob).and(reflowTrat)
        .submit("master&slave", outkeyA + outkeyB)
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
      val reflow = Reflow.create(trats.int2str0).submit("reflow test 2", kces.str)
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
      reflow.start((kces.str, "66666") + trans.str2int, feedback)
      info("现在它就在异步执行了")
      future map { out => assertResult("66666")(out(kces.str)) }
    }

    Scenario("将异步转换为同步") {
      info("Reflow 是异步调用的，但也支持同步。")
      info("要想转换为同步执行，只需在启动执行的`start()`方法后面跟`sync()`即可。")
      Given("一个Reflow")
      val reflow = Reflow.create(trats.int2str0).submit("reflow test 2", kces.str)
      Given("一个反馈接口")
      info("通常情况下，等待反馈接口的回调即可。")
      @volatile var syncOut: Out = null
      val feedback = new Feedback.Adapter {
        override def onComplete(out: Out): Unit = {
          syncOut = out
        }
      }
      Then("启动执行并后跟`sync()`")
      reflow.start((kces.str, "66666") + trans.str2int, feedback).sync()
      info(s"出现本行内容时已经同步执行完毕。syncOut:$syncOut。")
      val as = assertResult("66666")(syncOut(kces.str))
      info("不过`不推荐`这样写，仅为了方便测试。")
      info("如果真有此需求，请考虑使用本框架的`顺序依赖`结构进行重构。")
      as
    }
  }

  Feature("`Transformer`输出转换器") {
    Given("一个`Integer -> String`的转换器")
    val transformer = new Transformer[Integer, String](kces.int, kces.str) {
      override def transform(in: Option[Integer]) = in.map(String.valueOf)
    }
    Scenario("局部转换") {
      val scheduler = Reflow.create(
        Trait("int2str", TRANSIENT, kces.int, kces.str) { ctx =>
          ctx.output(kces.int, Integer.valueOf(ctx.input(kces.str).getOrElse("-1")))
        }, transformer)
        .submit("reflow test", kces.str)
        .start(kces.str -> "11111", implicitly)
      assertResult("11111")(scheduler.sync()(kces.str))
    }
    Scenario("全局转换") {
      val scheduler = Reflow.create(
        Trait("int2str", TRANSIENT, kces.int, kces.str) { ctx =>
          ctx.output(kces.int, Integer.valueOf(ctx.input(kces.str).getOrElse("-1")))
        }).next(transformer)
        .submit("reflow test", kces.str)
        .start(kces.str -> "11111", implicitly)
      assertResult("11111")(scheduler.sync()(kces.str))
    }
  }

  Feature("`Reinforce`强化运行模式") {
    Scenario("申请运行强化模式") {
      info("申请强化模式后，会分为两个阶段：`浏览`和`强化`。")
      info("也会分别有两次反馈：`onComplete()`和`onUpdate()`，结果根据实际情况而不同。")
      val feedback = new Feedback.Adapter {
        override def onComplete(out: Out): Unit = {
          assertResult(11111)(out(kces.int))
        }

        override def onUpdate(out: Out): Unit = {
          assertResult(12345)(out(kces.int))
        }
      }
      val scheduler = Reflow.create(
        Trait("int2str", TRANSIENT, kces.int, kces.str) { ctx =>
          if (ctx.isReinforcing) {
            // do something ...
            Thread.sleep(1000)
            ctx.output[Integer](kces.int, ctx.input(kces.int).orNull)
          } else {
            ctx.output(kces.int, Integer.valueOf(ctx.input(kces.str).getOrElse("-1")))
            // 申请强化运行
            ctx.requireReinforce()
            ctx.cache[Integer](kces.int, 12345)
          }
        })
        .submit("reflow test 4 reinforce", kces.int)
        .start(kces.str -> "11111", feedback)
      info(s"强化运行后的最终输出。out:${scheduler.sync(/*reinforce = true*/)}")
      assertResult(12345)(scheduler.sync(reinforce = true)(kces.int))
    }
  }

  Feature("跨线程回调反馈") {
    Scenario("使用`Poster`令`Feedback`在指定线程被调用") {
      Given("一个`Reflow`")
      val reflow = Reflow.create(trats.int2str0).submit("reflow test 2", kces.str)
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
      reflow.start((kces.str, "66666") + trans.str2int, feedback)(implicitly, poster).sync()
      Then("等待异步执行结束")
      assertResult(threadA)(threadB)
    }
  }

  Feature("`Progress`进度反馈策略") {
    info("有四种策略（Policy）")
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
    val interval = Policy.Interval(6)

    val reflow0 = Reflow.create(trat4Progress).submit("reflow0", none)
    val reflow1 = Reflow.create(trat4Progress).next(reflow0.torat()).submit("reflow1", none)
    val reflow2 = Reflow.create(trat4Progress).next(reflow1.torat()).submit("reflow2", none)
    val reflow3 = Reflow.create(trat4Progress).next(reflow2.torat()).submit("reflow3", none)
    val reflow4 = Reflow.create(trat4Progress).next(reflow3.torat()).submit("reflow4", none)
    val reflow5 = Reflow.create(trat4Progress).next(reflow4.torat()).submit("reflow5", none)
    val reflow6 = Reflow.create(trat4Progress).next(reflow5.torat()).submit("reflow6", none)
    val reflow7 = Reflow.create(trat4Progress).next(reflow6.torat()).submit("reflow7", none)
    val reflow8 = Reflow.create(trat4Progress).next(reflow7.torat()).submit("reflow8", none)
    val reflow9 = Reflow.create(trat4Progress).next(reflow8.torat()).submit("reflow9", none)
    info("串行任务进度测试")
    Scenario("1.全量（串行）") {
      Given("传入参数`Policy.FullDose`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(Policy.FullDose, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("2.丢弃拥挤的消息（串行）") {
      Given("传入参数`Policy.Fluent`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(Policy.Fluent, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3.基于子进度的深度（串行）") {
      Given("传入参数`Policy.Depth(2)`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9.start(none, implicitly)(Policy.Depth(2), poster)
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

    val reflow0x = Reflow.create(trat4Progress).submit("reflow0x", none)
    val reflow1x = Reflow.create(trat4Progress).and(reflow0x.torat()).submit("reflow1x", none)
    val reflow2x = Reflow.create(trat4Progress).and(reflow1x.torat()).submit("reflow2x", none)
    val reflow3x = Reflow.create(trat4Progress).and(reflow2x.torat()).submit("reflow3x", none)
    val reflow4x = Reflow.create(trat4Progress).and(reflow3x.torat()).submit("reflow4x", none)
    val reflow5x = Reflow.create(trat4Progress).and(reflow4x.torat()).submit("reflow5x", none)
    val reflow6x = Reflow.create(trat4Progress).and(reflow5x.torat()).submit("reflow6x", none)
    val reflow7x = Reflow.create(trat4Progress).and(reflow6x.torat()).submit("reflow7x", none)
    val reflow8x = Reflow.create(trat4Progress).and(reflow7x.torat()).submit("reflow8x", none)
    val reflow9x = Reflow.create(trat4Progress).and(reflow8x.torat()).submit("reflow9x", none)
    info("并行任务进度测试")
    Scenario("1.全量（并行）") {
      Given("传入参数`Policy.FullDose`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(Policy.FullDose, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("2.丢弃拥挤的消息（并行）") {
      Given("传入参数`Policy.Fluent`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(Policy.Fluent, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3.基于子进度的深度（并行）") {
      Given("传入参数`Policy.Depth(2)`，启动多层嵌套的 Reflow：")
      val scheduler = reflow9x.start(none, implicitly)(Policy.Depth(2), poster)
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
    info("还可以进行多Policy叠加")
    Scenario("1. 叠加方案: Policy.Fluent -> Policy.Depth(2)") {
      val policy = Policy.Fluent -> Policy.Depth(2)
      val scheduler = reflow9x.start(none, implicitly)(policy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("2. 叠加方案: Policy.Depth(2) -> Policy.Fluent") {
      val policy = Policy.Depth(2) -> Policy.Fluent
      val scheduler = reflow9x.start(none, implicitly)(policy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3. 叠加方案: Policy.Interval(6) -> Policy.Fluent") {
      val policy = Policy.Interval(6) -> Policy.Fluent
      val scheduler = reflow9x.start(none, implicitly)(policy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
    Scenario("3. 叠加方案: Policy.Depth(3) -> Policy.Interval(6)") {
      val policy = Policy.Depth(3) -> Policy.Interval(6)
      val scheduler = reflow9x.start(none, implicitly)(policy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
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
      override def reset(thread: Thread): Unit = {
        // 对于`Android`平台，线程的优先级是通过`Process`来调用的。
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
```
