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

import java.util
import java.util.concurrent.{LinkedBlockingQueue => _, _}
import hobby.chenai.nakam.lang.J2S.Run
import hobby.wei.c.tool
import org.scalatest._

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
class SnatcherSpec extends AsyncFeatureSpec with GivenWhenThen {
  @volatile var count = -1

  Feature("Snatcher 并发测试") {
    Scenario("测试") {
      val snatcher = new tool.Snatcher.ActionQueue
      val future = new FutureTask[Int](new Callable[Int] {
        override def call() = count
      })
      (0 until 300).foreach { _ =>
        sThreadPoolExecutor.submit(new Runnable {
          override def run(): Unit = while (true) {
            sThreadPoolExecutor.submit {
              snatcher.queueAction {
                count += 1
                println(s"Snatcher第${count}次计算~~~~~")
              }.run$
            }
            future.run()
          }
        })
      }
      future.get()
      assert(true)
    }
  }

  private lazy val sPoolWorkQueue: BlockingQueue[Runnable] = new util.concurrent.LinkedBlockingQueue[Runnable](2048000) {
    override def offer(r: Runnable) = {
      /* 如果不放入队列并返回false，会迫使增加线程。但是这样又会导致总是增加线程，而空闲线程得不到重用。
      因此在有空闲线程的情况下就直接放入队列。若大量长任务致使线程数增加到上限，
      则threadPool启动reject流程(见ThreadPoolExecutor构造器的最后一个参数)，此时再插入到本队列。
      这样即完美实现[先增加线程数到最大，再入队列，空闲释放线程]这个基本逻辑。*/
      val b = sThreadPoolExecutor.getActiveCount < sThreadPoolExecutor.getPoolSize && super.offer(r)
      b
    }
  }

  lazy val sThreadPoolExecutor: ThreadPoolExecutor = new ThreadPoolExecutor(8, 500,
    10, TimeUnit.SECONDS, sPoolWorkQueue, new ThreadFactory {
      override def newThread(r: Runnable) = new Thread(r)
    }, new RejectedExecutionHandler {
      override def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit = {
      }
    })
}
