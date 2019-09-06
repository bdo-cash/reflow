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

package reflow.test

import java.util.concurrent.{Callable, FutureTask}
import hobby.chenai.nakam.lang.J2S._
import hobby.chenai.nakam.tool.pool.S._2S
import hobby.wei.c.reflow._
import hobby.wei.c.reflow.implicits._
import org.scalatest.{AsyncFeatureSpec, BeforeAndAfter, BeforeAndAfterAll, GivenWhenThen}

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 07/07/2018
  */
class PulseSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {
  override protected def beforeAll(): Unit = {
    Reflow.setDebugMode(false)
  }

  implicit lazy val poster: Poster = null

  Feature("`Pulse`脉冲步进流式数据处理") {
    Scenario("数据将流经`集成任务集（Reflow）`，并始终保持输入时的先后顺序，多组数据会排队进入同一个任务。") {
      Given("创建一个`reflowX`作为嵌套的`SubReflow`")
      val reflowX = Reflow.create(Trait("pulse-0", LONG, kces.str) { ctx =>
        val times: Int = ctx.input(kces.int).getOrElse[Integer](0)
        println(s"再次进入任务${ctx.trat.name$}，缓存参数被累加：${times}。")
        if (times == 1) {
          println(s"------------------->(times:$times, ${ctx.trat.name$})休眠中，后续进入的数据会等待...")
          Thread.sleep(6000)
        }
        ctx.cache[Integer](kces.int, times + 1)
        ctx.output(kces.str, s"name:${ctx.trat.name$}, 第${times}次。")
      })
        .next(Trait("pulse-1", LONG, kces.str, kces.str) { ctx =>
          val times: Int = ctx.input(kces.int).getOrElse[Integer](0)
          if (times % 2 == 0) {
            println(s"------------------->(times:$times, ${ctx.trat.name$})休眠中，后续进入的数据会等待...")
            Thread.sleep(8000)
          }
          ctx.cache[Integer](kces.int, times + 1)
          ctx.output(kces.str, times + "")
        })
        .submit(kces.str)

      Given("创建一个顶层`reflow`")
      val reflow = Reflow.create(Trait("pulse-0", LONG, kces.str) { ctx =>
        val times: Int = ctx.input(kces.int).getOrElse[Integer](0)
        println(s"再次进入任务${ctx.trat.name$}，缓存参数被累加：${times}。")
        if (times == 1) {
          println(s"------------------->(times:$times, ${ctx.trat.name$})休眠中，后续进入的数据会等待...")
          Thread.sleep(5000)
        }
        ctx.cache[Integer](kces.int, times + 1)
        ctx.output(kces.str, s"name:${ctx.trat.name$}, 第${times}次。")
      }).and(reflowX.torat("pulseX0"))
        .next(Trait("pulse-1", LONG, kces.str, kces.str) { ctx =>
          val times: Int = ctx.input(kces.int).getOrElse[Integer](0)
          if (times % 2 == 0) {
            println(s"------------------->(times:$times, ${ctx.trat.name$})休眠中，后续进入的数据会等待...")
            Thread.sleep(3000)
          }
          ctx.cache[Integer](kces.int, times + 1)
          ctx.output(kces.str, times + "")
        })
        .submit(kces.str)

      @volatile var callableOut: Out = null
      val future = new FutureTask[Out](new Callable[Out] {
        override def call() = callableOut
      })

      Then("创建 pulse")
      lazy val pulse: Pulse = reflow.pulse(null, feedback, true)

      lazy val feedback = new Pulse.Feedback.Adapter {
        override def onComplete(serialNum: Long, out: Out): Unit = {
          if (serialNum == 4) {
            callableOut = out
            future.run()
            println("abort()...")
            pulse.abort()
          }
        }

        override def onAbort(serialNum: Long, trigger: Option[Trait]): Unit = {
          println("[onAbort]trigger:" + trigger.map(_.name$).orNull)
        }

        override def onFailed(serialNum: Long, trat: Trait, e: Exception): Unit = {
          println("[onAbort]trat:" + trat.name$.s + ", e:" + (e.getClass.getName + ":" + e.getMessage))
        }
      }

      var data = (kces.str, "66666") :: Nil
      data ::= data.head
      data ::= data.head
      data ::= data.head
      data ::= data.head
      Then("创建数据：" + data)

      When("向 pulse 输入数据")
      data.foreach(pulse.input(_))

      Then("等待结果")
      future map { out => assertResult("4")(out(kces.str)) }
    }
  }

  override protected def afterAll(): Unit = {
    info("All test done!!!~")
    Reflow.shutdown()
  }

  before {
    info("++++++++++>>>")
  }

  after {
    info("<<<----------")
  }
}
