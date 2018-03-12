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
  * @version 1.0, 14/08/2016
  */
object Helper {
  object Keys {
    def empty(): immutable.Set[Key$[_]] = immutable.Set.empty

    def add(key: Key$[_]): Builder = new Builder().add(key)

    class Builder private[reflow]() {
      private val keys = new mutable.HashSet[Key$[_]]

      def add(key: Key$[_]): Builder = {
        keys.add(key.ensuring(_.nonNull))
        this
      }

      def ok(): immutable.Set[Key$[_]] = keys.to[immutable.Set]
    }
  }

  object Transformers {
    /**
      * 将任务的某个输出在转换之后仍然保留。通过增加一个输出即输入转换。
      */
    def retain[O](key: Key$[O]): Transformer[O, O] = new Transformer[O, O](key, key) {
      override protected def transform(in: O) = in
    }

    def add(trans: Transformer[Any, Any]): Builder = new Builder().add(trans)

    class Builder private[reflow]() {
      private val trans = new mutable.HashSet[Transformer[Any, Any]]

      def add[IN, OUT](t: Transformer[IN, OUT]): Builder = {
        trans.add(t.ensuring(_.nonNull).as[Transformer[Any, Any]])
        this
      }

      def retain[O](key: Key$[O]): Builder = add(Transformers.retain[O](key))

      def ok(): immutable.Set[Transformer[Any, Any]] = trans.toSet[Transformer[Any, Any]]
    }
  }
}
