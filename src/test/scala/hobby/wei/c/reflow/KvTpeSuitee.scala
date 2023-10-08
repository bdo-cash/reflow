/*
 * Copyright (C) 2023-present, Chenai Nakam(chenai.nakam@gmail.com)
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

import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite

import scala.reflect.runtime.universe._

case class Abc[A, B, C](a: String, b: B, c: C)

class KvTpeSuitee extends AnyFunSuite with BeforeAndAfter {

  val kvTpe  = new KvTpe[Set[List[Array[Int]]]]("xy z")
  val kvTpe1 = new KvTpe("xyz", classOf[Set[List[Array[Int]]]])

  before {
    // ...
  }

  test("object KvTpe.createInstance") {
    assertResult(Abc("xyz", 123, 456.789)) {
      KvTpe.createInstance[Abc[String, Int, Double]]("xyz", 123, 456.7890000)
    }
  }

  test("kvTpe.tpe") {
    assertResult(typeOf[Set[List[Array[Int]]]]) {
      kvTpe.tpe
    }
    assert {
      kvTpe.tpe === typeOf[Set[List[Array[Int]]]]
    }
    assert {
      kvTpe1.tpe !== typeOf[Set[List[Array[Int]]]]
    }
    assert {
      kvTpe1.tpe !== typeOf[Set[List[Array[Int]]]].erasure
    }
    assert {
      kvTpe1.tpe <:< typeOf[Set[List[Array[Int]]]].erasure
    }
  }

  test("kvTpe.rawType") {
    assertResult(KvTpe.clazOf(typeOf[Set[List[String]]])) {
      println(kvTpe.rawType)
      kvTpe.rawType
    }
    assertResult(KvTpe.clazOf(typeOf[Set[List[Array[Int]]]])) {
      println(kvTpe1.rawType)
      kvTpe1.rawType
    }

    /*
     === 会在错误时在控制台输出：
         5 did not equal 6 (FunSuitee.scala:15)
     但 == 不会。
     */
    // assert(5 === 6)

    // expectXxx 已经不再 work，都改为 assertXxx 了。
    //  expectResult(5) {
    //    6
    //  }
    //
    //  expect(5 === 6)
  }

  after {
    // ...
  }
}
