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

package hobby.wei.c.reflow.lite

import hobby.chenai.nakam.basis.TAG
import hobby.chenai.nakam.basis.TAG.ThrowMsg
import hobby.chenai.nakam.lang.J2S._
import hobby.chenai.nakam.lang.TypeBring.AsIs
import hobby.wei.c.anno.proguard.Keep$
import hobby.wei.c.reflow
import hobby.wei.c.reflow._
import hobby.wei.c.reflow.Task.Context
import hobby.wei.c.reflow.implicits._
import hobby.wei.c.reflow.Feedback.Progress.Policy
import hobby.wei.c.reflow.Reflow.{debugMode, Period, logger => log}
import hobby.wei.c.reflow.lite.Task.Merge

import scala.reflect.ClassTag

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 14/06/2020
  */
object Task {
  lazy val KEY_DEF = getClass.getName
  lazy val defKeyVType = new Kce[AnyRef](Task.KEY_DEF) {}
  lazy val defKeyVTypes: Set[Kce[_ <: AnyRef]] = defKeyVType

  def apply[OUT <: AnyRef](input: => OUT)(implicit out: ClassTag[OUT]): Input[OUT] = Input[OUT](input)

  def apply[IN >: Null <: AnyRef, OUT <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (IN, Context) => OUT)(implicit in: ClassTag[IN], out: ClassTag[OUT]): Lite[IN, OUT] =
    new Lite[IN, OUT](_period, _priority, _name, _desc) {
      override protected[lite] val func = f
      override protected[lite] def newTask() = new reflow.Task.Context {
        protected final def input(): IN = input[IN](Task.KEY_DEF).get
        protected final def output(vo: OUT): Unit = output(Task.KEY_DEF, vo)

        override protected def doWork(): Unit = output(func(input(), this))
      }
    }

  private[lite] def par[IN >: Null <: AnyRef, OUT <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (index: Int, f: (IN, Context) => OUT)(implicit in: ClassTag[IN], out: ClassTag[OUT]): Parel[IN, OUT] =
    new Parel[IN, OUT](_period, _priority, _name, _desc) {
      lite =>
      lazy val outKeyIndexed = lite.OUT_KEY(index)
      override protected[lite] val func = f
      override protected[lite] def outs() = new KeyVType[AnyRef](outKeyIndexed) {}
      override protected[lite] def newTask() = new reflow.Task.Context {
        protected final def input(): IN = input[IN](Task.KEY_DEF).get
        protected final def output(vo: OUT): Unit = output(outKeyIndexed, vo)
        override protected def doWork(): Unit = output(func(input(), this))
      }
    }

  private[lite] def par[IN >: Null <: AnyRef, OUT <: AnyRef]
  (index: Int, le: Lite[IN, OUT])(implicit in: ClassTag[IN], out: ClassTag[OUT]): Parel[IN, OUT] = {
    if (le.isInstanceOf[Parel[_, _]] && le.parseIndex(le.intent.outs$.head.key) == index) le.as[Parel[IN, OUT]]
    else if (le.intent.is4Reflow) par(le.intent)
    else par[IN, OUT](le.intent.period$, le.intent.priority$, le.intent.name$, le.intent.desc$)(index, le.func)
  }

  private[lite] def par[IN >: Null <: AnyRef, OUT <: AnyRef]
  (_intent: Intent)(implicit in: ClassTag[IN], out: ClassTag[OUT]): Parel[IN, OUT] =
    new Parel[IN, OUT](TRANSIENT /*仅占位*/ , P_NORMAL, null, null) {
      override protected[lite] lazy val func = throwFuncShouldNotBeUsed
      override protected[lite] def newTask() = null
      override lazy val intent = _intent
    }

  private[lite] def sub[IN <: AnyRef, OUT <: AnyRef]
  (_intent: Intent)(implicit in: ClassTag[IN], out: ClassTag[OUT]): Lite[IN, OUT] =
    new Lite[IN, OUT](TRANSIENT /*仅占位*/ , P_NORMAL, null, null) {
      override protected[lite] lazy val func = throwFuncShouldNotBeUsed
      override protected[lite] def newTask() = null
      override lazy val intent = _intent
    }

  private[lite] def end[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (inputs: Set[KeyVType[_ <: AnyRef]])(task: () => reflow.Task)(implicit nxt: ClassTag[Next]): Lite[AnyRef, Next] =
    new Lite[AnyRef, Next](_period, _priority, _name, _desc) {
      override def classTags = (merge, nxt :: Nil)
      override protected[lite] lazy val func = throwFuncShouldNotBeUsed
      override protected[lite] def name$(): String = if (_name.isNull) name("merge") else _name
      override protected[lite] def requires() = inputs
      override protected[lite] def newTask() = task()
    }

  private lazy val merge = ClassTag(classOf[Merge])
  private[lite] final class Merge
}

protected[lite] trait ClassTags2Name extends TAG.ClassName {
  implicit protected def classTag2Seq(ct: ClassTag[_]): Seq[ClassTag[_]] = Seq(ct)
  protected def classTags: (Seq[ClassTag[_]], Seq[ClassTag[_]])

  override final def toString = super.toString
  override final def hashCode = super.hashCode
  final def short = {
    val s = toString
    val i = s.lastIndexOf('.')
    if (i > 0) s.substring(i + 1) else s
  }
  final def classTag = "[" +
    (if (classTags._1.isNull) "" else classTags._1.map { ct =>
      if (ct.isNull) "" else if (ct.runtimeClass == classOf[Merge]) "..." else ct.runtimeClass.getSimpleName
    }.mkString("|")) + "->" + classTags._2.map { ct =>
    if (ct.isNull) "" else ct.runtimeClass.getSimpleName
  }.mkString("|") + "]"
  final def name(tag: String): String = s"[${tag.toUpperCase}]$name"
  final lazy val name: String = s"$short$classTag"

  final def OUT_KEY(index: Int): String = name + index
  final def parseIndex(outKey: String): Int = {
    if (debugMode) log.i("parseIndex: %s", outKey)
    outKey.substring(outKey.lastIndexOf(']') + 1).toInt
  }
}

@Keep$
abstract class AbsLite[IN <: AnyRef, OUT <: AnyRef] private[lite](implicit in: ClassTag[IN], out: ClassTag[OUT])
  extends ClassTags2Name {
  override def classTags = (in, out :: Nil)

  def >>>[Next <: AnyRef](lite: Lite[OUT, Next])(implicit nxt: ClassTag[Next]): Serial[IN, Next] = next(lite, nxt)
  def next[Next <: AnyRef](implicit lite: Lite[OUT, Next], next: ClassTag[Next]): Serial[IN, Next] = {
    require(lite.nonNull)
    Serial(head = Some(this), tail = lite)
  }

  def >>>[Next <: AnyRef](serial: Serial[OUT, Next])(implicit nxt: ClassTag[Next]): Serial[IN, Next] = next(serial)
  def next[Next <: AnyRef](serial: Serial[OUT, Next])(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    require(serial.nonNull)

    def joinThis2Head(lite: AbsLite[_, _]): Serial[_ <: AnyRef, _ <: AnyRef] = lite match {
      case Serial(head, tail) =>
        val head$ = if (head.isEmpty) this else joinThis2Head(head.get)
        Serial(Some(head$), tail).as[Serial[_ <: AnyRef, _ <: AnyRef]]
      case lite: Lite[_, _] => Serial(Some(this), lite.as[Lite[_ <: AnyRef, _ <: AnyRef]])
      case l@_ => throwNotRequired(l)
    }

    joinThis2Head(serial).as[Serial[IN, Next]]
  }

  def run(feedback: Feedback = Feedback.Log)(implicit policy: Policy, poster: Poster): Scheduler = {
    def parseDepends(lite: AbsLite[_, _]): Dependency = lite match {
      case Serial(head, tail) if head.isDefined => parseDepends(head.get).next(tail.intent)
      case input: Input[_] => Reflow.builder
      case _ => throwInputRequired
    }

    @scala.annotation.tailrec
    def findIn(lite: AbsLite[_, _]): In = lite match {
      case Serial(head, _) if head.isDefined => findIn(head.get)
      case input: Input[_] => input.in
      case _ => throwInputRequired
    }

    parseDepends(this).submit().start(findIn(this), feedback)
  }

  protected def throwInputRequired = throw new IllegalArgumentException("`Input[]` required.".tag)
  protected def throwInputNotRequired = throw new IllegalArgumentException("`Input[]` NOT required.".tag)
  protected def throwNotRequired(l: AbsLite[_, _]) = throw new IllegalArgumentException(s"$l NOT required.".tag)
  protected def throwFuncShouldNotBeUsed = throw new IllegalArgumentException("This `func` should not be used.".tag)
}

object Input {
  def apply[OUT <: AnyRef](input: => OUT)(implicit out: ClassTag[OUT]): Input[OUT] = new Input(input)(out)
}

final class Input[OUT <: AnyRef] private[lite](input: => OUT)(implicit out: ClassTag[OUT])
  extends AbsLite[OUT, OUT]()(null, out) {
  private[lite] lazy val in: In = Task.defKeyVType -> input
  // Don't use this.
  //private[lite] lazy val builder = Reflow.builder
}

/** 单个任务。 */
abstract class Lite[IN <: AnyRef, OUT <: AnyRef] private[lite]
(_period: Period.Tpe, _priority: Int, _name: String, _desc: String)(implicit in: ClassTag[IN], out: ClassTag[OUT])
  extends AbsLite[IN, OUT] {
  lite =>
  // 如果是并行，需要重写 intent，会用到。
  protected[lite] val func: (IN, Context) => OUT

  lazy val intent: Intent = new Trait.Adapter {
    override protected def name() = lite.name$()
    override protected def requires() = lite.requires()
    override protected def outs() = lite.outs()
    override protected def priority() = _priority
    override protected def period() = _period
    override protected def desc() = if (_desc.isNull) name$ else _desc
    override def newTask() = lite.newTask()
  }

  protected[lite] def name$(): String = if (_name.isNull) lite.name else _name
  protected[lite] def requires(): Set[Kce[_ <: AnyRef]] = Task.defKeyVTypes
  protected[lite] def outs(): Set[Kce[_ <: AnyRef]] = Task.defKeyVTypes
  protected[lite] def newTask(): reflow.Task
}

/** 单个[并行]的任务。 */
// This class compiled to AbsLite$$anon$`${i}` because of not `final`.
abstract class Parel[IN <: AnyRef, OUT <: AnyRef] private[lite]
(_period: Period.Tpe, _priority: Int, _name: String, _desc: String)(implicit in: ClassTag[IN], out: ClassTag[OUT])
  extends Lite[IN, OUT](_period, _priority, _name, _desc)

/** 一列[串行]的任务。 */
final case class Serial[IN <: AnyRef, OUT <: AnyRef] private[lite]
(head: Option[AbsLite[IN, _]], tail: Lite[_ <: AnyRef, OUT])
(implicit in: ClassTag[IN], out: ClassTag[OUT]) extends AbsLite[IN, OUT] {
  def apply(_name: String = this.name, _desc: String = null): Lite[IN, OUT] = toSub(_name, _desc)
  /** 作为并行的其中一个子任务时，需要转换。即使：它本身是串行的。 */
  def toSub(_name: String = this.name, _desc: String = null): Lite[IN, OUT] = {
    def parseDepends(lite: AbsLite[_, _]): Dependency = lite match {
      case Serial(head, tail) => if (head.isEmpty) Reflow.builder else parseDepends(head.get).next(tail.intent)
      case lite: Lite[_, _] => Reflow.create(lite.intent)
      case _ => throwInputNotRequired
    }

    Task.sub[IN, OUT](parseDepends(this).submit(Task.defKeyVTypes).toSub(_name, _desc))
  }
}

protected[lite] trait AbsPar extends ClassTags2Name {
  protected final def parseDepends(pars: Seq[Parel[_, _]] = seq): Dependency = (Reflow.builder /: pars) { (dep, par) => dep.and(par.intent) }

  protected final def allOuts(pars: Seq[Parel[_, _]] = seq) =
    (Set.newBuilder[KeyVType[_ <: AnyRef]] /: pars) { (set, par) => set ++= par.intent.outs$ }.result()

  protected final def toIntent(pars: Seq[Parel[_, _]] = seq, tag: String = getClass.getSimpleName): Intent = {
    parseDepends(pars).submit(allOuts(pars)).toSub(name(tag))
  }

  def seq: Seq[Parel[_, _]]
}

/** 一列[并行]的任务。 */
final case class Par[IN >: Null <: AnyRef, OUT >: Null <: AnyRef]
(l: Lite[IN, OUT])(implicit in: ClassTag[IN], out: ClassTag[OUT]) extends AbsPar {
  override def classTags = (in, out :: Nil)
  override def seq = Seq(Task.par(0, l))

  def +>>[OUT1 >: Null <: AnyRef](lite: Lite[IN, OUT1])(implicit out1: ClassTag[OUT1]): Par2[IN, OUT, OUT1] = par(lite)
  def par[OUT1 >: Null <: AnyRef](lite: Lite[IN, OUT1])(implicit out1: ClassTag[OUT1]): Par2[IN, OUT, OUT1] =
    Par2(seq.head, Task.par(1, lite))

  def **>[Next <: AnyRef]
  (f: (OUT, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    implicit val lite = Task[OUT, Next](_period, _priority, _name, _desc)(f)
    l.next[Next]
  }
}

final case class Par2[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1])(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: Nil)
  override def seq = Seq(l, l1)

  def +>>[OUT2 >: Null <: AnyRef](lite: Lite[IN, OUT2])(implicit out2: ClassTag[OUT2]): Par3[IN, OUT, OUT1, OUT2] =
    par(lite)

  def par[OUT2 >: Null <: AnyRef](lite: Lite[IN, OUT2])(implicit out2: ClassTag[OUT2]): Par3[IN, OUT, OUT1, OUT2] =
    Par3(l, l1, Task.par(2, lite))

  def **>[Next <: AnyRef]
  (f: (OUT, OUT1, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  private def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par3[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: Nil)
  override def seq = Seq(l, l1, l2)

  def +>>[OUT3 >: Null <: AnyRef](lite: Lite[IN, OUT3])(implicit out3: ClassTag[OUT3]): Par4[IN, OUT, OUT1, OUT2, OUT3] =
    par(lite)

  def par[OUT3 >: Null <: AnyRef](lite: Lite[IN, OUT3])(implicit out3: ClassTag[OUT3]): Par4[IN, OUT, OUT1, OUT2, OUT3] =
    Par4(l, l1, l2, Task.par(3, lite))

  def **>[Next <: AnyRef]
  (f: (OUT, OUT1, OUT2, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  private def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par4[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: Nil)
  override def seq = Seq(l, l1, l2, l3)

  def +>>[OUT4 >: Null <: AnyRef](lite: Lite[IN, OUT4])(implicit out4: ClassTag[OUT4]): Par5[IN, OUT, OUT1, OUT2, OUT3, OUT4] =
    par(lite)

  def par[OUT4 >: Null <: AnyRef](lite: Lite[IN, OUT4])(implicit out4: ClassTag[OUT4]): Par5[IN, OUT, OUT1, OUT2, OUT3, OUT4] =
    Par5(l, l1, l2, l3, Task.par(4, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par5[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4)

  def +>>[OUT5 >: Null <: AnyRef](lite: Lite[IN, OUT5])(implicit out5: ClassTag[OUT5]): Par6[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5] =
    par(lite)

  def par[OUT5 >: Null <: AnyRef](lite: Lite[IN, OUT5])(implicit out5: ClassTag[OUT5]): Par6[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5] =
    Par6(l, l1, l2, l3, l4, Task.par(5, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par6[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5)

  def +>>[OUT6 >: Null <: AnyRef](lite: Lite[IN, OUT6])(implicit out6: ClassTag[OUT6]): Par7[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6] =
    par(lite)
  def par[OUT6 >: Null <: AnyRef](lite: Lite[IN, OUT6])(implicit out6: ClassTag[OUT6]): Par7[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6] =
    Par7(l, l1, l2, l3, l4, l5, Task.par(6, lite))


  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par7[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6)

  def +>>[OUT7 >: Null <: AnyRef](lite: Lite[IN, OUT7])(implicit out7: ClassTag[OUT7]): Par8[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7] =
    par(lite)

  def par[OUT7 >: Null <: AnyRef](lite: Lite[IN, OUT7])(implicit out7: ClassTag[OUT7]): Par8[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7] =
    Par8(l, l1, l2, l3, l4, l5, l6, Task.par(7, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par8[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7)

  def +>>[OUT8 >: Null <: AnyRef](lite: Lite[IN, OUT8])(implicit out8: ClassTag[OUT8]): Par9[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8] =
    par(lite)

  def par[OUT8 >: Null <: AnyRef](lite: Lite[IN, OUT8])(implicit out8: ClassTag[OUT8]): Par9[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8] =
    Par9(l, l1, l2, l3, l4, l5, l6, l7, Task.par(8, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par9[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8)

  def +>>[OUT9 >: Null <: AnyRef](lite: Lite[IN, OUT9])(implicit out9: ClassTag[OUT9]): Par10[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9] =
    par(lite)

  def par[OUT9 >: Null <: AnyRef](lite: Lite[IN, OUT9])(implicit out9: ClassTag[OUT9]): Par10[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9] =
    Par10(l, l1, l2, l3, l4, l5, l6, l7, l8, Task.par(9, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par10[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9)

  def +>>[OUT10 >: Null <: AnyRef](lite: Lite[IN, OUT10])(implicit out10: ClassTag[OUT10]): Par11[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10] =
    par(lite)

  def par[OUT10 >: Null <: AnyRef](lite: Lite[IN, OUT10])(implicit out10: ClassTag[OUT10]): Par11[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10] =
    Par11(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, Task.par(10, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par11[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10)

  def +>>[OUT11 >: Null <: AnyRef](lite: Lite[IN, OUT11])(implicit out11: ClassTag[OUT11]): Par12[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11] =
    par(lite)

  def par[OUT11 >: Null <: AnyRef](lite: Lite[IN, OUT11])(implicit out11: ClassTag[OUT11]): Par12[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11] =
    Par12(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, Task.par(11, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par12[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11)

  def +>>[OUT12 >: Null <: AnyRef](lite: Lite[IN, OUT12])(implicit out12: ClassTag[OUT12]): Par13[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12] =
    par(lite)

  def par[OUT12 >: Null <: AnyRef](lite: Lite[IN, OUT12])(implicit out12: ClassTag[OUT12]): Par13[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12] =
    Par13(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, Task.par(12, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par13[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12)

  def +>>[OUT13 >: Null <: AnyRef](lite: Lite[IN, OUT13])(implicit out13: ClassTag[OUT13]): Par14[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13] =
    par(lite)

  def par[OUT13 >: Null <: AnyRef](lite: Lite[IN, OUT13])(implicit out13: ClassTag[OUT13]): Par14[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13] =
    Par14(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, Task.par(13, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par14[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13)

  def +>>[OUT14 >: Null <: AnyRef](lite: Lite[IN, OUT14])(implicit out14: ClassTag[OUT14]): Par15[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14] =
    par(lite)

  def par[OUT14 >: Null <: AnyRef](lite: Lite[IN, OUT14])(implicit out14: ClassTag[OUT14]): Par15[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14] =
    Par15(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, Task.par(14, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par15[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14)

  def +>>[OUT15 >: Null <: AnyRef](lite: Lite[IN, OUT15])(implicit out15: ClassTag[OUT15]): Par16[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15] =
    par(lite)

  def par[OUT15 >: Null <: AnyRef](lite: Lite[IN, OUT15])(implicit out15: ClassTag[OUT15]): Par16[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15] =
    Par16(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, Task.par(15, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par16[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef, OUT15 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14], l15: Parel[IN, OUT15])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14], out15: ClassTag[OUT15])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: out15 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15)

  def +>>[OUT16 >: Null <: AnyRef](lite: Lite[IN, OUT16])(implicit out16: ClassTag[OUT16]): Par17[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16] =
    par(lite)

  def par[OUT16 >: Null <: AnyRef](lite: Lite[IN, OUT16])(implicit out16: ClassTag[OUT16]): Par17[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16] =
    Par17(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, Task.par(16, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          input[OUT15](l15.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par17[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef, OUT15 >: Null <: AnyRef, OUT16 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14], l15: Parel[IN, OUT15], l16: Parel[IN, OUT16])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14], out15: ClassTag[OUT15], out16: ClassTag[OUT16])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: out15 :: out16 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16)

  def +>>[OUT17 >: Null <: AnyRef](lite: Lite[IN, OUT17])(implicit out17: ClassTag[OUT17]): Par18[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17] =
    par(lite)

  def par[OUT17 >: Null <: AnyRef](lite: Lite[IN, OUT17])(implicit out17: ClassTag[OUT17]): Par18[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17] =
    Par18(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, Task.par(17, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          input[OUT15](l15.intent.outs$.head.key).get,
          input[OUT16](l16.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par18[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef, OUT15 >: Null <: AnyRef, OUT16 >: Null <: AnyRef, OUT17 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14], l15: Parel[IN, OUT15], l16: Parel[IN, OUT16], l17: Parel[IN, OUT17])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14], out15: ClassTag[OUT15], out16: ClassTag[OUT16], out17: ClassTag[OUT17])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: out15 :: out16 :: out17 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17)

  def +>>[OUT18 >: Null <: AnyRef](lite: Lite[IN, OUT18])(implicit out18: ClassTag[OUT18]): Par19[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18] =
    par(lite)

  def par[OUT18 >: Null <: AnyRef](lite: Lite[IN, OUT18])(implicit out18: ClassTag[OUT18]): Par19[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18] =
    Par19(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17, Task.par(18, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          input[OUT15](l15.intent.outs$.head.key).get,
          input[OUT16](l16.intent.outs$.head.key).get,
          input[OUT17](l17.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par19[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef, OUT15 >: Null <: AnyRef, OUT16 >: Null <: AnyRef, OUT17 >: Null <: AnyRef, OUT18 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14], l15: Parel[IN, OUT15], l16: Parel[IN, OUT16], l17: Parel[IN, OUT17], l18: Parel[IN, OUT18])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14], out15: ClassTag[OUT15], out16: ClassTag[OUT16], out17: ClassTag[OUT17], out18: ClassTag[OUT18])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: out15 :: out16 :: out17 :: out18 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17, l18)

  def +>>[OUT19 >: Null <: AnyRef](lite: Lite[IN, OUT19])(implicit out19: ClassTag[OUT19]): Par20[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19] =
    par(lite)

  def par[OUT19 >: Null <: AnyRef](lite: Lite[IN, OUT19])(implicit out19: ClassTag[OUT19]): Par20[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19] =
    Par20(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17, l18, Task.par(19, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          input[OUT15](l15.intent.outs$.head.key).get,
          input[OUT16](l16.intent.outs$.head.key).get,
          input[OUT17](l17.intent.outs$.head.key).get,
          input[OUT18](l18.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par20[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef, OUT15 >: Null <: AnyRef, OUT16 >: Null <: AnyRef, OUT17 >: Null <: AnyRef, OUT18 >: Null <: AnyRef, OUT19 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14], l15: Parel[IN, OUT15], l16: Parel[IN, OUT16], l17: Parel[IN, OUT17], l18: Parel[IN, OUT18], l19: Parel[IN, OUT19])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14], out15: ClassTag[OUT15], out16: ClassTag[OUT16], out17: ClassTag[OUT17], out18: ClassTag[OUT18], out19: ClassTag[OUT19])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: out15 :: out16 :: out17 :: out18 :: out19 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17, l18, l19)

  def +>>[OUT20 >: Null <: AnyRef](lite: Lite[IN, OUT20])(implicit out20: ClassTag[OUT20]): Par21[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19, OUT20] =
    par(lite)

  def par[OUT20 >: Null <: AnyRef](lite: Lite[IN, OUT20])(implicit out20: ClassTag[OUT20]): Par21[IN, OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19, OUT20] =
    Par21(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17, l18, l19, Task.par(20, lite))

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          input[OUT15](l15.intent.outs$.head.key).get,
          input[OUT16](l16.intent.outs$.head.key).get,
          input[OUT17](l17.intent.outs$.head.key).get,
          input[OUT18](l18.intent.outs$.head.key).get,
          input[OUT19](l19.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

final case class Par21[IN >: Null <: AnyRef, OUT >: Null <: AnyRef, OUT1 >: Null <: AnyRef, OUT2 >: Null <: AnyRef, OUT3 >: Null <: AnyRef, OUT4 >: Null <: AnyRef, OUT5 >: Null <: AnyRef, OUT6 >: Null <: AnyRef, OUT7 >: Null <: AnyRef, OUT8 >: Null <: AnyRef, OUT9 >: Null <: AnyRef, OUT10 >: Null <: AnyRef, OUT11 >: Null <: AnyRef, OUT12 >: Null <: AnyRef, OUT13 >: Null <: AnyRef, OUT14 >: Null <: AnyRef, OUT15 >: Null <: AnyRef, OUT16 >: Null <: AnyRef, OUT17 >: Null <: AnyRef, OUT18 >: Null <: AnyRef, OUT19 >: Null <: AnyRef, OUT20 >: Null <: AnyRef]
(l: Parel[IN, OUT], l1: Parel[IN, OUT1], l2: Parel[IN, OUT2], l3: Parel[IN, OUT3], l4: Parel[IN, OUT4], l5: Parel[IN, OUT5], l6: Parel[IN, OUT6], l7: Parel[IN, OUT7], l8: Parel[IN, OUT8], l9: Parel[IN, OUT9], l10: Parel[IN, OUT10], l11: Parel[IN, OUT11], l12: Parel[IN, OUT12], l13: Parel[IN, OUT13], l14: Parel[IN, OUT14], l15: Parel[IN, OUT15], l16: Parel[IN, OUT16], l17: Parel[IN, OUT17], l18: Parel[IN, OUT18], l19: Parel[IN, OUT19], l20: Parel[IN, OUT20])
(implicit in: ClassTag[IN], out: ClassTag[OUT], out1: ClassTag[OUT1], out2: ClassTag[OUT2], out3: ClassTag[OUT3], out4: ClassTag[OUT4], out5: ClassTag[OUT5], out6: ClassTag[OUT6], out7: ClassTag[OUT7], out8: ClassTag[OUT8], out9: ClassTag[OUT9], out10: ClassTag[OUT10], out11: ClassTag[OUT11], out12: ClassTag[OUT12], out13: ClassTag[OUT13], out14: ClassTag[OUT14], out15: ClassTag[OUT15], out16: ClassTag[OUT16], out17: ClassTag[OUT17], out18: ClassTag[OUT18], out19: ClassTag[OUT19], out20: ClassTag[OUT20])
  extends AbsPar {
  override def classTags = (in, out :: out1 :: out2 :: out3 :: out4 :: out5 :: out6 :: out7 :: out8 :: out9 :: out10 :: out11 :: out12 :: out13 :: out14 :: out15 :: out16 :: out17 :: out18 :: out19 :: out20 :: Nil)
  override def seq = Seq(l, l1, l2, l3, l4, l5, l6, l7, l8, l9, l10, l11, l12, l13, l14, l15, l16, l17, l18, l19, l20)

  def **>[Next <: AnyRef](f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19, OUT20, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] =
    merge()(f)(next)

  def merge[Next <: AnyRef]
  (_period: Period.Tpe = SHORT, _priority: Int = P_NORMAL, _name: String = null, _desc: String = null)
  (f: (OUT, OUT1, OUT2, OUT3, OUT4, OUT5, OUT6, OUT7, OUT8, OUT9, OUT10, OUT11, OUT12, OUT13, OUT14, OUT15, OUT16, OUT17, OUT18, OUT19, OUT20, Context) => Next)(implicit next: ClassTag[Next]): Serial[IN, Next] = {
    val pars = seq
    implicit val end: Lite[AnyRef, Next] = Task.end(_period, _priority, _name, _desc)(allOuts(pars)) { () =>
      new reflow.Task.Context {
        override protected def doWork(): Unit = output(Task.KEY_DEF, f(
          input[OUT](l.intent.outs$.head.key).get,
          input[OUT1](l1.intent.outs$.head.key).get,
          input[OUT2](l2.intent.outs$.head.key).get,
          input[OUT3](l3.intent.outs$.head.key).get,
          input[OUT4](l4.intent.outs$.head.key).get,
          input[OUT5](l5.intent.outs$.head.key).get,
          input[OUT6](l6.intent.outs$.head.key).get,
          input[OUT7](l7.intent.outs$.head.key).get,
          input[OUT8](l8.intent.outs$.head.key).get,
          input[OUT9](l9.intent.outs$.head.key).get,
          input[OUT10](l10.intent.outs$.head.key).get,
          input[OUT11](l11.intent.outs$.head.key).get,
          input[OUT12](l12.intent.outs$.head.key).get,
          input[OUT13](l13.intent.outs$.head.key).get,
          input[OUT14](l14.intent.outs$.head.key).get,
          input[OUT15](l15.intent.outs$.head.key).get,
          input[OUT16](l16.intent.outs$.head.key).get,
          input[OUT17](l17.intent.outs$.head.key).get,
          input[OUT18](l18.intent.outs$.head.key).get,
          input[OUT19](l19.intent.outs$.head.key).get,
          input[OUT20](l20.intent.outs$.head.key).get,
          this))
      }
    }
    Task.sub[IN, AnyRef](toIntent(pars)).next[Next]
  }
}

/* No more! Error: type Function23 is not a member of package scala! */
