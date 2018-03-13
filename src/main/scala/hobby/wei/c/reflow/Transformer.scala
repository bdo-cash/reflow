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

import hobby.chenai.nakam.lang.TypeBring.AsIs

import scala.collection._

/**
  * 任务输出转换器。包括key和value的转换，
  * 可定义仅转换value、或仅转换key、或key-value全都转换。
  *
  * @constructor 对于要将某key-value转换为其它key'-value'的, 应使用本构造方法。
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 31/07/2016
  */
abstract class Transformer[IN <: AnyRef, OUT <: AnyRef] protected(keyIn: String, keyOut: String) extends Equals {
  /**
    * 对于只转换某Key的值类型的, 应使用本构造方法。
    *
    * @param key
    */
  protected def this(key: String) = this(key, key)

  lazy val in: Kce[IN] = new Kce[IN](keyIn, this.getClass, 0) {}
  lazy val out: Kce[OUT] = new Kce[OUT](keyOut, this.getClass, 1) {}

  def transform(input: Map[String, _]): OUT = Option(in.takeValue(input)).fold(0.as[OUT])(transform)

  protected def transform(in: IN): OUT

  override def equals(any: Any): Boolean = any match {
    case that: Transformer[_, _] if that.canEqual(this) =>
      that.in == this.in && that.out == this.out
    case _ => false
  }

  override def canEqual(that: Any) = that.isInstanceOf[Transformer[_ <: AnyRef, _ <: AnyRef]]

  override def hashCode = in.hashCode * 41 + out.hashCode

  override def toString = s"${classOf[Transformer[_, _]].getSimpleName}[$in -> $out]"
}
