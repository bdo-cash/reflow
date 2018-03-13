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

import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.tool.Locker

import scala.collection._
import scala.ref.WeakReference

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 14/08/2016
  */
abstract class In protected(_keys: Set[Kce[_ <: AnyRef]], _trans: Transformer[_ <: AnyRef, _ <: AnyRef]*) {
  private[reflow] val keys: immutable.Set[Kce[_ <: AnyRef]] = requireKkDiff(requireElemNonNull(_keys.toSet))
  private[reflow] val trans: immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = requireTransInTpeSame$OutKDiff(requireElemNonNull(_trans.toSet))

  private[reflow] def fillValues(out: Out): Unit = (out.keysDef & keys).foreach { key => out.put(key.key, loadValue(key.key).orNull) }

  protected def loadValue(key: String): Option[Any]
}

object In {
  def map(key: String, value: Any): In = map(Map((key, value)))

  def map(map: Map[String, Any], trans: Transformer[_ <: AnyRef, _ <: AnyRef]*): In = new M(generate(map), map, trans: _*)

  def from(input: Out): In = new M(generate(input._map) ++ input._nullValueKeys.values, input._map)

  def add(key: String, value: Any): Builder = new Builder().add(key, value)

  class Builder private[reflow]() {
    private val map = new mutable.AnyRefMap[String, Any]
    private var tb: Helper.Transformers.Builder = _

    def add(key: String, value: Any): Builder = {
      map.put(key, value)
      this
    }

    def add(trans: Transformer[_ <: AnyRef, _ <: AnyRef]): Builder = {
      if (tb.isNull) tb = Helper.Transformers.add(trans)
      else tb.add(trans)
      this
    }

    def ok(): In = if (tb.isNull) In.map(map) else In.map(map, tb.ok().toSeq: _*)
  }

  private def generate(map: Map[String, Any]): Set[Kce[_ <: AnyRef]] = {
    val set = new mutable.HashSet[Kce[_ <: AnyRef]]
    map.foreach { kv: (String, Any) => set.add(new Kce(kv._1, kv._2.getClass) {}) }
    set
  }

  private class M private[reflow](keys: Set[Kce[_ <: AnyRef]], map: Map[String, Any],
                                  trans: Transformer[_ <: AnyRef, _ <: AnyRef]*) extends In(keys: Set[Kce[_ <: AnyRef]], trans: _*) {
    override protected def loadValue(key: String) = map.get(key)
  }

  def empty(): In = Locker.lazyGetr(getRef(emptyRef).orNull) {
    val in = new In(Helper.Keys.empty()) {
      override private[reflow] def fillValues(out: Out): Unit = {}

      override protected def loadValue(key: String) = None
    }
    emptyRef = new WeakReference(in)
    in
  }(Locker.getLockr(this)).get

  private var emptyRef: WeakReference[In] = _
}
