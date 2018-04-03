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
import hobby.chenai.nakam.tool.pool.S._2S
import hobby.wei.c.reflow.Assist.Throws
import hobby.wei.c.reflow.Dependency.{BasisMutable, IsPar, MapTo}
import hobby.wei.c.reflow.Reflow.{logger => log, _}

import scala.collection.{mutable, Set, _}
import scala.util.control.Breaks._

/**
  * 任务流通过本组件进行依赖关系组装。
  * 注意: 对本类的任何操作都应该是单线程的, 否则依赖关系是不可预期的, 没有意义。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
class Dependency private[reflow]() extends TAG.ClassName {
  private val basis = new BasisMutable
  private val names = new mutable.HashSet[String]
  // `Kce`是`transform`后的
  private val useless = new mutable.AnyRefMap[String, Map[String, Kce[_ <: AnyRef]]]
  private val inputRequired = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]

  /**
    * 给前面最后添加的任务增加并行任务。
    *
    * @param trat  新增的任务具有的特性。
    * @param trans 转换器列表。
    * @return 当前依赖组装器。
    */
  def and(trat: Trait, trans: Transformer[_ <: AnyRef, _ <: AnyRef]*): Dependency = {
    require(!trat.ensuring(_.nonNull).isPar)
    Assist.requireTaskNameDiff(trat, names)
    if (basis.traits.isEmpty) {
      basis.traits += trat
    } else {
      (basis.last(false).get match {
        case tt: Trait.Parallel => tt.traits()
        case last: Trait =>
          val parallel = new Trait.Parallel(last)
          // 注意：必须用 remove 和 +=，只有这俩是 ListBuffer 的方法，其他 Seq 方法会出现意想不到的状况。
          basis.traits.remove(basis.traits.length - 1)
          basis.traits += parallel
          parallel.traits()
      }) += trat
    }
    trans$(trans.toSet, trat)
    this
  }

  /**
    * 在前面已添加的任务之后，增加串行任务。
    *
    * @param trat 新增的任务具有的特性。
    * @return 当前依赖组装器。
    */
  def next(trat: Trait): Dependency = {
    require(!trat.ensuring(_.nonNull).isPar)
    Assist.requireTaskNameDiff(trat, names)
    basis.last(false).foreach(Dependency.genIOPrev(_, null, basis, inputRequired, useless))
    basis.traits += trat
    this
  }

  /**
    * 为前面所有任务的输出增加转换器。以便能够匹配后面任务的输入或结果的参数类型。
    * <p>
    * 注意：参数指定集合中的转换器不一定全都应用，取决于后面任务和结果的需求。
    *
    * @param trans 转换器列表。
    * @return 当前依赖组装器。
    * @see Transformer
    */
  def next(trans: Transformer[_ <: AnyRef, _ <: AnyRef]*): Dependency = next$(trans.toSet)

  /**
    * 在前面已添加的任务之后，增加已有的任务流。
    *
    * @param dependency 已定义的任务流。
    * @return 当前依赖组装器。
    */
  def next(dependency: Dependency): Dependency = {
    if (basis.traits.isEmpty) copy(dependency)
    else dependency.basis.traits.foreach { trat =>
      trat match {
        case par: Trait.Parallel =>
          var first = true
          par.traits().foreach { tt =>
            if (first) {
              next(tt).trans$(dependency.basis.transformers.getOrNull(tt.name$), tt)
              first = false
            } else {
              and(tt).trans$(dependency.basis.transformers.getOrNull(tt.name$), tt)
            }
          }
        case _ => next(trat).trans$(dependency.basis.transformers.getOrNull(trat.name$), trat)
      }
      next$(dependency.basis.transGlobal.getOrNull(trat.name$), trat)
    }
    this
  }

  /**
    * 为前面最后添加的任务增加输出转换器，以便能够匹配后面任务的输入或结果的参数类型。
    * <p>
    * 注意：本转换器仅作用于前面最后添加的任务。而且参数指定集合中的转换器不一定全都应用，取决于当前任务的输出。
    *
    * @param tranSet 转换器集合。
    * @return 当前依赖组装器。
    * @see Transformer
    */
  private def trans$(tranSet: Set[Transformer[_ <: AnyRef, _ <: AnyRef]], child: Trait = basis.last(true).get): Dependency = {
    if (tranSet.nonNull && tranSet.nonEmpty) basis.transformers.put(child.name$, Assist.requireTransInTpeSame$OutKDiff(Assist.requireElemNonNull(tranSet)))
    this
  }

  private def next$(tranSet: Set[Transformer[_ <: AnyRef, _ <: AnyRef]], top: Trait = basis.last(false).get): Dependency = {
    if (tranSet.nonNull && tranSet.nonEmpty) basis.transGlobal.put(top.name$, Assist.requireTransInTpeSame$OutKDiff(Assist.requireElemNonNull(tranSet)).to[mutable.Set])
    this
  }

  /**
    * 完成依赖的创建。
    *
    * @param outputs 输出值的key列表。
    * @return { @link Scheduler.Starter}接口。
    */
  def submit(name: String, outputs: Set[Kce[_ <: AnyRef]]): Reflow = {
    if (debugMode) log.w("[submit]")
    Assist.requireKkDiff(outputs)
    // 创建拷贝用于计算，以防污染当前对象中的原始数据。因为当前对象可能还会被继续使用。
    val uselesx = useless.mapValues(_.toMap.as[Map[String, Kce[_ <: AnyRef]]]).mutable
    val inputReqx = inputRequired.mutable
    val basisx = new BasisMutable(basis)
    Dependency.genIOPrev(basisx.last(false).get, null, basisx, inputReqx, uselesx)
    Dependency.genDeps(outputs, basisx.traits.reverse, basisx, inputReqx, uselesx)
    basisx.traits.foreach { trat =>
      if (trat.isPar) {
        trat.asPar.traits().foreach(t => basisx.dependencies.getOrElseUpdate(t.name$, mutable.Map.empty))
      } else basisx.dependencies.getOrElseUpdate(trat.name$, mutable.Map.empty)
    } // 避免空值
    // 必须先于下面transGlobal的读取。
    val trimmed = Dependency.trimOutsFlow(basisx, outputs, uselesx)
    new Reflow.Impl(name, new Dependency.Basis {
      override val traits = basisx.traits.to[immutable.Seq]
      override val dependencies = basisx.dependencies.mapValues(_.toMap).toMap
      override val transformers = basisx.transformers.mapValues(_.toSet).toMap
      override val transGlobal = basisx.transGlobal.mapValues(_.toSet).toMap
      override val outsFlowTrimmed = trimmed.mapValues(_.toSet).toMap
      override val inputs = inputReqx.values.toSet
      override val outs = outputs.toSet
    }, inputReqx.toMap)
  }

  def fork(): Dependency = Reflow.create(this)

  private def copy(dependency: Dependency): Unit = {
    dependency.names.foreach(names += _)
    basis.copyFrom(dependency.basis)
  }
}

object Dependency {
  trait Basis {
    val traits: Seq[Trait]
    /** 表示每个任务结束的时候应该为后面的任务保留哪些`Key$`(`transform`后的)。`key`为`top trat.name$`。注意：可能`get`出来为`empty`, 表示根本不用输出。 */
    val dependencies: Map[String, Map[String, Kce[_ <: AnyRef]]]
    /** 任务的输出经过转换（用不上的转换器将被忽略）, 生成最终输出传给后续任务。`key`为`top trat.name$`。注意：仅转换当前任务的输出，区别于`transGlobal`。可能`get`出来为`null`。 */
    val transformers: Map[String, Set[Transformer[_ <: AnyRef, _ <: AnyRef]]]
    /** 把截止到当前为止的全部输出作为输入的全局转换器（用不上的转换器将被忽略）。`key`为`top level trat.name$`。可能`get`出来为`null`。 */
    val transGlobal: Map[String, Set[Transformer[_ <: AnyRef, _ <: AnyRef]]]
    /** 虽然知道每个任务有哪些必要的输出, 但是整体上这些输出都要保留到最后吗? `key`为`top level trat.name$`。注意：存储的是`globalTrans`[前]的结果。 */
    val outsFlowTrimmed: immutable.Map[String, immutable.Set[Kce[_ <: AnyRef]]]
    /** 任务流需要的初始输入。不为`null`。 */
    val inputs: immutable.Set[Kce[_ <: AnyRef]]
    /** 任务流的最终输出。不为`null`。 */
    val outs: immutable.Set[Kce[_ <: AnyRef]]

    def steps() = traits.size

    def stepOf(trat: Trait): Int = {
      var step = traits.indexOf(trat)
      if (step < 0) breakable {
        for (tt <- traits)
          if (tt.isPar && tt.asPar.traits().contains(trat)) {
            step = traits.indexOf(tt)
            break
          }
      }
      step.ensuring(_ >= 0)
    }

    def traitOf(name: String): Trait = {
      var result: Trait = null
      breakable {
        for (tt <- traits)
          if (tt.name$ == name) {
            result = tt
            break
          } else if (tt.isPar) for (trat <- tt.asPar.traits() if trat.name$ == name) {
            result = trat
            break
          }
      }
      result.ensuring(_.nonNull)
    }

    def topOf(name: String): Trait = topOf(traitOf(name))

    def topOf(trat: Trait): Trait = if (trat.isPar) trat else {
      var result: Trait = null
      breakable {
        for (tt <- traits)
          if (tt == trat) {
            result = tt
            break
          } else if (tt.isPar && tt.asPar.traits().contains(trat)) {
            result = tt
            break
          }
      }
      result.ensuring(_.nonNull)
    }

    def first(child: Boolean): Option[Trait] = first$last(first$last = true, child)

    def last(child: Boolean): Option[Trait] = first$last(first$last = false, child)

    private def first$last(first$last: Boolean, child: Boolean): Option[Trait] = Option(
      if (traits.isEmpty) null
      else {
        val trat = traits.splitAt(if (first$last) 0 else traits.size - 1)._2.head
        if (child && trat.isPar) {
          if (first$last) trat.asPar.first() else trat.asPar.last()
        } else trat
      })

    def minPeriod(ts: Seq[Trait] = traits): Period.Tpe = (Period.TRANSIENT /: ts) { (l, r) =>
      import l.mkOrderingOps
      l min (if (r.isPar) minPeriod(r.asPar.traits()) else r.period$)
    }

    def maxPeriod(ts: Seq[Trait] = traits): Period.Tpe = (Period.TRANSIENT /: ts) { (l, r) =>
      import l.mkOrderingOps
      l max (if (r.isPar) maxPeriod(r.asPar.traits()) else r.period$)
    }

    def lowestPriority(ts: Seq[Trait] = traits): Int = (P_HIGH /: ts) { (l, r) =>
      l max (if (r.isPar) lowestPriority(r.asPar.traits()) else r.priority$)
    }

    def highestPriority(ts: Seq[Trait] = traits): Int = (P_LOW /: ts) { (l, r) =>
      l min (if (r.isPar) highestPriority(r.asPar.traits()) else r.priority$)
    }
  }

  class BasisMutable(basis: Basis) extends Basis {
    def this() = this(null)

    if (basis.nonNull) copyFrom(basis)

    override lazy val traits: mutable.ListBuffer[Trait] = new mutable.ListBuffer[Trait]
    override lazy val dependencies: mutable.AnyRefMap[String, mutable.Map[String, Kce[_ <: AnyRef]]] = new mutable.AnyRefMap[String, mutable.Map[String, Kce[_ <: AnyRef]]]
    override lazy val transformers: mutable.AnyRefMap[String, Set[Transformer[_ <: AnyRef, _ <: AnyRef]]] = new mutable.AnyRefMap[String, Set[Transformer[_ <: AnyRef, _ <: AnyRef]]]
    override lazy val transGlobal: mutable.AnyRefMap[String, mutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]]] = new mutable.AnyRefMap[String, mutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]]]
    override val outsFlowTrimmed = null
    override val inputs = null
    override val outs = null

    def copyFrom(src: Basis): Unit = {
      src.traits.foreach(traits += _)
      src.dependencies.foreach { kv: (String, Map[String, Kce[_ <: AnyRef]]) => dependencies.put(kv._1, kv._2.mutable) }
      src.transformers.foreach { kv: (String, Set[Transformer[_ <: AnyRef, _ <: AnyRef]]) => transformers.put(kv._1, kv._2.toSet) }
      src.transGlobal.foreach { kv: (String, Set[Transformer[_ <: AnyRef, _ <: AnyRef]]) => transGlobal.put(kv._1, kv._2.to[mutable.HashSet]) }
    }
  }

  implicit class MapTo[K <: AnyRef, V](map: Map[K, V]) {
    def mutable = new scala.collection.mutable.AnyRefMap[K, V] ++= map

    def concurrent = new scala.collection.concurrent.TrieMap[K, V] ++= map
  }

  implicit class SetTo[T](set: Set[T]) {
    def mutable = new scala.collection.mutable.HashSet[T] ++= set
  }

  implicit class IsPar(trat: Trait) {
    def isPar: Boolean = trat.isInstanceOf[Trait.Parallel]

    def asPar: Trait.Parallel = trat.as[Trait.Parallel]
  }

  /**
    * 处理最后一个`Trait`。会做两件事：
    * a. 生成向前的依赖。从最后一个`Trait`的前一个开始，根据{`Trait.requires` 必须输入}是否在`requires`或`useless`的输出（事件b）集合中，逐一匹配
    * `Kce.key`并检查{`Kce.isAssignableFrom(Kce)` 值类型}是否符合赋值关系，最后将符合条件的标记为`requires`，若不符合条件，则直接抛出异常；
    * b. 生成向后的输出。该输出会首先标记为`useless`，只有当需要的时候（事件a）才会取出并标记为`requires`。最终的`useless`将会被丢弃。
    * <p>
    * 注意：本方法会让并行的各任务先执行`transition(Set)`进行输出转换，以免在事件b中检查出相同的输出。
    */
  private def genIOPrev(last: Trait, mapParallelOuts: mutable.Map[String, Kce[_ <: AnyRef]], basis: BasisMutable,
                        inputRequired: mutable.Map[String, Kce[_ <: AnyRef]], mapUseless: mutable.Map[String, Map[String, Kce[_ <: AnyRef]]])
                       (implicit logTag: LogTag) {
    if (last.isPar) {
      val outsPal = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
      for (tt <- last.asPar.traits()) {
        genIOPrev(tt, outsPal, basis, inputRequired, mapUseless)
      }
      // 该消化的都已经消化了，剩下的可以删除，同时也等同于后面的覆盖了前面相同的`Kce.key`。
      // 所有的useless都不删除，为了transGlobal。
      // if (outsPal.nonEmpty) mapUseless.values.foreach(useless => outsPal.keySet.foreach(useless.-=))
      mapUseless.put(last.name$, outsPal)
    } else {
      /*##### for requires #####*/
      val requires = genDeps(last.requires$, basis.traits.reverse.tail /*从倒数第{二}个开始*/ , basis, inputRequired, mapUseless)
      // 前面的所有输出都没有满足, 那么看看初始输入。
      genInputRequired(requires, inputRequired)
      /*##### for outs #####*/
      val outs: mutable.Map[String, Kce[_ <: AnyRef]] = genOuts(last, mapParallelOuts, basis)
      // 后面的输出可以覆盖掉前面的useless输出, 不论值类型。
      // 在为并行任务确定依赖的时候，如果从parent中取值，会导致requires都被绑定到并行的第一个任务的错误。
      // if (mapParallelOuts.isNull) {
      // 所有的useless都不删除，为了transGlobal。
      // if (outs.nonEmpty) mapUseless.values.foreach(useless => outs.keySet.foreach(useless.-=))
      mapUseless.put(last.name$, outs)
      // }
    }
    if (debugMode) log.i("[genIOPrev]trait:%s, inputRequired:%s, mapUseless:%s.", last.name$.s, inputRequired, mapUseless)
  }

  /**
    * 根据最终的输出需求，向前生成依赖。同`genIOPrev()`。
    */
  private def genDeps(req: Set[Kce[_ <: AnyRef]], seq: Seq[Trait], basis: BasisMutable, inputRequired: mutable.Map[String, Kce[_ <: AnyRef]],
                      mapUseless: Map[String, Map[String, Kce[_ <: AnyRef]]])(implicit logTag: LogTag): mutable.Map[String, Kce[_ <: AnyRef]] = {
    val requires = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
    putAll(requires, req)
    breakable {
      seq.foreach { trat =>
        if (requires.isEmpty) break
        // 把符合requires需求的globalTrans输出对应的输入放进requires.
        consumeRequiresOnTransGlobal(trat, requires, basis, mapUseless(trat.name$))
        // 消化在计算输出(genOuts())的前面，是否不合理？注意输出的计算仅一次，
        // 而且是为了下一次的消化服务的。如果把输出放在前面，自己的输出会误被自己消化掉。
        consumeRequires(trat, null /*此处总是null*/ , requires, basis, mapUseless)
      }
    }
    genInputRequired(requires, inputRequired)
    if (debugMode) log.i("[genDeps]basis.dependencies:%s.", basis.dependencies)
    requires
  }

  /**
    * 必须单独放到最后计算，因为最后的输出需求的不同，决定了前面所有步骤的各自输出也可能不同。
    */
  private def trimOutsFlow(basis: BasisMutable, outputs: Set[Kce[_ <: AnyRef]],
                           mapUseless: Map[String, Map[String, Kce[_ <: AnyRef]]])(implicit logTag: LogTag): Map[String, Set[Kce[_ <: AnyRef]]] = {
    val outsFlow = new mutable.AnyRefMap[String, Set[Kce[_ <: AnyRef]]]
    val trimmed = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
    putAll(trimmed, outputs)
    basis.traits.reverse.foreach(trimOutsFlow(_, basis, outsFlow, trimmed, mapUseless))
    if (debugMode) log.i("[trimOutsFlow]outsFlow:%s, globalTrans:%s.", outsFlow, basis.transGlobal)
    outsFlow
  }

  /**
    * 必要的输出不一定都要保留到最后，指定的输出在某个任务之后就不再被需要了，所以要进行`trim`。
    */
  private def trimOutsFlow(trat: Trait, basis: BasisMutable, outsFlow: mutable.AnyRefMap[String, Set[Kce[_ <: AnyRef]]],
                           trimmed: mutable.Map[String, Kce[_ <: AnyRef]], mapUseless: Map[String, Map[String, Kce[_ <: AnyRef]]])(implicit logTag: LogTag) {
    consumeRequiresOnTransGlobal(trat, trimmed, basis, mapUseless(trat.name$), check = false, trim = true)
    // 注意：放在这里，存储的是globalTrans`前`的结果。
    // 如果要存储globalTrans`后`的结果，则应该放在consumeTransGlobal前边（即第1行）。
    outsFlow.put(trat.name$, trimmed.values.toSet)
    if (trat.isPar) {
      val inputs = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
      val outs = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
      for (tt <- trat.asPar.traits()) {
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
  }

  /**
    * 消化全局的转换器。这个跟任务的局部转换器在处理顺序和方式上大有不同。
    *
    * @param check 是否进行类型匹配检查（在最后`trim`的时候，不需要再检查一遍）。
    * @param trim  是否删除多余的全局转换（仅在`trim`阶段进行）。
    */
  private def consumeRequiresOnTransGlobal(prev: Trait, requires: mutable.Map[String, Kce[_ <: AnyRef]], basis: BasisMutable,
                                           prevOuts: Map[String, Kce[_ <: AnyRef]], check: Boolean = true, trim: Boolean = false)(implicit logTag: LogTag) {
    val transSet = basis.transGlobal.getOrElse(prev.name$, mutable.Set.empty)
    if (transSet.nonEmpty) {
      consumeTranSet(transSet, requires, prevOuts, check, trim)
      if (transSet.isEmpty) basis.transGlobal.remove(prev.name$) // 定义了用null表示空。
    }
  }

  /*
   * 一、如果不自动将`Transformer`的输入保留到输出集合，要解决的问题：
   * 1. 如果`requires`正好需要一个与`Transformer`的输入相同的输出，而又缺少一个[输入即输出]的转换，会导致消化检测阶段通过，但运行时出现缺少某输出的错误；
   * 2. 客户代码需要增加看上去多此一举的[输入即输出]转换（不自动增加的话）；
   * 二、如果自动保留（与`一`相反），要解决的问题：
   * 1. 在现有数据结构状况下，在运行时，无法知晓是否应该保留哪些`Transformer`的输入到输出集合。因为很多输入是因为转换的需求才加入的，而不一定总被后面的任务需要；
   * 2. 与任务的局部转换功能设计相冲突：局部转换的目的之一，是为了避免并行任务的输出`key`冲突，因此不应该自动保留；
   * 3. 如果全部保留，会导致不再需要的数据淤积，与设计初衷相悖（即使把局部和全局转换的运行时执行区分开，也无法解决数据淤积问题，即使淤积仅仅占用下一任务的时间）。
   * 最终方案：自动增加[输入即输出]转换（即：`retain`功能的`Transformer`）。
   */
  private[reflow] def consumeTranSet(tranSet: mutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]], requires: mutable.Map[String, Kce[_ <: AnyRef]],
                                     prevOuts: Map[String, Kce[_ <: AnyRef]], check: Boolean = true, trim: Boolean = false)(implicit logTag: LogTag) {
    var trans: List[Transformer[_ <: AnyRef, _ <: AnyRef]] = Nil
    var retains: List[Transformer[_ <: AnyRef, _ <: AnyRef]] = Nil
    var ignore: List[String] = Nil
    // 如果前一个的输出正好匹配需求，那么忽略与之相应的转换。
    if (prevOuts.nonEmpty) requires.toMap /*clone*/ .foreach { req =>
      if (prevOuts.contains(req._1) && req._2.isAssignableFrom(prevOuts(req._1))) ignore = req._1 :: ignore
    }
    if (debugMode) log.i("[consumeTranSet]ignore:%s.", ignore.mkString(",").s)
    breakable {
      tranSet.foreach { t =>
        if (requires.isEmpty) break
        if (!ignore.contains(t.out.key)) requires.get(t.out.key).foreach { k =>
          if (debugMode && check) requireTypeMatch4Consume(k, t.out)
          requires.remove(k.key)
          trans = t :: trans
        }
      }
      // 已经被应用的转换器的输入，也是消化`requires`的一大资源。这些资源将赤裸裸的输出，需要检查类型匹配。
      // 就算可以给未消化掉的加入到向前申请列表，但由于`key`的相同，有冲突。所以必须检查类型匹配。
      trans.foreach { t =>
        if (requires.isEmpty) break
        requires.get(t.in /*注意这里不一样*/ .key).foreach { k =>
          if (debugMode && check) requireTypeMatch4Consume(k, t.in)
          requires.remove(k.key)
          retains = Helper.Transformers.retain(t.in) :: retains
        }
      }
    }
    // 在构造的时候已经验证过`tranSet`：输入中相同的`in.key`有相同的`type`，而输出中的`out.key`各不相同。
    trans.map(t => requires.put(t.in.key, t.in))
    // 用不到的全局转换器一定要删除，否则它们可能会去消化某些本不需要转换的输入资源。
    // 不过：在构建任务流阶段的时候，不能删除；必须在`trim`之后再删除。
    if (trim) {
      tranSet.clear()
      tranSet ++= trans ++= retains
    }
    if (debugMode) log.i("[consumeTranSet]tranSet:%s.", tranSet)
  }

  /**
    * 从`useless`里面消化掉新的`requires`, 并把{[对`trans`输出的消化]对应的输入}增加到`requires`.
    * <p>
    * 背景上下文：前面已经执行过消化的`trait`不可能因为后面的任务而取消或减少消化，只会不变或增多，因此本逻辑合理且运算量小。
    */
  private def consumeRequires(prev: Trait, parent: Trait.Parallel, requires: mutable.Map[String, Kce[_ <: AnyRef]],
                              basis: BasisMutable, mapUseless: Map[String, Map[String, Kce[_ <: AnyRef]]]) {
    if (prev.isPar) {
      breakable {
        for (tt <- prev.asPar.traits()) {
          if (requires.isEmpty) break
          consumeRequires(tt, prev.asPar, requires, basis, mapUseless)
        }
      }
    } else {
      val outs = basis.dependencies.getOrElseUpdate(prev.name$,
        if (prev.outs$.isEmpty) mutable.Map.empty // 如果没有输出，那就算执行转换必然也是空的。
        else new mutable.AnyRefMap)
      // 不可以使用parent，否则会导致requires都被绑定到并行的第一个任务的错误。
      consumeRequires(prev, requires, outs, mapUseless(/*(if (parent.isNull) */ prev /* else parent)*/ .name$))
    }
  }

  /**
    * 注意：由于是从`useless`里面去拿，而`useless`都是已经转换过的，这符合`dependencies`的定义。
    */
  private def consumeRequires(prev: Trait, requires: mutable.Map[String, Kce[_ <: AnyRef]],
                              outs: mutable.Map[String, Kce[_ <: AnyRef]], useless: Map[String, Kce[_ <: AnyRef]]) {
    if (prev.outs$.isEmpty) return // 根本就没有输出，就不浪费时间了。
    if (requires.isEmpty) return
    requires.values.foreach { k =>
      outs.get(k.key).fold(
        if (useless.contains(k.key)) {
          val out = useless(k.key)
          requireTypeMatch4Consume(k, out)
          // 移入到依赖
          outs.put(out.key, out)
          // 所有的useless都不删除，为了transGlobal。
          // useless.remove(out.key)
          requires.remove(k.key)
        }) { out =>
        requireTypeMatch4Consume(k, out)
        // 直接删除, 不用再向前面的任务要求这个输出了。
        // 而对于并行的任务, 前面已经检查过并行的任务不会有相同的输出, 后面不会再碰到这个key。
        requires.remove(k.key)
      }
    }
  }

  private def requireTypeMatch4Consume(require: Kce[_ <: AnyRef], out: Kce[_ <: AnyRef]): Unit = if (debugMode &&
    !require.isAssignableFrom(out)) Throws.typeNotMatch4Consume(out, require)

  private def genOuts(trat: Trait, mapPal: mutable.Map[String, Kce[_ <: AnyRef]], basis: BasisMutable)
                     (implicit logTag: LogTag): mutable.Map[String, Kce[_ <: AnyRef]] = {
    if (trat.outs$.isEmpty) mutable.Map.empty
    else {
      val map = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
      trat.outs$.foreach(k => map.put(k.key, k))
      // 先加入转换
      transOuts(basis.transformers.getOrNull(trat.name$), map)
      // 再看看有没有相同的输出
      if (mapPal.nonNull) {
        if (debugMode) {
          if (mapPal.nonEmpty) map.values.foreach { k =>
            if (mapPal.contains(k.key)) {
              // 并行的任务不应该有相同的输出
              Throws.sameOutKeyParallel(k, trat)
            }
          }
        }
        mapPal ++= map
      }
      if (debugMode) log.i("[genOuts]trait:%s, mapPal:%s, map:%s.", trat.name$.s, mapPal, map)
      map
    }
  }

  private def transOuts(tranSet: Set[Transformer[_ <: AnyRef, _ <: AnyRef]], map: mutable.Map[String, Kce[_ <: AnyRef]]) {
    if (tranSet.nonNull && tranSet.nonEmpty && map.nonEmpty) {
      var trans: List[Transformer[_ <: AnyRef, _ <: AnyRef]] = Nil
      var sameKey: List[Transformer[_ <: AnyRef, _ <: AnyRef]] = Nil
      tranSet.filter(t => map.contains(t.in.key)).foreach { t =>
        // 先不从map移除, 可能多个transformer使用同一个源。
        val from = map(t.in.key)
        if (debugMode && !t.in.isAssignableFrom(from)) Throws.typeNotMatch4Trans(from, t.in)
        if (t.in.key == t.out.key) sameKey = t :: sameKey
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

  private def genInputRequired(requires: mutable.Map[String, Kce[_ <: AnyRef]], inputRequired: mutable.Map[String, Kce[_ <: AnyRef]]) {
    if (requires.nonEmpty) {
      requires.values.toSet.filter(k => inputRequired.contains(k.key)).foreach { k =>
        val in = inputRequired(k.key)
        if (debugMode) {
          if (!k.isAssignableFrom(in)) {
            // input不是require的子类, 但是require是input的子类, 那么把require存进去。
            if (in.isAssignableFrom(k)) inputRequired.put(k.key, k)
            else Throws.typeNotMatch4Required(in, k)
          }
        } else inputRequired.put(k.key, k)
        requires.remove(k.key)
      }
      // 初始输入里(前面任务放入的)也没有, 那么也放进去。
      inputRequired ++= requires
    }
  }

  /**
    * 检测输入参数是否足够。
    *
    * @param in            启动任务流的时候构造的输入参数。
    * @param inputRequired 任务流提交时生成的必须输入参数。
    * @return 输入对象`in`运行后的真实输出结果类型。
    */
  private[reflow] def requireInputsEnough(in: In, inputRequired: Map[String, Kce[_ <: AnyRef]]): Map[String, Kce[_ <: AnyRef]] = {
    val inputs = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
    putAll(inputs, in.keys)
    transOuts(in.trans, inputs)
    requireRealInEnough(inputRequired.values.toSet, inputs)
    inputs
  }

  private[reflow] def requireRealInEnough(requires: Set[Kce[_ <: AnyRef]], realIn: Map[String, Kce[_ <: AnyRef]]): Unit = if (debugMode) requires.foreach { k =>
    realIn.get(k.key).fold(Throws.lackIOKey(k, in$out = true)) { kIn =>
      if (!k.isAssignableFrom(kIn)) Throws.typeNotMatch4RealIn(kIn, k)
    }
  }

  /**
    * 用于运行时执行转换操作。注意：本方法已经忽略了用不上的`Transformer`。
    *
    * @param tranSet   转换器集合。本方法执行完成后，仅包含被应用的`Transformer`集合（因为有些可能用不上）。
    * @param map       输出不为`null`的值集合。
    * @param nullVKeys 输出为`null`的值的`Key$`集合。
    * @param prefer    输出所关心的`Key$`s。可不传参，表示应用所有转换。注意：如果传`empty`集合，将导致[不会]应用任何转换。
    *                  <p>
    *                  本参数的存在源于两种的不同的需求：
    *                  1. 输入是精简后的，即：已有的输入都应当尽可能的去作转换（依赖构建的时候已检测通过）。这种情况不需要传本参数；
    *                  2. 已知输出是精简后的，但输入有可能冗余。这种情况应尽量传本参数，以便减少运算。
    */
  def doTransform(tranSet: mutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]], map: mutable.Map[String, Any], nullVKeys: mutable.Map[String, Kce[_ <: AnyRef]],
                  prefer: Map[String, Kce[_ <: AnyRef]] = null): Unit = {
    if (tranSet.nonEmpty && (map.nonEmpty || nullVKeys.nonEmpty)) {
      val out: mutable.Map[String, Any] = if (map.isEmpty) mutable.Map.empty else new mutable.AnyRefMap
      val nulls = new mutable.AnyRefMap[String, Kce[_ <: AnyRef]]
      var trans: List[Transformer[_ <: AnyRef, _ <: AnyRef]] = Nil
      // 不过这里跟transOuts()的算法不同，所以不需要这个了。
      // val sameKey = new mutable.HashSet[Transformer[_]]
      tranSet.filter(t => if (prefer.isNull) true else prefer.contains(t.out.key)).foreach { t =>
        if (map.contains(t.in.key)) {
          // 先不从map移除, 可能多个transformer使用同一个源。
          t.transform(map).fold[Unit](nulls.put(t.out.key, t.out))(out.put(t.out.key, _))
          trans = t :: trans
        } else if (nullVKeys.contains(t.in.key)) {
          t.transform(None).fold[Unit](nulls.put(t.out.key, t.out))(out.put(t.out.key, _))
          trans = t :: trans
        }
      }
      trans.foreach { t =>
        map.remove(t.in.key)
        nullVKeys.remove(t.in.key)
      }
      if (out.nonEmpty) map ++= out
      if (nulls.nonEmpty) nullVKeys ++= nulls
      tranSet.clear()
      tranSet ++= trans
    } else tranSet.clear()
  }

  private[reflow] def putAll[M <: mutable.Map[String, Kce[_ <: AnyRef]]](map: M, keys: Set[Kce[_ <: AnyRef]]): M = (map /: keys) {
    (m, k) => m += ((k.key, k))
  } /*keys.foreach(k => map.put(k.key, k)); map*/

  private[reflow] def removeAll[K](map: mutable.Map[K, _], set: Set[K]): Unit = set.foreach(map.remove)

  private[reflow] def removeAll[K, V](map: mutable.Map[K, V], src: Map[K, V]): Unit = removeAll(map, src.keySet)
}
