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
class Out private[reflow](map: Map[String, Key$[_]]) {
  private[reflow] def this() = this(Map.empty[String, Key$[_]])

  private[reflow] def this(keys: Set[Key$[_]]) = this(
    (new mutable.AnyRefMap[String, Key$[_]] /: keys) {
      (m, k) =>
        m += (k.key, k)
        m
    })

  // 仅读取
  private[reflow] val _keys = map.toMap[String, Key$[_]]
  // 由于并行的任务，不可能有相同的key, 没有必要让本类的整个方法调用都进行sync, 因此用并行库是最佳方案。
  private[reflow] val _map = new concurrent.TrieMap[String, Any]
  private[reflow] val _nullValueKeys = new concurrent.TrieMap[String, Key$[_]]

  private[reflow] def fillWith(out: Out) {
    putWith(out._map, out._nullValueKeys, ignoreDiffType = true, fullVerify = true)
  }

  private[reflow] def verify(): Unit = putWith(immutable.Map.empty, immutable.Map.empty, ignoreDiffType = true, fullVerify = true)

  /**
    * 若调用本方法, 则必须一次填满, 否则报异常。
    *
    * @param map
    * @param nulls          因为value为null导致无法插入到map的key的集合。
    * @param ignoreDiffType 是否忽略不同值类型({Key$})。
    *                       有时候类型可以被覆盖（如：后面的任务的输出可以覆盖前面任务相同key的输出），
    *                       而有时候不可以（如：当前任务给既定的输出赋值）。
    * @param fullVerify     检查{#keys}是否全部输出。
    */
  private[reflow] def putWith(map: Map[String, Any], nulls: Map[String, Key$[_]],
                              ignoreDiffType: Boolean, fullVerify: Boolean): Unit = {
    _keys.values.foreach { k =>
      if (map.contains(k.key)) {
        if (k.putValue(_map, map(k.key), ignoreDiffType)) {
          _nullValueKeys.remove(k.key)
        }
      } else if (!_map.contains(k.key)) {
        if (nulls.contains(k.key)) {
          _nullValueKeys.put(k.key, k)
        } else if (fullVerify) {
          if (debugMode) Throws.lackIOKey(k, in$out = false)
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

  /**
    * 取得key对应的value。
    *
    * @param key
    * @tparam T
    * @return
    */
  def get[T](key: String): Option[T] = get(_keys(key).as[Key$[T]])

  /**
    * 取得key对应的value。
    *
    * @param key
    * @tparam T
    * @return
    */
  def get[T](key: Key$[T]): Option[T] = Option(key.takeValue(_map))

  /**
    * 取得预定义的keys及类型。即: 希望输出的keys。
    *
    * @return
    */
  def keysDef(): immutable.Set[Key$[_]] = _keys.values.toSet

  /**
    * 取得实际输出的keys。
    *
    * @return
    */
  def keys(): immutable.Set[Key$[_]] = {
    val result = new mutable.HashSet[Key$[_]]
    _keys.values.foreach { k =>
      if (_map.contains(k.key)) {
        result.add(k)
      } else if (_nullValueKeys.contains(k.key)) {
        result.add(k)
      }
    }
    result.toSet
  }

  override def toString = "keys:" + keys + ", values:" + _map + (if (_nullValueKeys.isEmpty) "" else ", null:" + _nullValueKeys.values)
}
