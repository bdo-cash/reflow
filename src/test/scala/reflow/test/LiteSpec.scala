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
import hobby.wei.c.reflow.{GlobalTrack, Poster, Reflow, State}
import hobby.wei.c.reflow
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.lite._
import hobby.wei.c.reflow.Reflow.GlobalTrack.GlobalTrackObserver
import hobby.wei.c.reflow.Trait.ReflowTrait
import org.scalatest.{AsyncFeatureSpec, BeforeAndAfter, BeforeAndAfterAll, GivenWhenThen}
import java.util.concurrent.{Callable, FutureTask}

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.util.control.Breaks

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 04/07/2020
  */
class LiteSpec extends AsyncFeatureSpec with GivenWhenThen with BeforeAndAfter with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    Reflow.setDebugMode(false)
//    Reflow.setConfig(Config(5, 7))
//    Reflow.setConfig(SINGLE_THREAD)

    Reflow.GlobalTrack.registerObserver(new GlobalTrackObserver {
      override def onUpdate(current: GlobalTrack, items: All): Unit = {
        if (!current.isSubReflow && current.scheduler.getState == State.EXECUTING) {
//          println(s"++++++++++++++++++++[[[current.state:${current.scheduler.getState}")
//          items().foreach(println)
//          println(current)
//          println("--------------------]]]")
        }
      }
    })(null)
  }

  trait AbsTag extends TAG.ClassName {
//    Reflow.logger.i(toString)(implicitly)
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
  implicit lazy val d2b   = Task[Ddd, Bbb]() { (ddd, ctx) => ddd.ccc.bbb }
  implicit lazy val b2a   = Task[Bbb, Aaa]() { (bbb, ctx) => bbb.aaa }
  implicit lazy val a2d   = Task[Aaa, Ddd]() { (aaa, ctx) => new Ddd(new Ccc(new Bbb(aaa))) }
  implicit lazy val c2abc = c2a >>> a2b >>> b2c

  implicit lazy val strategy       = FullDose
  implicit lazy val poster: Poster = null

  lazy val feedback = new reflow.Feedback.Adapter

  Feature("使用 reflow.lite 库简化 Reflow 编码") {
    Scenario("简单`【串】行任务`组装") {
      info("以上定义了一些任务")
      info("再定义一个输入：")
      val input = Task[Aaa]

      Then("组装任务：")
      info("1. 利用`类型匹配 + 隐世转换`自动组装；")

      input.next[Bbb].next[Ccc].next[Ddd].run(new Aaa) sync ()

      info("2. 直接用任务的`引用`组装；")
      input >>> a2b >>> b2c >>> c2d run (new Aaa) sync ()

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
      Input[Aaa] >>> a2b >>> b2c >>> pars run (new Aaa) sync ()

      assert(true)
    }

    Scenario("带有输入类型的 `Pulse` 流处理器") {
      val pars =
        (
//          c2d
//          +>>
          (c2abc >>> Task[Ccc, Ddd]() { (ccc, ctx) =>
            if (ctx.input[String]("flag").isEmpty) {
              ctx.cache("flag", "")
              throw new IllegalArgumentException
            } else {
              ctx.cache("flag", "")
              new Ddd(ccc)
            }
          // 一个串行的里面不能有重名的。
          } >>> d2b >>> b2a >>> a2d) //.inPar("name#c2abc", "c2abc`串行`混入`并行`")
          +>>
          (c2b >>> b2c >>> Task[Ccc, Aaa]() { (ccc, ctx) =>
            val n: Integer = ctx.input[Integer]("int").getOrElse(1)
            if (n == 2) {
              throw new IllegalStateException
            } else {
              ctx.cache("int", n + 1)
              ccc.bbb.aaa
            }
          } >>> a2b)
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
      val future                     = new FutureTask[Int](() => callableOut)

      val repeatCount = 1000 // Int.MaxValue

      val pulse = (Input[Aaa] >>> a2b >>> b2c >>> pars) pulse (new reflow.Pulse.Feedback.Lite[Ddd] {
        override def onStart(serialNum: Long): Unit = println(
          s"[onStart] |||||||||||||||||||||||||||||||||||||||||||| $serialNum ||||||||||||||||||||||||||||||||||||||||||||"
        )
        override def onAbort(serialNum: Long, trigger: Option[Intent], parent: Option[ReflowTrait], depth: Int): Unit = {
          callableOut = repeatCount
          future.run()
          println(s"[onAbort] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! $serialNum, ${trigger.map(_.name$).orNull} !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        }
        override def onFailed(serialNum: Long, trat: Intent, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = {
          if (serialNum == repeatCount) {
            callableOut = repeatCount
            future.run()
          }
          println(s"[onFailed] ?????????????????????????????????????????? $serialNum, ${trat.name$}, ${e.getMessage} ??????????????????????????????????????????")
        }

        override def liteOnComplete(serialNum: Long, value: Option[Ddd]): Unit = {
          if (serialNum == repeatCount) {
            callableOut = repeatCount
            future.run()
          }
          println(s"[liteOnComplete] ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ $serialNum, $value ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        }
      }, abortIfError = false, execCapacity = 7)

      Breaks.breakable {
        for (_ <- 0 to repeatCount) {
          val in = new Aaa
          var i  = 0
          while (!pulse.input(in)) {
            if (pulse.pulse.isDone) Breaks.break
            println(s"[pulse.input] @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ wait repeat times: $i @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
            i += 1
            Thread.sleep(250 * i.millis.toMillis)
          }
        }
      }

      future map { result =>
        require(pulse.pulse.isCurrAllCompleted)

        assertResult(repeatCount)(result)
      }
    }

    Scenario("`Pulse` 的多层嵌套组装测试") {
      val pars = {
        // 切记：不能用 val 替换 def，否则不但有并行的同名的任务，而且深层的【同名同层】会导致 Pulse.Interact 接口无法辨识而出现阻塞的情况。
        def p = (c2d +>> c2b) **> { (d, _, ctx) => println(":: 0"); ctx.progress(1, 2); d } +|- { (_, d) => d }
        @tailrec
        def loop(s: () => Serial[Ccc, Ddd], times: Int = 0, limit: Int = 10): Serial[Ccc, Ddd] =
          if (times >= limit) s()
          else {
            def p =
              (
                s() +|- { (_, d) => d }
                +>>
                s()
                +>>
                s()
              ) **> { (d, _, _, ctx) => println(s":: ${times + 1} " + ("::" * (times + 1))); ctx.progress(1, 2); d }
            loop(() => p, times + 1, limit)
          }
        loop(() => p, limit = 3) // 最大 depth = 2 * limit + 1, depth 从 0 开始。
      }

      @volatile var callableOut: Int = 0
      val future                     = new FutureTask[Int](() => callableOut)

      val repeatCount = 1000 // Int.MaxValue

      val pulse = (Input[Aaa] >>> a2b >>> b2c >>> pars) pulse (new reflow.Pulse.Feedback.Lite[Ddd] {
        override def onStart(serialNum: Long): Unit = {}
        override def onAbort(serialNum: Long, trigger: Option[Intent], parent: Option[ReflowTrait], depth: Int): Unit = {
          callableOut = repeatCount
          future.run()
          println(s"[onAbort] !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! $serialNum, ${trigger.map(_.name$).orNull} !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        }
        override def onFailed(serialNum: Long, trat: Intent, parent: Option[ReflowTrait], depth: Int, e: Exception): Unit = {
          if (serialNum == repeatCount) {
            callableOut = repeatCount
            future.run()
          }
          future.run()
          println(s"[onFailed] ?????????????????????????????????????????? $serialNum, ${trat.name$}, ${e.getMessage} ??????????????????????????????????????????")
        }

        override def liteOnComplete(serialNum: Long, value: Option[Ddd]): Unit = {
          if (serialNum == repeatCount) {
            callableOut = repeatCount
            future.run()
          }
          if (serialNum % 700 == 0)
            println(s"[liteOnComplete] ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ $serialNum, $value ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
          if (serialNum == repeatCount)
            println(s"[liteOnComplete] ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ DONE | $serialNum, $value ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~")
        }
      }, abortIfError = true, execCapacity = 7, globalTrack = false)

      Breaks.breakable {
        for (_ <- 0 to repeatCount) {
          val in = new Aaa
          var i  = 0
          while (!pulse.input(in)) {
            if (pulse.pulse.isDone) Breaks.break
            println(s"[pulse.input] @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ wait repeat times: $i @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@")
            i += 1
            Thread.sleep(250 * i.millis.toMillis)
          }
        }
      }

      future map { result =>
        require(pulse.pulse.isCurrAllCompleted)

        assertResult(repeatCount)(result)
      }
    }
  }
}
