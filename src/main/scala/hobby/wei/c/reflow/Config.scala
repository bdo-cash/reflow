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

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 11/04/2016
  */
class Config protected() {
  require(maxPoolSize >= corePoolSize && corePoolSize > 0)

  lazy val corePoolSize = Config.CPU_COUNT + 1

  lazy val maxPoolSize = Config.CPU_COUNT * 5 + 1

  /**
    * 空闲线程保留时间。单位: 秒。
    */
  lazy val keepAliveTime = 5
}

object Config {
  lazy val CPU_COUNT = Runtime.getRuntime.availableProcessors
  lazy val DEF = new Config

  lazy val SINGLE_THREAD = Config(1, 1)

  def apply(coreSize: => Int, maxSize: => Int, aliveTime: => Int = DEF.keepAliveTime): Config = new Config() {
    override lazy val corePoolSize = coreSize

    override lazy val maxPoolSize = maxSize

    override lazy val keepAliveTime = aliveTime
  }
}
