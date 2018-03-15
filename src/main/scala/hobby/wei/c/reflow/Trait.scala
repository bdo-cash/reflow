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

import java.util.concurrent.atomic.AtomicInteger
import hobby.chenai.nakam.lang.J2S.NonNull
import hobby.wei.c.reflow.Assist._
import hobby.wei.c.reflow.Reflow.{Period, _}
import hobby.wei.c.reflow.Tracker.SubReflowTask

import scala.collection.{mutable, _}

/**
  * 用于发布{@link Task}的I/O接口及调度策略信息。
  * 而{@link Task}本身仅用于定义任务实现。
  * <p>
  * 注意: 实例不应保留状态。
  *
  * @tparam T
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 12/04/2015
  */
trait Trait[T <: Task] extends Equals {
  /**
    * 任务名称。
    */
  protected def name(): String

  /**
    * 创建任务。
    */
  /*protected*/ def newTask(): T

  /**
    * 必须输入的参数keys及value类型(可由初始参数传入, 或者在本Task前面执行的Tasks输出{@link #outs()}而获得)。
    */
  protected def requires(): immutable.Set[Kce[_ <: AnyRef]]

  /**
    * 该任务输出的所有key-value类型。
    */
  protected def outs(): immutable.Set[Kce[_ <: AnyRef]]

  /**
    * 优先级。范围 [ {@link Reflow#P_HIGH P_HIGH} ~ {@link Reflow#P_LOW P_LOW} ]。
    */
  protected def priority(): Int

  /**
    * 任务大概时长。
    */
  protected def period(): Period.Tpe

  /**
    * 任务描述, 将作为进度反馈的部分信息。
    */
  protected def desc(): String

  private[reflow] lazy val name$: String = requireNonEmpty(name())

  private[reflow] lazy val requires$: immutable.Set[Kce[_ <: AnyRef]] = requireKkDiff(requireElemNonNull(requires()))

  private[reflow] lazy val outs$: immutable.Set[Kce[_ <: AnyRef]] = requireKkDiff(requireElemNonNull(outs()))

  private[reflow] lazy val priority$: Int = between(P_HIGH, priority(), P_LOW).toInt

  private[reflow] lazy val period$: Period.Tpe = period().ensuring(_.nonNull)

  private[reflow] lazy val desc$: String = desc().ensuring(_.nonNull /*可以是""*/)

  override def equals(any: scala.Any) = super.equals(any)

  override def canEqual(that: Any) = super.equals(that)

  override def hashCode() = super.hashCode()

  override def toString = "name:%s, requires:%s, out:%s, priority:%s, period:%s, description: %s" format(
    name$, requires$, outs$, priority$, period$, desc$)
}

private[reflow] object Trait {
  private final val sCount = new AtomicInteger(0)

  private[reflow] final class Parallel private[reflow](trats: Seq[Trait[_ <: Task]]) extends Trait[Task] {
    // 提交调度器之后具有不变性
    private val _traits = new mutable.ListBuffer[Trait[_ <: Task]]

    _traits ++= trats

    private[reflow] def this(t: Trait[_ <: Task]) = this(Seq(t))

    private[reflow] def traits() = _traits

    private[reflow] def add(t: Trait[_ <: Task]): Unit = {
      assertf(!t.isInstanceOf[Parallel])
      _traits += t
    }

    private[reflow] def first(): Trait[_ <: Task] = _traits.head

    private[reflow] def last(): Trait[_ <: Task] = _traits.last

    override protected def name() = {
      // 由于不允许同一个队列里面有相同的名字，所以取第一个的名字即可区分。
      classOf[Parallel].getName + "#" + _traits.head.name$
    }

    override def newTask() = ???

    override protected def requires() = Helper.Kces.empty()

    override protected def outs() = Helper.Kces.empty()

    override protected def priority() = Reflow.P_NORMAL

    override protected def period() = ???

    override protected def desc() = ???
  }

  trait Adapter extends Trait[Task] {
    override protected def name() = classOf[Adapter].getName + "#" + sCount.getAndIncrement()

    override protected def requires() = Helper.Kces.empty()

    override protected def outs() = Helper.Kces.empty()

    override protected def priority() = Reflow.P_NORMAL

    override protected def desc() = name$
  }

  private[reflow] final class Input(in: In, outsTrimmed: immutable.Set[Kce[_ <: AnyRef]], override val priority: Int) extends Adapter {
    override protected def name() = classOf[Input].getName + "#" + sCount.getAndIncrement()

    override def newTask() = new Task {
      override protected def doWork(): Unit = in.fillValues(getEnv.out)
    }

    override protected def outs() = outsTrimmed

    override protected def period() = Period.TRANSIENT
  }

  private[reflow] abstract class ReflowTrait(val reflow: Reflow, val feedback: Feedback, val poster: Poster = null) extends Trait[SubReflowTask] {
    override protected def name() = classOf[ReflowTrait].getName + "#" + sCount.getAndIncrement()

    override final def newTask() = new SubReflowTask()
  }
}
