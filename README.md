# Reflow

A light-weight lock-free `series/parallel` combined scheduling framework for tasks. The goal is to maximize parallelism in order to minimize the execution time overall.

----

* From imperative to `Monad`: **[lite.Task](https://github.com/dedge-space/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/lite/Task.scala) —— Simplified and Type-safe Task Assembly Toolset**
  - Simplify the mixed assembly of 'series/parallel' tasks, and each task definition and assembly tool are marked with `I/O` type, using the compiler check, match errors at a glance. Examples see _[here](https://github.com/dedge-space/Reflow/blob/master/src/test/scala/reflow/test/LiteSpec.scala#L95)_.

* From single execution to streaming: **[Pulse](https://github.com/dedge-space/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Pulse.scala#L46) —— Step Streaming Data Processor**
  - Data flows through a `set of large-scale integration tasks`, can always be entered in the order in which it was entered, can be 'queued' (`FIFO`, using a ingenious scheduling strategy that doesn't actually have queue) into each tasks,
    and each task can also retain the mark specially left by the previous data during processing, so as to be used for the next data processing.
    It doesn't matter in any subtask of any depth, and it doesn't matter if the previous data stays in a subtask much longer than the latter.
    Examples see _[here](https://github.com/dedge-space/Reflow/blob/master/src/test/scala/reflow/test/PulseSpec.scala)_.

----

## Overview

"The maximum improvement in system performance is determined by the parts that cannot be parallelized."
        —— [Amdahl's law](https://en.wikipedia.org/wiki/Amdahl%27s_law)

In a large system, there is often a lot of business logic and control logic, which is the embodiment of hundreds of "jobs". The logic is intertwined and, taken as a whole, often intricate.
On the other hand, if this large amount of "jobs" is not organized reasonably and effectively, the execution time overall and performance are also worrying.
So how do we abstract and simplify such a complex problem?

> Programs = Algorithms + Data Structures  
> Algorithm = Logic + Control  

Inspired by this idea, we can separate the business logic from the control logic, abstract the control logic as a framework, and construct the business logic as a **Task**.
The relationship between tasks can also be further classified into two types: _dependent_ and _non-dependent_, that is, **serial** and **parallel** (dependent tasks must be executed sequentially, and non-dependent tasks can be executed in parallel).
The user programmer only needs to focus on writing task one by one and leave the rest to the framework. **Reflow** is designed around the control logic that handles these tasks.

**Reflow** was developed to simplify the **coding complexity** of data-flow and event-processing between multiple [tasks](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L28)
in _complex business logic_. Through the design of [ I](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L48)
/[ O](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L53)
that requires [explicit definition](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Trait.scala#L27)
of tasks, intelligent [dependency management](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L31)
based on [keyword](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/KvTpe.scala#L26)
and [value-type](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/KvTpe.scala#L26)
analysis, unified operation [scheduling](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Scheduler.scala#L26),
[event feedback](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Feedback.scala#L25)
and [error handling](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Task.scala#L124)
interface, the set goal is realized: task [_series_](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L78)
/[ _parallel_](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Dependency.scala#L52)
combined scheduling. _Data_ is **electricity**, _task_ is **component**.
In addition to simplifying the coding complexity, the established framework can standardize the original chaotic and intricate writing method,
and coding errors can be easily detected, which will greatly enhance the **readability**, **robustness** and **scalability** of the program.

In Reflow basic logic, a complex business should first be broken down into a series of **single-function**, **no-blocking**, **single-threaded** task sets and packaged in `Trait` that explicitly define the attributes of the task.
Dependencies are then built and committed using `Dependency`, and a working `reflow` object is finally obtained, which is started and the task flow can be executed.


## Features

- The intelligent dependency analysis algorithm based on **keyword** and **value-type** can analyze errors in task assembly stage, ensuring that there will not be keyword missing or value-type mismatch errors during task execution;
- On-demand task loading mechanism based on **priority** and **estimated duration**. **On-demand** means that the task is put into the _priority-bucket_ to **wait for** to be executed when it is its turn to execute.
  Why wait? Because the current tasks to be executed in different task-flows will be put into the same priority-bucket, the existing tasks in the bucket will be sorted according to their priorities;
- A task-flow can be requested to **browse** mode first and then **reinforce** mode. **Browse** mode allows data to be loaded quickly;
- Tasks can be nested and assembled indefinitely;
- Event feedback can be specified to the thread. For example: UI thread;
- If subsequent tasks do not depend on the output of a task, the output is discarded, allowing memory to be used efficiently;
- Convenient **synchronous/asynchronous** mode switching:
  > 
  ```Scala
    // Asynchronous by default
    reflow.start(input, feedback)
    // To change to sync, simply add 'sync()' to the end.
    reflow.start(input, feedback).sync()
  ```
- Task execution progress can be strategically feed back:
  > 
  ```Scala
    // `Progress.Strategy.Xxx` e.g.
    implicit lazy val strategy: Strategy = Strategy.Depth(3) -> Strategy.Fluent -> Strategy.Interval(600)
  ```
- Deterministic classification of exceptions:
  > 
  ```Scala
    // Feedback.scala
    def onFailed(trat: Trait, e: Exception): Unit = {
    e match {
      // There are two categories:
      // 1. `RuntimeException` caused by customer code quality issues such as `NullPointerException` are wrapped in `CodeException`,
      // The specific exception object can be retrieved through the `CodeException#getCause()` method.
      case c: CodeException => c.getCause
      // 2. Custom `Exception`, which is explicitly passed to the 'Task#failed(Exception)' method parameter, possible be
      // `null` (although Scala considers `null` to be low level).
      case e: Exception => e.printStackTrace()
    }
  }
  ```
- Clever interrupt strategy;
- Global precise tasks monitoring and management:
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
      })(Strategy.Interval(600), null)
  ```
- Optimized configurable thread pool: If tasks continues scheduling to the thread pool, increase the size of threads to the `configured maximum`, then enqueue, idle release threads until `core size`;
- Thread implementation without blocking (lock-free) efficient utilization:
  > There is no code for `future.get()` mechanism;  
  > This requires no blocking in the user-defined tasks (if there is a block, you can split the task into multiple dependent but non-blocking tasks, except for network requests).

These features greatly meet the practical requirements of various projects.


### _1.1 Related_

The main features of this framework are similar to [Facebook Bolts](http://github.com/BoltsFramework/Bolts-Android) and [RxJava](https://github.com/ReactiveX/RxJava), can be seen as fine-grained extensions of their task-combination capabilities, but it's more intuitive to use, more rigorous, high-spec, and closely aligned with actual project needs.

This framework is implemented based on the **thread-pool** (`java.util.concurrent.ThreadPoolExecutor`) instead of the **Fork-Join** framework (`java.util.concurrent.ForkJoinPool`), and improved the former (see [Worker](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Worker.scala#L59)) to conform to the basic logic of
_**increase the size of threads to [maximum](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/Config.scala#L28) first, or else enqueue, release thread when idle**_.
The latter is suitable for computationally intensive tasks, but is not suitable for the design objectives of this framework, and is not suitable for resource-constrained devices (e.g. mobile phones, etc).


### _1.2 Instruction_

This framework is completely written in Scala language, and all parameters support **[shorthand](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala#L130)**, will be automatically escaped as **needed** ([implicit](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/reflow/implicits.scala#L40)), can be used on any platform that adopts **jvm-like** (e.g. Android Runtime).

This framework is based on a special **anti-blocking** thread synchronization tool [Snatcher](https://github.com/WeiChou/Reflow/blob/master/src/main/scala/hobby/wei/c/tool/Snatcher.scala#L25), see the code documentation for details.

* Note: This framework does not use `java.util.concurrent.Future<V>` tools to handle parallel tasks, since it is implemented based on the **thread blocking** model, it does not meet the design goals of this framework: **lock-free**.


## How Reflow works
![Reflow operation principle diagram](https://github.com/WeiChou/Reflow/blob/master/reflow_operation-principle-diagram.png "Reflow operation principle diagram")


## Start using Reflow

### _3.1 Dependencies_

Please click here [![](https://jitpack.io/v/bdo-cash/reflow.svg)](https://jitpack.io/#bdo-cash/reflow)

### _3.2 Example_

See _below_ or [LiteSpec](https://github.com/dedge-space/Reflow/blob/master/src/test/scala/reflow/test/LiteSpec.scala#L95), and [~~ReflowSpec~~](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala).

* If using on Android platform, please make the following settings first.

```Scala
class App extends AbsApp {
  override def onCreate(): Unit = {
    App.reflow.init()
    super.onCreate()
  }
}

object App {
  object implicits {
    // Feedback strategy for task-flow execution progress
    implicit lazy val strategy: Strategy = Strategy.Depth(3) -> Strategy.Fluent -> Strategy.Interval(600)
    implicit lazy val poster: Poster = new Poster {
      // Post feedback to the UI thread
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

* Below is part of [LiteSpec](https://github.com/dedge-space/Reflow/blob/master/src/test/scala/reflow/test/LiteSpec.scala#L95), which is very concise and recommended:

```Scala
// ...
Scenario("`Serial/Parallel` Tasks mixed assembly") {
  val pars =
    (
      c2d
      +>>
      c2abc.inPar("name#c2abc", "`Serial` mix in `Parallel`")
      +>>
      (c2b >>> b2c >>> c2a >>> a2b) //.inPar("xxx") is optional.
      +>>
      c2a
    ) **> { (d, c, b, a, ctx) =>
      info(a.toString)
      info(b.toString)
      info(c.toString)
      info(d.toString)
      d
    }
  Input(new Aaa) >>> a2b >>> b2c >>> pars run() sync()

  assert(true)
}

// `Pulse`, more complex:
Scenario("Multi-level nested assembly test of `Pulse`") {
  val pars = {
    def p = (c2d +>> c2b) **> { (d, _, ctx) => ctx.progress(1, 2); d } +|- { (_, d) => d }
    @tailrec
    def loop(s: () => Serial[Ccc, Ddd], times: Int = 0, limit: Int = 10): Serial[Ccc, Ddd] =
      if (times >= limit) s()
      else {
        def p =
          (
            s() +|- { (_, d) => d }
            +>>
            s()
            +>>
            s()
          ) **> { (d, _, _, ctx) => ctx.progress(1, 2); d }
        loop(() => p, times + 1, limit)
      }
    loop(() => p, limit = 3)
  }

  @volatile var callableOut: Int = 0
  val future                     = new FutureTask[Int](() => callableOut)

  val repeatCount = 1000 // Int.MaxValue

  val pulse = (Input[Aaa] >>> a2b >>> b2c >>> pars) pulse (new reflow.Pulse.Feedback.Lite[Ddd] {
    override def onStart(serialNum: Long): Unit = {}
    // ...
  }
  // ...
```

* Additional, refer to [ReflowSpec](https://github.com/WeiChou/Reflow/blob/master/src/test/scala/reflow/test/ReflowSpec.scala), which is verbose and no longer recommended.

