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

import hobby.chenai.nakam.lang.J2S.{future2Scala, Obiter, Run}
import hobby.wei.c.reflow.Reflow
import hobby.wei.c.tool
import hobby.wei.c.tool.Snatcher.ReentrantLockError
import org.scalatest._
import java.util
import java.util.concurrent.{LinkedBlockingQueue => _, _}

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class SnatcherSpec extends AsyncFeatureSpec with GivenWhenThen {
  Reflow.setDebugMode(true)

  Feature("Snatcher 测试") {
    /*Scenario("并发不同步测试") { // 并不会发生
      @volatile var count = -1L
      val queue = new ConcurrentLinkedQueue[Option[Any]]
      while (true) {
        println(s"----------------------count:${count += 1; count}-------------------------------------")
        while (!queue.offer(None)) Thread.`yield`()
        try {
          queue.remove()
        } catch {
          case t: Throwable => t.printStackTrace()
            throw t
        }
      }
      assert(true)
    }*/

    Scenario("Reentrant 抛出异常测试") {
      val snatcher = new tool.Snatcher
      new Thread(snatcher.tryOn({
        info("第 1 层 tryOn(...)")
        snatcher.tryOn({
          info("第 2 层 tryOn(...)")
          snatcher.tryOn({
            info("第 3 层 tryOn(...)")
            info("do something")
            Thread.sleep(3000)
            info("第 3 层 tryOn(...), Done.")
          }, true)
          info("第 2 层 tryOn(...), Done.")
        }, true)
        info("第 1 层 tryOn(...), Done.")
      }, true).run$).start()

      Thread.sleep(2000)
      assertThrows[ReentrantLockError] {
        snatcher.tryOn({
          info("第 1 层")
          snatcher.tryOn({
            info("第 2 层")
            info("第 2 层, Done.")
          }, true)
          info("第 1 层, Done.")
        }, true)
      }
    }

    Scenario("传名参数") {
      val snatcher = new tool.Snatcher.ActionQueue(false)
      val future = new FutureTask[Int](new Callable[Int] {
        override def call() = 0
      })
      sThreadPoolExecutor.execute({
        snatcher.queAc {
          println("抢占 | Holding...")
          Thread.sleep(5000)
          println("抢占 | Done.")
        }
        }.run$)
      Thread.sleep(2000)
      snatcher.queAc {
        println("在 snatcher 调度器内部执行")
      }
      snatcher.queAc {
        println("在 snatcher 调度器内部执行")
        println("在 queueAction Done 之后输出，即为正确。")
        future.run()
      }
      println("queueAction Done.")

      future.map(_ => assert(true))
    }

    Scenario("测试 Snatcher.ActionQueue 并发问题") {
      if (false) {
        @volatile var count = -1L
        @volatile var stop = false

        val snatcher = new tool.Snatcher.ActionQueue(true)
        val future = new FutureTask[Long](new Callable[Long] {
          override def call() = count
        })
        (0 until 3).foreach { i =>
          sThreadPoolExecutor.submit(new Runnable {
            override def run(): Unit = while (!stop) {
              println(s"------------ $i ----------------submit snatcher queueAction, active:${sThreadPoolExecutor.getActiveCount}, queue:${sThreadPoolExecutor.getQueue.size}")
              sThreadPoolExecutor.submit {
                {
                  try {
                    snatcher.queAc(canAbandon = (Math.random() >= 0.6).obiter {
                      println("-----------------------------------------------------------")
                    }) {} { _ =>
                      count += 1
                      println(s"~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~thread:${Thread.currentThread.getId}~~~~~~~~~~~~~~~~~~~~~~~~~Snatcher第${count}次计算~~~~~")
                    }
                  } catch {
                    case t: Throwable => t.printStackTrace()
                      stop = true
                      println(s"Throwable<<<<<<thread:${Thread.currentThread.getId}>>>>>>第${count}次计算")
                  }
                  }.run$
              }
            }
          })
        }
        future.get()
      }
      assert(true)
    }
  }

  private lazy val sPoolWorkQueue: BlockingQueue[Runnable] = new util.concurrent.LinkedBlockingQueue[Runnable](2048) {
    override def offer(r: Runnable) = {
      /* 如果不放入队列并返回false，会迫使增加线程。但是这样又会导致总是增加线程，而空闲线程得不到重用。
      因此在有空闲线程的情况下就直接放入队列。若大量长任务致使线程数增加到上限，
      则threadPool启动reject流程(见ThreadPoolExecutor构造器的最后一个参数)，此时再插入到本队列。
      这样即完美实现[先增加线程数到最大，再入队列，空闲释放线程]这个基本逻辑。*/
      val b = sThreadPoolExecutor.getActiveCount < sThreadPoolExecutor.getPoolSize && super.offer(r)
      b
    }
  }

  lazy val sThreadPoolExecutor: ThreadPoolExecutor = new ThreadPoolExecutor(8, 24,
    10, TimeUnit.SECONDS, sPoolWorkQueue, new ThreadFactory {
      override def newThread(r: Runnable) = new Thread(r)
    }, new RejectedExecutionHandler {
      override def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit = {
      }
    })
}
