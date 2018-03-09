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
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Dependency._
import hobby.wei.c.reflow.Reflow._

import scala.collection.{Set, _}
import scala.util.control.Breaks._

/**
  * 任务流通过本组件进行依赖关系组装。
  * 注意: 对本类的任何操作都应该是单线程的, 否则依赖关系是不可预期的, 没有意义。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
class Dependency private[reflow]() {
  private val basis = new BasisMutable
  private val names = new mutable.HashSet[String]
  // Key$是transform后的
  private val useless = new mutable.AnyRefMap[String, mutable.Map[String, Key$[_]]]
  private val inputRequired = new mutable.AnyRefMap[String, Key$[_]]

  /**
    * 给前面最后添加的任务增加并行任务。
    *
    * @param trat 新增的任务具有的特性。
    * @return 当前依赖组装器。
    */
  def and(trat: Trait[_ <: Task]): Dependency = {
    require(!trat.ensuring(_.nonNull).isParallel)
    requireTaskNameDifferent(trat, names)
    if (basis.traits.isEmpty) {
      basis.traits += trat
    } else {
      (basis.last(false).get match {
        case tt: Trait.Parallel => tt.traits()
        case last =>
          val parallel = new Trait.Parallel(last)
          // 注意：必须用 remove 和 +=，只有这俩是 ListBuffer 的方法，其他 Seq 方法会出现意想不到的状况。
          basis.traits.remove(basis.traits.length - 1)
          basis.traits += parallel
          parallel.traits()
      }).+=(trat)
    }
    this
  }

  /**
    * 在前面已添加的任务之后，增加串行任务。
    *
    * @param trat 新增的任务具有的特性。
    * @return 当前依赖组装器。
    */
  def next(trat: Trait[_]): Dependency = {
    require(!trat.ensuring(_.nonNull).isParallel)
    requireTaskNameDifferent(trat, names)
    basis.last(false).foreach { last =>
      genIOPrev(last, null, basis, inputRequired, useless)
    }
    basis.traits += trat
    this
  }

  /**
    * 在前面已添加的任务之后，增加已有的任务流。
    *
    * @param dependency 已定义的任务流。
    * @return 当前依赖组装器。
    */
  def next(dependency: Dependency): Dependency = {
    if (basis.traits.isEmpty) copy(dependency)
    else for (trat <- dependency.basis.traits) {
      trat match {
        case trat: Trait.Parallel =>
          var first = true
          for (tt <- trat.traits()) {
            if (first) {
              next(tt).transition$(dependency.basis.transformers(tt.name$), check = false)
              first = false
            } else {
              and(tt).transition$(dependency.basis.transformers(tt.name$), check = false)
            }
          }
        case _ => next(trat).transition$(dependency.basis.transformers(trat.name$), check = false)
      }
      next$(dependency.basis.transGlobal(trat.name$), check = false)
    }
    this
  }

  /**
    * @see #transition(Set)
    */
  def transition(trans: Transformer[_, _]): Dependency = transition(Set(trans))

  /**
    * 为前面最后添加的任务增加输出转换器，以便能够匹配后面任务的输入或结果的参数类型。
    * <p>
    * 注意：本转换器仅作用于前面最后添加的任务。而且参数指定集合中的转换器不一定全都应用，
    * 取决于后面任务和结果的需求，以及当前任务的输出。
    *
    * @param tranSet 转换器集合。
    * @return 当前依赖组装器。
    * @see Transformer
    */
  def transition(tranSet: Set[Transformer[_, _]]): Dependency = transition$(tranSet, check = true)

  private def transition$(tranSet: Set[Transformer[_, _]], check: Boolean): Dependency = {
    if (check.ensuring(tranSet.nonNull) || tranSet.nonNull) if (tranSet.nonEmpty) {
      basis.transformers.put(basis.last(true).get.name$, requireTransInTypeSame(requireElemNonNull(tranSet)))
    }
    this
  }

  /**
    * @see #then(Set)
    */
  def next(trans: Transformer[_, _]): Dependency = next(Set(trans))

  /**
    * 为前面所有任务的输出增加转换器。以便能够匹配后面任务的输入或结果的参数类型。
    * <p>
    * 注意：参数指定集合中的转换器不一定全都应用，取决于后面任务和结果的需求。
    *
    * @param tranSet 转换器集合。
    * @return 当前依赖组装器。
    * @see Transformer
    */
  def next(tranSet: Set[Transformer[_, _]]): Dependency = next$(tranSet, check = true)

  private def next$(tranSet: Set[Transformer[_, _]], check: Boolean): Dependency = {
    if (check.ensuring(tranSet.nonNull) || tranSet.nonNull) if (tranSet.nonEmpty)
      basis.transGlobal.put(basis.last(false).get.name$, requireTransInTypeSame(requireElemNonNull(tranSet)))
    this
  }

  /**
    * 完成依赖的创建。
    *
    * @param outputs 输出值的key列表。
    * @return { @link Scheduler.Starter}接口。
    */
  def submit(outputs: Set[Key$[_]]): Reflow = {
    requireKey$kDiff(outputs)
    // 创建拷贝用于计算，以防污染当前对象中的原始数据。因为当前对象可能还会被继续使用。
    val uselesx = useless.mapValues(_.mutable.as[mutable.Map[String, Key$[_]]]).mutable
    val inputReqx = inputRequired.mutable
    val basisx = new BasisMutable(basis)
    genIOPrev(basisx.last(false).get, null, basisx, inputReqx, uselesx)
    genIOuts(outputs, basisx, inputReqx, uselesx)
    new Reflow.Impl(new Basis {
      override val traits = basis.traits.to[immutable.Seq]
      override val dependencies = basis.dependencies.mapValues(_.toMap).toMap
      override val transformers = basis.transformers.mapValues(_.toSet).toMap
      override val transGlobal = basis.transGlobal.mapValues(_.toSet).toMap
      override val outsFlowTrimmed = trimOutsFlow(basisx).mapValues(_.toSet).toMap
      override val inputs = inputReqx.values.toSet
      override val outs = outputs.toSet
    }, inputReqx)
  }

  def fork(): Dependency = Reflow.create(this)

  private def copy(dependency: Dependency): Unit = {
    dependency.names.foreach(names += _)
    basis.copyFrom(dependency.basis)
  }
}

object Dependency extends TAG.ClassName {
  trait Basis {
    val traits: Seq[Trait[_ <: Task]]
    /** 表示每个任务结束的时候应该为后面的任务保留哪些Key$(transform后的)。注意：可能get出来为null, 表示根本不用输出。 */
    val dependencies: Map[String, Map[String, Key$[_]]]
    /** 任务的输出经过转换, 生成最终输出传给下一个任务。 */
    val transformers: Map[String, Set[Transformer[_, _]]]
    /** 可把前面任意任务的输出作为输入的全局转换器。 */
    val transGlobal: Map[String, Set[Transformer[_, _]]]
    /** 虽然知道每个任务有哪些必要的输出, 但是整体上这些输出都要保留到最后吗? 注意：存储的是`globalTrans`[前]的结果。 */
    val outsFlowTrimmed: immutable.Map[String, immutable.Set[Key$[_]]]
    val inputs: immutable.Set[Key$[_]]
    val outs: immutable.Set[Key$[_]]

    def steps() = traits.size

    def stepOf(trat: Trait[_]): Int = {
      var step = traits.indexOf(trat)
      if (step < 0) breakable {
        for (tt <- traits)
          if (tt.isParallel && tt.asParallel.traits().indexOf(trat) >= 0) {
            step = traits.indexOf(tt)
            assert(step >= 0)
            break
          }
      }
      step
    }

    def first(child: Boolean): Option[Trait[_]] = first$last(first$last = true, child)

    def last(child: Boolean): Option[Trait[_]] = first$last(first$last = false, child)

    private def first$last(first$last: Boolean, child: Boolean): Option[Trait[_]] = {
      Option(if (traits.isEmpty) null
      else {
        val trat = traits.splitAt(if (first$last) 0 else traits.size - 1)._2.head
        if (child && trat.isParallel) {
          if (first$last) trat.asParallel.first() else trat.asParallel.last()
        } else trat
      })
    }
  }

  class BasisMutable(basis: Basis) extends Basis {
    def this() = this(null)

    if (basis.nonNull) copyFrom(basis)

    override val traits: mutable.ListBuffer[Trait[_]] = new mutable.ListBuffer[Trait[_]]
    override val dependencies: mutable.AnyRefMap[String, mutable.Map[String, Key$[_]]] = new mutable.AnyRefMap[String, mutable.Map[String, Key$[_]]]
    override val transformers: mutable.AnyRefMap[String, Set[Transformer[_, _]]] = new mutable.AnyRefMap[String, Set[Transformer[_, _]]]
    override val transGlobal: mutable.AnyRefMap[String, Set[Transformer[_, _]]] = new mutable.AnyRefMap[String, Set[Transformer[_, _]]]
    override val outsFlowTrimmed = null
    override val inputs = null
    override val outs = null

    def copyFrom(src: Basis): Unit = {
      src.traits.foreach(traits += _)
      src.dependencies.foreach { kv: (String, Map[String, Key$[_]]) =>
        dependencies.put(kv._1, kv._2.mutable)
      }
      src.transformers.foreach { kv: (String, Set[Transformer[_, _]]) =>
        transformers.put(kv._1, kv._2.toSet)
      }
      src.transGlobal.foreach { kv: (String, Set[Transformer[_, _]]) =>
        transGlobal.put(kv._1, kv._2.toSet)
      }
    }
  }

  implicit class ToMap[K, V](map: Map[K, V]) {
    def mutable = {
      val mutMap = new mutable.AnyRefMap[K, V]
      map.foreach(mutMap += _)
      mutMap
    }

    def concurrent = {
      val concurMap = new concurrent.TrieMap[K, V]
      map.foreach(concurMap += _)
      concurMap
    }
  }

  implicit class IsPar(trat: Trait[_]) {
    def isParallel: Boolean = trat.isInstanceOf[Trait.Parallel]

    def asParallel: Trait.Parallel = trat.as[Trait.Parallel]
  }

  /**
    * 处理最后一个{@link Trait}. 会做两件事：
    * a. 生成向前的依赖；从最后一个{@link Trait}的前一个开始，根据{@link Trait#requires$() 必须输入}在`
    * requires`或`useless`的输出（事件b）集合中，逐一匹配{@link Key$#key
     * key}并检查{@link Key$#isAssignableFrom(Key$) 值类型}是否符合赋值关系，最后将符合条件的标记为`
    * requires`, 若不符合条件，则直接抛出异常；
    * b. 生成向后的输出。该输出会首先标记为`useless`, 只有当需要的时候（事件a）才会取出并标记为`
    * requires`. 最终的`useless`将会被丢去。
    * <p>
    * 注意: 本方法会让并行的任务先执行{@link #transition(Set)}进行输出转换, 以免在事件b中检查出相同的输出。
    */
  private def genIOPrev(last: Trait[_], mapParallelOuts: mutable.Map[String, Key$[_]], basis: BasisMutable,
                        inputRequired: mutable.Map[String, Key$[_]], mapUseless: mutable.Map[String, mutable.Map[String, Key$[_]]]) {
    if (last.isParallel) {
      val outsPal = new mutable.AnyRefMap[String, Key$[_]]
      for (tt <- last.asParallel.traits()) {
        genIOPrev(tt, outsPal, basis, inputRequired, mapUseless)
      }
      if (outsPal.nonEmpty) mapUseless.values.foreach(useless => outsPal.keySet.foreach(useless.remove))
      mapUseless.put(last.name$, outsPal)
    } else {
      /*##### for requires #####*/
      val requires = new mutable.AnyRefMap[String, Key$[_]]
      putAll(requires, last.requires$)
      breakable {
        for (trat <- basis.traits.reverse.tail /*从倒数第{二}个开始*/ ) {
          if (requires.isEmpty) break
          // 把符合requires需求的globalTrans输出对应的输入放进requires.
          consumeRequiresOnTransGlobal(trat, requires, basis, check = true)
          // 消化在计算输出(genOuts())的前面，是否不合理？注意输出的计算仅一次，
          // 而且是为了下一次的消化服务的。如果把输出放在前面，自己的输出会误被自己消化掉。
          consumeRequires(trat, null /*此处总是null*/ , requires, basis, mapUseless)
        }
      }
      // 前面的所有输出都没有满足, 那么看看初始输入。
      genInputRequired(requires, inputRequired)
      /*##### for outs #####*/
      val outs: mutable.Map[String, Key$[_]] = genOuts(last, mapParallelOuts, basis)
      // 后面的输出可以覆盖掉前面的useless输出, 不论值类型。
      // 但只有非并行任务才可以。并行任务见上面if分支。
      if (mapParallelOuts.isNull) {
        if (outs.nonEmpty) mapUseless.values.foreach(useless => outs.keySet.foreach(useless.remove))
        mapUseless.put(last.name$, outs)
      }
    }
    logger.i("[genIOPrev]trait:%s, inputRequired:%s, mapUseless:%s", last.name$, inputRequired, mapUseless)
  }

  /**
    * 为输出集合向前生成依赖。同{@link #genIOPrev(Trait, Map, Basis, Map, Map)}.
    */
  private def genIOuts(outs: Set[Key$[_]], basis: BasisMutable, inputRequired: mutable.Map[String, Key$[_]],
                       mapUseless: Map[String, mutable.Map[String, Key$[_]]]) {
    val requires = new mutable.AnyRefMap[String, Key$[_]]
    putAll(requires, outs)
    breakable {
      for (trat <- basis.traits.reverse /*从倒数第{一}个开始*/ ) {
        if (requires.isEmpty) break
        consumeRequiresOnTransGlobal(trat, requires, basis, check = true)
        consumeRequires(trat, null, requires, basis, mapUseless)
      }
    }
    genInputRequired(requires, inputRequired)
  }

  /**
    * @param check 是否进行类型检查(在最后trim的时候，不需要再检查一遍)。
    */
  private def consumeRequiresOnTransGlobal(prev: Trait[_], requires: mutable.Map[String, Key$[_]], basis: Basis, check: Boolean) {
    val copy = requires.values.toSet
    basis.transGlobal.get(prev.name$ /*不能是并行的，而这里必然不是*/).foreach { tranSet =>
      tranSet.foreach { t =>
        breakable {
          for (k <- copy if k.key == t.out.key) {
            // 注意这里可能存在的一个问题：有两拨不同的需求对应同一个转换key但类型不同，
            // 这里不沿用consumeRequires()中的做法(将消化掉的分存)。无妨。
            if (check) requireTypeMatch4Consume(k, t.out)
            requires.remove(k.key)
            requires.put(t.in.key, t.in)
            break
          }
        }
      }
    }
  }

  /**
    * 从`useless`里面消化掉新的`requires`, 并把{[对trans输出的消化]对应的输入}增加到`requires`.
    * <p>
    * 背景上下文：前面已经执行过消化的trait不可能因为后面的任务而取消或减少消化，只会不变或增多，因此本逻辑合理且运算量小。
    */
  private def consumeRequires(prev: Trait[_], parent: Trait.Parallel, requires: mutable.Map[String, Key$[_]],
                              basis: BasisMutable, mapUseless: Map[String, mutable.Map[String, Key$[_]]]) {
    if (prev.isParallel) {
      breakable {
        for (tt <- prev.asParallel.traits()) {
          if (requires.isEmpty) break
          consumeRequires(tt, prev.asParallel, requires, basis, mapUseless)
        }
      }
    } else {
      val outs = basis.dependencies.getOrElseUpdate(prev.name$,
        if (prev.outs$.isEmpty) mutable.Map.empty[String, Key$[_]] // 如果没有输出，那就算执行转换必然也是空的。
        else new mutable.AnyRefMap[String, Key$[_]])
      consumeRequires(prev, requires, outs, mapUseless((if (parent.isNull) prev else parent).name$))
    }
  }

  /** 注意：由于是从`useless`里面去拿，而`useless`都是已经转换过的，这符合`dependencies`的定义。 */
  private def consumeRequires(prev: Trait[_], requires: mutable.Map[String, Key$[_]],
                              outs: mutable.Map[String, Key$[_]], useless: mutable.Map[String, Key$[_]]) {
    if (prev.outs$.isEmpty) return // 根本就没有输出，就不浪费时间了。
    if (requires.isEmpty) return
    requires.values.to[Set].foreach { k =>
      outs.get(k.key).fold(
        if (useless.contains(k.key)) {
          val out = useless(k.key)
          requireTypeMatch4Consume(k, out)
          // 移入到依赖
          outs.put(out.key, out)
          useless.remove(out.key)
          requires.remove(k.key)
        }) { out =>
        requireTypeMatch4Consume(k, out)
        // 直接删除, 不用再向前面的任务要求这个输出了。
        // 而对于并行的任务, 前面已经检查过并行的任务不会有相同的输出, 后面不会再碰到这个key。
        requires.remove(k.key)
      }
    }
  }

  private def requireTypeMatch4Consume(require: Key$[_], out: Key$[_]): Unit = if (!require.isAssignableFrom(out)) Throws.typeNotMatch4Consume(out, require)

  private def genOuts(trat: Trait[_], mapPal: mutable.Map[String, Key$[_]], basis: Basis): mutable.Map[String, Key$[_]] = {
    if (trat.outs$.isEmpty) mutable.Map.empty
    else {
      val map = new mutable.AnyRefMap[String, Key$[_]]
      trat.outs$.foreach(k => map.put(k.key, k))
      // 先加入转换
      transOuts(basis.transformers(trat.name$), map)
      // 再看看有没有相同的输出
      if (mapPal.nonNull) {
        if (mapPal.nonEmpty) map.values.foreach { k =>
          if (mapPal.contains(k.key)) {
            // 并行的任务不应该有相同的输出
            Throws.sameOutKeyParallel(k, trat)
          }
        }
        mapPal ++= map
      }
      logger.i("[genOuts]trait:%s, mapPal:%s, map:%s", trat.name$, mapPal, map)
      map
    }
  }

  private def transOuts(tranSet: Set[Transformer[_, _]], map: mutable.Map[String, Key$[_]]) {
    if (tranSet.nonNull && tranSet.nonEmpty && map.nonEmpty) {
      var trans: List[Transformer[_, _]] = Nil
      var sameKey: List[Transformer[_, _]] = Nil
      tranSet.filter(t => map.contains(t.in.key)).foreach { t =>
        // 先不从map移除, 可能多个transformer使用同一个源。
        val from = map(t.in.key)
        if (!t.in.isAssignableFrom(from)) Throws.typeNotMatch4Trans(from, t.in)
        if (t.in.key.equals(t.out.key)) sameKey = t :: sameKey
        else trans = t :: trans
      }
      trans.foreach { t =>
        map.remove(t.in.key)
        map.put(t.out.key, t.out)
      }
      // 如果只有一个transformer使用同一个源，那么以上逻辑即可，
      // 但是如果多个transformer使用同一个源，则由于顺序的不确定性，相同key的transformer可能被移除。
      sameKey.foreach { t =>
        // map.remove(t.in.key)   // 不要这句，反正key是一样的，value会覆盖。
        map.put(t.out.key, t.out)
      }
    }
  }

  private def genInputRequired(requires: mutable.Map[String, Key$[_]], inputRequired: mutable.Map[String, Key$[_]]) {
    if (requires.nonEmpty) {
      requires.values.toSet.filter(k => inputRequired.contains(k.key)).foreach { k =>
        val in = inputRequired(k.key)
        if (!k.isAssignableFrom(in)) {
          // input不是require的子类, 但是require是input的子类, 那么把require存进去。
          if (in.isAssignableFrom(k)) inputRequired.put(k.key, k)
          else Throws.typeNotMatch4Required(in, k)
        }
        requires.remove(k.key)
      }
      // 初始输入里(前面任务放入的)也没有, 那么也放进去。
      inputRequired ++= requires
    }
  }

  private[reflow] def requireInputsEnough(in: In, inputRequired: Map[String, Key$[_]], trans4Input: Set[Transformer[_, _]]): Map[String, Key$[_]] = {
    val inputs = new mutable.AnyRefMap[String, Key$[_]]
    in.keys.foreach(k => inputs.put(k.key, k))
    transOuts(trans4Input, inputs)
    requireRealInEnough(inputRequired.values.toSet, inputs)
    inputs
  }

  private def requireRealInEnough(requires: Set[Key$[_]], realIn: Map[String, Key$[_]]): Unit = requires.foreach { k =>
    realIn.get(k.key).fold(Throws.lackIOKey(k, in$out = true)) { kIn =>
      if (!k.isAssignableFrom(kIn)) Throws.typeNotMatch4RealIn(kIn, k)
    }
  }

  private def trimOutsFlow(basis: Basis): Map[String, Set[Key$[_]]] = {
    val outsFlow = new mutable.AnyRefMap[String, Set[Key$[_]]]
    val trimmed = new mutable.AnyRefMap[String, Key$[_]]
    putAll(trimmed, basis.outs)
    basis.traits.reverse.foreach { trat =>
      trimOutsFlow(outsFlow, trat, basis, trimmed)
    }
    outsFlow
  }

  /**
    * 必要的输出不一定都要保留到最后，指定的输出在某个任务之后就不再被需要了，所以要进行trim。
    */
  private def trimOutsFlow(outsFlow: mutable.AnyRefMap[String, Set[Key$[_]]], trat: Trait[_], basis: Basis, trimmed: mutable.Map[String, Key$[_]]) {
    consumeRequiresOnTransGlobal(trat, trimmed, basis, check = false)
    // 注意：放在这里，存储的是globalTrans`前`的结果。
    // 如果要存储globalTrans`后`的结果，则应该放在consumeTransGlobal前边（即第1行）。
    outsFlow.put(trat.name$, trimmed.values.toSet)
    if (trat.isParallel) {
      val inputs = new mutable.AnyRefMap[String, Key$[_]]
      val outs = new mutable.AnyRefMap[String, Key$[_]]
      for (tt <- trat.asParallel.traits()) {
        // 根据Tracker实现的实际情况，弃用这行。
        // outsFlow.put(tt.name$(), flow)
        basis.dependencies.get(tt.name$).fold() {
          outs ++= _
        }
        putAll(inputs, tt.requires$)
      }
      removeAll(trimmed, outs)
      trimmed ++= inputs
    } else {
      basis.dependencies.get(trat.name$).foreach { dps =>
        removeAll(trimmed, dps)
      }
      putAll(trimmed, trat.requires$)
    }
    if (debugMode) requireKey$kDiff(trimmed.values)
  }

  /**
    * 用于运行时执行转换操作。
    *
    * @param tranSet   转换器集合。
    * @param map       输出不为`null`的值集合。
    * @param nullVKeys 输出为`null`的值的{ @link Key$}集合。
    */
  def doTransform(tranSet: Set[Transformer[_, _]], map: mutable.Map[String, Any], nullVKeys: mutable.Map[String, Key$[_]]): Unit = {
    if (tranSet.nonNull && tranSet.nonEmpty && (map.nonEmpty || nullVKeys.nonEmpty)) {
      val out: mutable.Map[String, Any] = if (map.isEmpty) mutable.Map.empty else new mutable.AnyRefMap
      val nulls = new mutable.AnyRefMap[String, Key$[_]]
      var trans: List[Transformer[_, _]] = Nil
      // 不过这里跟transOuts()的算法不同，所以不需要这个了。
      // val sameKey = new mutable.HashSet[Transformer[_]]
      tranSet.foreach { t =>
        if (map.contains(t.in.key)) {
          // 先不从map移除, 可能多个transformer使用同一个源。
          val o = t.transform(map)
          if (o.isNull) nulls.put(t.out.key, t.out)
          else out.put(t.out.key, o)
          trans = t :: trans
        } else if (nullVKeys.contains(t.in.key)) {
          nulls.put(t.out.key, t.out)
          trans = t :: trans
        }
      }
      trans.foreach { t =>
        map.remove(t.in.key)
        nullVKeys.remove(t.in.key)
      }
      if (out.nonEmpty) map ++= out
      if (nulls.nonEmpty) nullVKeys ++= nulls
    }
  }

  def copy[C <: mutable.SetLike[Trait[_], C]](src: C, dest: C): C = {
    src.foreach { trat =>
      dest += (if (trat.isParallel) new Trait.Parallel(trat.asParallel.traits()) else trat)
    }
    dest
  }

  private[reflow] def putAll[M <: mutable.Map[String, Key$[_]]](map: M, keys: Set[Key$[_]]): M = (map /: keys) {
    (m, k) =>
      m += ((k.key, k))
      m
  } /*keys.foreach(k => map.put(k.key, k)); map*/

  private[reflow] def removeAll[K](map: mutable.Map[K, _], set: Set[K]): Unit = set.foreach(map.remove)

  private[reflow] def removeAll[K, V](map: mutable.Map[K, V], src: Map[K, V]): Unit = removeAll(map, src.keySet)
}
