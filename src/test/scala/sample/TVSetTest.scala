package sample

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

class TVSetTest extends AnyFeatureSpec with GivenWhenThen with Matchers {
  info("As a TV Set owner")
  info("I want to be able to turn the TV on and off")
  info("So I can watch TV when I want")
  info("And save energy when I'm not watching TV")

  Feature("TV power button") {
    Scenario("User press power button when TV is off") {
      Given("a TV set that is switched off")
      val tv = new TVSet
      tv.isOn should be(false)

      When("The power button is pressed")
      tv.pressPowerButton

      Then("The TV should switch on")
      tv.isOn should be(true)
    }
  }
}

class TVSet {
  private var _on = false

  def isOn = _on

  def pressPowerButton: Unit = {
    _on = true
  }
}
