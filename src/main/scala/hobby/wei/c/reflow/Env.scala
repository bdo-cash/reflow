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

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.reflow.Reflow.{debugMode, logger => log}
import hobby.wei.c.reflow.Trait.ReflowTrait

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 31/01/2018
  */
private[reflow] trait Env extends TAG.ClassName {
  private[reflow] val trat: Trait
  private[reflow] val tracker: Tracker
  private[reflow] final lazy val input: Out = {
    val in = new Out(trat.requires$)
    in.fillWith(tracker.getPrevOutFlow)
    val cached = if (isPulseMode && !tracker.isInput(trat)) tracker.pulse.getCache(subDepth, trat, parent) else myCache(create = false)
    if (cached.nonNull) in.cache(cached)
    if (debugMode) log.i("input: %s.", in)
    in
  }
  private[reflow] final lazy val out: Out = new Out(trat.outs$)

  private final def superCache: ReinforceCache = tracker.getCache

  /** 在reinforce阶段，从缓存中取回。 */
  private[reflow] final def obtainCache: Option[ReinforceCache] = {
    assert(isReinforcing)
    superCache.subs.get(trat.name$)
  }

  /** `Task`的当前缓存。 */
  private[reflow] final def myCache(create: Boolean = false): Out = if (create) {
    superCache.caches.getOrElseUpdate(trat.name$, new Out(Helper.KvTpes.empty()))
  } else superCache.caches.get(trat.name$).orNull

  private[reflow] final def cache[V](key: String, value: V): Unit = myCache(create = true).cache(key, value)

  final def subDepth: Int = tracker.subDepth

  final lazy val weightPar: Int = tracker.reflow.basis.weightedPeriod(trat)

  /**
    * 请求强化运行。
    *
    * @return （在本任务或者本次调用）之前是否已经请求过, 同`isReinforceRequired()`。
    */
  final def requireReinforce(t: Trait = trat): Boolean = tracker.requireReinforce(t)
  final def isReinforceRequired: Boolean = tracker.isReinforceRequired
  final def isReinforcing: Boolean = tracker.isReinforcing
  final def isPulseMode: Boolean = tracker.isPulseMode
  final def isSubReflow: Boolean = tracker.isSubReflow
  final def parent: Option[ReflowTrait] = tracker.outer.map(_.trat.as[ReflowTrait])
}

private[reflow] object Env {
  def apply(_trat: Trait, _tracker: Tracker): Env = new Env {
    override private[reflow] val trat = _trat
    override private[reflow] val tracker = _tracker
  }
}
