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
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadPoolExecutor
import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.LogTag
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.anno.proguard.Burden
import hobby.wei.c.log.Logger

import scala.collection.mutable

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
object Assist extends TAG.ClassName {
  val DEBUG = false
  val logger = new Logger()

  def getRef[T](ref: WeakReference[T]): Option[T] = if (ref.isNull) None else Option(ref.get())

  def between(min: Float, value: Float, max: Float) = min max value min max

  @Burden
  def assertx(b: Boolean, msg: => String = null): Unit = assertf(b, msg, force = false)

  def assertf(b: Boolean, msg: => String = null): Unit = assertf(b, msg, force = true)

  private def assertf(b: Boolean, msg: => String, force: Boolean = true): Unit = if ((force || DEBUG) && !b) Throws.assertError(msg)

  def requireNonEmpty(s: String): String = {
    assertf(s.nonNull && s.nonEmpty)
    s
  }

  def assertElemNonNull[C <: mutable.Set[_ <: AnyRef]](col: C): C = {
    col.seq.foreach(t => assertf(t.nonNull, "元素不能为null."))
    col
  }

  def requireTaskNameDifferent(trat: Trait[_], names: mutable.Set[String]) {
    val name = trat.name$
    if (names.contains(name)) Throws.sameName(name)
    names.add(name)
  }

  /**
    * 由于{@link Key$#equals(Object)}是比较了所有参数，所以这里还得重新检查。
    */
  def requireKey$kDiff[C <: mutable.Set[Key$[_]]](keys: C): C = {
    if (keys.nonEmpty) {
      val ks = new util.HashSet[String]
      for (k <- keys.seq) {
        if (ks.contains(k.key)) Throws.sameKey$k(k)
        ks.add(k.key)
      }
    }
    keys
  }

  /**
    * 要求相同的输入key的type也相同，但不要求不能有相同的输出key，因为输出key由需求决定，而需求已经限制了不同。
    */
  def requireTransInTypeSame[C <: mutable.Set[Transformer[_]]](tranSet: C): C = {
    if (tranSet.nonEmpty) {
      val map = new mutable.HashMap[String, Transformer[_]]()
      for (t <- tranSet) {
        if (map.contains(t.in.key)) {
          val trans = map(t.in.key)
          if (!t.in.equals(trans.in)) Throws.tranSameKeyButDiffType(t.in, trans.in)
        } else {
          map.put(t.in.key, t)
        }
      }
    }
    tranSet
  }

  def eatExceptions(work: () => Unit, onError: => Unit = ()) {
    try {
      work()
    } catch {
      case e: Exception =>
        logger.w("eatExceptions.", e)
        onError
    }
  }

  private[reflow] object Throws {
    def sameName(name: String) = throw new IllegalArgumentException(s"队列中不可以有相同的任务名称。名称为\"$name\"的Task已存在, 请确认或尝试重写其name()方法。")

    def sameOutKeyParallel(key: Key$[_], trat: Trait[_]) = throw new IllegalArgumentException(s"并行的任务不可以有相同的输出。key: \"${key.key}\", Task: \"${trat.name$()}\"。")

    def sameCacheKey(key: Key$[_]) = throw new IllegalArgumentException(s"Task.cache(key, value)不可以和与该Task相关联的Trait.requires()有相同的key: \"${key.key}\"。")

    def sameKey$k(key: Key$[_]) = throw new IllegalArgumentException("集合中的Key$.key不可以重复: \"$key\"。")

    def lackIOKey(key: Key$[_], in$out: Boolean) = throw new IllegalStateException(s"缺少${if (in$out) "输入" else "输出"}参数: $key。")

    def lackOutKeys() = throw new IllegalStateException("所有任务的输出都没有提供最终输出, 请检查。")

    def typeNotMatch(key: Key$[_], clazz: Class[_]) = throw new IllegalArgumentException(s"key为\"${key.key}\"的参数值类型与定义不一致: 应为\"${key.tpe}\", 实际为\"$clazz\"。")

    def typeNotMatch4Trans(from: Key$[_], to: Key$[_]) = typeNotMatch(to, from, "转换。")

    def typeNotMatch4Consume(from: Key$[_], to: Key$[_]) = typeNotMatch(to, from, "消化需求。")

    def typeNotMatch4Required(from: Key$[_], to: Key$[_]) = typeNotMatch(to, from, "新增初始输入。")

    def typeNotMatch4RealIn(from: Key$[_], to: Key$[_]) = typeNotMatch(to, from, "实际输入。")

    private def typeNotMatch(from: Key$[_], to: Key$[_], opt: String) = throw new IllegalArgumentException(s"赋值类型不匹配: \"${to.tpe}\" but \"${from.tpe}\". 操作: \"$opt\"。")

    def tranSameKeyButDiffType(one: Key$[_], another: Key$[_]) = throw new IllegalArgumentException(s"多个转换使用同一输入key但类型不一致: key: \"${one.key}\", types: \"${one.tpe}\"、\"${another.tpe}\"。")

    def assertError(msg: String) = throw new AssertionError(msg)
  }

  private[reflow] object Monitor extends TAG.ClassName {
    private def tag(name: String): LogTag = new LogTag(TAG + "." + name)

    def duration(name: String, begin: Long, end: Long, period: Reflow.Period) {
      val duration = end - begin
      val avg = period.average(duration)
      if (avg == 0 || duration <= avg) {
        logger.i("task:%s, period:%s, duration:%fs, average:%fs", name, period, duration / 1000f, avg / 1000f)(tag("duration"))
      } else {
        logger.w("task:%s, period:%s, duration:%fs, average:%fs", name, period, duration / 1000f, avg / 1000f)(tag("duration"))
      }
    }

    def abortion(triggerFrom: String, name: String, forError: Boolean) = logger.i("triggerFrom:%1$s, task:%2$s, forError:%3$s", triggerFrom, name, forError)(tag("abortion"))

    @Burden
    def assertStateOverride(prev: State, state: State, success: Boolean) {
      if (!success) {
        logger.e("illegal state override! prev:%s, state:%s", prev, state)(tag("abortion"))
        assertx(success)
      }
    }

    def complete(step: Int, out: Out, flow: Out, trimmed: Out) = logger.i("step:%d, out:%s, flow:%s, trimmed:%s", step, out, flow, trimmed)(tag("complete"))

    def threadPool(pool: ThreadPoolExecutor, addThread: Boolean, reject: Boolean) = logger.i(
      "{ThreadPool}%s, active/core:(%d/%d/%d), taskCount:%d, largestPool:%d",
      if (reject) "reject runner" else if (addThread) "add thread" else "offer queue",
      pool.getActiveCount, pool.getPoolSize, pool.getMaximumPoolSize,
      pool.getTaskCount, pool.getLargestPoolSize)(tag("threadPool"))

    def threadPoolError(t: Throwable) = logger.e(t)(tag("threadPoolError"))
  }

  class FailedException(t: Throwable) extends Exception(t: Throwable)

  class AbortException(t: Throwable) extends Exception(t: Throwable)

  class FailedError(t: Throwable) extends Error(t: Throwable)

  class AbortError(t: Throwable = null) extends Error(t: Throwable)

  class InnerError(t: Throwable) extends Error(t: Throwable)
}