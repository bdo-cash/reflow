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

import java.util
import java.util.concurrent.ThreadPoolExecutor
import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.tool.pool.S._2S
import hobby.wei.c.anno.proguard.Burden
import hobby.wei.c.reflow.Dependency.IsPar
import hobby.wei.c.reflow.Reflow.{logger => log, _}
import hobby.wei.c.reflow.Trait.ReflowTrait
import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
object Assist {
  def between(min: Float, value: Float, max: Float) = min max value min max

  @Burden
  def assertx(b: Boolean): Unit = assertf(b, "", force = false)

  @Burden
  def assertx(b: Boolean, msg: => String): Unit                                = assertf(b, msg, force = false)
  def assertf(b: Boolean): Unit                                                = assertf(b, "", force = true)
  def assertf(b: Boolean, msg: => String): Unit                                = assertf(b, msg, force = true)
  private def assertf(b: Boolean, msg: => String, force: Boolean = true): Unit = if ((force || debugMode) && !b) Throws.assertError(msg)

  def requireNonEmpty(s: String): String = { assertf(s.nonNull && s.nonEmpty); s }

  def requireElemNonNull[C <: Set[_ <: AnyRef]](col: C): C = {
    if (debugMode) col.seq.foreach(t => assertf(t.nonNull, "元素不能为null."))
    col
  }

  def requireTaskNameDiffAndUpdate(trat: Trait, names: mutable.Set[String]): Unit = {
    val name = trat.name$
    if (names.contains(name)) Throws.sameName(name)
    names.add(name)
  }

  def requirePulseKeyDiffAndUpdate(depth: Int, trat: Trait, parent: Option[ReflowTrait], keySet: mutable.Set[(Int, String, String)]): Unit = {
    val key = resolveKey(depth, trat, parent)
    if (keySet.contains(key)) Throws.sameKeyPulse(key)
    keySet.add(key)
  }

  def requirePulseKeyDiff(reflow: Reflow): Reflow = {
    val keySet = new mutable.HashSet[(Int, String, String)]
    def loop(trat: Trait, parent: Option[ReflowTrait] = None, depth: Int = 0) {
      // `Tracker`中已忽略`is4Reflow`与`Pulse.Interact`的交互。
      if (trat.is4Reflow) trat.asSub.reflow.basis.traits.foreach(loop(_, Some(trat.asSub), depth + 1))
      else if (trat.isPar) trat.asPar.traits().foreach(loop(_, parent, depth))
      else requirePulseKeyDiffAndUpdate(depth, trat, parent, keySet)
    }
    reflow.basis.traits.foreach(loop(_))
    reflow
  }

  def resolveKey(depth: Int, trat: Trait, parent: Option[ReflowTrait]): (Int, String, String) = (depth, trat.name$, parent.map(_.name$).orNull)

  def genSerialNum(reflow: Reflow, key: (Int, String, String), which: Int): Long = {
    3
  }

  /**
    * 由于{@link Key$#equals(Object)}是比较了所有参数，所以这里还得重新检查。
    */
  def requireKkDiff[C <: Iterable[KvTpe[_ <: AnyRef]]](keys: C): C = {
    if (/*debugMode &&*/ keys.nonEmpty) {
      val ks = new util.HashSet[String]
      for (k <- keys.seq) {
        if (ks.contains(k.key)) Throws.sameKey$k(k)
        ks.add(k.key)
      }
    }
    keys
  }

  /**
    * 要求相同的输入key的type也相同，且不能有相同的输出k.key。
    */
  def requireTransInTpeSame$OutKDiff[C <: Set[Transformer[_ <: AnyRef, _ <: AnyRef]]](tranSet: C): C = {
    if (/*debugMode &&*/ tranSet.nonEmpty) {
      val map = new mutable.AnyRefMap[String, Transformer[_ <: AnyRef, _ <: AnyRef]]()
      for (t <- tranSet) {
        if (map.contains(t.in.key)) {
          val trans = map(t.in.key)
          if (t.in != trans.in) Throws.tranSameKeyButDiffType(t.in, trans.in)
        } else {
          map.put(t.in.key, t)
        }
      }
      requireKkDiff(tranSet.map(_.out))
    }
    tranSet
  }

  def eatExceptions(work: => Unit)(implicit tag: LogTag) {
    try { work }
    catch { case e: Exception => log.w(e, "eatExceptions.") }
  }

  private[reflow] object Throws {
    def sameName(name: String)                                                              = throw new IllegalArgumentException(s"任务队列中不可以有相同的任务名称。名称为`$name`的 Task 已存在, 请检查。建议把 lite.Task 定义的 val 改为 def（如果用 lite 库的话），或尝试重写其 Trait.name() 方法。")
    def sameKeyPulse(key: (Int, String, String))                                            = throw new IllegalArgumentException(s"Pulse 中不可以有`层级、任务名称、父任务名称`三者都相同的组。组名称为`$key`的 Task 已存在, 请检查。建议把 lite.Task 定义的 val 改为 def（如果用 lite 库的话），或尝试重写其 Trait.name() 方法。")
    def sameOutKeyParallel(kvt: KvTpe[_ <: AnyRef], trat: Trait)                            = throw new IllegalArgumentException(s"并行的任务不可以有相同的输出。key: `${kvt.key}`, Task: `${trat.name$}`。")
    def sameCacheKey(kvt: KvTpe[_ <: AnyRef])                                               = throw new IllegalArgumentException(s"Task.cache(key, value) 不可以和与该 Task 相关的 Trait.requires() 有相同的 key: `${kvt.key}`。")
    def sameKey$k(kvt: KvTpe[_ <: AnyRef])                                                  = throw new IllegalArgumentException(s"集合中的 KvTpe.key 不可以重复: `$kvt`。")
    def lackIOKey(kvt: KvTpe[_ <: AnyRef], in$out: Boolean)                                 = throw new IllegalStateException(s"缺少${if (in$out) "输入" else "输出"}参数: `$kvt`。")
    def lackOutKeys()                                                                       = throw new IllegalStateException("所有任务的输出都没有提供最终输出, 请检查。")
    def typeNotMatch(kvt: KvTpe[_ <: AnyRef], clazz: Class[_])                              = throw new IllegalArgumentException(s"key 为`${kvt.key}`的参数值类型与定义不一致: 应为`${kvt.tpe}`, 实际为`$clazz`。")
    def typeNotMatch4Trans(from: KvTpe[_ <: AnyRef], to: KvTpe[_ <: AnyRef])                = typeNotMatch(to, from, "转换。")
    def typeNotMatch4Consume(from: KvTpe[_ <: AnyRef], to: KvTpe[_ <: AnyRef])              = typeNotMatch(to, from, "消化需求。")
    def typeNotMatch4Required(from: KvTpe[_ <: AnyRef], to: KvTpe[_ <: AnyRef])             = typeNotMatch(to, from, "新增初始输入。")
    def typeNotMatch4RealIn(from: KvTpe[_ <: AnyRef], to: KvTpe[_ <: AnyRef])               = typeNotMatch(to, from, "实际输入。")
    private def typeNotMatch(from: KvTpe[_ <: AnyRef], to: KvTpe[_ <: AnyRef], opt: String) = throw new IllegalArgumentException(s"赋值类型不匹配: `${to.tpe}` but `${from.tpe}`. 操作: `$opt`。")
    def tranSameKeyButDiffType(one: KvTpe[_ <: AnyRef], another: KvTpe[_ <: AnyRef])        = throw new IllegalArgumentException(s"多个转换使用同一输入key但类型不一致: key: `${one.key}`, types: `${one.tpe}`、`${another.tpe}`。")
    def assertError(msg: String)                                                            = throw new AssertionError(msg)
  }

  private[reflow] object Monitor extends TAG.ClassName {
    private def tag(name: String): LogTag = new LogTag(className + "/" + name)

    @Burden
    def duration(name: String, begin: Long, end: Long, period: Reflow.Period.Tpe): Unit = if (debugMode) {
      val duration = end - begin
      val avg      = period.average(duration)
      if (avg == 0 || duration <= avg) {
        log.i("task:%s, period:%s, duration:%fs, average:%fs.", name.s, period, duration / 1000f, avg / 1000f)(tag("duration"))
      } else {
        log.w("task:%s, period:%s, duration:%fs, average:%fs.", name.s, period, duration / 1000f, avg / 1000f)(tag("duration"))
      }
    }

    @Burden
    def duration(reflow: TAG.ClassName, begin: Long, end: Long, state: State.Tpe, state$ : State.Tpe, subReflow: Boolean): Unit = if (!subReflow)
      if (debugMode) log.w("[Reflow Time Duration]>>>>>>>>>> duration:%fs, state:%s/%s <<<<<<<<<<.", (end - begin) / 1000f, state, state$)(reflow.className)

    @Burden
    def abortion(trigger: String, task: String, forError: Boolean): Unit = if (debugMode) log.i("trigger:%1$s, task:%2$s, forError:%3$s.", trigger.s, task.s, forError)(tag("abortion"))

    @Burden
    def assertStateOverride(prev: State.Tpe, state: State.Tpe, success: Boolean) {
      if (!success) {
        log.w("`state override` maybe illegal, IGNORED! prev:%s, state:%s.", prev, state)(tag("abortion"))
        // 允许`success = false`, 不用中止。
        //assertx(success)
      }
    }

    @Burden
    def complete(step: => Int, out: Out, flow: Out, trimmed: Out): Unit = if (debugMode) log.i("step:%d, out:%s, flow:%s, trimmed:%s.", step, out, flow, trimmed)(tag("complete"))

    @Burden
    def threadPool(pool: ThreadPoolExecutor, addThread: Boolean, reject: Boolean): Unit = if (debugMode) log.i(
      "{ThreadPool}%s, active/core/max:(%d/%d/%d), queueSize:%d, taskCount:%d, largestPool:%d.",
      if (reject) "reject runner".s else if (addThread) "add thread".s else "offer queue".s,
      pool.getActiveCount,
      pool.getPoolSize,
      pool.getMaximumPoolSize,
      pool.getQueue.size(),
      pool.getTaskCount,
      pool.getLargestPoolSize
    )(tag("threadPool"))

    def threadPoolError(t: Throwable): Unit = log.e(t)(tag("threadPoolError"))
  }

  class FailedException(t: Throwable)   extends Exception(t: Throwable)
  class AbortException(t: Throwable)    extends Exception(t: Throwable)
  class FailedError(t: Throwable)       extends Error(t: Throwable)
  class AbortError(t: Throwable = null) extends Error(t: Throwable)
  class InnerError(t: Throwable)        extends Error(t: Throwable)
}
