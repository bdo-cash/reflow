package sample

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AsyncFeatureSpec
import scala.concurrent.{ExecutionContext, Future}

// Defining actor messages
case object IsOn
case object PressPowerButton

class TVSetActor { // Simulating an actor
  private var on: Boolean = false

  def !(msg: PressPowerButton.type): Unit = synchronized {
    on = !on
  }

  def ?(msg: IsOn.type)(implicit c: ExecutionContext): Future[Boolean] = Future {
    synchronized {
      on
    }
  }
}

class TVSetActorSpec extends AsyncFeatureSpec with GivenWhenThen {
  implicit override def executionContext: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  info("As a TV set owner")
  info("I want to be able to turn the TV on and off")
  info("So I can watch TV when I want")
  info("And save energy when I'm not watching TV")

  Feature("TV power button") {
    Scenario("User presses power button when TV is off") {

      Given("a TV set that is switched off")
      val tvSetActor = new TVSetActor

      When("the power button is pressed")
      tvSetActor ! PressPowerButton

      Then("the TV should switch on")
      val futureBoolean = tvSetActor ? IsOn
      futureBoolean map { isOn => assert(isOn) }
    }

    Scenario("User presses power button when TV is on") {

      Given("a TV set that is switched on")
      val tvSetActor = new TVSetActor
      tvSetActor ! PressPowerButton

      When("the power button is pressed")
      tvSetActor ! PressPowerButton

      Then("the TV should switch off")
      val futureBoolean = tvSetActor ? IsOn
      futureBoolean map { isOn => assert(!isOn) }
    }
  }
}
