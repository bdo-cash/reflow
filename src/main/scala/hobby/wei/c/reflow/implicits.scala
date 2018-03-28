/*
 * Copyright (C) 2018-present, Wei Chou(weichou2010@gmail.com)
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

import scala.collection.immutable
import scala.language.implicitConversions

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 28/03/2018
  */
object implicits {
  implicit class KceAdd(kce: Kce[_ <: AnyRef]) {
    def +(ks: Kce[_ <: AnyRef]*) = Helper.Kces + kce + (ks: _*)

    def ok() = Helper.Kces + kce ok()
  }

  object KceAdd {
    implicit def kceAddOk(kce: Kce[_ <: AnyRef]): immutable.Set[Kce[_ <: AnyRef]] = kce ok()

    implicit def kceAddOk(kb: Helper.Kces.Builder): immutable.Set[Kce[_ <: AnyRef]] = kb ok()
  }

  implicit class TransAdd(trans: Transformer[_ <: AnyRef, _ <: AnyRef]) {
    def +(ts: Transformer[_ <: AnyRef, _ <: AnyRef]*) = Helper.Transformers + trans + (ts: _*)

    def ok() = Helper.Transformers + trans ok()
  }

  object TransAdd {
    implicit def transAddOk(trans: Transformer[_ <: AnyRef, _ <: AnyRef]): immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = trans ok()

    implicit def transAddOk(tb: Helper.Transformers.Builder): immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = tb ok()
  }

  implicit class InAdd[V](kv: (String, V)) {
    def +[VK <: AnyRef](kce: Kce[VK], value: VK) = In + kv + (kce.key, value)

    def +(_kv: (String, Any)) = In + kv + _kv

    def +(key: String, value: Any) = In + kv + (key, value)

    def ok() = In + kv ok()
  }

  object InAdd {
    implicit def inAddOk[V](kv: (String, V)): In = kv ok()

    implicit def inAddOk[V](ib: In.Builder): In = ib ok()
  }

  implicit class InAddK[V <: AnyRef](kv: (Kce[V], V)) {
    def +[VK <: AnyRef](_kv: (Kce[VK], VK)) = In + (kv._1.key, kv._2) + (_kv._1.key, _kv._2)

    def +[VK <: AnyRef](kce: Kce[VK], value: VK) = In + (kv._1.key, kv._2) + (kce.key, value)

    def +(key: String, value: Any) = In + (kv._1.key, kv._2) + (key, value)

    def ok() = In + (kv._1.key, kv._2) ok()
  }

  object InAddK {
    implicit def inAddOk[V <: AnyRef](kv: (Kce[V], V)): In = kv ok()

    implicit def inAddOk[V](ib: In.Builder): In = ib ok()
  }
}
