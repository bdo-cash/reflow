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

import java.lang.reflect.Type
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.tool.Reflect

import scala.collection._

/**
  * 定义key及value类型。value类型由泛型指定。
  * 注意: 本类的子类必须在运行时创建, 即匿名子类, 否则泛型信息可能在Proguard时被删除, 从而导致解析失败。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 21/07/2016
  */
abstract class KvTpe[T <: AnyRef] private[reflow](_key: String, _clazz: Class[_], _index: Int = 0, _raw: Boolean = false) extends Equals {
  protected def this(_key: String) = this(_key, null)

  final val key: String = _key.ensuring(_.nonEmpty)
  /**
    * 泛型参数的类型, 类似于这种结构: java.util.List<java.util.List<int[]>>。
    */
  final val tpe: Type = (if (_raw) _clazz else Reflect.getSuperclassTypeParameter(if (_clazz.isNull) this.getClass else _clazz, true)(_index)).ensuring(_.nonNull)
  /**
    * 第一级泛型参数的Class表示。
    */
  private lazy val rawType: Class[_ >: T] = Reflect.getRawType(tpe).as[Class[_ >: T]]
  /**
    * 第一级泛型参数的子泛型参数, 可能不存在。作用或结构与tpe类似。
    */
  private lazy val subTypes: Array[Type] = Reflect.getSubTypes(tpe)

  /**
    * 走这个方法作类型转换, 确保value类型与定义的一致性。
    * @return 返回Task在执行时当前key对应值的目标类型。
    */
  def asType(value: Any): T = value.as[T]

  def putValue(map: mutable.Map[String, Any], value: Any): Boolean = putValue(map, value, ignoreTpeDiff = false)

  /**
    * 将输出值按指定类型(作类型检查)插入Map。
    *
    * @param map           输出到的Map。
    * @param value         要输出的值。
    * @param ignoreTpeDiff 如果value参数类型不匹配，是否忽略。
    * @return true成功，else失败。
    */
  def putValue(map: mutable.Map[String, Any], value: Any, ignoreTpeDiff: Boolean): Boolean = {
    val v = requireSameType(value, ignoreTpeDiff)
    if (v.nonNull) {
      map.put(key, v)
      true
    } else false
  }

  /**
    * 走这个方法取值, 确保value类型与定义的一致性。
    *
    * @param map Task的输入参数。
    * @return 返回Task在执行时当前key对应值的目标类型。
    */
  def takeValue(map: Map[String, Any]): Option[T] = {
    // 强制类型转换比较宽松, 只会检查对象类型, 而不会检查泛型。
    // 但是由于value值对象无法获得泛型类型, 因此这里不再作泛型检查。也避免了性能问题。
    map.get(key).as[Option[T]]
  }

  private def requireSameType(value: Any, ignoreDiffType: Boolean): Any = {
    if (value.nonNull) {
      val clazz = value.getClass
      if (!rawType.isAssignableFrom(clazz)) {
        if (ignoreDiffType) null
        else Assist.Throws.typeNotMatch(this, clazz)
      } else value
      // 数值对象通常已经失去了泛型参数, 因此不作检查
    } else value
  }

  /**
    * 参数的value类型是否与本value类型相同或者子类型。
    * 同{Class#isAssignableFrom(Class)}
    *
    * @param key
    * @return
    */
  def isAssignableFrom(key: KvTpe[_ <: AnyRef]): Boolean = {
    if (!rawType.isAssignableFrom(key.rawType)) false
    else if (subTypes.length != key.subTypes.length) false
    else subTypes.indices.forall(i => subTypes(i) == key.subTypes(i))
  }

  override def equals(any: scala.Any) = any match {
    case that: KvTpe[_] if that.canEqual(this) => that.key == this.key && that.tpe == this.tpe
    case _ => false
  }

  override def canEqual(that: Any) = that.isInstanceOf[KvTpe[T]]

  override def hashCode = key.hashCode * 41 + tpe.hashCode

  override def toString = s"${classOf[KvTpe[_]].getSimpleName}[$key -> $tpe]"
}
