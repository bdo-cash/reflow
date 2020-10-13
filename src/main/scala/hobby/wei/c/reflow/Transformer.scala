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

import scala.collection._

/**
  * 任务输出转换器。包括`key`和`value`的转换，
  * 可定义仅转换`value`、或仅转换`key`、或`key-value`全都转换。
  *
  * @constructor 对于要将某key-value转换为其它key'-value'的, 应使用本构造方法。
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 31/07/2016
  */
abstract class Transformer[IN <: AnyRef, OUT <: AnyRef] private(_in: KvTpe[IN], _out: KvTpe[OUT], _keyIn: String, _keyOut: String) extends Equals {
  protected def this(keyIn: String, keyOut: String) = this(null, null, keyIn, keyOut)

  protected def this(in: KvTpe[IN], out: KvTpe[OUT]) = this(in, out, null, null)

  lazy val in: KvTpe[IN] = if (_in.nonNull) _in else new KvTpe[IN](_keyIn, this.getClass, 0) {}
  lazy val out: KvTpe[OUT] = if (_out.nonNull) _out else new KvTpe[OUT](_keyOut, this.getClass, 1) {}

  final def transform(input: Map[String, _]): Option[OUT] = transform(in.takeValue(input))

  def transform(in: Option[IN]): Option[OUT]

  override def equals(any: Any): Boolean = any match {
    case that: Transformer[_, _] if that.canEqual(this) =>
      that.in == this.in && that.out == this.out
    case _ => false
  }

  override def canEqual(that: Any) = that.isInstanceOf[Transformer[_ <: AnyRef, _ <: AnyRef]]

  override def hashCode = in.hashCode * 41 + out.hashCode

  override def toString = s"${classOf[Transformer[_, _]].getSimpleName}[$in -> $out]"
}
