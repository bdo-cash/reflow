package sample

import org.scalatest.funspec.AnyFunSpec

class SetSpec extends AnyFunSpec {
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
