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
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Reflow._

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016
  */
class Out private[reflow](map: Map[String, Kce[_ <: AnyRef]]) {
  private[reflow] def this() = this(Map.empty[String, Kce[_ <: AnyRef]])

  private[reflow] def this(keys: Set[Kce[_ <: AnyRef]]) = this((new mutable.AnyRefMap[String, Kce[_ <: AnyRef]] /: keys) { (m, k) => m += (k.key, k) })

  // 仅读取
  private[reflow] val _keys = map.toMap[String, Kce[_ <: AnyRef]]
  // 由于并行的任务，不可能有相同的key, 没有必要让本类的整个方法调用都进行sync, 因此用并行库是最佳方案。
  private[reflow] val _map = new concurrent.TrieMap[String, Any]
  private[reflow] val _nullValueKeys = new concurrent.TrieMap[String, Kce[_ <: AnyRef]]

  private[reflow] def fillWith(out: Out, fullVerify: Boolean = true): Unit = putWith(out._map, out._nullValueKeys, ignoreDiffType = true, fullVerify)

  private[reflow] def verify(): Unit = putWith(immutable.Map.empty, immutable.Map.empty, ignoreDiffType = false, fullVerify = true)

  /**
    * 若调用本方法, 则必须一次填满, 否则报异常。
    *
    * @param map
    * @param nulls          因为value为null导致无法插入到map的key的集合。
    * @param ignoreDiffType 是否忽略不同值类型(`Key$`)。仅有一个场景用到：使用上一个任务的输出填充当前输出（在当前对象创建的时候。
    *                       通常情况下，前面任务的输出可能继续向后流动），有时候后面可能会出现相同的`k.key`但类型不同，这是允许的（即：相同的key可以被覆盖）。
    *                       但在使用上一个任务的输出填充当前对象的时候，如果`k.key`相同但类型不匹配，会抛出异常。为了简化起见，设置了本参数。
    * @param fullVerify     检查`_keys`是否全部输出。
    */
  private[reflow] def putWith(map: Map[String, Any], nulls: Map[String, Kce[_ <: AnyRef]],
                              ignoreDiffType: Boolean = false, fullVerify: Boolean = false): Unit = {
    _keys.values.foreach { k =>
      if (map.contains(k.key)) {
        if (k.putValue(_map, map(k.key), ignoreDiffType)) {
          _nullValueKeys.remove(k.key)
        }
      } else if (!_map.contains(k.key)) {
        if (nulls.contains(k.key)) {
          _nullValueKeys.put(k.key, k)
        } else if (debugMode && fullVerify) {
          if (!_nullValueKeys.contains(k.key)) Throws.lackIOKey(k, in$out = false)
        }
      }
    }
  }

  private[reflow] def put[T](key: String, value: T): Boolean = {
    if (_keys.contains(key)) {
      val k = _keys(key)
      if (value.isNull && !_map.contains(key)) {
        _nullValueKeys.put(k.key, k)
      } else {
        k.putValue(_map, value)
        _nullValueKeys.remove(k.key)
      }
      true
    } else false
  }

  private[reflow] def cache(out: Out): Unit = {
    out._map.toMap.foreach { kv: (String, Any) =>
      cache(kv._1, kv._2)
    }
  }

  /**
    * 有reinforce需求的任务, 可以将中间结果缓存在这里。
    * 注意: 如果在输入中({#keys Out(Set)}构造器参数)含有本key, 则无法将其缓存。
    *
    * @param key
    * @param value
    * @tparam T
    * @return true 成功; false 失败, 说明key重复, 应该换用其它的key。
    */
  private[reflow] def cache[T](key: String, value: T): Unit = {
    if (_keys.contains(key)) {
      if (debugMode) Throws.sameCacheKey(_keys(key))
    } else if (value.nonNull) {
      _map.put(key, value)
    }
  }

  private[reflow] def remove(key: String): Unit = {
    _map -= key
    _nullValueKeys -= key
  }

  def apply[T >: Null](key: String): Option[T] = get[T](key)

  /**
    * 取得key对应的value。
    *
    * @param key
    * @tparam T
    * @return
    */
  def get[T](key: String): Option[T] = _map.get(key).as[Option[T]] // 由于`cache`的也是放在一起，其`key`在`_keys`范围之外。所以只能用这种方式读取。
  //_keys.get(key).fold[Option[T]](None) { k => get(k.as[Kce[T]]) }

  /**
    * 取得key对应的value。
    *
    * @param key
    * @tparam T
    * @return
    */
  def get[T <: AnyRef](key: Kce[T]): Option[T] = Option(key.takeValue(_map))

  /**
    * 取得预定义的keys及类型。即: 希望输出的keys。
    *
    * @return
    */
  def keysDef(): immutable.Set[Kce[_ <: AnyRef]] = _keys.values.toSet

  /**
    * 取得实际输出的keys。
    *
    * @return
    */
  def keys(): immutable.Set[Kce[_ <: AnyRef]] = _keys.values.filter { k => _map.contains(k.key) || _nullValueKeys.contains(k.key) }.toSet

  override def toString = "keys:" + keys + ", values:" + _map + (if (_nullValueKeys.isEmpty) "" else ", null:" + _nullValueKeys.values)
}
