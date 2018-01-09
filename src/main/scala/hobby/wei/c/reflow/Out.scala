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

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 26/06/2016
  */
class Out private[reflow](_map: Map[String, Key$[_]]) {
  private[reflow] def this(_keys: Set[Key$[_]]) = this(
    (new mutable.HashMap[String, Key$[_]] /: _keys) {
      (m, k) =>
        m.put(k.key, k)
        m
    })

  // 仅读取
  private[reflow] val _keys: Map[String, Key$[_]] = _map.to[immutable.Map].as[Map[String, Key$[_]]]
  // 作了synchronized同步
  private[reflow] val map: mutable.Map[String, AnyRef] = new mutable.HashMap[String, AnyRef]
  private[reflow] val nullValueKeys: mutable.Set[Key$[_]] = new mutable.HashSet[Key$[_]]

  private[reflow] def fillWith(out: Out) {
    putWith(out.map, out.nullValueKeys, true, true)
  }

  private[reflow] def verify() {
    putWith(Map.empty, Set.empty, true, true)
  }

  /**
    * 若调用本方法, 则必须一次填满, 否则报异常。
    *
    * @param map
    * @param nullValueKeys  因为value为null导致无法插入到map的key的集合。
    * @param ignoreDiffType 是否忽略不同值类型({Key$})。
    * @param fullVerify     检查{#keys}是否全部输出。
    */
  private[reflow] def putWith(map: Map[String, AnyRef], nullValueKeys: Set[Key$[_]], ignoreDiffType: Boolean, fullVerify: Boolean): Unit = synchronized {
    _keys.values.foreach { k =>
      if (map.contains(k.key)) {
        if (k.putValue(this.map, map.get(k.key), ignoreDiffType)) {
          this.nullValueKeys.remove(k)
        }
      } else if (!this.map.contains(k.key)) {
        if (nullValueKeys.contains(k)) {
          this.nullValueKeys.add(k)
        } else if (fullVerify) {
          Assist.Throws.lackIOKey(k, false)
        }
      }
    }
  }

  private[reflow] def put[T <: AnyRef](key: String, value: T): Boolean = synchronized {
    if (_keys.contains(key)) {
      val k = _keys(key)
      if (value.isNull && !map.contains(key)) {
        nullValueKeys.add(k)
      } else {
        k.putValue(map, value)
        nullValueKeys.remove(k)
      }
      true
    } else false
  }

  private[reflow] def cache(out: Out): Unit = {
    out.map.foreach { kv: (String, AnyRef) =>
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
  private[reflow] def cache[T <: AnyRef](key: String, value: T): Unit = synchronized {
    if (_keys.contains(key)) {
      Assist.Throws.sameCacheKey(_keys(key))
    } else if (value.nonNull) {
      map.put(key, value)
    }
  }

  /**
    * 取得key对应的value。
    *
    * @param key
    * @tparam T
    * @return
    */
  def get[T](key: String): T = get(_keys.get(key).as[Key$[T]])

  /**
    * 取得key对应的value。
    *
    * @param key
    * @tparam T
    * @return
    */
  def get[T](key: Key$[T]): T = synchronized {
    key.takeValue(map)
  }

  /**
    * 取得预定义的keys及类型。即: 希望输出的keys。
    *
    * @return
    */
  def keysDef(): Set[Key$[_]] = synchronized {
    _keys.values.toSet
  }

  /**
    * 取得实际输出的keys。
    *
    * @return
    */
  def keys(): Set[Key$[_]] = synchronized {
    val result = new mutable.HashSet[Key$[_]]
    _keys.values.foreach { k =>
      if (map.contains(k.key)) {
        result.add(k)
      } else if (nullValueKeys.contains(k)) {
        result.add(k)
      }
    }
    return result.toSet
  }

  override def toString = "keys:" + keys + ", values:" + map + (if (nullValueKeys.isEmpty) "" else ", null:" + nullValueKeys)
}
