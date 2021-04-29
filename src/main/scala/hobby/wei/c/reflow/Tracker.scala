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
import hobby.wei.c.reflow.Feedback.Progress.Weight
import hobby.wei.c.reflow.Feedback.Progress.Strategy
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.reflow.State._
import hobby.wei.c.reflow.Tracker.Runner
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool.Snatcher
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.{Condition, ReentrantLock}
import scala.collection.{mutable, _}
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
  *          2.4, 30/04/2021, 增加`Progress.Weight`，优化`autoProgress = false`时子进度的更新问题。
  * @param strategy 当前`Reflow`启动时传入的`Strategy`。由于通常要考虑到父级`Reflow`的`Strategy`，因此通常使用`strategyRevised`以取代本参数；
  * @param pulse    流处理模式下的交互接口。可能为`null`，表示非流处理模式。
  */
private[reflow] abstract class Tracker(val reflow: Reflow, val strategy: Strategy, val outer: Option[Env], val pulse: Pulse.Interact) extends TAG.ClassName {
  require(strategy.nonNull)
  private lazy final val snatcher4Init = new Snatcher
  // 这两个变量，在浏览运行阶段会根据需要自行创建（任务可能需要缓存临时参数到cache中）；
  // 而在`Reinforce`阶段，会从外部传入。
  // 因此有这样的设计。
  // 1.3, 23/03/2019, 将`false`改为了`true`。
  @volatile private var cacheInited: Boolean = outer.fold(true)(_.isReinforcing)
  // 1.3, 23/03/2019, 最外层的`Tracker`在各个阶段（`Reinforce`）都是同一个实例，因此本变量也是同一个实例。
  private final lazy val reinforceCache = outer.fold(new ReinforceCache) { env =>
    if (isPulseMode) assert(!env.isReinforcing)
    // 1.3, 23/03/2019, 去掉`env.obtainCache.get`后面的`OrElse(new ReinforceCache)`。
    if (env.isReinforcing) env.obtainCache.get else new ReinforceCache
  }

  @deprecated(message = "不要直接调用本属性，特别是对于`SubReflow`，根本不需要使用它，否则会导致状态错误。", since = "0.0.1")
  private final lazy val reinforceRequired = new AtomicBoolean(false)

  final lazy val subDepth: Int = outer.fold(0)(_.subDepth + 1)

  private final def getOrInitFromOuterCache(trat: String = null, sub: Option[ReinforceCache] = None): ReinforceCache = {
    // 1.3, 23/03/2019, 本操作和下面的`cache init`是不相干的两件事。本操作有可能执行多次，因为有可能是多个并行子任务。
    // `Reinforce`阶段，`cacheInited`生来为`true`，不需要初始化，也就不会触发本方法递归，因此`sub`始终为`None`, 不会
    // 引起不必要的多余调用，结果是直接返回（不过`getCache`和上面`obtainCache`会触发循环递归，直到最外层的`Tracker`或`reinforceCache`已经初始化）；
    // 只是在非`Reinforce`阶段，会有一些重复不必要的调用，但没有更好的办法。
    sub.foreach(reinforceCache.subs.putIfAbsent(trat, _))
    if (!cacheInited) {
      snatcher4Init.tryOn({
        if (!cacheInited) {
          outer.foreach { env =>
            env.tracker.getOrInitFromOuterCache(env.trat.name$, Option(reinforceCache))
          }
          cacheInited = true
        }
      }, true)
    }
    reinforceCache
  }

  final def getCache = getOrInitFromOuterCache()

  private[reflow] def getPrevOutFlow: Out

  def getState: State.Tpe

  private[reflow] def isInput(trat: Trait): Boolean

  final def isPulseMode: Boolean = pulse.nonNull

  final def isSubReflow: Boolean = outer.isDefined

  final def isReinforceRequired: Boolean = outer.fold(reinforceRequired.get)(_.isReinforceRequired)

  final def isReinforcing: Boolean = outer.fold(getState.group == REINFORCING.group /*group代表了几个状态*/)(_.isReinforcing)

  final def requireReinforce(trat: Trait): Boolean = {
    assert(!trat.isPar)
    // 必须提前执行，以便递归。本实现支持并发，线程安全。
    val required = outer.fold(reinforceRequired.getAndSet(true))(_.requireReinforce())
    val cache = getCache
    if (cache.inputs.isNull) {
      cache.inputs = getPrevOutFlow.ensuring(_.nonNull)
      if (debugMode) log.w("[requireReinforce]**********************************************************cache.inputs:%s.", cache.inputs)
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
    * @param trigger 触发者。可能是从里层的任务传来的。
    * @param trat    当前正在执行的trat。
    * @param forError
    * @param e
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
  lazy val caches = new concurrent.TrieMap[String, Out]
  /** 子`Trait`的`Task`启动的`Reflow`对应的`Tracker`的`Cache`。 */
  lazy val subs = new concurrent.TrieMap[String, ReinforceCache]
  /** 作`Set`用。`reinforce`阶段开始时的`Trait.name$`。 */
  lazy val begins = new concurrent.TrieMap[String, Unit]
  @volatile var inputs: Out = _
  /** 对于一个并行任务的某些子任务的`reinforce`请求，我们不打算再次执行整个并行任务，因此
    * 需要保留`浏览`运行模式的`输入`（见`inputs`）和`结果`（本`outs`），以便在`reinforce`之后合并输出。
    * 本输出包含的`_keys`是当前`并行`任务中所有未申请`reinforce`的执行输出。 */
  @volatile var outs: Out = _

  override def toString = s"ReinforceCache:\n caches:$caches,\n subs:$subs,\n begins:$begins,\n inputs:$inputs,\n outs:$outs."
}

private[reflow] object Tracker {
  private[reflow] final class Impl(reflow: Reflow, traitIn: Trait, transIn: immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]],
                                   state: Scheduler.State$, feedback: Feedback, strategy: Strategy, outer: Option[Env], pulse: Pulse.Interact)
    extends Tracker(reflow: Reflow, strategy: Strategy, outer: Option[Env], pulse: Pulse.Interact) with Scheduler with TAG.ClassName {
    private lazy val snatcher = new Snatcher.ActionQueue(strategy.isFluentMode)
    private lazy val syncLock: ReentrantLock = new ReentrantLock
    @volatile private var condition: Condition = _

    private lazy val sum = reflow.basis.traits.length
    private lazy val runnersParallel = new concurrent.TrieMap[Runner, Any]
    private lazy val progress = new concurrent.TrieMap[String, Progress]
    private lazy val weightes = reflow.basis.weightedPeriods()
    private lazy val reporter = if (debugMode && !strategy.isFluentMode /*受`snatcher`的参数的牵连*/ ) new Reporter4Debug(reflow, feedback, sum) else new Reporter(feedback)
    @volatile private var remaining = reflow.basis.traits
    @volatile private var normalDone, reinforceDone: Boolean = false
    @volatile private var outFlowTrimmed, prevOutFlow: Out = _
    @volatile private var timeStart = 0L

    private[reflow] def start$(): Unit = {
      if (debugMode) log.w("[start]reinforcing:%s, subReflow:%s, subDepth:%s.=====>>>>>", isReinforcing, isSubReflow, subDepth)
      assert(remaining.nonEmpty, s"`start()`时候，不应该存在空任务列表。isReinforcing:$isReinforcing")
      // 如果当前是子Reflow, 则首先看是不是到了reinforce阶段。
      if (isReinforcing) {
        if (isSubReflow) {
          state.forward(COMPLETED)
          state.forward(REINFORCE_PENDING)
        } else assert(state.get == REINFORCE_PENDING)
        val cache = getCache
        assert(cache.begins.nonEmpty)
        assert(cache.inputs.nonNull, s"${cache.inputs} 应该缓存有输入参数。")
        // 切换到reinforce的开始位置
        switch2ReinforceBegins(cache)
        prevOutFlow = cache.inputs
        outFlowTrimmed = new Out(reflow.basis.outsFlowTrimmed(remaining.head.name$))
        outFlowTrimmed.fillWith(prevOutFlow, fullVerify = false)
        tryScheduleNext(remaining.head)
      } else {
        timeStart = System.currentTimeMillis
        assert(state.get == PENDING)
        snatcher.queAc(reporter.reportOnPending())
        // prevOutFlow = new Out() // 不用赋值
        outFlowTrimmed = new Out(traitIn.outs$)
        tryScheduleNext(traitIn)
      }
    }

    override private[reflow] def endRunner(runner: Runner): Unit = {
      if (debugMode) log.i("[endRunner]depth:%s, trait:%s, parent:%s.", subDepth, runner.trat.name$.s, outer.map(_.trat.name$.s).orNull)
      // 拿到父级`trait`（注意：如果当前是并行的任务，则`runner.trat`是子级）。
      val (tratGlobal, veryBeginning) = if (isInput(runner.trat)) (traitIn, true) else (remaining.head, false)
      // 断言`trat`与`remaining`的一致性。
      assert(runner.trat == tratGlobal || (tratGlobal.isPar && tratGlobal.asPar.traits().contains(runner.trat)))
      // 处理对`pulse`的支持。
      if (isPulseMode && !isInput(runner.trat)) {
        // 1.5, 04/10/2019, fix 了有关`Pulse`的一个 bug。
        // bug fix: 加了如下判断。
        if (!runner.trat.is4Reflow) pulse.evolve(subDepth, runner.trat, outer.map(_.trat.as[ReflowTrait]), runner.env.myCache(create = false), state.get$ == FAILED)
      }
      runnersParallel -= runner
      // 并行任务全部结束
      if (runnersParallel.isEmpty) snatcher.queAc {
        val state$ = state.get$
        // 判断放在里面，`snatcher`如果在执行完毕后发现了信号重来，可以再次执行本判断，避免重复。
        // 正常情况下，本函数提执行完毕，由于已经直接或间接执行了`tryScheduleNext()`，`runnersParallel`不可能再为`empty`，
        // 除非任务执行速度过快，下一轮已经完毕再到了这里。那么，本`tryOn{}`可以再次重复，这是正确的。
        if (runnersParallel.isEmpty &&
          // 如果已经中断，由于本方法体是`endRunner`，反正都执行完毕了，那么就什么也不用再干了，Game Over吧。
          // 至于`interruptSync()`，在`performAbort()`也已处理妥当。
          state$ != ABORTED && state$ != FAILED &&
          // 不过假如本`Reflow`执行完毕了，`runnersParallel`将一直是`empty`。下面的条件确保不会重复执行。
          state$ != COMPLETED && state$ != UPDATED) {
          if (veryBeginning) {
            // nothing ...
          } else {
            val cache = getCache
            if (isReinforcing) { // 如果当前任务`是`申请了`reinforce`的且处于执行阶段，则应该把输出进行合并。
              // 本条件判断必须在上一个的后面
              if (isOnReinforceBegins(tratGlobal, cache))
                if (tratGlobal.isPar) {
                  assert(cache.outs.nonNull)
                  joinOutFlow(cache.outs)
                } else {
                  assert(cache.begins.size == 1)
                  assert(cache.outs.isNull)
                  // nothing ...
                }
            } else if (isReinforceRequired /*必须放在`else`分支，即必须在`!isReinforcing`的前提下。*/ ) {
              if (isOnReinforceBegins(tratGlobal, cache))
                if (tratGlobal.isPar) {
                  val map = (new mutable.AnyRefMap[String, KvTpe[_ <: AnyRef]] /: cache.begins.keySet) (_ ++= reflow.basis.dependencies(_))
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
          val currIsLast = if (veryBeginning) remaining.isEmpty.ensuring(!_) else remaining.tail.isEmpty
          // 切换任务结果集
          outFlowNextStage(tratGlobal, if (currIsLast) null else if (veryBeginning) remaining.head else remaining.tail.head,
            transGlobal, (_, afterGlobalTrans) => {
              // 处理完成事件
              if (currIsLast) { // 当前是最后一个
                if (isReinforcing) {
                  val prev = state.get
                  val success = state.forward(UPDATED)
                  // 会被混淆优化掉
                  Monitor.assertStateOverride(prev, UPDATED, success)
                  // 放在interruptSync()的前面，虽然不能保证有Poster的事件到达会在sync()返回结果的前面，但没有Poster的还是可以的，这样便于测试。
                  // 这个比较特殊：因为本执行体已经在queueAction()里面了。
                  /*snatcher.queueAction {*/ reporter.reportOnUpdate(afterGlobalTrans) /*}*/
                  interruptSync(true)
                } else {
                  val prev = state.get
                  val success = state.forward(COMPLETED)
                  // 会被混淆优化掉
                  Monitor.assertStateOverride(prev, COMPLETED, success)
                  // 这个比较特殊：因为本执行体已经在queueAction()里面了。
                  /*snatcher.queueAction {*/ reporter.reportOnComplete(afterGlobalTrans) /*}*/
                  interruptSync(!isReinforceRequired)
                }
              } else if (state.forward(PENDING)) { // 确保符合`PENDING`的定义
                // 没必要，如果接下来非常顺畅又开始执行了呢。
                // snatcher.queueAction(reporter.reportOnPending())
              }
            })
          progress.clear()
          if (!veryBeginning) remaining = remaining.tail
          if (remaining.nonEmpty) {
            tryScheduleNext(remaining.head)
          } else if (!isPulseMode && !isSubReflow && isReinforceRequired && state.forward(REINFORCE_PENDING)) {
            remaining = reflow.basis.traits
            start$()
          }
        }
      }
    }

    // 必须在进度反馈完毕之后再下一个，否则可能由于线程优先级问题，导致低优先级的进度没有反馈完，而新进入的高优先级任务又要争用同步锁，造成死锁的问题。
    private def tryScheduleNext(trat: Trait): Unit = {
      assert(runnersParallel.isEmpty)
      if (trat.isPar) {
        val cache = getCache
        val begin = isReinforcing && isOnReinforceBegins(trat, cache)
        trat.asPar.traits().filter { t => // 过滤掉没有申请reinforce的
          if (begin) cache.begins.contains(t.name$) else true
        }.foreach { t =>
          val env = Env(t, this)
          runnersParallel += ((new Runner(env), None))
          progress.put(t.name$, Progress(1, 0, Weight(0, 1, env.weightPar))) // 把并行的任务put进去，不然计算子进度会有问题。
        }
      } else {
        //progress.put(trat.name$, 0f)
        runnersParallel += ((new Runner(Env(trat, this)), None))
      }
      if (isPulseMode && !isInput(trat)) runnersParallel.keys.foreach { runner: Runner =>
        // 1.5, 04/10/2019, fix 了有关`Pulse`的一个 bug。
        // bug fix: 加了如下判断。
        if (runner.trat.is4Reflow) Worker.scheduleRunner(runner, bucket = false)
        else pulse.forward(subDepth, runner.trat, outer.map(_.trat.as[ReflowTrait]), () => Worker.scheduleRunner(runner, bucket = true))
      } else runnersParallel.keys.foreach {
        Worker.scheduleRunner(_, bucket = false)
      }
      Worker.scheduleBuckets()
    }

    override private[reflow] def innerError(runner: Runner, t: Throwable): Unit = {
      if (debugMode) log.e("[innerError]trait:%s.", runner.trat.name$.s)
      // 正常情况下是不会走的，仅用于测试。
      //performAbort(Some(runner.trat), runner.trat, outer.map(_.trat.as[ReflowTrait]), subDepth, forError = true, e)
    }

    override private[reflow] def getPrevOutFlow = prevOutFlow

    override private[reflow] def isInput(trat: Trait) = trat == traitIn

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

    private def outFlowNextStage(trat: Trait, next: Trait /*`null`表示当前已是最后*/ ,
                                 transGlobal: Option[Set[Transformer[_ <: AnyRef, _ <: AnyRef]]], onTransGlobal: (Out, Out) => Unit): Unit = {
      verifyOutFlow()
      // outFlowTrimmed这里需要作一次变换：
      // 由于outsFlowTrimmed存储的是globalTrans`前`的输出需求，
      // 而prevOutFlow需要存储globalTrans`后`的结果。
      val transOut = transGlobal.fold {
        // 对于Input任务，如果没用trans，则其输出与basis.inputs完全一致；
        // 而对于其它任务，本来在转换之前的就是trimmed了的，没用trans，那就保留原样。
        if (debugMode && isInput(trat)) assert(outFlowTrimmed._keys.values.toSet == reflow.basis.inputs)
        outFlowTrimmed
      } { ts =>
        val tranSet = ts.mutable
        val map = outFlowTrimmed._map.mutable
        val nulls = outFlowTrimmed._nullValueKeys.mutable
        doTransform(tranSet, map, nulls)
        val flow = new Out(if (isInput(trat)) reflow.basis.inputs else {
          val reasoning = outFlowTrimmed._keys.values.toSet -- tranSet.map(_.in) ++ tranSet.map(_.out)
          if (next.isNull) reflow.basis.outs.ensuring(_.forall(reasoning.contains))
          else reasoning
        })
        flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
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

    private def joinOutFlow(flow: Out): Unit = if (flow /*input任务可能是一样的*/ ne outFlowTrimmed) outFlowTrimmed.putWith(flow._map, flow._nullValueKeys,
      ignoreDiffType = false /*合并到整体输出流，这里类型都是绝对匹配的。*/ ,
      fullVerify = false /*本方法的目的是一部分一部分的填入输出，在并行的任务没有全部执行完毕的情况下，通常是处于还没有填满的状态，所以不应该进行满载验证。*/)

    private def verifyOutFlow(): Unit = if (debugMode) outFlowTrimmed.verify()

    override private[reflow] def performAbort(trigger: Option[Trait], curr: Trait, parent: Option[ReflowTrait], depth: Int, forError: Boolean, e: Exception): Unit = {
      if (state.forward(if (forError) FAILED else ABORTED)) {
        // 如果能走到这里，那么总是先于endRunner之前执行，也就意味着runnersParallel不可能为empty。
        // 但是也有可能由外部scheduler触发，runnersParallel还是会empty。
        Monitor.abortion(trigger.fold[String](null)(_.name$), curr.name$, forError)
        runnersParallel.foreach(_._1.abort())
        snatcher.queAc {
          if (forError) reporter.reportOnFailed(trigger.get /*为`None`时不会走到这里*/, parent, depth, e)
          else reporter.reportOnAbort(trigger /*为`None`时说明是外部scheduler主动触发*/, parent, depth)
        }
      } else if (state.abort()) {
        // 已经到达COMPLETED/REINFORCE阶段了
      } else {
        // 如果本方法被多次被调用，则会进入本case. 虽然逻辑上并不存在本case, 但没有影响。
      }
      interruptSync(true /*既然是中断，应该让reinforce级别的sync请求也终止*/)
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
            if (debugMode) log.i("[sync]++++++++++>>>")
            if (milliseconds == -1) {
              condition.await()
              if (debugMode) log.i("[sync]++++++++++done, 0.")
            } else {
              val delta = milliseconds - ((System.nanoTime - start) / 1e6).toLong
              if (delta <= 0 || !condition.await(delta, TimeUnit.MILLISECONDS)) {
                if (debugMode) log.i("[sync]++++++++++done, 1.")
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
        if (debugMode) log.i("[interruptSync]----------reinforce:%s.", reinforce)
        normalDone = true
        if (reinforce) reinforceDone = true
        syncLock.lock()
        try {
          if (condition.nonNull) {
            condition.signalAll()
            if (debugMode) log.i("[interruptSync]----------signalAll.")
          }
        } finally {
          syncLock.unlock()
        }
      }
    }

    override def abort(): Unit = {
      val rem = remaining
      if (rem.nonEmpty) performAbort(outer.map(_.trat), rem.head, outer.map(_.trat.as[ReflowTrait]), subDepth - 1, forError = false, null)
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
        // 1. 以防止并行的任务发生reportOnProgress在reportOnStart之前的错误；
        // 2. 对于同步阻塞开销更小。
        snatcher.queAc {
          if (state.forward(EXECUTING)) {
            // 但反馈有且只有一次（上面forward方法只会成功一次）
            if (reflow.basis.stepOf(trat) == 0) reporter.reportOnStart()
            else { // progress会在任务开始、进行中及结束时report，这里do nothing。
            }
          }
        }
      }
    }

    override private[reflow] def onTaskProgress(trat: Trait, sub: Progress, out: Out, depth: Int, publish: Boolean): Unit = {
      if (!isInput(trat)) {
        // 跟上面onTaskStart()保持一致（包在外面），否则会出现顺序问题。
        snatcher.queAc(canAbandon = true) {
          subProgress(trat, sub) // 在abandon之前必须要做的
        } { subs =>
          // 即使对于REINFORCING, Task还是会进行反馈，但是这里需要过滤掉。
          if (publish && state.get == EXECUTING) {
            val top = remaining.head
            val step = reflow.basis.stepOf(top)
            // 1.6, 12/07/2020, fix bug: Progress(..trat)。
            // 2.0, 13/10/2020, fallback `Progress(..trat)` -> `Progress(..top)`, and add `Progress(..trigger)`.
            reporter.reportOnProgress(Progress(sum, step, resolveWeight(step), Option(top), if (sub.trigger.isNull) sub.copy(trat = Option(trat)) else sub.trigger, Option(subs)), out, depth)
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

    // 注意：本方法为单线程操作。
    private def subProgress(trat: Trait, sub: Progress): Seq[Progress] = {
      if (progress.size <= 1) sub :: Nil
      else {
        assert(progress.contains(trat.name$))
        progress.update(trat.name$, sub)
        progress.values.toSeq
      }
    }

    override private[reflow] def onTaskComplete(trat: Trait, out: Out, flow: Out): Unit = {
      joinOutFlow(flow)
      Monitor.complete(if (isInput(trat)) -1 else reflow.basis.stepOf(trat), out, flow, outFlowTrimmed)
    }
  }

  private[reflow] class Runner private(val env: Env, trat: Trait) extends Worker.Runner(trat, null) with Equals with TAG.ClassName {
    def this(env: Env) = this(env, env.trat)

    implicit lazy val logTag: LogTag = new LogTag(className + "/…" + trat.name$.takeRight(8))

    private lazy val workDone = new AtomicBoolean(false)
    private lazy val runnerDone = new AtomicBoolean(false)
    // 24/12/2020, fix bug 时重构了，但不影响下面 JSR-133 用法。
    private lazy val aborted = new AtomicBoolean(false)
    @volatile private var task: Task = _
    private var timeBegin: Long = _

    override final def equals(any: scala.Any) = super.equals(any)
    override final def canEqual(that: Any) = super.equals(that)
    override final def hashCode() = super.hashCode()

    // 该用法遵循 JSR-133。
    def abort(): Unit = if ( /*!aborted*/ aborted.compareAndSet(false, true)) {
      //aborted = true
      if (task.nonNull) task.abort()
    }

    override def run(): Unit = {
      if (debugMode) log.i("[run]----->>>>>")
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
              case _ =>
                onFailed(e = new CodeException(e))
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
        runnerDone.set(true)
        endMe()
      }
    }

    private def transOutput(): Out = {
      if (debugMode) log.i("[transOutput]")
      if (env.tracker.isInput(trat)) env.out // 不在这里作转换的理由：无论如何，到tracker里去了之后还要进行一遍trim合并，那就在这里节省一遍吧。
      else {
        val flow = new Out(env.tracker.reflow.basis.dependencies(trat.name$))
        env.tracker.reflow.basis.transformers.get(trat.name$).fold {
          flow.putWith(env.out._map, env.out._nullValueKeys, ignoreDiffType = false, fullVerify = true)
          flow
        } { t =>
          val map = env.out._map.mutable
          val nulls = env.out._nullValueKeys.mutable
          doTransform(t.mutable, map, nulls, flow._keys)
          flow.putWith(map, nulls, ignoreDiffType = false, fullVerify = true)
          flow
        }
      }
    }

    private def afterWork(flow: Out) {
      if (debugMode) log.i("[afterWork]")
      onComplete(env.out, flow)
      if (aborted.get) onAbort()
    }

    /** 仅在任务`成功`执行之后才可以调用本方法。 */
    def onWorkDone(): Unit = onWorkEnd(afterWork(transOutput()))

    /** 在任务执行`失败`后应该调用本方法。 */
    def onWorkEnd(doSth: => Unit) {
      if (debugMode) log.i("[onWorkEnd]workDone:%s, runner:%s.", workDone.get, this)
      if (workDone.compareAndSet(false, true)) {
        doSth
        endMe()
      }
    }

    private def endMe(): Unit = {
      if (debugMode) log.i("[endMe]workDone:%s, runnerDone:%s, runner:%s.", workDone.get, runnerDone.get, this)
      if (workDone.get && runnerDone.compareAndSet(true, false)) env.tracker.endRunner(this)
    }

    def onStart() {
      if (debugMode) log.i("[onStart]")
      env.tracker.onTaskStart(trat)
      timeBegin = System.currentTimeMillis
    }

    private def onComplete(out: Out, flow: Out) {
      if (debugMode) log.i("[onComplete]")
      Monitor.duration(trat.name$, timeBegin, System.currentTimeMillis, trat.period$)
      env.tracker.onTaskComplete(trat, out, flow)
    }

    def onAbort(trigger: Option[Trait] = Some(trat) /*与`parent`保持一致*/ , parent: Option[ReflowTrait] = env.tracker.outer.map(_.trat.as[ReflowTrait]), depth: Int = env.subDepth) {
      if (debugMode) log.e("[onAbort]")
      withAbort(trigger, parent, depth, null)
    }

    def onFailed(trat: Trait = trat, parent: Option[ReflowTrait] = env.tracker.outer.map(_.trat.as[ReflowTrait]), depth: Int = env.subDepth, e: Exception) {
      if (debugMode) log.e(e, "[onFailed]aborted:%s.", aborted)
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
      scheduler = env.trat.as[ReflowTrait].reflow.start(In.from(env.input), new SubReflowFeedback(env, runner, progress(10, 10, publish = autoProgress)),
        env.tracker.strategy.toSub, null, env, env.tracker.pulse)
      false // 异步。
    }
    override protected def autoProgress: Boolean = false
    override protected def doWork(): Unit = {}

    override protected def onAbort(): Unit = {
      if (scheduler.nonNull) scheduler.abort()
      super.onAbort()
    }
  }

  private[reflow] class SubReflowFeedback(env: Env, runner: Runner, doSth: => Unit) extends Feedback with TAG.ClassName {
    override def onPending(): Unit = {}

    override def onStart(): Unit = {
      if (debugMode) log.i("[onStart]maybe call repeat, but no side effect:")
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
    private[reflow] def reportOnPending(): Unit = eatExceptions(feedback.onPending())

    private[reflow] def reportOnStart(): Unit = eatExceptions(feedback.onStart())

    private[reflow] def reportOnProgress(progress: Progress, out: Out, depth: Int): Unit = eatExceptions(feedback.onProgress(progress, out, depth))

    private[reflow] def reportOnComplete(out: Out): Unit = eatExceptions(feedback.onComplete(out))

    private[reflow] def reportOnUpdate(out: Out): Unit = eatExceptions(feedback.onUpdate(out))

    private[reflow] def reportOnAbort(trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit = eatExceptions(feedback.onAbort(trigger, parent, depth))

    private[reflow] def reportOnFailed(trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = eatExceptions(feedback.onFailed(trat, parent, depth, e))
  }

  private class Reporter4Debug(reflow: Reflow, feedback: Feedback, sum: Int)(implicit tag: LogTag) extends Reporter(feedback) {
    @volatile private var step: Int = _
    @volatile private var sub: Float = _
    @volatile private var beenReset = true

    override private[reflow] def reportOnStart(): Unit = {
      log.i("[reportOnStart]beenReset:%b.", beenReset)
      assert(beenReset)
      step = -1
      sub = 0
      super.reportOnStart()
    }

    override private[reflow] def reportOnProgress(progress: Progress, out: Out, depth: Int): Unit = {
      log.i("[reportOnProgress]progress:%s, out:%s, depth:%s.", progress, out, depth)
      assert(progress.sub >= sub, s"调用没有同步？`${progress.trat.get.name$}`。")
      if (beenReset && progress.sub == 0) {
        assert(progress.step == step + 1)
        step = progress.step
        beenReset = false
      } else assert(progress.step == step)
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
      log.i("[reportOnComplete]step:%d, sub:%f, sum:%d, _stateResetted:%b, out:%s.", step, sub, sum, beenReset, out)
      assert(beenReset && step == sum - 1 && sub == 0)
      super.reportOnComplete(out)
    }

    override private[reflow] def reportOnUpdate(out: Out): Unit = {
      log.i("[reportOnUpdate]out:%s.", out)
      super.reportOnUpdate(out)
    }
  }
}
