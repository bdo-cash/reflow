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

import hobby.wei.c.anno.proguard.{KeepMp$, KeepVp$}
import hobby.wei.c.reflow.Reflow.Period
import hobby.wei.c.reflow.lite.{Lite, Par, Par2, Serial}
import scala.collection.immutable
import scala.language.implicitConversions
import scala.reflect.ClassTag

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 28/03/2018
  */
@KeepVp$
@KeepMp$
object implicits {
  val P_HIGH = Reflow.P_HIGH
  val P_NORMAL = Reflow.P_NORMAL
  val P_LOW = Reflow.P_LOW

  val TRANSIENT: Period.Tpe = Period.TRANSIENT
  val SHORT: Period.Tpe = Period.SHORT
  val LONG: Period.Tpe = Period.LONG
  val INFINITE: Period.Tpe = Period.INFINITE

  lazy val SINGLE_THREAD = Config.SINGLE_THREAD

  lazy val Strategy = Feedback.Progress.Strategy
  lazy val FullDose = Strategy.FullDose
  lazy val Fluent = Strategy.Fluent
  lazy val Depth = Strategy.Depth
  lazy val Interval = Strategy.Interval

  type Intent = Trait
  val Intent = Trait

  def none[A]: immutable.Set[KvTpe[_ <: AnyRef]] = Helper.KvTpes.empty()

  def none: In = In.empty()

  implicit class TransformerRetain(kce: KvTpe[_ <: AnyRef]) {
    @inline def re: Transformer[_ <: AnyRef, _ <: AnyRef] = Helper.Transformers.retain(kce)
  }

  implicit def lite2Par[IN >: Null <: AnyRef, OUT >: Null <: AnyRef]
  (lite: Lite[IN, OUT])(implicit in: ClassTag[IN], out: ClassTag[OUT]): Par[IN, OUT] = Par(lite)

  implicit def serialInPar[IN >: Null <: AnyRef, OUT >: Null <: AnyRef]
  (serial: Serial[IN, OUT])(implicit in: ClassTag[IN], out: ClassTag[OUT]): Lite[IN, OUT] = serial.inPar()

  implicit class SerialInPar2Par[IN >: Null <: AnyRef, OUT >: Null <: AnyRef]
  (serial: Serial[IN, OUT])(implicit in: ClassTag[IN], out: ClassTag[OUT]) {
    def +>>[OUT1 >: Null <: AnyRef](lite: Lite[IN, OUT1])(implicit out1: ClassTag[OUT1]): Par2[IN, OUT, OUT1] = par(lite)
    def par[OUT1 >: Null <: AnyRef](lite: Lite[IN, OUT1])(implicit out1: ClassTag[OUT1]): Par2[IN, OUT, OUT1] =
      serialInPar(serial) +>> lite
  }

  def +|-[IN >: Null <: AnyRef, Next >: Null <: AnyRef]
  (f: IN => Next)(implicit in: ClassTag[IN], nxt: ClassTag[Next]): Lite[IN, Next] =
    lite.Task[IN, Next](TRANSIENT, P_HIGH, visible = false)((in, _) => f(in))

  // def方法不能直接起作用，这里转换为函数值。
  implicit lazy val f0 = kce2Bdr _
  implicit lazy val f1 = trans2Bdr _
  implicit lazy val f2 = kceKv2Bdr _
  implicit lazy val f3 = strKv2Bdr _

  implicit def kce2Bdr(kce: KvTpe[_ <: AnyRef]): Helper.KvTpes.Builder = Helper.KvTpes + kce

  implicit def kce2Ok(kce: KvTpe[_ <: AnyRef]): immutable.Set[KvTpe[_ <: AnyRef]] = kce ok()

  implicit def kceBdr2Ok(kb: Helper.KvTpes.Builder): immutable.Set[KvTpe[_ <: AnyRef]] = kb ok()

  implicit def trans2Bdr(trans: Transformer[_ <: AnyRef, _ <: AnyRef]): Helper.Transformers.Builder = Helper.Transformers + trans

  implicit def trans2Ok(trans: Transformer[_ <: AnyRef, _ <: AnyRef]): immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = trans ok()

  implicit def transBdr2Ok(tb: Helper.Transformers.Builder): immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]] = tb ok()

  implicit def kceKv2Bdr[V <: AnyRef](kv: (KvTpe[V], V)): In.Builder = In + kv

  implicit def kceKv2Ok[V <: AnyRef](kv: (KvTpe[V], V)): In = kv ok()

  implicit def strKv2Bdr[V](kv: (String, V)): In.Builder = In + (kv._1, kv._2)

  implicit def strKv2Ok[V](kv: (String, V)): In = kv ok()

  implicit def inBdr2Ok(ib: In.Builder): In = ib ok()
}
