/*
 * Copyright (C) 2020-present, Chenai Nakam(chenai.nakam@gmail.com)
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

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.lang.J2S.future2Scala
import hobby.wei.c.reflow.{GlobalTrack, Out, Poster, Reflow, State}
import hobby.wei.c.reflow
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.lite._
import hobby.wei.c.reflow.Reflow.GlobalTrack.GlobalTrackObserver
import org.scalatest.{AsyncFeatureSpec, BeforeAndAfter, BeforeAndAfterAll, GivenWhenThen}
import java.util.concurrent.{Callable, FutureTask}

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 04/07/2020
  */
class LiteSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    Reflow.setDebugMode(false)

    Reflow.GlobalTrack.registerObserver(new GlobalTrackObserver {
      override def onUpdate(current: GlobalTrack, items: All): Unit = {
        if (!current.isSubReflow && current.scheduler.getState == State.EXECUTING) {
          println(s"++++++++++++++++++++[[[current.state:${current.scheduler.getState}")
//          items().foreach(println)
          println(current)
          println("--------------------]]]")
        }
      }
    })(null)
  }

  trait AbsTag extends TAG.ClassName {
    Reflow.logger.i(toString)(implicitly)
    override def toString = className.toString
  }
  class Aaa extends AbsTag

  class Bbb(val aaa: Aaa) extends AbsTag {
    override def toString = s"${super.toString}(${aaa.toString})"
  }

  class Ccc(val bbb: Bbb) extends AbsTag {
    override def toString = s"${super.toString}(${bbb.toString})"
  }

  class Ddd(val ccc: Ccc) extends AbsTag {
    override def toString = s"${super.toString}(${ccc.toString})"
  }

  implicit lazy val a2b   = Task[Aaa, Bbb]() { (aaa, ctx) => new Bbb(aaa) }
  implicit lazy val b2c   = Task[Bbb, Ccc]() { (bbb, ctx) => new Ccc(bbb) }
  implicit lazy val c2a   = Task[Ccc, Aaa]() { (ccc, ctx) => ccc.bbb.aaa }
  implicit lazy val c2b   = Task[Ccc, Bbb]() { (ccc, ctx) => ccc.bbb }
  implicit lazy val c2d   = Task[Ccc, Ddd]() { (ccc, ctx) => new Ddd(ccc) }
  implicit lazy val c2abc = c2a >>> a2b >>> b2c

  implicit lazy val strategy       = FullDose
  implicit lazy val poster: Poster = null

  lazy val feedback = new reflow.Feedback.Adapter

  Feature("使用 reflow.lite 库简化 Reflow 编码") {
    Scenario("简单`【串】行任务`组装") {
      info("以上定义了一些任务")
      info("再定义一个输入：")
      val input = Task(new Aaa)

      Then("组装任务：")
      info("1. 利用`类型匹配 + 隐世转换`自动组装；")

      input.next[Bbb].next[Ccc].next[Ddd].run() sync ()

      info("2. 直接用任务的`引用`组装；")
      input >>> a2b >>> b2c >>> c2d run () sync ()

      info("这两种方法是等价的，后面跟`run()`即可运行。")

      When("调用`run(feedback)(strategy，poster)`运行")
      info("观察输出")
      assert(true)
    }

    Scenario("`【串/并】行任务`混合组装") {
      val pars =
        (
          c2d
          +>>
          c2abc.inPar("name#c2abc", "c2abc`串行`混入`并行`")
          +>>
          (c2b >>> b2c >>> c2a >>> a2b).inPar()
          +>>
          c2a
        ) **> { (d, c, b, a, ctx) =>
          info(a.toString)
          info(b.toString)
          info(c.toString)
          info(d.toString)
          d
        }
      Input(new Aaa) >>> a2b >>> b2c >>> pars run () sync ()

      assert(true)
    }

    Scenario("带有输入类型的 `Pulse` 流处理器") {
      val pars =
        (
//          c2d
//          +>>
          (c2abc >>> c2d) //.inPar("name#c2abc", "c2abc`串行`混入`并行`")
          +>>
          (c2b >>> b2c >>> c2a >>> a2b)
          +>>
          c2b
          +>>
          c2a
        ) **> { (d, c, b, a, ctx) =>
//          info(a.toString)
//          info(b.toString)
//          info(c.toString)
//          info(d.toString)
          d
        }

      @volatile var callableOut: Int = 0
      val future = new FutureTask[Int](new Callable[Int] {
        override def call() = callableOut
      })

      val pulse = (Input(new Aaa) >>> a2b >>> b2c >>> pars) pulse (new reflow.Pulse.Feedback.Lite[Ddd] {
        override def onStart(serialNum: Long): Unit = println(
          s"[onStart] |||||||||||||||||||||||||||||||||||||||||||| $serialNum ||||||||||||||||||||||||||||||||||||||||||||"
        )
        override def onComplete(serialNum: Long, out: Out): Unit = {
          super.onComplete(serialNum, out)
          if (serialNum == 19) {
            callableOut = 19
            future.run()
          }
          println(
            s"[onComplete] ======================================== $serialNum ========================================"
          )
        }
        override def onAbort(serialNum: Long, trigger: Option[Intent]): Unit = println(
          s"[onAbort] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! $serialNum, ${trigger.map(_.name$).orNull} !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
        )
        override def onFailed(serialNum: Long, trat: Intent, e: Exception): Unit = println(
          s"[onFailed] ?????????????????????????????????????????? $serialNum, ${trat.name$}, ${e.getMessage} ??????????????????????????????????????????"
        )

        override def liteOnComplete(serialNum: Long, value: Option[Ddd]): Unit = println(
          s"[liteOnComplete] ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ $serialNum, $value ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
        )
      }, abortIfError = true)

      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)

      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)
      pulse.input(new Aaa)

      future map { result =>
        require(pulse.pulse.isCurrAllCompleted)

        assertResult(19)(result)
      }
    }
  }
}
