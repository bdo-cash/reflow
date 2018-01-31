/*
 * Copyright (C) 2017-present, Wei Chou(weichou2010@gmail.com)
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

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 31/01/2018
  */
trait Env extends Feedback {
  private[reflow] val trat: Trait[_]
  private[reflow] val tracker: Tracker
  private[reflow] val input: Out
  private[reflow] val out: Out

  private def superCache: Cache = tracker.getCache(trat.name$)
  /** 在reinforce阶段，从缓存中取回。 **/
  def obtainCache: Option[Cache] = superCache.subs.get(trat.name$)
  def myCache(create: Boolean = false): Out = if (create) {
    superCache.caches.getOrElseUpdate(trat.name$, new Out(Helper.Keys.empty()))
  } else superCache.caches.get(trat.name$).orNull
  def cache[V](key: String, value: V): Unit = myCache(true).cache(key, value)

  def isSubReflow: Boolean = tracker.isSubReflow
  def isReinforceRequired: Boolean = tracker.isReinforceRequired
  def isReinforcing: Boolean = tracker.isReinforcing
  /**
    * 请求强化运行。
    *
    * @return 之前的任务是否已经请求过, 同{isReinforceRequired()}
    */
  def requireReinforce(): Boolean = tracker.requireReinforce()
}
