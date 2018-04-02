package sample

import org.scalatest.{BeforeAndAfter, FunSuite}

class FunSuitee extends FunSuite with BeforeAndAfter {
  // http://doc.scalatest.org/1.8/org/scalatest/BeforeAndAfter.html
  before {
    // ...
  }

  test("测试 expect 的用法") {
    assertResult(5) {
      // 6
      5
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
