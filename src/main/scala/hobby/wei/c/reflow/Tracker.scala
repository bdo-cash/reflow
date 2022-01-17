/*
 * Copyright (C) 2016-present, Wei Chou(weichou2010@gmail.com)
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

package hobby.wei.c.reflow

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.log.Logger._
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Dependency.{IsPar, SetTo, _}
import hobby.wei.c.reflow.Feedback.Progress
import hobby.wei.c.reflow.Feedback.Progress.{Strategy, Weight}
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.reflow.State._
import hobby.wei.c.reflow.Tracker.Runner
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool.Snatcher
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Condition, ReentrantLock}
import scala.collection.{mutable, _}
import scala.collection.concurrent.TrieMap
import scala.util.control.Breaks._
import scala.util.control.NonFatal

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016;
  *          1.1, 31/01/2018, 重启本项目直到完成；
  *          1.2, 05/07/2018, 更新以便支持`Pulse`功能；
  *          1.3, 23/03/2019, 修改了`cacheInited`和`reinforceCache`以及`cache`初始化相关方法；
  *          1.4, 08/04/2019, fix 全局转换时的一个偶现的 bug；
  *          1.5, 04/10/2019, fix 了有关`Pulse`的一个 bug;
  *          1.6, 12/07/2020, fix bug: Progress(..trat);
  *          1.7, 29/09/2020, 小优化：`System.currentTimeMillis` -> `System.nanoTime`;
  *          2.0, 13/10/2020, fallback `Progress(..trat)` -> `Progress(..top)`, and add `Progress(..trigger)`;
  *          2.1, 18/12/2020, bug fix: 支持`Pulse.abortIfError = false`的定义（在异常出错时也能正常前进）；
  *          2.2, 22/12/2020, `sync()`的实现去掉了`Locker`，改为原生用法，并对`interruptSync()`作了小优化；
  *          2.3, 04/01/2021, 增加`autoProgress`控制；
  *          2.4, 30/04/2021, 增加`Progress.Weight`，优化`autoProgress = false`时子进度的更新问题；
  *          2.5, 30/12/2021, bug fix: 任务执行过快而引发的时序问题。发现：`lite.Par2.merge.input0`返回`None`(应恒为`Some(Xxx)`)；
  *          清理`debugMode`判断；重构了`lite.ParN.merge`，大大减少了不必要的`SubReflow`层级。
  * @param strategy 当前`Reflow`启动时传入的`Strategy`。由于通常要考虑到父级`Reflow`的`Strategy`，因此通常使用`strategyRevised`以取代本参数；
  * @param pulse    流处理模式下的交互接口。可能为`null`，表示非流处理模式。
  */
private[reflow] abstract class Tracker(val reflow: Reflow, val strategy: Strategy, val outer: Option[Env], val pulse: Pulse.Interact, val serialNum: Long, val globalTrack: Boolean) extends TAG.ClassName {
  require(strategy.nonNull)
  // 在浏览运行阶段会根据需要自行创建（任务可能需要缓存临时参数到 cache 中）；
  // 而在`Reinforce`阶段，会从外部传入。
  // 因此有这样的设计。
  // 1.3, 23/03/2019, 将`false`改为了`true`。
  private lazy val cacheInited = new AtomicBoolean(outer.fold(true)(_.isReinforcing))

  // 1.3, 23/03/2019, 最外层的`Tracker`在各个阶段（`Reinforce`）都是同一个实例，因此本变量也是同一个实例。
  private final lazy val reinforceCache = outer.fold(new ReinforceCache) { env =>
    if (isPulseMode) assert(!env.isReinforcing)
    // 1.3, 23/03/2019, 去掉`env.obtainCache.get`后面的`OrElse(new ReinforceCache)`。
    if (env.isReinforcing) env.obtainCache.get else new ReinforceCache
  }

  @deprecated(message = "不要直接调用本属性，特别是对于`SubReflow`，根本不需要使用它，否则会导致状态错误。", since = "0.0.1")
  private final lazy val reinforceRequired = new AtomicBoolean(false)

  private final def getOrInitFromOuterCache(trat: String = null, sub: Option[ReinforceCache] = None): ReinforceCache = {
    // 1.3, 23/03/2019, 本操作和下面的`cache init`是不相干的两件事。本操作有可能执行多次，因为有可能是多个并行子任务。
    // `Reinforce`阶段，`cacheInited`生来为`true`，不需要初始化，也就不会触发本方法递归，因此`sub`始终为`None`, 不会
    // 引起不必要的多余调用，结果是直接返回（不过`getCache`和上面`obtainCache`会触发循环递归，直到最外层的`Tracker`或`reinforceCache`已经初始化）；
    // 只是在非`Reinforce`阶段，会有一些重复不必要的调用，但没有更好的办法。
    sub.foreach(reinforceCache.subs.putIfAbsent(trat, _))
    if (cacheInited.compareAndSet(false, true)) {
      outer.foreach { env => env.tracker.getOrInitFromOuterCache(env.trat.name$, Option(reinforceCache)) }
    }
    reinforceCache
  }
  final def getCache = getOrInitFromOuterCache()

  final def subDepth: Int               = outer.fold(0)(_.depth + 1)
  final def reflowTop: Reflow           = outer.fold(reflow)(_.reflowTop)
  final def parent: Option[ReflowTrait] = outer.map(_.trat.as[ReflowTrait])

  def getState: State.Tpe
  private[reflow] def isInput(trat: Trait): Boolean
  private[reflow] def getPrevOutFlow: Out

  final def isPulseMode: Boolean         = pulse.nonNull
  final def isSubReflow: Boolean         = outer.isDefined
  final def isReinforceRequired: Boolean = outer.fold(reinforceRequired.get)(_.isReinforceRequired)
  final def isReinforcing: Boolean       = outer.fold(getState.group == REINFORCING.group /*group代表了几个状态*/ )(_.isReinforcing)

  final def requireReinforce(trat: Trait): Boolean = {
    assert(!trat.isPar)
    // 必须提前执行，以便递归。本实现支持并发，线程安全。
    val required = outer.fold(reinforceRequired.getAndSet(true))(_.requireReinforce())
    val cache    = getCache
    if (cache.inputs.isNull) {
      cache.inputs = getPrevOutFlow.ensuring(_.nonNull)
    }
    if (cache.begins.isEmpty || reflow.basis.topOf(cache.begins.head._1) == reflow.basis.topOf(trat) /*处于同一个并行组*/ ) {
      cache.begins += ((trat.name$, ()))
      onRequireReinforce(trat, cache)
    }
    required
  }

  /** 注意：对于并行任务，每次请求`reinforce`都会回调一次本方法。 */
  private[reflow] def onRequireReinforce(trat: Trait, cache: ReinforceCache): Unit = {}
  private[reflow] def onTaskStart(trat: Trait): Unit
  private[reflow] def onTaskProgress(trat: Trait, progress: Progress, out: Out, depth: Int, publish: Boolean): Unit
  private[reflow] def onTaskComplete(trat: Trait, out: Out, flow: Out): Unit
  /**
    * @param trigger 触发者。可能是从内层（嵌套的`SubReflow`）的任务传来的。
    * @param curr    当前正在执行的trat。
    */
  private[reflow] def performAbort(trigger: Option[Trait], curr: Trait, parent: Option[ReflowTrait], depth: Int, forError: Boolean, e: Exception): Unit
  /** 先于`endRunner(Runner)`执行。 */
  private[reflow] def innerError(runner: Runner, t: Throwable): Unit
  /** `Task`完全运行完毕（意味着线程在执行本方法体之后，`Task`的代码即全部处理完毕，线程将会结束或者接着处理下一个执行体）。 */
  private[reflow] def endRunner(runner: Runner): Unit
}

private[reflow] class ReinforceCache {
  // 用`trat.name$`作为`key`, 同一个`Reflow`中，`name$`是不能重名的，因此该方法可靠。
  /** 子`Trait`的`Task`缓存用到的`Out`。 */
  lazy val caches = TrieMap.empty[String, Out]
  /** 子`Trait`的`Task`启动的`Reflow`对应的`Tracker`的`Cache`。 */
  lazy val subs = TrieMap.empty[String, ReinforceCache]
  /** 作`Set`用。`reinforce`阶段开始时的`Trait.name$`。 */
  lazy val begins           = TrieMap.empty[String, Unit]
  @volatile var inputs: Out = _
  /** 对于一个并行任务的某些子任务的`reinforce`请求，我们不打算再次执行整个并行任务，因此
    * 需要保留`浏览`运行模式的`输入`（见`inputs`）和`结果`（本`outs`），以便在`reinforce`之后合并输出。
    * 本输出包含的`_keys`是当前`并行`任务中所有未申请`reinforce`的执行输出。
    */
  @volatile var outs: Out = _

  override def toString = s"ReinforceCache:\n caches:$caches,\n subs:$subs,\n begins:$begins,\n inputs:$inputs,\n outs:$outs."
}

private[reflow] object Tracker {

  private[reflow] final class Impl(
      reflow: Reflow,
      traitIn: Trait,
      transIn: immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]],
      state: Scheduler.State$,
      feedback: Feedback,
      strategy: Strategy,
      outer: Option[Env],
      pulse: Pulse.Interact,
      serialNum: Long,
      globalTrack: Boolean
  ) extends Tracker(reflow, strategy, outer, pulse, serialNum, globalTrack)
         with Scheduler
         with TAG.ClassName {
    private lazy val snatcher                  = new Snatcher.ActionQueue(strategy.isFluentMode)
    private lazy val syncLock: ReentrantLock   = new ReentrantLock
    @volatile private var condition: Condition = _

    private def sum                                          = reflow.basis.traits.length
    private lazy val runnersParallel                         = TrieMap.empty[String, Map[Runner, (AtomicBoolean, AtomicBoolean)]]
    private lazy val progress                                = TrieMap.empty[String, Progress]
    private lazy val weightes                                = reflow.basis.weightedPeriods()
    private lazy val reporter                                = if (debugMode && !strategy.isFluentMode /*受`snatcher`的参数的牵连*/ ) new Reporter4Debug(reflow, feedback, sum) else new Reporter(feedback)
    @volatile private var remaining                          = reflow.basis.traits
    @volatile private var normalDone, reinforceDone: Boolean = false
    @volatile private var outFlowTrimmed, prevOutFlow: Out   = _
    @volatile private var timeStart                          = 0L

    private[reflow] def start$(): Unit = {
      //if (debugMode) log.w("[start]serialNum:%s, reinforcing:%s, subReflow:%s, subDepth:%s.", serialNum, isReinforcing, isSubReflow, subDepth)
      assert(remaining.nonEmpty, s"`start()`时，不应该存在非空任务列表。isReinforcing:$isReinforcing")
      if (isReinforcing) { // 如果当前是子 Reflow，则首先要看是不是到 reinforce 阶段了。
        if (isSubReflow) {
          state.forward(COMPLETED)
          state.forward(REINFORCE_PENDING)
        } else assert(state.get == REINFORCE_PENDING)
        val cache = getCache
        assert(cache.begins.nonEmpty)
        assert(cache.inputs.nonNull, s"缓存应该有输入参数：${cache.inputs}。")
        // 切换到reinforce的开始位置
        switch2ReinforceBegins(cache)
        prevOutFlow = cache.inputs
        outFlowTrimmed = new Out(reflow.basis.outsFlowTrimmed(remaining.head.name$))
        outFlowTrimmed.fillWith(prevOutFlow, fullVerify = false)
        tryScheduleNext(remaining.head)
      } else {
        timeStart = System.currentTimeMillis
        if (state.get == PENDING) { // 实测可能被`ABORTED`
          snatcher.queAc(reporter.reportOnPending())
          //prevOutFlow = new Out() // 不用赋值
          outFlowTrimmed = new Out(traitIn.outs$)
          tryScheduleNext(traitIn)
        }
      }
    }

    override private[reflow] def isInput(trat: Trait) = trat == traitIn
    private def tratTopOrIn(curr: Trait): Trait       = if (isInput(curr)) traitIn else reflow.basis.topOf(curr)
    private def stepOfWithIn(curr: Trait): Int        = if (isInput(curr)) -1 else reflow.basis.stepOf(curr)

    override private[reflow] def innerError(runner: Runner, t: Throwable): Unit = {
      log.e(t, "[innerError] %s : %s : %s | trat:%s, parent:%s.", serialNum, subDepth, stepOfWithIn(runner.trat), runner.trat.name$.s, parent.map(_.name$.s).orNull)
      // 正常情况下不要调用，仅用于测试。
      //performAbort(Some(runner.trat), runner.trat, parent, subDepth, forError = true, e)
    }

    override private[reflow] def endRunner(runner: Runner): Unit = {
      //if (debugMode) log.i("[endRunner] %s : %s : %s | trat:%s, parent:%s.", serialNum, subDepth, stepOfWithIn(runner.trat), runner.trat.name$.s, parent.map(_.name$.s).orNull)
      // 拿到父级`trait`（注意：如果当前是并行的任务，则`runner.trat`是子级）。
      val tratGlobal = tratTopOrIn(runner.trat)
      // 断言`trat`与`remaining`的一致性。
      assert(runner.trat == tratGlobal || remaining.head == tratGlobal)
      // 处理对`pulse`的支持。
      if (isPulseMode && !isInput(runner.trat)) {
        // 1.5, 04/10/2019, fix 了有关`Pulse`的一个 bug。
        // bug fix: 加了如下判断。
        if (!runner.trat.is4Reflow) pulse.evolve(subDepth, runner.trat, parent, runner.env.myCache(create = false), state.get$ == FAILED)
      }
      // 不在这里 wait 是为了避免`ABORT`或`FAILED`时的死循环。
      val maybeEnding = updateRunnerEndFlagAndJudgeIfEnding(runner)
      // 2.5, 30/12/2021, bug fix: 任务执行过快而引发的时序问题。原因如下：
      // 1. 以下逻辑常常会执行两次，对于特别轻量的执行时间极短的任务，如：`lite.ParN.merge`。但
      // 这是不对的：有对`prevOutFlow`和`outFlowTrimmed`的处理，它们是协同的，只能处理一次，否则就会紊乱。
      // 2. 时序问题：`joinOutFlow()`未经过线程同步，导致之后`verifyOutFlow()`验证异常。
      if (maybeEnding) snatcher.queAc { endStepByStep(tratGlobal) }
    }

    private def firstBeginStep: Option[Trait] = (traitIn :: reflow.basis.traits.toList) find { t =>
      val opt = runnersParallel.get(t.name$)
      // `forall`没有`nonEmpty`的判断，但由于多线程并行执行，存在偶尔是`isEmpty`的情况。
      opt.isDefined && opt.get.ensuring(_.nonEmpty).values.forall(!_._1.get)
    }

    private def firstEndStep: Option[Trait] = (traitIn :: reflow.basis.traits.toList) find { t =>
      val opt = runnersParallel.get(t.name$)
      opt.isDefined && opt.get.ensuring(_.nonEmpty).values.forall(_._2.get)
    }

    private def lastExistsEndStep: Option[Trait] = (traitIn :: reflow.basis.traits.toList).reverse.find { t =>
      val opt = runnersParallel.get(t.name$)
      opt.isDefined && opt.get.ensuring(_.nonEmpty).values.exists(_._2.get)
    }

    private def updateOutJoinFlag(curr: Trait) {
      // 当前逻辑其实用不上`visWait`。
      Snatcher.visWait { runnersParallel.get(tratTopOrIn(curr).name$) } { o => o.isDefined && o.get.exists(_._1.trat == curr) }(_ => s"[updateOutJoinFlag]trat:${curr.name$}")
        .get.find(_._1.trat == curr).get._2._1.ensuring(!_.get).set(true)
    }

    private def updateRunnerEndFlagAndJudgeIfEnding(runner: Runner): Boolean = {
      val top = tratTopOrIn(runner.trat)
      // 当前逻辑其实用不上`visWait`。
      val map = Snatcher.visWait { runnersParallel.get(top.name$) } { o => o.isDefined && o.get.isDefinedAt(runner) }(_ => s"[updateRunnerEndFlagAndJudgeIfEnding]trat:${runner.trat.name$}, global:${top.name$}, runner:$runner").get
      map(runner)._2.ensuring(!_.get).set(true)
      // 必然存在至少一次返回 true 的情况，因为：存在 n 个并行任务的最后 m 个同时到达本行，再执行最后一行而返回。
      map.ensuring(_.nonEmpty).values.forall(t => t._2.get /* || t._1.get*/ ) // 没必要
    }

    private def waitRunnersEndFlagCorrect(trat: Trait) {
      val top = tratTopOrIn(trat)
      Snatcher.visWait { runnersParallel.get(top.name$) } { o =>
        o.isDefined && o.get.ensuring(_.nonEmpty).values.forall(t => t._1.get && t._2.get)
      }(_ => s"[waitRunnersEndFlagCorrect] wait for Out join flag | global:${top.name$}.")
    }

    private def endStepByStep(top: Trait) {
      val stepTratOp = firstEndStep
      val state$     = state.get$
      // 如果不加`top`的判断，也会引发时序错误。具体表现为：
      // 如果上一步是并行任务，则会触发多次将本方法`snatcher.queAc()`，如果此时下一步的 MERGE 也`endRunner()`并走了进度`onProgress()`，那么
      // 时序错误大概率会触发。本方法的下一次执行会让`progress.clear()`发生在 snatcher 处理进度之前，进而引发混乱（断言错误）。
      // 详细分析：假如有 2 个并行后跟 MERGE，2 个并行把本方法的`snatcher.queAc()`执行了 2 次（条件较宽松，即使收紧也还是存在）。
      // 第 1 次正常处理并行的 end，并`tryScheduleNext()`把 MERGE 送入线程池，由于 MERGE 任务本就几乎什么也没做，执行太快，等本方法返回时，MERGE
      // 已经`endRunner()`了顺便`snatcher.queAc()`第 3 次。
      // 第 2 次执行本方法时，拿到的`firstEndStep`就是那个 MERGE，提前执行了`progress.clear()`（重申，前两次`snatcher.queAc()`发生在把 MERGE 送入
      // 线程池之前，即在 snatcher 队列中排在 MERGE 的任何事件之前，包括`onProgress()`）。
      // 因此，第 3 次执行时，已经拿不到`firstEndStep`了（但 MERGE 本应该在第 3 次时执行才时序正确）。
      if (stepTratOp.isDefined && stepTratOp.get == top && state$ != ABORTED && state$ != FAILED && state$ != COMPLETED && state$ != UPDATED) {
        val (tratGlobal, veryBeginning) = (stepTratOp.get, isInput(stepTratOp.get))
        waitRunnersEndFlagCorrect(tratGlobal)
        runnersParallel.remove(tratGlobal.name$)
        if (veryBeginning) { // nothing ...
        } else {
          lazy val cache = getCache
          if (isReinforcing) {                          // 如果当前任务`是`申请了`reinforce`的且处于执行阶段，则应该把输出进行合并。
            if (isOnReinforceBegins(tratGlobal, cache)) // 本条件判断必须在上一个的后面
              if (tratGlobal.isPar) {
                assert(cache.outs.nonNull)
                joinOutFlow(tratGlobal, cache.outs)
              } else {
                assert(cache.begins.size == 1)
                assert(cache.outs.isNull)
                // nothing ...
              }
          } else if (isReinforceRequired) { // 必须放在`else`分支，即必须在`!isReinforcing`的前提下。
            if (isOnReinforceBegins(tratGlobal, cache))
              if (tratGlobal.isPar) {
                val map = (new mutable.AnyRefMap[String, KvTpe[_ <: AnyRef]] /: cache.begins.keySet)(_ ++= reflow.basis.dependencies(_))
                // val keys = outFlowTrimmed._keys.keySet &~ map.keySet
                // val out = new Out(outFlowTrimmed._keys.filterKeys(keys.contains))
                val out = new Out(outFlowTrimmed._keys.filterNot(map contains _._1))
                out.fillWith(outFlowTrimmed)
                cache.outs = out
              } else {
                assert(cache.begins.size == 1)
                assert(cache.outs.isNull)
                // nothing ...
              }
          }
        }
        val transGlobal = if (veryBeginning) Option(transIn) else reflow.basis.transGlobal.get(tratGlobal.name$)
        val currIsLast  = if (veryBeginning) remaining.isEmpty.ensuring(!_) else remaining.tail.isEmpty
        // 切换任务结果集
        outFlowNextStage(
          tratGlobal,
          if (currIsLast) null else if (veryBeginning) remaining.head else remaining.tail.head,
          transGlobal,
          (_, afterGlobalTrans) => { // 处理完成事件
            if (currIsLast) {        // 当前是最后一个
              if (isReinforcing) {
                val prev    = state.get
                val success = state.forward(UPDATED)
                // 会被混淆优化掉
                Monitor.assertStateOverride(prev, UPDATED, success)
                // 放在`interruptSync()`的前面，虽然不能保证有`Poster`的事件到达会在`sync()`返回结果的前面，但没有`Poster`的还是可以的，这样便于测试。
                /*snatcher.queAc {*/
                reporter.reportOnUpdate(afterGlobalTrans) /*}*/
                interruptSync(true)
              } else {
                //val prev    = state.get
                val success = state.forward(COMPLETED)
                // 会被混淆优化掉
                //Monitor.assertStateOverride(prev, COMPLETED, success)
                /*snatcher.queAc {*/
                // 存在并行反馈`FAILED`的情况，在预期内（详见本逻辑所在方法）。
                if (success) reporter.reportOnComplete(afterGlobalTrans) /*}*/
                interruptSync(!isReinforceRequired)
              }
            } else if (state.forward(PENDING)) { // 确保符合`PENDING`的定义
              // 没必要，如果接下来非常顺畅又开始执行了呢。
              //snatcher.queAc { reporter.reportOnPending() }
            }
          }
        )
        // 安排下一步任务
        progress.clear()
        if (!veryBeginning) remaining = remaining.tail
        if (remaining.nonEmpty) tryScheduleNext(remaining.head)
        else if (!isPulseMode && !isSubReflow && isReinforceRequired && state.forward(REINFORCE_PENDING)) {
          remaining = reflow.basis.traits
          start$()
        }
      }
    }

    private def tryScheduleNext(global: Trait): Unit = {
      var map = Map.empty[Runner, (AtomicBoolean, AtomicBoolean)]
      if (global.isPar) {
        lazy val cache = getCache
        val begin      = isReinforcing && isOnReinforceBegins(global, cache)
        global.asPar.traits().filter { t => // 过滤掉没有申请`reinforce`的
          if (begin) cache.begins.contains(t.name$) else true
        }.foreach { t =>
          val env = Env(t, this)
          map += ((new Runner(env), (new AtomicBoolean(false), new AtomicBoolean(false))))
          progress.put(t.name$, Progress(1, 0, Weight(0, 1, env.weightPar))) // 把并行的任务 put 进去，不然计算子进度会有问题。
        }
      } else {
        val env = Env(global, this)
        map += ((new Runner(env), (new AtomicBoolean(false), new AtomicBoolean(false))))
        progress.put(global.name$, Progress(1, 0, Weight(0, 1, env.weightPar)))
      }
      runnersParallel.update(global.name$, map)
      if (isPulseMode && !isInput(global)) map.keys.foreach { runner =>
        // 1.5, 04/10/2019, fix 了有关`Pulse`的一个 bug。
        // bug fix: 加了如下判断。
        if (runner.trat.is4Reflow) Worker.scheduleRunner(runner, bucket = false)
        else pulse.forward(subDepth, runner.trat, parent, () => Worker.scheduleRunner(runner, bucket = true))
      }
      else map.keys.foreach { Worker.scheduleRunner(_, bucket = false) }
      Worker.scheduleBuckets()
    }

    /** 切换到reinforce的开始位置。 */
    private def switch2ReinforceBegins(cache: ReinforceCache): Unit = breakable {
      while (true) {
        if (remaining.head.isPar) {
          if (remaining.head.asPar.traits().forall(t => !cache.begins.contains(t.name$))) {
            remaining = remaining.tail
          } else break
        } else {
          if (remaining.head.name$ != cache.begins.head._1 /*只有一个元素*/ ) {
            remaining = remaining.tail
          } else break
        }
      }
    }

    private def isOnReinforceBegins(trat: Trait = remaining.head, cache: ReinforceCache = getCache): Boolean = {
      assert(isReinforcing || isReinforceRequired, "调用时机有误。")
      cache.begins.nonEmpty && {
        if (trat.isPar) trat.asPar.traits().exists(t => cache.begins.contains(t.name$))
        else trat.name$ == cache.begins.head._1
      }
    }

    private def outFlowNextStage(trat: Trait, next: Trait /*`null`表示当前已是最后*/, transGlobal: Option[Set[Transformer[_ <: AnyRef, _ <: AnyRef]]], onTransGlobal: (Out, Out) => Unit): Unit = {
      verifyOutFlow(trat)
      // outFlowTrimmed 这里需要作一次变换：
      // 由于 outsFlowTrimmed 存储的是 globalTrans [前]的输出需求，
      // 而 prevOutFlow 需要存储 globalTrans [后]的结果。
      val transOut = transGlobal.fold {
        // 对于 Input 任务，如果没用 trans，则其输出与 basis.inputs 完全一致；
        // 而对于其它任务，本来在转换之前的就是 trimmed 了的，没用 trans，那就保留原样。
        if (debugMode && isInput(trat)) outFlowTrimmed._keys.values.toSet.ensuring(_.forall(reflow.basis.inputs.contains))
        outFlowTrimmed
      } { ts =>
        val tranSet = ts.mutable
        val map     = outFlowTrimmed._map.mutable
        val nulls   = outFlowTrimmed._nullValueKeys.mutable
        doTransform(tranSet, map, nulls)
        val flow = new Out(if (isInput(trat)) reflow.basis.inputs
        else {
          val reasoning = outFlowTrimmed._keys.values.toSet -- tranSet.map(_.in) ++ tranSet.map(_.out)
          if (next.isNull) reflow.basis.outs.ensuring(_.forall(reasoning.contains))
          else reasoning
        })
        flow.putWith(map, nulls, ignoreTpeDiff = false, fullVerify = true)
        flow
      }
      // 1.4, 08/04/2019, fix 全局转换时的一个偶现的 bug。
      // bug fix: 将本行移到下面了。
      // onTransGlobal(outFlowTrimmed, transOut)
      // 1.4, 08/04/2019, bug 的复现方法，恢复上一行的同时，再启用下一行：
      // Thread.sleep(100)
      val trimmed = outFlowTrimmed
      prevOutFlow = transOut
      if (next.nonNull) {
        outFlowTrimmed = new Out(reflow.basis.outsFlowTrimmed(next.name$))
        outFlowTrimmed.fillWith(prevOutFlow, fullVerify = false)
      } else {
        // 全部执行完毕
        outFlowTrimmed = transOut
        // nothing ...
      }
      // 1.4, 08/04/2019, fix 全局转换时的一个偶现的 bug。
      onTransGlobal(trimmed, transOut)
    }

    override private[reflow] def getPrevOutFlow = prevOutFlow

    // 2.5, 30/12/2021, bug fix: 任务执行过快而引发的时序问题。
    private def joinOutFlow(curr: Trait, flow: Out): Unit = {
      if (flow ne outFlowTrimmed) outFlowTrimmed.putWith(flow._map, flow._nullValueKeys, ignoreTpeDiff = false /*合并到整体输出流，这里需要类型绝对匹配。*/, fullVerify = false /*本方法的目的是一部分一部分的填入输出，在并行的任务没有全部执行完毕的情况下，通常是处于还没有填满的状态，所以不应该进行满载验证。*/ )
      updateOutJoinFlag(curr)
    }

    // 2.5, 30/12/2021, bug fix: 任务执行过快而引发的时序问题。
    private def verifyOutFlow(curr: Trait): Unit = if (debugMode) { outFlowTrimmed.verify() }

    override private[reflow] def performAbort(trigger: Option[Trait], curr: Trait, parent: Option[ReflowTrait], depth: Int, forError: Boolean, e: Exception): Unit = {
      if (state.forward(if (forError) FAILED else ABORTED)) {
        // 如果能走到这里，那么总是先于`endRunner`之前执行，也就意味着`runnersParallel`不可能为`empty`。
        // 但是也有可能由外部`scheduler`触发，`runnersParallel`还是会`empty`。
        Monitor.abortion(trigger.fold[String](null)(_.name$), curr.name$, forError)
        runnersParallel.get(tratTopOrIn(curr).name$).foreach(_.foreach(_._1.abort()))
        snatcher.queAc {
          if (forError) reporter.reportOnFailed(trigger.get /*为`None`时不会走到这里*/, parent, depth, e)
          else reporter.reportOnAbort(trigger /*为`None`时说明是外部 scheduler 主动触发*/, parent, depth)
        }
      } else if (state.abort()) {
        // 已经到达 COMPLETED/REINFORCE 阶段了
      } else {
        // 如果本方法被多次被调用，则会进入本 case。虽然逻辑上并不存在本 case，但没有影响。
      }
      interruptSync(true /*既然是中断，应该让 reinforce 级别的 sync 请求也终止*/ )
    }

    @deprecated(message = "已在{Impl}中实现, 本方法不会被调用。", since = "0.0.1")
    override def sync(reinforce: Boolean = false): Out = ???

    @throws[InterruptedException]
    override def sync(reinforce: Boolean, milliseconds: Long): Option[Out] = {
      // 1.7, 29/09/2020, 小优化：`System.currentTimeMillis` -> `System.nanoTime`。
      val start = System.nanoTime
      // 2.2, 22/12/2020, 去掉了`Locker`，改为原生用法。
      syncLock.lock()
      try {
        breakable {
          // 不去判断`state`是因为任务流可能会失败
          while (!(if (reinforce) reinforceDone else normalDone)) {
            if (condition.isNull) condition = syncLock.newCondition()
            if (milliseconds == -1) {
              condition.await()
            } else {
              val delta = milliseconds - ((System.nanoTime - start) / 1e6).toLong
              if (delta <= 0 || !condition.await(delta, TimeUnit.MILLISECONDS)) {
                break
              } else Thread.`yield`()
            }
          }
        }
      } finally {
        syncLock.unlock()
      }
      val state = getState
      Option(if (reinforce) {
        if (state == UPDATED) outFlowTrimmed else null // 可能中断了，所以为`null`。
      } else {
        if (state == COMPLETED || state.group > COMPLETED.group) outFlowTrimmed else null // 可能中断了，所以为`null`。
      })
    }

    private def interruptSync(reinforce: Boolean) {
      Monitor.duration(this, timeStart, System.currentTimeMillis, state.get, state.get$, isSubReflow)
      if (!normalDone || (reinforce && !reinforceDone)) { // 不加这行判断也没问题
        normalDone = true
        if (reinforce) reinforceDone = true
        syncLock.lock()
        try {
          if (condition.nonNull) condition.signalAll()
        } finally {
          syncLock.unlock()
        }
      }
    }

    override def abort(): Unit = {
      val rem = remaining
      if (rem.nonEmpty) performAbort(outer.map(_.trat), rem.head, parent, subDepth - 1, forError = false, null)
    }

    override def getState = state.get

    @deprecated(message = "不要调用。", since = "0.0.1")
    override def isDone = ???

    /**
      * 每个Task都执行。
      */
    override private[reflow] def onTaskStart(trat: Trait): Unit = {
      if (isReinforcing) state.forward(REINFORCING)
      else if (!isInput(trat)) {
        // 必须放在外面：
        // 1. 以防止并行的任务发生 reportOnProgress 在 reportOnStart 之前的错误；
        // 2. 对于同步阻塞开销更小。
        snatcher.queAc {
          if (state.forward(EXECUTING)) {
            // 但反馈有且只有一次（上面 forward 方法只会成功一次）
            if (reflow.basis.stepOf(trat) == 0) reporter.reportOnStart()
            else { // progress 会在任务开始、进行中及结束时 report，这里 do nothing。
            }
          }
        }
      }
    }

    override private[reflow] def onTaskProgress(trat: Trait, sub: Progress, out: Out, depth: Int, publish: Boolean): Unit = {
      if (!isInput(trat)) {
        // 跟上面`onTaskStart()`保持一致（包在外面），否则会出现顺序问题。
        snatcher.queAc(canAbandon = true) {
          subProgress(trat, sub) // 在 abandon 之前必须要做的
        } { subs =>
          // 即使对于 REINFORCING, Task 还是会进行反馈，但是这里需要过滤掉。
          if (publish && state.get == EXECUTING) {
            val top  = tratTopOrIn(trat)
            val step = reflow.basis.stepOf(top)
            reporter.reportOnProgress(Progress(sum, step, resolveWeight(step), Some(top), if (sub.trigger.isNull) sub.copy(trat = Some(trat)) else sub.trigger, Option(subs)), out, depth)
          }
        }
      }
    }

    private def resolveWeight(step: Int): Weight = {
      val weightSum = weightes.sum
      Weight(
        serial = (sum * weightes.take(step).sum * 10000f / weightSum).round / 10000f,
        rate = (sum * weightes(step) * 10000f / weightSum).round / 10000f,
        par = weightSum // 仅在`subReflow`向外反馈时起作用，如果是顶层，则会闲置。
      )
    }

    private def subProgress(trat: Trait, sub: Progress): Seq[Progress] = {
      val top = tratTopOrIn(trat)
      Snatcher.visWait(progress.get(trat.name$))(_.isDefined)(_ => (if (trat == top) "top" else "par") + s" progress of ${trat.name$}")
      progress.+=((trat.name$, sub)).values.toSeq.ensuring { sub(); true } // `sub()`触发释放`sub.subs`。
    }

    override private[reflow] def onTaskComplete(trat: Trait, out: Out, flow: Out): Unit = {
      joinOutFlow(trat, flow)
      Monitor.complete(stepOfWithIn(trat), out, flow, outFlowTrimmed)
    }
  }

  private[reflow] class Runner private (val env: Env, trat: Trait) extends Worker.Runner(trat, null) with Equals with TAG.ClassName {
    def this(env: Env) = this(env, env.trat)

    implicit lazy val logTag: LogTag = new LogTag(className + "/…" + trat.name$.takeRight(8))

    // 24/12/2020, fix bug 时重构了，但不影响下面 JSR-133 用法。
    private lazy val aborted  = new AtomicBoolean(false)
    private lazy val workDone = new AtomicBoolean(false)
    //private lazy val runnerDone = new AtomicBoolean(false)
    @volatile private var task: Task = _
    //private var timeBegin: Long      = _

    override final def equals(any: scala.Any) = super.equals(any)
    override final def canEqual(that: Any)    = super.equals(that)
    override final def hashCode()             = super.hashCode()

    // 该用法遵循 JSR-133。
    def abort(): Unit = if (/*!aborted*/ aborted.compareAndSet(false, true)) {
      //aborted = true
      if (task.nonNull) task.abort()
    }

    override def run(): Unit = {
      var working = false
      try {
        task = trat.newTask()
        // 判断放在`task`的创建后面, 配合`abort()`中的顺序。
        if (aborted.get) {
          onAbort()
          onWorkEnd()
        } else {
          onStart()
          working = true
          if (task.exec(env, this)) {
            working = false
            onWorkDone()
          } else if (aborted.get) {
            working = false
            onAbort()
            onWorkEnd()
          } else {
            // `SubReflowTask`的异步
          }
        }
      } catch {
        case e: Exception =>
          if (working) {
            e match {
              case _: AbortException => // 框架抛出的, 表示成功中断。
                onAbort(Some(trat))
              case e: FailedException =>
                onFailed(e = e.getCause.as[Exception])
              case e: CodeException => // 客户代码问题
                onFailed(e = e)
              case _ => ??? // 没有更多
            }
            // 这里能够`catch`住的异常，必然是同步执行的。
            onWorkEnd()
          } else {
            innerError(e)
          }
        case NonFatal(t) =>
          if (working) {
            throw t // `AssertionError` 之类的，必须崩溃中断，不应处理。
          } else {
            innerError(t)
          }
      } finally {
        //runnerDone.set(true)
        //endMe()
      }
    }

    private def transOutput(): Out = {
      if (env.tracker.isInput(trat)) env.out // 不在这里作转换的理由：无论如何，到 tracker 里去了之后还要进行一遍 trim 合并，那就在这里节省一遍吧。
      else {
        val flow = new Out(env.tracker.reflow.basis.dependencies(trat.name$))
        env.tracker.reflow.basis.transformers.get(trat.name$).fold {
          flow.putWith(env.out._map, env.out._nullValueKeys, ignoreTpeDiff = false, fullVerify = true)
          flow
        } { t =>
          val map   = env.out._map.mutable
          val nulls = env.out._nullValueKeys.mutable
          doTransform(t.mutable, map, nulls, flow._keys)
          flow.putWith(map, nulls, ignoreTpeDiff = false, fullVerify = true)
          flow
        }
      }
    }

    private def afterWork(flow: Out) {
      onComplete(env.out, flow)
      if (aborted.get) onAbort()
    }

    /** 仅在任务`成功`执行之后才可以调用本方法。 */
    def onWorkDone(): Unit = onWorkEnd(afterWork(transOutput()))

    /** 在任务执行`失败`后应该调用本方法。 */
    def onWorkEnd(doSth: => Unit) {
      if (workDone.compareAndSet(false, true)) {
        doSth
        endMe()
      }
    }

    private def endMe() {
      if (workDone.get /*&& runnerDone.compareAndSet(true, false)*/ )
        env.tracker.endRunner(this)
    }

    def onStart() {
      env.tracker.onTaskStart(trat)
      //timeBegin = System.currentTimeMillis
    }

    private def onComplete(out: Out, flow: Out) {
      //Monitor.duration(trat.name$, timeBegin, System.currentTimeMillis, trat.period$)
      env.tracker.onTaskComplete(trat, out, flow)
    }

    def onAbort(trigger: Option[Trait] = Some(trat) /*与`parent`保持一致*/, parent: Option[ReflowTrait] = env.parent, depth: Int = env.depth) {
      withAbort(trigger, parent, depth, null)
    }

    def onFailed(trat: Trait = trat, parent: Option[ReflowTrait] = env.parent, depth: Int = env.depth, e: Exception) {
      withAbort(Some(trat), parent, depth, e)
    }

    private def withAbort(trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int, e: Exception) {
      aborted.compareAndSet(false, true)
      env.tracker.performAbort(trigger, trat, parent, depth, forError = e.nonNull, e)
    }

    private def innerError(t: Throwable) {
      env.tracker.innerError(this, t)
      throw new InnerError(t)
    }
  }

  private[reflow] class SubReflowTask extends Task {
    @volatile private var scheduler: Scheduler.Impl = _

    override private[reflow] def exec$(env: Env, runner: Runner): Boolean = {
      progress(0, 10, publish = autoProgress)
      scheduler = env.trat.asSub.reflow.start$(
        In.from(env.input),
        new SubReflowFeedback(env, runner, progress(10, 10, publish = autoProgress)),
        env.tracker.strategy.toSub,
        null,
        env,
        env.tracker.pulse,
        env.serialNum,
        env.globalTrack
      )
      false // 异步。
    }
    override protected def autoProgress: Boolean = false
    override protected def doWork(): Unit = {}

    override protected def onAbort(): Unit = {
      if (scheduler.nonNull) scheduler.abort()
      super.onAbort()
    }
  }

  private[reflow] class SubReflowFeedback(env: Env, runner: Runner, doSth: => Unit) extends Feedback {
    override def onPending(): Unit = {}

    override def onStart(): Unit = {
      // Maybe call repeat, but no side effect.
      runner.onStart()
    }

    override def onProgress(progress: Progress, out: Out, depth: Int): Unit = {
      // if (out ne env.out) env.out.fillWith(out, fullVerify = false) // 暂不要，以提高效率。
      env.tracker.onTaskProgress(env.trat, progress, out, depth, publish = true)
    }

    override def onComplete(out: Out): Unit = {
      if (out ne env.out) env.out.fillWith(out)
      doSth
      runner.onWorkDone()
    }

    override def onUpdate(out: Out): Unit = onComplete(out)

    override def onAbort(trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit = {
      runner.onAbort(trigger, parent, depth)
      runner.onWorkEnd()
    }

    override def onFailed(trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = {
      runner.onFailed(trat, parent, depth, e)
      runner.onWorkEnd()
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////
  //************************************ Reporter ************************************//

  /**
    * 该结构的目标是保证进度反馈的递增性。同时保留关键点，丢弃密集冗余。
    * 注意：事件到达本类，已经是单线程操作了。
    */
  private[reflow] class Reporter(feedback: Feedback)(implicit tag: LogTag) {
    private[reflow] def reportOnPending(): Unit                                                                  = eatExceptions(feedback.onPending())
    private[reflow] def reportOnStart(): Unit                                                                    = eatExceptions(feedback.onStart())
    private[reflow] def reportOnProgress(progress: Progress, out: Out, depth: Int): Unit                         = eatExceptions(feedback.onProgress(progress, out, depth))
    private[reflow] def reportOnComplete(out: Out): Unit                                                         = eatExceptions(feedback.onComplete(out))
    private[reflow] def reportOnUpdate(out: Out): Unit                                                           = eatExceptions(feedback.onUpdate(out))
    private[reflow] def reportOnAbort(trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit     = eatExceptions(feedback.onAbort(trigger, parent, depth))
    private[reflow] def reportOnFailed(trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = eatExceptions(feedback.onFailed(trat, parent, depth, e))
  }

  private class Reporter4Debug(reflow: Reflow, feedback: Feedback, sum: Int)(implicit tag: LogTag) extends Reporter(feedback) {
    @volatile private var step: Int  = _
    @volatile private var sub: Float = _
    @volatile private var beenReset  = true

    override private[reflow] def reportOnStart(): Unit = {
      log.i("[reportOnStart]beenReset:%b.", beenReset)
      assert(beenReset)
      step = -1
      sub = 0
      super.reportOnStart()
    }

    override private[reflow] def reportOnProgress(progress: Progress, out: Out, depth: Int): Unit = {
      log.i("[reportOnProgress]progress:%s, out:%s, depth:%s.", progress, out, depth)
      // 改了逻辑，不总是反馈`0%`和`100%`的状态，中间也不都反馈，见`Task.autoProgress`。
      //assert(progress.sub >= sub, s"调用没有同步？`${progress.trat.get.name$}`。")
      if (beenReset && progress.sub >= 0) {
        assert(progress.step >= step + 1)
        step = progress.step
        beenReset = false
      } //else assert(progress.step == step, s"beenReset:$beenReset, progress.sub:${progress.sub}, progress.step:${progress.step}, step:$step.")
      if (progress.sub > sub) {
        sub = progress.sub
        // 一定会有1的, Task#exec()里有progress(1), 会使单/并行任务到达1.
        if (progress.sub == 1) {
          sub = 0
          beenReset = true
        }
      }
      super.reportOnProgress(progress, out, depth)
    }

    override private[reflow] def reportOnComplete(out: Out): Unit = {
      log.i("[reportOnComplete]step:%d, sub:%f, sum:%d, beenReset:%b, out:%s.", step, sub, sum, beenReset, out)
      // 改了逻辑，不总是反馈`0%`和`100%`的状态，中间也不都反馈，见`Task.autoProgress`。
      //assert(beenReset && step == sum - 1 && sub == 0)
      super.reportOnComplete(out)
    }

    override private[reflow] def reportOnUpdate(out: Out): Unit = {
      log.i("[reportOnUpdate]out:%s.", out)
      super.reportOnUpdate(out)
    }
  }
}
