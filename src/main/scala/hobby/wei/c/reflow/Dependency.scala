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

import scala.collection._
import scala.util.control.Breaks._

/**
 * 任务流通过本组件进行依赖关系组装。
 * 注意: 对本类的任何操作都应该是单线程的, 否则依赖关系是不可预期的, 没有意义。
 *
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 02/07/2016
 */
class Dependency private[reflow]() extends TAG.ClassName {
  class Basis(val traits: Seq[Trait[_]],
              /* 表示每个任务结束的时候应该为后面的任务保留哪些Key$(transform后的)。
              注意：可能get出来为null, 表示根本不用输出。 */
              val dependencies: Map[String, Map[String, Key$[_]]],
              /* 任务的输出经过转换, 生成最终输出传给下一个任务。 */
              val transformers: Map[String, Set[Transformer[_]]],
              /* 可把前面任意任务的输出作为输入的全局转换器。 */
              val transGlobal: Map[String, Set[Transformer[_]]],
              /* 虽然知道每个任务有哪些必要的输出, 但是整体上这些输出都要保留到最后吗? */
              val outsFlowTrimmed: Map[String, Set[Key$[_]]],
              val outs: Set[Key$[_]]) {

    def steps() = traits.size

    def stepOf(trat: Trait[_]): Int = {
      var step = traits.indexOf(trat)
      breakable {
        if (step < 0) for (tt <- traits)
          if (tt.isInstanceOf[Trait.Parallel] && tt.as[Trait.Parallel].traits.indexOf(trat) >= 0) {
            step = traits.indexOf(tt)
            assertx(step >= 0)
            break
          }
      }
      step
    }

    def first(child: Boolean): Option[Trait[_]] = first$last(true, child)

    def last(child: Boolean): Option[Trait[_]] = first$last(false, child)

    private def first$last(first$last: Boolean, child: Boolean): Option[Trait[_]] = {
      Option(if (traits.isEmpty) null
      else {
        val trat = traits.splitAt(if (first$last) 0 else traits.size - 1)._2.head
        if (child && trat.isInstanceOf[Trait.Parallel]) {
          if (first$last) trat.as[Trait.Parallel].first() else trat.as[Trait.Parallel].last()
        } else trat
      })
    }
  }

  private val basis = new Basis(null,
    new mutable.HashMap[String, Map[String, Key$[_]]],
    new mutable.HashMap[String, Set[Transformer[_]]],
    new mutable.HashMap[String, Set[Transformer[_]]],
    null, null) {
    // 重写类型，以方便操作。
    override val traits: mutable.ArrayBuffer[Trait[_]] = new mutable.ArrayBuffer[Trait[_]]
  }
  private val names = new mutable.HashSet[String]
  // Key$是transform后的
  private val useless = new mutable.HashMap[String, Map[String, Key$[_]]]
  private val inputRequired = new mutable.HashMap[String, Key$[_]]

  implicit class IsPar(trat:Trait[_]) {
   def isParallel:Boolean = trat.isInstanceOf[Trait.Parallel]
  }

  /**
    * 给前面最后添加的任务增加并行任务。
    *
    * @param trat 新增的任务具有的特性。
    * @return 当前依赖组装器。
    */
  def and(trat: Trait[_]): Dependency = {
    require(!trat.ensuring(_.nonNull).isParallel)
    requireTaskNameDifferent(trat, names)
    if (basis.traits.isEmpty) {
      basis.traits += trat
    } else {
      basis.last(false) match {
        case tt: Trait.Parallel => tt.traits()
        case last =>
          val parallel = new Trait.Parallel(last)
          // 注意：必须用 remove 和 +=，只有这俩是 ArrayBuffer 的方法，其他 Seq 方法会出现意想不到的状况。
          basis.traits.remove(basis.traits.length - 1)
          basis.traits += parallel
          parallel.traits()
      }
      .add(trat)
    }
    this
  }

  /**
    * 在前面已添加的任务之后，增加串行任务。
    *
    * @param trat 新增的任务具有的特性。
    * @return 当前依赖组装器。
    */
  def then(trat: Trait[_]): Dependency = {
    require(!trat.ensuring(_.nonNull).isParallel)
    requireTaskNameDifferent(trat, names)
    val last = basis.last(false)
    if (last.nonNull) genIOPrev(last, null, basis, inputRequired, useless)
    basis.traits += trat
    this
  }

  /**
    * 在前面已添加的任务之后，增加已有的任务流。
    *
    * @param dependency 已定义的任务流。
    * @return 当前依赖组装器。
    */
  def then(dependency: Dependency): Dependency = {
    if (basis.traits.isEmpty) copy(dependency)
    else for (trat <- dependency.basis.traits) {
      trat match {
        case trat: Trait.Parallel =>
          var first = true
          for (tt <- trat.traits()) {
            if (first) {
              then(tt).transition$(dependency.basis.transformers.get(tt.name$()), false)
              first = false
            } else {
              and(tt).transition$(dependency.basis.transformers.get(tt.name$()), false)
            }
          }
        case _ => then(trat).transition$(dependency.basis.transformers.get(trat.name$()), false)
      }
      then$(dependency.basis.transGlobal.get(trat.name$()), false)
    }
    this
  }

    /**
     * @see #transition(Set)
     */
    def transition(trans: Transformer[_]):Dependency = transition(Set(trans))

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
    def transition(tranSet: Set[Transformer[_]]):Dependency = transition$(tranSet, true)

    private Dependency transition$(Set<Transformer> tranSet, boolean check) {
        if (check || tranSet != null) if (!requireNonNull(tranSet).isEmpty())
            basis.transformers.put(basis.last(true).name$(),
                    requireTransInTypeSame(requireNonNullElem(tranSet)));
        return this;
    }

    /**
     * @see #then(Set)
     */
    public Dependency then(Transformer trans) {
        return then(Collections.singleton(trans));
    }

    /**
     * 为前面所有任务的输出增加转换器。以便能够匹配后面任务的输入或结果的参数类型。
     * <p>
     * 注意：参数指定集合中的转换器不一定全都应用，取决于后面任务和结果的需求。
     *
     * @param tranSet 转换器集合。
     * @return 当前依赖组装器。
     * @see Transformer
     */
    public Dependency then(Set<Transformer> tranSet) {
        return then$(tranSet, true);
    }

    private Dependency then$(Set<Transformer> tranSet, boolean check) {
        if (check || tranSet != null) if (!requireNonNull(tranSet).isEmpty())
            basis.transGlobal.put(basis.last(false).name$(),
                    requireTransInTypeSame(requireNonNullElem(tranSet)));
        return this;
    }

    /**
     * 完成依赖的创建。
     *
     * @param outs 输出值的key列表。
     * @return {@link Scheduler.Starter}接口。
     */
    public Scheduler.Starter submit(Set<Key$> outs) {
        requireKey$kDiff(outs);
        final Basis basis = copy(this.basis, new Basis());
        final Map<String, Map<String, Key$>> useless = copy(this.useless, new HashMap<String, Map<String, Key$>>(), sCpMap);
        final Map<String, Key$> inputRequired = copy(this.inputRequired, new HashMap<String, Key$>(), sCpKey$);
        genIOPrev(basis.last(false), null, basis, inputRequired, useless);
        genIOuts(outs, basis, inputRequired, useless);
        basis.outs = new HashSet<>(outs);
        trimOutsFlow(basis);
        return new Scheduler.Starter.Impl(basis, inputRequired);
    }

    public Dependency fork() {
        return Reflow.create(this);
    }

    private void copy(Dependency dependency) {
        names.addAll(dependency.names);
        copy(dependency.basis, basis);
        copy(dependency.useless, useless, sCpMap);
        copy(dependency.inputRequired, inputRequired, sCpKey$);
    }

    private static Basis copy(Basis src, Basis dest) {
        copy(src.traits, dest.traits);
        copy(src.dependencies, dest.dependencies, sCpMap);
        copy(src.transformers, dest.transformers, sCpSet);
        copy(src.transGlobal, dest.transGlobal, sCpSet);
        return dest;
    }

    /**
     * @param trait 前面的一个串联的任务。意味着如果是并联的任务，则应该用parent.
     * @return 串中唯一的名称。
     */
    private static String nameGlobal(Trait trait) {
        return trait.name$() + trait.hashCode();
    }

    /**
     * 处理最后一个{@link Trait}. 会做两件事：
     * a. 生成向前的依赖；从最后一个{@link Trait}的前一个开始，根据{@link Trait#requires$() 必须输入}在<code>
     * requires</code>或<code>useless</code>的输出（事件b）集合中，逐一匹配{@link Key$#key
     * key}并检查{@link Key$#isAssignableFrom(Key$) 值类型}是否符合赋值关系，最后将符合条件的标记为<code>
     * requires</code>, 若不符合条件，则直接抛出异常；
     * b. 生成向后的输出。该输出会首先标记为<code>useless</code>, 只有当需要的时候（事件a）才会取出并标记为<code>
     * requires</code>. 最终的<code>useless</code>将会被丢去。
     * <p>
     * 注意: 本方法会让并行的任务先执行{@link #transition(Set)}进行输出转换, 以免在事件b中检查出相同的输出。
     */
    private static void genIOPrev(Trait<?> last, Map<String, Key$> mapParallelOuts, Basis basis,
                                  Map<String, Key$> inputRequired, Map<String, Map<String, Key$>> mapUseless) {
        if (last instanceof Trait.Parallel) {
            final Map<String, Key$> outsPal = new HashMap<>();
            for (Trait<?> tt : ((Trait.Parallel) last).traits()) {
                genIOPrev(tt, outsPal, basis, inputRequired, mapUseless);
            }
            if (!outsPal.isEmpty()) {
                for (Map<String, Key$> useless : mapUseless.values()) {
                    // outsPal.keySet().forEach(useless::remove);
                    for (String k : outsPal.keySet()) {
                        useless.remove(k);
                    }
                }
            }
            mapUseless.put(last.name$(), outsPal);
        } else {
            /*##### for requires #####*/
            final Map<String, Key$> requires = new HashMap<>();
            putAll(requires, last.requires$());
            for (int i = basis.traits.size() - 2/*从倒数第二个开始*/; i >= 0; i--) {
                if (requires.isEmpty()) break;
                final Trait trait = basis.traits.get(i);
                // 把符合requires需求的globalTrans输出对应的输入放进requires.
                consumeRequiresOnTransGlobal(trait, requires, basis, true);
                // 消化在计算输出(genOuts())的前面，是否不合理？注意输出的计算仅一次，
                // 而且是为了下一次的消化服务的。如果把输出放在前面，自己的输出会误被自己消化掉。
                consumeRequires(trait, null/*此处总是null*/, requires, basis, mapUseless);
            }
            // 前面的所有输出都没有满足, 那么看看初始输入。
            genInputRequired(requires, inputRequired);
            /*##### for outs #####*/
            final Map<String, Key$> outs = genOuts(last, mapParallelOuts, basis);
            // 后面的输出可以覆盖掉前面的useless输出, 不论值类型。
            // 但只有非并行任务才可以。并行任务见上面if分支。
            if (mapParallelOuts == null) {
                if (!outs.isEmpty()) {
                    for (Map<String, Key$> useless : mapUseless.values()) {
                        //outs.keySet().forEach(useless::remove);
                        for (String k : outs.keySet()) {
                            useless.remove(k);
                        }
                    }
                }
                mapUseless.put(last.name$(), outs);
            }
        }
        logger.i(TAG, "genIOPrev", "trait:%s, inputRequired:%s, mapUseless:%s", last.name$(), inputRequired, mapUseless);
    }

    /**
     * 为输出集合向前生成依赖。同{@link #genIOPrev(Trait, Map, Basis, Map, Map)}.
     */
    private static void genIOuts(Set<Key$> outs, Basis basis, Map<String, Key$> inputRequired,
                                 Map<String, Map<String, Key$>> mapUseless) {
        final Map<String, Key$> requires = new HashMap<>();
        putAll(requires, outs);
        for (int i = basis.traits.size() - 1/*从倒数第{一}个开始*/; i >= 0; i--) {
            if (requires.isEmpty()) break;
            final Trait trait = basis.traits.get(i);
            consumeRequiresOnTransGlobal(trait, requires, basis, true);
            consumeRequires(trait, null, requires, basis, mapUseless);
        }
        genInputRequired(requires, inputRequired);
    }

    /**
     * @param check 是否进行类型检查(在最后trim的时候，不需要再检查一遍)。
     */
    private static void consumeRequiresOnTransGlobal(Trait<?> prev, Map<String, Key$> requires, Basis basis, boolean check) {
        final Set<Transformer> tranSet = basis.transGlobal.get(nameGlobal(prev/*不能是并行的，而这里必然不是*/));
        final Map<String, Key$> copy = new HashMap<>(requires);
        for (Transformer t : tranSet) {
            for (Key$ k : copy.values())
                if (k.key.equals(t.out.key)) {
                    // 注意这里可能存在的一个问题：有两拨不同的需求对应同一个转换key但类型不同，
                    // 这里不沿用consumeRequires()中的做法(将消化掉的分存)。无妨。
                    if (check) requireTypeMatch4Consume(k, t.out);
                    requires.remove(k.key);
                    requires.put(t.in.key, t.in);
                    break;
                }
        }
    }

    /**
     * 从<code>useless</code>里面消化掉新的<code>requires</code>, 并把{[对trans输出的消化]对应的输入}增加到<code>requires</code>.
     * <p>
     * 背景上下文：前面已经执行过消化的trait不可能因为后面的任务而取消或减少消化，只会不变或增多，因此本逻辑合理且运算量小。
     */
    private static void consumeRequires(Trait<?> prev, Trait.Parallel parent, Map<String, Key$> requires,
                                        Basis basis, Map<String, Map<String, Key$>> mapUseless) {
        if (prev instanceof Trait.Parallel) {
            for (Trait<?> tt : ((Trait.Parallel) prev).traits()) {
                if (requires.isEmpty()) break;
                consumeRequires(tt, (Trait.Parallel) prev, requires, basis, mapUseless);
            }
        } else {
            Map<String, Key$> outs = basis.dependencies.get(prev.name$());
            if (outs == null) {
                outs = prev.outs$().isEmpty() ? Collections.<String, Key$>emptyMap() : new HashMap<String, Key$>();
            }
            consumeRequires(prev, requires, outs, mapUseless.get((parent == null ? prev : parent).name$()));
        }
    }

    private static void consumeRequires(Trait<?> prev, Map<String, Key$> requires,
                                        Map<String, Key$> outs, Map<String, Key$> useless) {
        if (prev.outs$().isEmpty()) return; // 根本就没有输出，就不浪费时间了。
        if (requires.isEmpty()) return;
        for (Key$ k : new HashSet<>(requires.values())) {
            Key$ out = outs.get(k.key);
            if (out != null) {
                requireTypeMatch4Consume(k, out);
                // 直接删除, 不用再向前面的任务要求这个输出了。
                // 而对于并行的任务, 前面已经检查过并行的任务不会有相同的输出, 后面不会再碰到这个key.
                requires.remove(k.key);
            } else if (useless.containsKey(k.key)) {
                out = useless.get(k.key);
                requireTypeMatch4Consume(k, out);
                // 移入到依赖
                outs.put(out.key, out);
                useless.remove(out.key);
                requires.remove(k.key);
            }
        }
    }

    private static void requireTypeMatch4Consume(Key$ require, Key$ out) {
        if (!require.isAssignableFrom(out)) {
            Throws.typeNotMatch4Consume(out, require);
        }
    }

    private static Map<String, Key$> genOuts(Trait<?> trait, Map<String, Key$> mapPal, Basis basis) {
        if (trait.outs$().isEmpty()) return Collections.emptyMap();
        final Map<String, Key$> map = new HashMap<>();
        for (Key$ k : trait.outs$()) {
            map.put(k.key, k);
        }
        // 先加入转换
        transOuts(basis.transformers.get(trait.name$()), map);
        // 再看看有没有相同的输出
        if (mapPal != null) {
            if (!mapPal.isEmpty() && !map.isEmpty()) {
                for (Key$ k : map.values()) {
                    if (mapPal.containsKey(k.key)) {
                        // 并行的任务不应该有相同的输出
                        Throws.sameOutKeyParallel(k, trait);
                    }
                }
            }
            mapPal.putAll(map);
        }
        logger.i(TAG, "genOuts", "trait:%s, mapPal:%s, map:%s", trait.name$(), mapPal, map);
        return map;
    }

    private static void transOuts(Set<Transformer> tranSet, Map<String, Key$> map) {
        if (tranSet == null || tranSet.isEmpty() || map.isEmpty()) return;
        final Set<Transformer> trans = new HashSet<>();
        final Set<Transformer> sameKey = new HashSet<>();
        tranSet.stream().filter(t -> map.containsKey(t.in.key)).forEach(t -> {
            // 先不从map移除, 可能多个transformer使用同一个源。
            final Key$ from = map.get(t.in.key);
            if (!t.in.isAssignableFrom(from)) {
                Throws.typeNotMatch4Trans(from, t.in);
            }
            if (t.in.key.equals(t.out.key)) sameKey.add(t);
            else trans.add(t);
        });
        for (Transformer t : trans) {
            map.remove(t.in.key);
            map.put(t.out.key, t.out);
        }
        // 如果只有一个transformer使用同一个源，那么以上逻辑即可，
        // 但是如果多个transformer使用同一个源，则由于顺序的不确定性，相同key的transformer可能被移除。
        for (Transformer t : sameKey) {
            // map.remove(t.in.key);   // 不要这句，反正key是一样的，value会覆盖。
            map.put(t.out.key, t.out);
        }
    }

    private static void genInputRequired(Map<String, Key$> requires, Map<String, Key$> inputRequired) {
        if (requires.isEmpty()) return;
        new HashSet<>(requires.values()).stream().filter(k -> inputRequired.containsKey(k.key)).forEach(k -> {
            final Key$ in = inputRequired.get(k.key);
            if (!k.isAssignableFrom(in)) {
                // input不是require的子类, 但是require是input的子类, 那么把require存进去。
                if (in.isAssignableFrom(k)) {
                    inputRequired.put(k.key, k);
                } else {
                    Throws.typeNotMatch4Required(in, k);
                }
            }
            requires.remove(k.key);
        });
        // 初始输入里(前面任务放入的)也没有, 那么也放进去。
        putAll(inputRequired, requires);
    }

    static Map<String, Key$> requireInputsEnough(In in, Map<String, Key$> inputRequired,
                                                 Set<Transformer> trans4Input) {
        final Map<String, Key$> inputs = in.keys.isEmpty() ? Collections.emptyMap() : new HashMap<>();
        for (Key$ k : in.keys) {
            inputs.put(k.key, k);
        }
        transOuts(trans4Input, inputs);
        requireRealInEnough(inputRequired.values(), inputs);
        return inputs;
    }

    private static void requireRealInEnough(Collection<Key$> requires, Map<String, Key$> realIn) {
        for (Key$ k : requires) {
            final Key$ kIn = realIn.get(k.key);
            if (kIn != null) {
                if (!k.isAssignableFrom(kIn)) {
                    Throws.typeNotMatch4RealIn(kIn, k);
                }
            } else {
                Throws.lackIOKey(k, true);
            }
        }
    }

    private static void trimOutsFlow(Basis basis) {
        basis.outsFlowTrimmed = new HashMap<>();
        final Map<String, Key$> trimmed = new HashMap<>();
        putAll(trimmed, basis.outs);
        for (int i = basis.traits.size() - 1; i >= 0; i--) {
            final Trait<?> trait = basis.traits.get(i);
            trimOutsFlow(trait, basis, trimmed);
        }
    }

    /**
     * 必要的输出不一定都要保留到最后，指定的输出在某个任务之后就不再被需要了，所以要进行trim.
     */
    private static void trimOutsFlow(Trait<?> trait, Basis basis, Map<String, Key$> trimmed) {
        consumeRequiresOnTransGlobal(trait, trimmed, basis, false);
        final Set<Key$> flow = new HashSet<>(trimmed.values());
        basis.outsFlowTrimmed.put(trait.name$(), flow);
        if (trait instanceof Trait.Parallel) {
            final Map<String, Key$> inputs = new HashMap<>();
            final Map<String, Key$> outs = new HashMap<>();
            for (Trait<?> tt : ((Trait.Parallel) trait).traits()) {
                // 根据Tracker实现的实际情况，弃用这行。
                // basis.outsFlowTrimmed.put(tt.name$(), flow);
                final Map<String, Key$> dps = basis.dependencies.get(tt.name$());
                if (dps != null) putAll(outs, dps);
                putAll(inputs, tt.requires$());
            }
            removeAll(trimmed, outs);
            putAll(trimmed, inputs);
        } else {
            final Map<String, Key$> dps = basis.dependencies.get(trait.name$());
            if (dps != null) removeAll(trimmed, dps);
            putAll(trimmed, trait.requires$());
        }
        if (DEBUG) requireKey$kDiff(trimmed.values());
    }

    /**
     * 用于运行时执行转换操作。
     *
     * @param tranSet       转换器集合。
     * @param map           输出不为<code>null</code>的值集合。
     * @param nullValueKeys 输出为<code>null</code>的值的{@link Key$}集合。
     * @param global        对于一个全局的转换，在最终输出集合里不用删除所有转换的输入。
     */
    static void doTransform(Set<Transformer> tranSet, Map<String, Object> map, Set<Key$> nullValueKeys, boolean global) {
        if (tranSet == null || tranSet.isEmpty() || (map.isEmpty() && nullValueKeys.isEmpty())) return;
        final Map<String, Object> out = map.isEmpty() ? Collections.emptyMap() : new HashMap<>();
        final Set<Key$> nulls = nullValueKeys.isEmpty() ? Collections.emptySet() : new HashSet<>();
        final Set<Transformer> trans = new HashSet<>();
        // 不过这里跟transOuts()的算法不同，所以不需要这个了。
        // final Set<Transformer> sameKey = new HashSet<>();
        for (Transformer<?, ?> t : tranSet) {
            if (map.containsKey(t.in.key)) {
                // 先不从map移除, 可能多个transformer使用同一个源。
                final Object o = t.transform(map);
                if (o == null) nulls.add(t.out);
                else out.put(t.out.key, o);
                trans.add(t);
            } else if (nullValueKeys.contains(t.in)) {
                nulls.add(t.out);
                trans.add(t);
            }
        }
        if (!global) for (Transformer t : trans) {
            map.remove(t.in.key);
            nullValueKeys.remove(t.in);
        }
        if (!out.isEmpty()) map.putAll(out);
        if (!nulls.isEmpty()) nullValueKeys.addAll(nulls);
    }

    private static void requireTaskNameDifferent(Trait trait, Set<String> names) {
        final String name = trait.name$();
        if (names.contains(name)) Throws.sameName(name);
        names.add(name);
    }

    ////////////////////////////////////////////////////////////////////////////
    //***************************** static tools *****************************//
    private static CP<Map<String, Key$>> sCpMap = new CP<Map<String, Key$>>() {
        @Override
        public Map<String, Key$> copy(Map<String, Key$> value) {
            return new HashMap<>(value);
        }
    };

    private static CP<Set<Transformer>> sCpSet = new CP<Set<Transformer>>() {
        @Override
        public Set<Transformer> copy(Set<Transformer> value) {
            return new HashSet<>(value);
        }
    };

    private static CP<Key$> sCpKey$ = new CP<Key$>() {
        @Override
        public Key$ copy(Key$ value) {
            return value;
        }
    };

    private static <K, V> Map<K, V> copy(Map<K, V> src, Map<K, V> dest, CP<V> cp) {
        for (Map.Entry<K, V> entry : src.entrySet()) {
            dest.put(entry.getKey(), cp.copy(entry.getValue()));
        }
        return dest;
    }

    private interface CP<T> {
        T copy(T value);
    }

    static Collection<Trait<?>> copy(Collection<Trait<?>> src, Collection<Trait<?>> dest) {
        for (Trait trait : src) {
            if (trait instanceof Trait.Parallel) {
                dest.add(new Trait.Parallel(((Trait.Parallel) trait).traits()));
            } else {
                dest.add(trait);
            }
        }
        return dest;
    }

    private static void putAll(Map<String, Key$> map, Set<Key$> keys) {
        for (Key$ k : keys) {
            map.put(k.key, k);
        }
    }

    private static <K, V> void putAll(Map<K, V> map, Map<K, V> src) {
        map.putAll(src);
    }

    private static <K> void removeAll(Map<K, ?> map, Set<K> set) {
        for (K key : set) {
            map.remove(key);
        }
    }

    private static <K, V> void removeAll(Map<K, V> map, Map<K, V> src) {
        removeAll(map, src.keySet());
    }
}
