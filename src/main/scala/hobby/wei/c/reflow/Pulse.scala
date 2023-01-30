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

package hobby.wei.c.reflow

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.{LogTag, ThrowMsg}
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Assist.eatExceptions
import hobby.wei.c.reflow.Feedback.{Log, Progress}
import hobby.wei.c.reflow.Feedback.Progress.Strategy
import hobby.wei.c.reflow.Pulse.Reporter
import hobby.wei.c.reflow.Reflow.{debugMode, logger => log}
import hobby.wei.c.reflow.State._
import hobby.wei.c.reflow.Trait.ReflowTrait
import hobby.wei.c.tool
import hobby.wei.c.tool.Snatcher
import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter

/**
  * 脉冲步进流式数据处理器。
  * <p>
  * 数据流经`大规模集成任务集（Reflow）`，能够始终保持输入时的先后顺序，会排队进入各个任务，每个任务可保留前一个数据在处理时
  * 特意留下的标记。无论在任何深度的子任务中，也无论前一个数据在某子任务中停留的时间是否远大于后一个。
  *
  * @param reflow        每个`流处理器`都`Base`在一个主`Reflow`上。
  * @param abortIfError  当某次`input`出现错误时，是否中断。默认为`false`（无法保证出错的任务留下的路标在下一个`input`成功读取到）。
  * @param inputCapacity 输入数据的缓冲容量。
  * @param execCapacity  不能一直无限制地[[reflow.start]]，也不能让`onStart`和`onComplete`的计数差值过大，会导致任务对象大量堆积、内存占用升高等问题。
  *
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 01/07/2018;
  *          1.5, 04/10/2019, fix 了一个很重要的 bug（版本号与`Tracker`保持一致：`Tracker`也作了修改）;
  *          1.7, 29/09/2020, 增加了`strategy`和`queueCapacity`实现，至此算是完美了；
  *          1.8, 01/10/2020, 修复`Tactic.snatcher`处于不同上下文导致线程不串行而偶现异常的问题；
  *          1.9, 11/10/2020, 为`Pulse.Interact`接口方法增加`parent`参数，修复[深度]和[任务]都相同的两个并行子任务干扰的问题；
  *          2.1, 18/12/2020, bug fix: 支持`Pulse.abortIfError = false`的定义（在异常出错时也能正常前进）；
  *          2.2, 30/12/2021, 微调优化；
  *          2.3, 10/01/2122, 修复诡异的 bug: 最终定位到`TrieMap`的`put()`,`remove()`不能并发读写，即使是不同的`key`。
  */
// format: off
class Pulse(val reflow: Reflow, feedback: Pulse.Feedback, val abortIfError: Boolean = false,
            val inputCapacity: Int = Config.DEF.maxPoolSize * 3, val execCapacity: Int = 3, val globalTrack: Boolean = false)
           (implicit strategy: Strategy, poster: Poster) extends Scheduler with TAG.ClassName { // format: on
  private lazy val sna4In     = new tool.Snatcher
  private lazy val snatcher   = new tool.Snatcher.ActionQueue
  private lazy val reporter   = new Reporter(feedback.wizh(poster))
  private lazy val state      = new Scheduler.State$
  private lazy val inputQueue = new LinkedBlockingQueue[In](inputCapacity)
  private val serialNum       = new AtomicLong(-1)
  private val startedSNum     = new AtomicLong(-1)
  private val completedSNum   = new AtomicLong(-1)
  private val head            = new AtomicReference[Tactic]

  // format: off
  /**
    * 流式数据输入。应自行保证多线程输入时的顺序性（如果需要）。
    * 输入数据`in`会首先存入[[LinkedBlockingQueue]]，如果达到了[[inputCapacity]] size, 则会返回`false`。
    * 需要关注返回值。
    */
  def input(in: In): Boolean = if (!isDone) {
    val succeed = inputQueue.offer(in)
    if (succeed) consumeInputQueue()
    asyncDebug()
    succeed
  } else false

  private def consumeInputQueue(): Unit = {
    def updateMum(num: Long, to: AtomicLong): Unit = {
      var n = to.get
      while (num > n && !to.compareAndSet(n, num))
        n = to.get
    }
    if (debugMode) log.i("[consumeInputQueue](0)serialNum:%d, startedSNum:%d, completedSNum:%d, inputQueue.size:%s.", serialNum.get, startedSNum.get, completedSNum.get, inputQueue.size)
    sna4In.tryOn {
      while (canForward
        && startedSNum.get - completedSNum.get < execCapacity
        && startedSNum.get > serialNum.get - execCapacity /*允许比已`onStart`的超前`execCapacity`个。*/
        && !inputQueue.isEmpty) {
        if (debugMode) log.i("[consumeInputQueue](1)serialNum:%d, startedSNum:%d, completedSNum:%d, inputQueue.size:%s.", serialNum.get, startedSNum.get, completedSNum.get, inputQueue.size)
        state.forward(PENDING)
        head.updateAndGet { prev =>
          new Tactic(Option(prev), this, serialNum.incrementAndGet, strategy, 
            { sn => updateMum(sn, startedSNum); consumeInputQueue() },
            { sn => updateMum(sn, completedSNum); consumeInputQueue() })
        }.start(inputQueue.poll().ensuring(_.nonNull))
      }
    }
  } // format: on

  def currentScheduledSize = serialNum.get + 1
  def currentCompletedSize = completedSNum.get + 1
  def inputQueueSize       = inputQueue.size()

  /** 当前所有的输入是否都已成功运行。瞬时状态，即使当前返回`true`了，可能后续会再次输入，就又返回`false`了。 */
  def isCurrAllCompleted = inputQueue.isEmpty && currentScheduledSize == currentCompletedSize

  /**
    * 是否已经结束运行。
    *
    * @return 若为`true`，则新的[[input]]会被忽略，已经在缓冲队列中的[[In]]也将不被执行。
    */
  override def isDone = {
    val s = getState
    s == COMPLETED /*不应该存在*/ || s == FAILED || s == ABORTED || s == UPDATED /*不应该存在*/
  }

  override def getState = state.get

  private[reflow] def canForward = !isDone

  @deprecated
  override def sync(reinforce: Boolean = false) = ??? // head.get.scheduler.sync(reinforce)
  @deprecated
  override def sync(reinforce: Boolean, milliseconds: Long) = ??? // head.get.scheduler.sync(reinforce, milliseconds)

  override def abort(): Unit = snatcher.queAc {
    if (state.forward(PENDING)) { // 说明还没有开始
      if (state.forward(ABORTED)) {}
    }
    abortHead(Option(head.get))
    head.set(null)
  }

  private[reflow] def onFailed(): Unit = snatcher.queAc {
    if (abortIfError) {
      state.forward(FAILED)
      // 后面的`state.forward()`是不起作用的。
      abort()
    }
  }

  private[reflow] def onAbort(): Unit = snatcher.queAc {
    state.forward(ABORTED)
    abort()
  }

  private[reflow] def abortHead(head: Option[Tactic]): Unit = head.foreach { tac =>
    // 顺序很重要，由于`abort()`事件会触发tac重置 head, 所以这里先引用，后执行，确保万无一失。
    val thd = tac.head
    while (tac.scheduler.isNull) {
      // 稍等片刻，等`scheduler`拿到实例。
      Thread.`yield`()
    }
    tac.scheduler.abort()
    abortHead(thd) // 递归
  }

  private[reflow] class Tactic(@volatile var head: Option[Tactic], pulse: Pulse, serialNum: Long, strategy: Strategy, onStartCallback: Long => Unit, onCompleteCall: Long => Unit) extends TAG.ClassName {
    private lazy val snatcher = new tool.Snatcher
    private lazy val roadmap  = new ConcurrentHashMap[(Int, String, String), Option[Out]]
    private lazy val suspend  = new ConcurrentHashMap[(Int, String, String), () => Unit]
    // 不可以释放实例，只可以赋值。
    @volatile var scheduler: Scheduler = _
    @volatile var failed: Boolean      = false

    // format: off
    def start(in: In): Unit = scheduler = pulse.reflow.start$(in, feedback, strategy,
        /*不使用外部客户代码提供的`poster`，以确保`feedback`和`cacheBack`反馈顺序问题。*/
        null, null, interact, serialNum, pulse.globalTrack)
    // format: on

    private def doPushForward(): Unit = if (pulse.canForward) snatcher.tryOn {
      /*lazy val keys = roadmap.keys
      for ((k, go) <- suspend) { // 放到`map`中的肯定是并行的任务或单个任务，因此遍历时不用管它们放进去的顺序。
        if (failed || keys.exists(_ == k)) {
          suspend.remove(k)
          go()
        }
      }*/
      suspend.forEach((k: (Int, String, String), go: () => Unit) => {
        if (failed || roadmap.containsKey(k)) {
          suspend.remove(k)
          go()
        }
      })
    }

    private lazy val interact = new Pulse.Interact {

      override def evolve(depth: Int, trat: Trait, parent: Option[ReflowTrait], cache: Option[Out], failed: Boolean): Unit = {
        val key = Assist.resolveKey(depth, trat, parent)
        if (debugMode) log.i("(%d)[interact.evolve]%s.", serialNum, key)
        if (failed && !pulse.abortIfError) {
          Tactic.this.failed = true
        }
        roadmap.put(key, cache)
        doPushForward()
      }

      override def forward(depth: Int, trat: Trait, parent: Option[ReflowTrait], go: () => Unit): Unit = {
        val key = Assist.resolveKey(depth, trat, parent)
        if (debugMode) log.i("(%d)[interact.forward]%s.", serialNum, key)
        head.fold(go()) { tac =>
          if (tac.failed) go()
          else {
            tac.suspend.put(key, go)
            tac.doPushForward()
          }
        }
      }

      override def getCache(depth: Int, trat: Trait, parent: Option[ReflowTrait]): Option[Out] = {
        val key = Assist.resolveKey(depth, trat, parent)
        if (debugMode) log.i("(%d)[interact.getCache]%s.", serialNum, key)
        head.fold[Option[Out]](None) { tac =>
          Snatcher.visWait(Option(tac.roadmap.remove(key)))(_.nonEmpty || tac.failed)(_ => key.toString).flatten
        }
      }
    }

    // 再次用`pulse.snatcher`串行化没意义。
    // 1. `feedback`本身已经被串行化了；
    // 2. 影响性能，同时也是个伪命题（没有绝对的先后顺序，无法判定）；
    // 3. 性能优先。并行程序应该最大化性能。
    private lazy val feedback = new Feedback {
      override def onPending(): Unit = pulse.reporter.reportOnPending(serialNum)

      override def onStart(): Unit = {
        onStartCallback(serialNum)
        pulse.reporter.reportOnStart(serialNum)
      }

      override def onProgress(progress: Progress, out: Out, depth: Int): Unit =
        pulse.reporter.reportOnProgress(serialNum, progress, out, depth)

      override def onComplete(out: Out): Unit = {
        onCompleteCall(serialNum)
        onCompleteDebug()
        pulse.reporter.reportOnComplete(serialNum, out)
        // 释放`head`。由于总是会引用前一个，会造成内存泄露。
        head = None
        // 本次脉冲走完所有`Task`，结束。
      }

      override def onUpdate(out: Out): Unit = assert(assertion = false, "对于`Pulse`中的`Reflow`，不应该走到`onUpdate()`这里。".tag)

      override def onAbort(trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit = {
        pulse.onAbort()
        pulse.reporter.reportOnAbort(serialNum, trigger, parent, depth)
        // 放到最后
        head = None
      }

      override def onFailed(trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = {
        if (!pulse.abortIfError) {
          onCompleteCall(serialNum)
          onCompleteDebug()
          // 需要告诉下一个`input`虽然当前`input`的后续任务都不会执行了，但它还是应该继续。
          // 需要注意的是：本`feedback`是最外层的，如果是`SubReflow`触发的`onFailed()`，就不能保证以下语句在`interact.evolve()`之前。
          // 就会出现一种情况：
          // 本`serialNum`层`Reflow`排在失败任务后面的任务，永远不会`interact.evolve()`，但下一个`input`（即：`serialNum + 1`层）
          // 的该任务`interact.forward()`的时候，`failed`标志还未被置为`true`，就会出现`serialNum + 1`层及以后的该任务永远得不到执行的情况。
          // 复现该 bug: 启用下面一句，注释掉`interact.evolve()`中对`failed`的判断，并打开`onCompleteDebug()`和`asyncDebug()`里的`debugMode`开关。
          //failed = true
          // 解决：在`interact.evolve()`接口中增加`failed`字段。
        }
        pulse.onFailed()
        pulse.reporter.reportOnFailed(serialNum, trat, parent, depth, e)
        // 放到最后
        head = None
      }
    }

    private def onCompleteDebug(): Unit = if (debugMode) {
      debugFuncMap.put(
        (System.currentTimeMillis + 10 * 1000, serialNum),
        (time, sn) => {
          if (time <= System.currentTimeMillis) {
            // 这里还是不能用`head.suspend`，head 是给`serialNum + 1`次访问这一次用的，底层逻辑还是同一层的两个集合相匹配。
            (sn, failed, suspend.keys.asScala.toList, roadmap.keys.asScala.toList)
          } else (-1, false, Nil, Nil)
        }
      )
    }
  }

  private lazy val debugFuncMap = TrieMap.empty[(Long, Long), (Long, Long) => (Long, Boolean, List[(Int, String, String)], List[(Int, String, String)])]

  private def asyncDebug(): Unit = if (debugMode) {
    var list1: List[(Long, String, String, String, String, String)] = Nil
    var list2: List[(Long, String, String, String, String, String)] = Nil
    debugFuncMap.snapshot().foreach { case (k, v) =>
      val t = v(k._1, k._2)
      if (t._1 >= 0) {
        debugFuncMap.remove(k)
        // 已经进入 suspend 的，无论如何都会推进。
        list1 :::= t._3.map { x => (t._1, s"需要被${t._1 + 1}消化！", s"failed:${t._2}", s"suspend.key:$x", s"roadmap.exists:${t._4.contains(x)}", s"step:${Assist.findKeyStep(x, reflow)}") }
        // 如果`!abortIfError`，raadmap 没有被消化是正常的：上一轮完成了所有任务，但这一轮某任务`onFailed()`了，就不向后推进了（不会`tryScheduleNext()`）。
        list2 :::= t._4.flatMap { x => if (!abortIfError) None else Some(t._1, s"需要被${t._1 + 1}消化！", s"failed:${t._2}", s"suspend.exists:${t._3.contains(x)}", s"roadmap.key:$x", s"step:${Assist.findKeyStep(x, reflow)}") }
      }
    }
    if (list1.nonEmpty) {
      log.i("========== ========== ========== ========== ========== ========== ========== ========== ========== ==========")
      list1.foreach { v => log.i("========== %s", v) }
      throw new IllegalStateException(s"%%%%%%%%%% %%%%%%%%%% %%%%%%%%%% 异常情况：累积了 ${list1.size} 个没有推进的任务。%%%%%%%%%% %%%%%%%%%% %%%%%%%%%%")
    }
    if (list2.nonEmpty) {
      log.i("========== ========== ========== ========== ========== ========== ========== ========== ========== ==========")
      list2.foreach { v => log.i("---------- %s", v) }
      throw new IllegalStateException(s"%%%%%%%%%% %%%%%%%%%% %%%%%%%%%% 异常情况：累积了 ${list2.size} 个没有读取缓存的任务。%%%%%%%%%% %%%%%%%%%% %%%%%%%%%%")
    }
  }
}

object Pulse {

  trait Feedback extends Equals {
    def onPending(serialNum: Long): Unit
    def onStart(serialNum: Long): Unit
    def onProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit
    def onComplete(serialNum: Long, out: Out): Unit
    def onAbort(serialNum: Long, trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit
    def onFailed(serialNum: Long, trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit

    override def equals(any: Any)    = super.equals(any)
    override def canEqual(that: Any) = false
  }

  object Feedback {

    trait Adapter extends Feedback {
      override def onPending(serialNum: Long): Unit = {}
      override def onStart(serialNum: Long): Unit = {}
      override def onProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit = {}
      override def onComplete(serialNum: Long, out: Out): Unit = {}
      override def onAbort(serialNum: Long, trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit = {}
      override def onFailed(serialNum: Long, trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = Log.onFailed(trat, parent, depth, e)
    }

    abstract class Butt[T >: Null <: AnyRef](kce: KvTpe[T], watchProgressDepth: Int = 0) extends Adapter {

      override def onProgress(serialNum: Long, progress: Progress, out: Out, fromDepth: Int): Unit = {
        super.onProgress(serialNum, progress, out, fromDepth)
        if (fromDepth == watchProgressDepth && out.keysDef().contains(kce))
          onValueGotOnProgress(serialNum, out.get(kce), progress)
      }

      override def onComplete(serialNum: Long, out: Out): Unit = {
        super.onComplete(serialNum, out)
        onValueGotOnComplete(serialNum, out.get(kce))
      }

      def onValueGotOnProgress(serialNum: Long, value: Option[T], progress: Progress): Unit = {}
      def onValueGotOnComplete(serialNum: Long, value: Option[T]): Unit
    }

    abstract class Lite[-T <: AnyRef](watchProgressDepth: Int = 0) extends Butt(lite.Task.defKeyVType, watchProgressDepth) {

      @deprecated
      override final def onValueGotOnProgress(serialNum: Long, value: Option[AnyRef], progress: Progress): Unit =
        liteValueGotOnProgress(serialNum, value.as[Option[T]], progress)

      @deprecated
      override final def onValueGotOnComplete(serialNum: Long, value: Option[AnyRef]): Unit =
        liteOnComplete(serialNum, value.as[Option[T]])

      def liteValueGotOnProgress(serialNum: Long, value: Option[T], progress: Progress): Unit = {}
      def liteOnComplete(serialNum: Long, value: Option[T]): Unit
    }
  }

  private[reflow] trait Interact {
    /**
      * 进展，完成某个小`Task`。
      * <p>
      * 表示上一个`Tactic`反馈的最新进展（或当前`Tactic`要反馈给下一个`Tactic`的进展）。
      *
      * @param depth  `SubReflow`的嵌套深度，顶层为`0`。在同一深度下的`trat`名称不会重复。
      * @param trat   完成的`Task`。
      * @param parent 如果是`SubReflow`，则表示其父级别。
      * @param cache  留给下一个路过`Task`的数据。
      * @param failed 当前`Task`是否异常失败了。
      */
    def evolve(depth: Int, trat: Trait, parent: Option[ReflowTrait], cache: Option[Out], failed: Boolean): Unit

    /**
      * 当前`Tactic`的某[[Task]]询问是否可以启动执行。必须在前一条数据输入[[Pulse.input]]执行完毕该`Task`后才可以启动。
      *
      * @param depth  同上。
      * @param trat   同上。
      * @param parent 同上。
      * @param go     一个函数，调用以推进询问的`Task`启动执行。如果在询问时，前一个`Tactic`的该`Task`未执行完毕，则应该
      *               将本参数缓存起来，以备在`evolve()`调用满足条件时，再执行本函数以推进`Task`启动。
      */
    def forward(depth: Int, trat: Trait, parent: Option[ReflowTrait], go: () => Unit): Unit

    /**
      * 当前`Tactic`的某`Task`执行的时候，需要获得上一个`Tactic`留下的数据。
      *
      * @param depth  同上。
      * @param trat   同上。
      * @param parent 同上。
      * @return
      */
    def getCache(depth: Int, trat: Trait, parent: Option[ReflowTrait]): Option[Out]
  }

  implicit class WithPoster(feedback: Feedback) {

    def wizh(poster: Poster): Feedback =
      if (poster.isNull) feedback
      else if (feedback.isNull) feedback
      else new Feedback {
        require(poster.nonNull)
        override def onPending(serialNum: Long): Unit                                                                    = poster.post(feedback.onPending(serialNum))
        override def onStart(serialNum: Long): Unit                                                                      = poster.post(feedback.onStart(serialNum))
        override def onProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit                         = poster.post(feedback.onProgress(serialNum, progress, out, depth))
        override def onComplete(serialNum: Long, out: Out): Unit                                                         = poster.post(feedback.onComplete(serialNum, out))
        override def onAbort(serialNum: Long, trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit     = poster.post(feedback.onAbort(serialNum, trigger, parent, depth))
        override def onFailed(serialNum: Long, trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = poster.post(feedback.onFailed(serialNum, trat, parent, depth, e))
      }
  }

  private[reflow] class Reporter(feedback: Feedback)(implicit tag: LogTag) {
    private[reflow] def reportOnPending(serialNum: Long): Unit                                                                    = eatExceptions(feedback.onPending(serialNum))
    private[reflow] def reportOnStart(serialNum: Long): Unit                                                                      = eatExceptions(feedback.onStart(serialNum))
    private[reflow] def reportOnProgress(serialNum: Long, progress: Progress, out: Out, depth: Int): Unit                         = eatExceptions(feedback.onProgress(serialNum, progress, out, depth))
    private[reflow] def reportOnComplete(serialNum: Long, out: Out): Unit                                                         = eatExceptions(feedback.onComplete(serialNum, out))
    private[reflow] def reportOnAbort(serialNum: Long, trigger: Option[Trait], parent: Option[ReflowTrait], depth: Int): Unit     = eatExceptions(feedback.onAbort(serialNum, trigger, parent, depth))
    private[reflow] def reportOnFailed(serialNum: Long, trat: Trait, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = eatExceptions(feedback.onFailed(serialNum, trat, parent, depth, e))
  }
}
