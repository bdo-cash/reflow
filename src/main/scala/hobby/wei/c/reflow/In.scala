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

import hobby.chenai.nakam.lang.J2S
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Dependency.MapTo
import hobby.wei.c.tool.Locker

import scala.collection._
import scala.ref.WeakReference

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 14/08/2016;
  *          2.0, 23/04/2019, 修复 Scala 枚举在`In`中的 Bug。
  */
abstract class In protected (_keys: Set[KvTpe[_ <: AnyRef]], _trans: Transformer[_ <: AnyRef, _ <: AnyRef]*) {
  private[reflow] val keys: immutable.Set[KvTpe[_ <: AnyRef]]                     = requireKkDiff(requireElemNonNull(_keys.toSet))
  private[reflow] val trans: immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = requireTransInTpeSame$OutKDiff(requireElemNonNull(_trans.toSet))

  private[reflow] def fillValues(out: Out): Unit = (out.keysDef & keys).foreach { key => out.put(key.key, loadValue(key.key).orNull) }

  protected def loadValue(key: String): Option[Any]
}

object In {

  def map(map: Map[String, Any], mapKes: Map[KvTpe[_ <: AnyRef], AnyRef], trans: Transformer[_ <: AnyRef, _ <: AnyRef]*): In = new M(
    generate(map) ++ mapKes.keySet,
    (map.mutable /: mapKes) { (m, kv) => m += (kv._1.key, kv._2) },
    trans: _*
  )

  def from(input: Out): In = new M(input._keys.values.toSet, input._map)

  def +[T <: AnyRef](kv: (KvTpe[T], T)): Builder = new Builder + kv

  @deprecated("Use with caution, since there is no type safety protection.")
  def +(key: String, value: Any): Builder = new Builder + (key, value)

  class Builder private[reflow] () {
    private lazy val mapKes                          = new mutable.AnyRefMap[KvTpe[_ <: AnyRef], AnyRef]
    private lazy val map                             = new mutable.AnyRefMap[String, Any]
    private lazy val tb: Helper.Transformers.Builder = new Helper.Transformers.Builder

    def +[T <: AnyRef](kv: (KvTpe[T], T)): this.type = {
      require(!map.contains(kv._1.key))
      mapKes += kv
      this
    }

    @deprecated("Use with caution, since there is no type safety protection.")
    def +(key: String, value: Any): this.type = {
      require(!mapKes.exists(_._1.key == key))
      map += (key, value)
      this
    }

    def +(ts: Transformer[_ <: AnyRef, _ <: AnyRef]*): this.type = {
      tb + (ts: _*)
      this
    }

    def ok(): In = In.map(map, mapKes, tb.ok().toSeq: _*)
  }

  private def generate(map: Map[String, Any]): Set[KvTpe[_ <: AnyRef]] =
    if (map.isEmpty) Set.empty
    else
      (new mutable.HashSet[KvTpe[_ <: AnyRef]] /: map) { (set, kv) =>
        set += new KvTpe(
          kv._1,
          kv._2.as[AnyRef] /*强制转换不会改变实际类型*/ .getClass
        )
      }

  private class M private[reflow] (keys: Set[KvTpe[_ <: AnyRef]], map: Map[String, Any], trans: Transformer[_ <: AnyRef, _ <: AnyRef]*) extends In(keys, trans: _*) {
    override protected def loadValue(key: String) = map.get(key)
  }

  def empty(): In = Locker.lazyGetr(J2S.getRef(emptyRef).orNull) {
    val in = new In(Helper.KvTpes.empty()) {
      override private[reflow] def fillValues(out: Out): Unit = {}

      override protected def loadValue(key: String) = None
    }
    emptyRef = new WeakReference(in)
    in
  }(Locker.getLockr(this))

  private var emptyRef: WeakReference[In] = _
}
