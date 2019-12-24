## Reflow:  任务 _`串/并联`_ 组合调度框架，脉冲步进流处理框架。

* 新增:  **[Pulse](https://github.com/dedge-space/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Pulse.scala#L46) 步进流式数据处理器**
  - 数据流经`大规模集成任务集（Reflow）`，能够始终保持输入时的先后顺序，会“排队”（`FIFO`，使用了一种巧妙的调度策略而不会真的有队列）进入各个任务，每个任务还可保留前一个数据在处理时特意留下的标记。无论在任何深度的子任务中，也无论前一个数据在某子任务中停留的时间是否远大于后一个。

#### 一、概述

在一个大型系统中，往往存在着大量的业务逻辑和控制逻辑，它们是数以百计的“工作”的具体化。这些逻辑交织在一起，从整体上看，往往错综复杂。那么我们该如何抽象并简化这些复杂的逻辑呢？受

> **Programs = Algorithms + Data Structures**  
> **Algorithm = Logic + Control**  

思想的启发，我们可以将业务逻辑和控制逻辑分开，把控制逻辑抽象为框架，把业务逻辑构造为**任务（Task）**。而任务之间的关系也可进一步归纳为两类：有依赖和无依赖，即：**串行**和**并行**。用户程序员只需要专注于编写任务集（即：拆分业务逻辑），其它交给框架。Reflow 的设计便是围绕着处理这些任务的控制逻辑而展开。

Reflow 为 _简化复杂业务逻辑中_ **多[任务](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L28)之间的数据流转和事件处理** 的 _编码复杂度_ 而生。通过 **要求[显式定义](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L27) 任务的[ I ](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L48)/[O ](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L53)**、基于 [**关键字** 和 **值类型**](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Kce.scala#L26) 分析的智能化 [**依赖管理**](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L31)、 一致的 [**运行调度**](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L26)、[**事件反馈**](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L25) 及 [**错误处理**](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L124) 接口等设计，实现了既定目标：**任务[串](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L78)/[并联](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L52)组合调度**。 _数据_ 即 **电流**， _任务_ 即 **元件**。在简化编码复杂度的同时，确定的框架可以将原本杂乱无章、错综复杂的写法规范化，编码失误也极易被检测，这将大大增强程序的 **易读性**、**健壮性** 和 **可扩展性**。

##### Reflow 有以下特性：

- 基于**关键字**和**值类型**的智能化依赖分析算法，在任务组装阶段即可分析出错误，确保了在任务执行期间不会出现关键字缺失或值类型不匹配的错误；
- 基于**优先级**和**预估时长**的按需的任务装载机制。**按需** 是指，轮到该任务执行时，它才会被放入优先级桶中**等待**被执行。为什么还要等待？因为不同任务流中当前待执行的任务会被放入同一个优先级桶中，那么这些已经存在于桶中的任务会按各自的优先级进行排序；
- 一个任务流可申请先**浏览**后**强化**的运行模式。**浏览**模式使得数据可以被快速加载；
- 任务可以无限嵌套组装；
- 事件反馈可指定到线程。如：UI 线程；
- 如果后续任务不依赖于某任务的某个输出，那么该输出将会被丢弃，使得内存能够被有效利用；
- 便捷的 **同/异步** 模式切换：
  > 
  ```Scala
    // 默认异步
    reflow.start(input, feedback)
    // 若要改为同步，只需在结尾加上`sync()`
    reflow.start(input, feedback).sync()
  ```
- 任务执行进度可以被策略化反馈：
  > 
  ```Scala
    // Progress.Policy.Xxxx, 如：
    implicit lazy val policy: Policy = Policy.Depth(3) -> Policy.Fluent -> Policy.Interval(600)
  ```
- 对异常事件有确定性分类：
  > 
  ```Scala
    // Feecback.scala
    def onFailed(trat: Trait, e: Exception): Unit = {
    e match {
      // 分为两类:
      // 1. 由客户代码质量问题导致的`RuntimeException`如`NullPointerException`等，这些异常被包裹在`CodeException`里，
      // 可以通过`CodeException#getCause()`方法取出具体的异常对象。
      case c: CodeException => c.getCause
      // 2. 客户代码自定义的`Exception`，即显式传给`Task#failed(Exception)`方法的参数，可能为`null`（虽然 Scala 认为`null`是 low level 的）。
      case e: Exception => e.printStackTrace()
    }
  }
  ```
- 巧妙的中断策略；
- 全局精准的任务监控和管理；
  > 
  ```Scala
    Reflow.GlobalTrack.registerObserver(new GlobalTrackObserver {
        override def onUpdate(current: GlobalTrack, items: All): Unit = {
          if (!current.isSubReflow && current.scheduler.getState == State.EXECUTING) {
            println(s"++++++++++[[[current.state:${current.scheduler.getState}")
            items().foreach(println)
            println("----------]]]")
          }
        }
      })(Policy.Interval(600), null)
  ```
- 优化的可配置线程池：如果有任务持续进入线程池队列，那么先增加线程数直到配置的最大值，再入队列，空闲释放线程直到`core size`；
- 线程实现了无阻塞高效利用：
  > 没有`future.get()`机制的代码；  
  > 这需要用户定义的任务中无阻塞（若有阻塞可以拆分成多个有依赖关系但无阻塞的任务，网络请求除外）。

这些特性极大地满足了各种项目的实际需求。在 Reflow 的逻辑里，首先应将复杂业务拆分成多个**功能单一**的、没有**阻塞**等待的、**单线程**结构的一系列任务集合，并将它们包装在显式定义了任务的各种属性的`Trait`里，然后使用`Dependency`构建依赖关系并提交，最终获得一个可运行的`reflow`对象，启动它，任务流便可执行。


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

请戳这里 [![](https://jitpack.io/v/dedge-space/reflow.svg)](https://jitpack.io/#dedge-space/reflow)

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
    // 任务流执行进度的反馈策略
    implicit lazy val policy: Policy = Policy.Depth(3) -> Policy.Fluent -> Policy.Interval(600)
    implicit lazy val poster: Poster = new Poster {
      // 把反馈 post 到 UI 线程
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
package reflow.test

import java.util.concurrent.{Callable, FutureTask}
import hobby.chenai.nakam.lang.J2S.future2Scala
import hobby.wei.c.reflow._
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.Reflow.GlobalTrack.GlobalTrackObserver
import org.scalatest._
import reflow.test.enum.EnumTest

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
      val reflow = dependency.submit(kces.outputstr)
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
      val scheduler = Reflow.create(trat).next(Trait("t1", SHORT) { _ => }).submit(kces.outputstr)
        .start(none, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(outputStr)(scheduler.sync()(kces.outputstr.key))
    }

    Scenario("[Scala 枚举]在`In`中的 Bug") {
      val trat = Trait("t1", SHORT, none, kces.enum) { _ => }
      val scheduler = Reflow.create(trat).submit(kces.enum)
        .start(kces.enum -> EnumTest.A, implicitly)
      info("输出：" + scheduler.sync())
      assertResult(EnumTest.A)(scheduler.sync()(kces.enum))
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
      val reflowTrat = reflow0.torat("master&slave")
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
      val reflow = Reflow.create(trats.int2str0).submit(kces.str)
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
      val reflow = Reflow.create(trats.int2str0).submit(kces.str)
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
    Scenario("[局部]转换") {
      val scheduler = Reflow.create(
        Trait("int2str", TRANSIENT, kces.int, kces.str) { ctx =>
          ctx.output(kces.int, Integer.valueOf(ctx.input(kces.str).getOrElse("-1")))
        }, transformer)
        .submit(kces.str)
        .start(kces.str -> "00000", implicitly)
      assertResult("00000")(scheduler.sync()(kces.str))
    }
    Scenario("[全局]转换 1") {
      val scheduler = Reflow.create(
        Trait("int2str 1", TRANSIENT, kces.int, kces.str) { ctx =>
          ctx.output(kces.int, Integer.valueOf(ctx.input(kces.str).get))
        }).next(transformer)
        .submit(kces.str)
        .start(kces.str -> "11111", implicitly)
      assertResult("11111")(scheduler.sync()(kces.str))
    }
    Scenario("[不]转换 2") {
      val scheduler = Reflow.create(
        Trait("int2str 2", TRANSIENT, kces.int, kces.str) { ctx =>
          ctx.output(kces.int, Integer.valueOf(ctx.input(kces.str).get))
        }, transformer).next(transformer)
        .submit(kces.int)
        .start(kces.str -> "22222", implicitly)
      assertResult(22222)(scheduler.sync()(kces.int))
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
        .submit(kces.int)
        .start(kces.str -> "11111", feedback)
      info(s"强化运行后的最终输出。out:${scheduler.sync(/*reinforce = true*/)}")
      assertResult(12345)(scheduler.sync(reinforce = true)(kces.int))
    }
  }

  Feature("跨线程回调反馈") {
    Scenario("使用`Poster`令`Feedback`在指定线程被调用") {
      Given("一个`Reflow`")
      val reflow = Reflow.create(trats.int2str0).submit(kces.str)
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

    val reflow0 = Reflow.create(trat4Progress).submit(none)
    val reflow1 = Reflow.create(trat4Progress).next(reflow0.torat("reflow0")).submit(none)
    val reflow2 = Reflow.create(trat4Progress).next(reflow1.torat("reflow1")).submit(none)
    val reflow3 = Reflow.create(trat4Progress).next(reflow2.torat("reflow2")).submit(none)
    val reflow4 = Reflow.create(trat4Progress).next(reflow3.torat("reflow3")).submit(none)
    val reflow5 = Reflow.create(trat4Progress).next(reflow4.torat("reflow4")).submit(none)
    val reflow6 = Reflow.create(trat4Progress).next(reflow5.torat("reflow5")).submit(none)
    val reflow7 = Reflow.create(trat4Progress).next(reflow6.torat("reflow6")).submit(none)
    val reflow8 = Reflow.create(trat4Progress).next(reflow7.torat("reflow7")).submit(none)
    val reflow9 = Reflow.create(trat4Progress).next(reflow8.torat("reflow8")).submit(none)
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

    val reflow0x = Reflow.create(trat4Progress).submit(none)
    val reflow1x = Reflow.create(trat4Progress).and(reflow0x.torat("reflow0x")).submit(none)
    val reflow2x = Reflow.create(trat4Progress).and(reflow1x.torat("reflow1x")).submit(none)
    val reflow3x = Reflow.create(trat4Progress).and(reflow2x.torat("reflow2x")).submit(none)
    val reflow4x = Reflow.create(trat4Progress).and(reflow3x.torat("reflow3x")).submit(none)
    val reflow5x = Reflow.create(trat4Progress).and(reflow4x.torat("reflow4x")).submit(none)
    val reflow6x = Reflow.create(trat4Progress).and(reflow5x.torat("reflow5x")).submit(none)
    val reflow7x = Reflow.create(trat4Progress).and(reflow6x.torat("reflow6x")).submit(none)
    val reflow8x = Reflow.create(trat4Progress).and(reflow7x.torat("reflow7x")).submit(none)
    val reflow9x = Reflow.create(trat4Progress).and(reflow8x.torat("reflow8x")).submit(none)
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
    Scenario("4. 叠加方案: Policy.Depth(3) -> Policy.Interval(6)") {
      val policy = Policy.Depth(3) -> Policy.Interval(6)
      val scheduler = reflow9x.start(none, implicitly)(policy, poster)
      Then("观察输出的`Progress`日志")
      scheduler.sync()
      assert(true)
    }
  }

  Feature("`fork()`") {
    val dependency = Reflow.create(trats.int2str0).next(trats.str2int)
    info("`dependency`可以`fork`")
    val reflow = dependency.fork().submit(kces.str)
    info("`reflow`也可以`fork`")
    reflow.fork().start(kces.int -> Integer.valueOf(66666), implicitly)
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
```
