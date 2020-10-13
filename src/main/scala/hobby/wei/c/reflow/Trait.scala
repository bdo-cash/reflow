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
import hobby.wei.c.reflow.implicits.none

import scala.collection.{mutable, _}

/**
  * 用于发布{@link Task}的I/O接口及调度策略信息。
  * 而{@link Task}本身仅用于定义任务实现。
  * <p>
  * 注意: 实例不应保留状态。
  *
  * @author Wei Chou(weichou2010@gmail.com)
  * @version 1.0, 12/04/2015
  */
trait Trait extends Equals {
  val is4Reflow: Boolean = false

  /**
    * 任务名称。
    */
  protected def name(): String

  /**
    * 创建任务。
    */
  /*protected*/ def newTask(): Task

  /**
    * 必须输入的参数keys及value类型(可由初始参数传入, 或者在本Task前面执行的Tasks输出{@link #outs()}而获得)。
    */
  protected def requires(): immutable.Set[KvTpe[_ <: AnyRef]]

  /**
    * 该任务输出的所有key-value类型。
    */
  protected def outs(): immutable.Set[KvTpe[_ <: AnyRef]]

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

  lazy val name$: String = name().ensuring(_.nonEmpty)
  lazy val requires$: immutable.Set[KvTpe[_ <: AnyRef]] = requireKkDiff(requireElemNonNull(requires()))
  lazy val outs$: immutable.Set[KvTpe[_ <: AnyRef]] = requireKkDiff(requireElemNonNull(outs()))
  lazy val priority$: Int = between(P_HIGH, priority(), P_LOW).toInt
  lazy val period$: Period.Tpe = period().ensuring(_.nonNull)
  lazy val desc$: String = desc().ensuring(_.nonNull /*可以是""*/)

  override def equals(any: scala.Any) = super.equals(any)
  override def canEqual(that: Any) = super.equals(that)
  override def hashCode() = super.hashCode()

  override def toString = "name:%s, requires:%s, out:%s, priority:%s, period:%s, description: %s" format(
    name$, requires$, outs$, priority$, period$, desc$)
}

object Trait {
  @deprecated
  def apply(_name: String,
            _period: Period.Tpe,
            _outs: immutable.Set[KvTpe[_ <: AnyRef]] = none,
            _requires: immutable.Set[KvTpe[_ <: AnyRef]] = none,
            _priority: Int = Reflow.P_NORMAL,
            _desc: String = null)(
             _dosth: Task.Context => Unit): Trait = new Trait {
    override protected def name() = _name
    override protected def requires() = _requires
    override protected def outs() = _outs
    override protected def priority() = _priority
    override protected def period() = _period
    override protected def desc() = if (_desc.isNull) name$ else _desc
    override def newTask() = Task(_dosth)
  }

  private final val sCount = new AtomicInteger(0)

  private[reflow] final class Parallel private[reflow](trats: Seq[Trait]) extends Trait {
    // 提交调度器之后具有不变性
    private val _traits = new mutable.ListBuffer[Trait]

    _traits ++= trats

    private[reflow] def this(t: Trait) = this(Seq(t))

    private[reflow] def traits() = _traits
    private[reflow] def add(t: Trait): Unit = {
      assertf(!t.isInstanceOf[Parallel])
      _traits += t
    }

    private[reflow] def first(): Trait = _traits.head
    private[reflow] def last(): Trait = _traits.last

    override protected def name() = classOf[Parallel].getName + "#" + sCount.getAndIncrement()
    override def newTask() = ???
    override protected def requires() = none
    override protected def outs() = none
    override protected def priority() = Reflow.P_NORMAL
    override protected def period() = ???
    override protected def desc() = name$
  }

  trait Adapter extends Trait {
    override protected def name() = classOf[Adapter].getName + "#" + sCount.getAndIncrement()
    override protected def requires() = none
    override protected def outs() = none
    override protected def priority() = Reflow.P_NORMAL
    override protected def desc() = name$
  }

  private[reflow] final class Input(reflow: Reflow, in: In, outsTrimmed: immutable.Set[KvTpe[_ <: AnyRef]]) extends Adapter {
    override protected def name() = classOf[Input].getName + "#" + sCount.getAndIncrement()

    override def newTask() = new Task {
      override protected def doWork(): Unit = in.fillValues(env.out)
    }

    override protected def outs() = outsTrimmed
    override protected def priority() = reflow.basis.first(child = true).get.priority$
    override protected def period() = Period.TRANSIENT
  }

  private[reflow] abstract class ReflowTrait(val reflow: Reflow) extends Trait {
    override final val is4Reflow = true

    override protected def name() = classOf[ReflowTrait].getName + "#" + sCount.getAndIncrement()
    override final def newTask() = new SubReflowTask()
    override protected final def priority() = reflow.basis.first(child = true).get.priority$
    // 这只是一个外壳，调度瞬间完成。子任务执行时，这层壳不会阻塞线程（事件回调机制）。
    override protected final def period() = Period.TRANSIENT
  }
}
