/*
 * Copyright (C) 2018-present, Chenai Nakam(chenai.nakam@gmail.com)
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

import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 23/03/2018
  */
class TVSetTest extends FeatureSpec with GivenWhenThen with Matchers {
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
