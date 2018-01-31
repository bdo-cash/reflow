/*
 * Copyright (C) 2017-present, Chenai Nakam(chenai.nakam@gmail.com)
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

package sample

import org.scalatest.Spec

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 15/10/2017
  */
class SetSpec extends Spec {
  object `A Set` {
    object `when empty` {
      def `should have size 0` {
        assert(Set.empty.size === 0)
      }

      def `当 '.head' 被调用，应该产生 NoSuchElementException 异常` {
        assertThrows[NoSuchElementException] {
          Set.empty.head
        }
      }
    }
  }
}
