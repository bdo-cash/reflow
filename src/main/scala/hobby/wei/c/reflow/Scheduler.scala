/*
 * Copyright (C) 2016-present, Wei Chou(weichou2010@gmail.com)
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

import java.util.concurrent.locks.ReentrantLock
import hobby.chenai.nakam.lang.J2S
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.State._
import hobby.wei.c.tool.Locker

import scala.collection._

/**
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 02/07/2016
  */
trait Scheduler {
  /**
    * @see #sync(boolean, long)
    */
  @deprecated(message = "好用但应慎用。会block住当前线程，几乎是不需要的。", since = "0.0.1")
  def sync(reinforce: Boolean = false): Out

  /**
    * 等待任务运行完毕并输出最终结果。如果没有拿到结果(已经{@link #isDone()}), 则会重新{@link Impl#start()} 启动。但
    * 这种情况极少见。
    *
    * @param reinforce    是否等待`reinforce`阶段结束。
    * @param milliseconds 延迟的deadline, 单位：毫秒。
    * @return 任务的最终结果，不会为`null`。
    * @throws InterruptedException 到达deadline了或者被中断。
    */
  @deprecated(message = "好用但应慎用。会block住当前线程，几乎是不需要的。", since = "0.0.1")
  @throws[InterruptedException]
  def sync(reinforce: Boolean, milliseconds: Long): Option[Out]

  def abort(): Unit

  def getState: Tpe

  /**
    * 判断整个任务流是否运行结束。
    * 注意: 此时的{@link #getState()}值可能是{@link State#COMPLETED}、{@link State#FAILED}、
    * {@link State#ABORTED}或{@link State#UPDATED}中的某一种。
    *
    * @return true 已结束。
    */
  def isDone: Boolean
}

object Scheduler {
  /**
    * @author Wei Chou(weichou2010@gmail.com)
    * @version 1.0, 07/08/2016
    */
  private[reflow] class Impl(reflow: Reflow, traitIn: Trait, inputTrans: immutable.Set[Transformer[_ <: AnyRef, _ <: AnyRef]],
                             feedback: Feedback, policy: Policy, outer: Env = null, pulse: Pulse.Interact = null) extends Scheduler {
    private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)
    private lazy val state = new State$()
    @volatile private var delegatorRef: ref.WeakReference[Tracker.Impl] = _

    private[reflow] def start$(): this.type = {
      var permit = false
      Locker.syncr {
        if (isDone) {
          state.reset()
          permit = true
        } else {
          permit = !state.isOverrided
        }
      }
      if (permit && state.forward(PENDING) /*可看作原子锁*/ ) {
        val tracker = new Tracker.Impl(reflow, traitIn, inputTrans, state, feedback, policy, Option(outer), pulse)
        // tracker启动之后被线程引用, 任务完毕之后被线程释放, 同时被gc。
        // 这里增加一层弱引用, 避免在任务完毕之后得不到释放。
        delegatorRef = new ref.WeakReference[Tracker.Impl](tracker)
        tracker.start$()
        this
      } else null
    }

    private[reflow] def getDelegator: Option[Tracker.Impl] = J2S.getRef(delegatorRef)

    override def sync(reinforce: Boolean = false): Out = {
      try {
        sync(reinforce, -1).get
      } catch {
        case e: InterruptedException => throw new IllegalStateException(e)
      }
    }

    @throws[InterruptedException]
    override def sync(reinforce: Boolean, milliseconds: Long): Option[Out] = {
      val begin = System.nanoTime
      var loop = true
      var delegator: Tracker.Impl = null
      while (loop) {
        getDelegator.fold {
          Option(start$()).fold {
            // 如果还没拿到, 说明其他线程也在同时start().
            Thread.`yield`() // 那就等一下下再看看
          } { _ => } // 重启成功，再走一次循环拿值。
        } { d =>
          delegator = d
          loop = false
        }
      }
      delegator.sync(reinforce, if (milliseconds == -1) -1 else milliseconds - ((System.nanoTime - begin) / 1e6).toLong)
    }

    override def abort(): Unit = getDelegator.fold()(_.abort())

    override def getState: State.Tpe = state.get

    override def isDone: Boolean = {
      val state = this.state.get
      state == COMPLETED && getDelegator.fold(true /*若引用释放,说明任务已不被线程引用,即运行完毕。*/) {
        !_.isReinforceRequired
      } || state == FAILED || state == ABORTED || state == UPDATED
    }
  }

  class State$ {
    private implicit lazy val lock: ReentrantLock = Locker.getLockr(this)

    @volatile private var state = State.IDLE
    @volatile private var state$ = State.IDLE
    @volatile private var overrided = false

    def forward(state: State.Tpe): Boolean = Locker.syncr {
      if (this.state.canOverrideWith(state)) {
        this.state = state
        this.state$ = state
        if (!overrided) overrided = true
        true
      } else false
    }

    /**
      * 更新中断后的状态。
      *
      * @return 返回值与forward(State)方法互补的值。
      */
    def abort(): Boolean = Locker.syncr {
      state$ = State.ABORTED
      state match {
        case State.REINFORCE_PENDING | State.REINFORCING =>
          state = State.COMPLETED
          true
        case State.COMPLETED | State.UPDATED => true
        case _ => false
      }
    }

    def get: Tpe = state

    def get$: Tpe = state$

    private[reflow] def reset(): Unit = Locker.syncr {
      state = State.IDLE
      state$ = State.IDLE
    }

    /**
      * 可用于标识是否启动过。
      */
    private[reflow] def isOverrided: Boolean = overrided
  }
}
