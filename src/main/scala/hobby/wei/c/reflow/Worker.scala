/*
 * Copyright (C) 2017-present, Wei Chou(weichou2010@gmail.com)
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

package hobby.wei.c.reflow

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.reflow.Reflow._
import hobby.wei.c.tool.{Locker, Snatcher}

import scala.util.control.Breaks._

/**
  * 优化的线程池实现。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 11/02/2017
  */
object Worker {
  private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)

  private final val sThreadFactory = new ThreadFactory() {
    private val mIndex = new AtomicInteger(0)

    def newThread(runnable: Runnable): Thread = {
      val thread = new Thread(runnable, "pool-thread-" + Worker.getClass.getName + "#" + mIndex.getAndIncrement())
      resetThread(thread)
      thread
    }
  }

  private var sThreadResetor = new ThreadResetor() {
    override def reset(thread: Thread): Unit = {
      if (thread.getPriority != Thread.NORM_PRIORITY) {
        thread.setPriority(Thread.NORM_PRIORITY)
      }
    }
  }

  private def resetThread(thread: Thread) {
    sThreadResetor.reset(thread)
    Thread.interrupted()
    if (thread.isDaemon) thread.setDaemon(false)
  }

  private var sThreadPoolExecutor: ThreadPoolExecutor = _
  private final val sPoolWorkQueue: BlockingQueue[Runnable] = new LinkedTransferQueue[Runnable]() {
    override def offer(r: Runnable) = {
      /* 如果不放入队列并返回false，会迫使增加线程。但是这样又会导致总是增加线程，而空闲线程得不到重用。
      因此在有空闲线程的情况下就直接放入队列。若大量长任务致使线程数增加到上限，
      则threadPool启动reject流程(见ThreadPoolExecutor构造器的最后一个参数)，此时再插入到本队列。
      这样即完美实现[先增加线程数到最大，再入队列，空闲释放线程]这个基本逻辑。*/
      val b = sThreadPoolExecutor.getActiveCount < sThreadPoolExecutor.getPoolSize && super.offer(r)
      Assist.Monitor.threadPool(sThreadPoolExecutor, b, reject = false)
      b
    }
  }

  private def getExecutor: ThreadPoolExecutor = {
    if (sThreadPoolExecutor.isNull) {
      Locker.syncr {
        if (sThreadPoolExecutor.isNull) {
          val config = Reflow.config
          sThreadPoolExecutor = new ThreadPoolExecutor(config.corePoolSize(), config.maxPoolSize(),
            config.keepAliveTime(), TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory, new RejectedExecutionHandler {
              override def rejectedExecution(r: Runnable, executor: ThreadPoolExecutor): Unit = {
                Assist.Monitor.threadPool(sThreadPoolExecutor, addThread = false, reject = true)
                try {
                  sPoolWorkQueue.offer(r, 0, TimeUnit.MILLISECONDS)
                } catch {
                  case ignore: InterruptedException /*不可能出现*/ =>
                    throw ignore
                }
              }
            })
        }
      }
    }
    sThreadPoolExecutor
  }

  private[reflow] def setThreadResetor(resetor: ThreadResetor) = Locker.syncr {
    sThreadResetor = resetor
  }

  private[reflow] def updateConfig(config: Config) {
    Locker.syncr {
      if (sThreadPoolExecutor.isNull) return
    }
    sThreadPoolExecutor.setCorePoolSize(config.corePoolSize())
    sThreadPoolExecutor.setMaximumPoolSize(config.maxPoolSize())
    sThreadPoolExecutor.setKeepAliveTime(config.keepAliveTime(), TimeUnit.SECONDS)
  }

  object sPreparedBuckets {
    val sTransient = new PriorityBlockingQueue[Runner]
    val sShort = new PriorityBlockingQueue[Runner]
    val sLong = new PriorityBlockingQueue[Runner]
    val sInfinite = new PriorityBlockingQueue[Runner]
    val sQueues = Array[BlockingQueue[Runner]](sTransient, sShort, sLong, sInfinite)

    def queue4(period: Period.Tpe): BlockingQueue[Runner] = {
      import Period._
      period match {
        case TRANSIENT => sTransient
        case SHORT => sShort
        case LONG => sLong
        case INFINITE => sInfinite
        case _ => sLong
      }
    }
  }

  private object sExecuting {
    val sTransient = new AtomicInteger(0)
    val sShort = new AtomicInteger(0)
    val sLong = new AtomicInteger(0)
    val sInfinite = new AtomicInteger(0)
    val sCounters = Array[AtomicInteger](sTransient, sShort, sLong, sInfinite)
  }

  private val sSnatcher = new Snatcher

  def scheduleBuckets() {
    if (!sSnatcher.snatch()) return
    val executor = getExecutor
    breakable {
      while (true) {
        val allowRunLevel = {
          val maxPoolSize = executor.getMaximumPoolSize
          // sTransient和sShort至少会有一个线程，策略就是拼优先级了。不过如果线程已经满载，
          // 此时即使有更高优先级的任务到来，那也得等着，谁叫你来的晚呢！
          /*if (sExecuting.sShort.get() + sExecuting.sLong.get()
                  + sExecuting.sInfinite.get() >= maxPoolSize) {
                      allowRunLevel = 1;
            } else*/
          // 给短任务至少留一个线程，因为后面可能还会有高优先级的短任务。
          // 但假如只有3个以内的线程，其中2个被sInfinite占用，怎么办呢？
          // 1. 有可能某系统根本就没有sLong任务，那么剩下的一个刚好留给短任务；
          // 2. 增加一个最大线程数通常不会对系统造成灾难性的影响，那么应该修改配置Config.
          if (sExecuting.sLong.get() + sExecuting.sInfinite.get() >= maxPoolSize - 1) {
            1
          }
          // 除了长连接等少数长任务外，其它几乎都可以拆分成短任务，因此这里必须限制数量。
          else if (sExecuting.sInfinite.get() >= maxPoolSize * 2 / 3) {
            2
          } else 3
        }
        var runner: Runner = null
        var index = -1
        for (i <- 0 to Math.min(allowRunLevel, sPreparedBuckets.sQueues.length - 1)) {
          val r = sPreparedBuckets.sQueues(i).peek()
          if (r.nonNull && (runner.isNull || // 值越小优先级越大
            ((r.trat.priority$ + r.trat.period$.weight /*采用混合优先级*/)
              < runner.trat.priority$ + runner.trat.period$.weight))) {
            runner = r
            index = i
          }
        }
        if (runner.isNull) if (!sSnatcher.glance()) break
        else {
          // 队列结构可能发生改变，不能用poll(); 而remove()是安全的：runner都是重新new出来的，不会出现重复。
          if (sPreparedBuckets.sQueues(index).remove(runner)) {
            sExecuting.sCounters(index).incrementAndGet
            executor.execute(new Runnable {
              override def run(): Unit = {
                try {
                  runner.run()
                } catch {
                  case ignore: Throwable => Assist.Monitor.threadPoolError(ignore)
                } finally {
                  // runner.run()里的endMe()会触发本调度方法，但发生在本句递减计数之前，因此调度几乎无效，已删。
                  sExecuting.sCounters(index).decrementAndGet
                  resetThread(Thread.currentThread)
                  scheduleBuckets()
                }
              }
            })
          }
        }
      }
    }
  }

  class Runner(val trat: Trait[_ <: Task], runnable: Runnable) extends Runnable with Comparable[Runner] {

    override def compareTo(o: Runner) = Integer.compare(trat.priority$, o.trat.priority$)

    override def run(): Unit = runnable.run()
  }
}
