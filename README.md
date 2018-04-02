## Reflow: 任务 _`串/并联`_ 组合调度框架


#### 概述

本框架为 _简化复杂业务逻辑中 **多[任务](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L28)之间的数据流转和事件处理**_ 的 _编码复杂度_ 而生。通过 **_要求[显式定义](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L27)_ 任务的 [I](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L48)/[O](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L53)**、基于 [_**关键字**_ 和 _**值类型**_](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Kce.scala#L26) 分析的智能化 **[依赖管理](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L31)**、一致的 **[运行调度](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L26)**、**[事件反馈](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L25)** 及 **[错误处理](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L56)** 接口等设计，实现了既定目标：**任务 _`[串](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L78)/[并](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L52)联`_ 组合调度**。 _数据_ 即 **电流**， _任务_ 即 **元件**。在简化编码复杂度的同时，确定的框架可以将原本杂乱无章、错综复杂的写法规范化，编码失误也极易被检测，这将大大增强程序的 **易读性**、**健壮性** 和 **可扩展性**。

此外还有优化的 _[可配置](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Config.scala#L19)_ [线程池](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Worker.scala#L71)、基于 _[优先级](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L58)_ 和 _[预估时长](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L63)_ 的 **按需的** 任务装载机制、便捷的 **[同](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L34)/异（默认）步** 切换调度、巧妙的 _[中断](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L51)策略_ 、任务的 _无限_ **嵌套** 组装、**浏览/[强化](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L100)** 运行模式、 _无依赖输出_ **丢弃**、事件反馈可 **指定到线程** 和对异常事件的 _确定性分类_ 等设计，实现了线程的 **无** _阻塞_ 高效利用、全局 **精准** 的任务管理、内存的 _有效利用_(垃圾丢弃)、以及数据的 _快速加载_(**浏览** 模式)和进度的 _策略化反馈_ ，极大地满足了大型项目的需求。


#### 相关

本框架的主要功能类似 [Facebook Bolts](http://github.com/BoltsFramework/Bolts-Android) 和 [RxJava](https://github.com/ReactiveX/RxJava)，可以视为对它们 _任务组合能力_ 的细粒度扩展，但更加严谨、高规格和 **贴近实际项目需求**。

本框架基于 **线程池**(`java.util.concurrent.ThreadPoolExecutor`) 实现而非 **Fork/Join框架（JDK 1.7）**(`java.util.concurrent.ForkJoinPool`)，并对前者作了 [改进](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Worker.scala#L59) 以符合 **先增加线程数到 [最大](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Config.scala#L28)，再入队列，空闲释放线程** 这个基本逻辑；后者适用于计算密集型任务，但不适用于本框架的设计目标，也不适用于资源受限的设备（如：手机等）。


#### 说明

本框架完全采用 Scala 语言编写，参数都支持 **[简写](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala#L130)**，会自动 **按需** 转义（[implicit](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/implicits.scala#L40) 隐式转换）。可用于采用 jvm 的任何平台。

本框架衍生了一个特别的 **抗阻塞**_线程同步_ 工具 [`Snatcher`](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/tool/Snatcher.scala#L25)（详见代码文档）。

* 特别说明：本框架没有采用 `java.util.concurrent.Future<V>` 工具来处理并行任务，因为它是基于 **线程阻塞** 模型实现的，不符合本框架的设计目标。

#### 开始使用 Reflow

**应用示例** 见特性测试 [`ReflowSpec`](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala)。

