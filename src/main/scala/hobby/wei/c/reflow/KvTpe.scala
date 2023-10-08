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
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.KvTpe._

import scala.collection._
import scala.reflect.runtime.universe._

/** 定义`key`及`value`的类型。`value`类型由参数化类型（泛型）指定。
  * 注意: 本类的子类必须在运行时创建, 即匿名子类, 否则泛型信息可能在`Proguard/R8`时被删除, 从而导致解析失败。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 21/07/2016;
  *          2.0, 07/10/2023, 用 Scala 的标准方式重构：`TypeTag`。
  */
final class KvTpe[T <: AnyRef: TypeTag] private[reflow] (
    _key: String,
    @deprecated("Use with caution, since there is no type safety protection.")
    _clazz: Class[T] /* 这样拿不到嵌套层，即`subTypes`，如果有。so…@deprecated */
) extends Equals {
  def this(_key: String) = this(_key, null)

  val key: String = _key ensuring (_.nonEmpty)

  /** 参数化类型（泛型）的参数类型，结构类似于：
    * {{{ KvTpe[Map[Sting, List[Array[Int]]]] }}} 中的
    * {{{ Map[Sting, List[Array[Int]]] }}}
    *
    * @see [[scala.reflect.api.Types.TypeRef]]
    */
  val tpe: Type = Option(_clazz).map { tpeOf(_)(typeTag[T].mirror) }.getOrElse(typeOf[T])

  /** 第一级泛型参数的 Class 表示。 */
  lazy val rawType: Class[_ >: T] = clazOf(tpe).as[Class[_ >: T]]

  /** 走这个方法作类型转换, 确保`value`类型与定义的一致性。
    * @return 返回 Task 在执行时当前`key`对应值的目标类型。
    */
  def asType(value: Any): T = value.as[T]

  def putValue(map: mutable.Map[String, Any], value: Any): Boolean = putValue(map, value, ignoreTpeDiff = false)

  /** 将输出值按指定类型(作类型检查)插入 Map。
    *
    * @param map           输出到的 Map
    * @param value         要输出的值
    * @param ignoreTpeDiff 如果`value`参数类型不匹配，是否忽略。
    * @return `true`成功，`else`失败。
    */
  def putValue(map: mutable.Map[String, Any], value: Any, ignoreTpeDiff: Boolean): Boolean = {
    val v = requireSameType(value, ignoreTpeDiff)
    if (v.nonNull) {
      map.put(key, v)
      true
    } else false
  }

  /** 走这个方法取值, 确保`value`类型与定义的一致性。
    *
    * @param map Task 的输入参数。
    * @return 返回 Task 在执行时当前`key`对应值的目标类型。
    */
  def takeValue(map: Map[String, Any]): Option[T] = {
    // 强制类型转换比较宽松, 只会检查对象类型, 而不会检查泛型。
    // 但是由于`value`值对象无法获得泛型类型, 因此这里不再作泛型检查。也避免了性能问题。
    map.get(key).as[Option[T]]
  }

  private def requireSameType(value: Any, ignoreDiffType: Boolean): Any = {
    if (value.nonNull) {
      val clazz = value.getClass
//      val tc = tpeOf(clazz)(typeTag[T].mirror)
//      if (!(tc <:< tpe.erasure || anyValOf(tc) <:< tpe.erasure)) {
      // 保留原状，省事。
      if (!rawType.isAssignableFrom(clazz)) {
        if (ignoreDiffType) null
        else Assist.Throws.typeNotMatch(this, clazz)
      } else value
      // 数值对象通常已经失去了泛型参数, 因此不作检查。
    } else value
  }

  /** 参数的`value`类型是否与本`value`类型相同或者子类型。
    * 同 [[Class#isAssignableFrom(Class)]]
    */
  def isAssignableFrom(key: KvTpe[_ <: AnyRef]): Boolean = key.tpe weak_<:< tpe

  override def equals(any: scala.Any) = any match {
    case that: KvTpe[_] if that.canEqual(this) => that.key == this.key && that.tpe =:= this.tpe
    case _                                     => false
  }

  override def canEqual(that: Any) = that.isInstanceOf[KvTpe[_]]

  override def hashCode = key.hashCode * 41 + tpe.hashCode

  override def toString = s"${classOf[KvTpe[_]].getSimpleName}[$key -> $tpe]"
}

object KvTpe {
  import scala.reflect.runtime.currentMirror

  def tpeOf[C](clazz: Class[C])(implicit runtimeMirror: Mirror = currentMirror): Type =
    runtimeMirror.classSymbol(clazz).toType

  def clazOf(tpe: Type)(implicit runtimeMirror: Mirror = currentMirror): RuntimeClass =
    runtimeMirror.runtimeClass(tpe)

  private lazy val ANY_VAL              = tpeOf(classOf[AnyVal]) //(t.mirror)
  private lazy val (sDOUBLE, jDOUBLE)   = (tpeOf(classOf[Double]), tpeOf(classOf[java.lang.Double]))
  private lazy val (sFLOAT, jFLOAT)     = (tpeOf(classOf[Float]), tpeOf(classOf[java.lang.Float]))
  private lazy val (sLONG, jLONG)       = (tpeOf(classOf[Long]), tpeOf(classOf[java.lang.Long]))
  private lazy val (sINT, jINT)         = (tpeOf(classOf[Int]), tpeOf(classOf[java.lang.Integer]))
  private lazy val (sCHAR, jCHAR)       = (tpeOf(classOf[Char]), tpeOf(classOf[java.lang.Character]))
  private lazy val (sBYTE, jBYTE)       = (tpeOf(classOf[Byte]), tpeOf(classOf[java.lang.Byte]))
  private lazy val (sBOOLEAN, jBOOLEAN) = (tpeOf(classOf[Boolean]), tpeOf(classOf[java.lang.Boolean]))

  def anyValOf(A: Type): Type = {
    /*if (A <:< ANY_VAL) A // 实测不行，也就是`java.lang.Integer` <:< / weak_<:< `scala.AnyVal`, 但不<:< / weak_<:< `scala.Int`!!!
    else*/
    if (A =:= jDOUBLE) sDOUBLE
    else if (A =:= jFLOAT) sFLOAT
    else if (A =:= jLONG) sLONG
    else if (A =:= jINT) sINT
    else if (A =:= jCHAR) sCHAR
    else if (A =:= jBYTE) sBYTE
    else if (A =:= jBOOLEAN) sBOOLEAN
    else A
  }

  /** 创建`T`所代表的对象的实例。<br>
    * 如果有多个构造函数，自动根据参数列表的数量以及类型，寻找匹配类型的构造方法并`apply`参数列表。
    * @throws ScalaReflectionException class Abc is an inner class, use reflectClass on an InstanceMirror to obtain its ClassMirror
    *                                  暂不解决。
    */
  def createInstance[T <: Any: TypeTag](argss: Any*): T = {
    lazy val C    = typeOf[T].typeSymbol.asClass
    lazy val C_Ps = C.typeParams.map(_.asType.toType)

    val t = typeTag[T]

    /** 将[占位符]类型实例化，即：寻找占位符类型所表示的实际`Class`类型。例如：{{{
      * class Abc[A, B, C](a: A, b: B, c: C)
      * }}}
      * 实例化为：{{{ new Abc[String, Int, Double](…) }}}
      * 那么，本方法寻找`A`所代表的`String`、` `所代表的`Int`…。
      * @param A 注意虽然是`Type`，但可能是占位符，而不是实际`Class`类型。因此需要首先判断
      *          [[A.typeSymbol.isClass]]，`false`时才需要调用本方法。
      */
    def Ps2Claz(A: Type): Type = C_Ps.find(_ =:= A).get.asSeenFrom(t.tpe, C)

    t.mirror.reflectClass(C).reflectConstructor(
      t.tpe.members.filter { m =>
        m.isMethod && Some(m.asMethod).forall(c => c.isConstructor && c.isPublic)
      }.map(_.asMethod).filter { c =>
        val params = c.paramLists // 可能不止一个参数列表，所以才会嵌套`List`。
        params.zipWithIndex.forall { case (lis, i) =>
          lis.zipWithIndex.forall { case (sym, j) =>
            // 只有一个参数列表（0 个参数列表，走不到这个 case）
            // 注意下面统一用`j`（没有地方用`i`）
            val args = if (params.length == 1) argss else if (params.length > 1) Seq(argss(i)) else Seq.empty[Any]
            lis.length == args.length && {
              val argTpe = tpeOf(args(j).getClass)(t.mirror)
              val tpe1   = sym.typeSignature
              val sym1   = tpe1.typeSymbol
              val defTpe = if (sym1.isClass) tpe1 else Ps2Claz(tpe1)
              // 不是一定要`AnyVal`类型，可能原本就需要 Java 类型。
              (argTpe weak_<:< defTpe) || (anyValOf(argTpe) weak_<:< defTpe)
            }
          }
        }
      }.toSeq.head
    )(argss: _*).as[T]
  }
}
