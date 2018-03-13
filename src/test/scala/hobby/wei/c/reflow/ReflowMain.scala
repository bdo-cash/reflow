package hobby.wei.c.reflow

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 13/03/2018
  */
object ReflowMain /* extends App*/ {
}

class ReflowCreater {
  Reflow.setDebugMode(true)
  import Traits._

  Reflow.execute(new Runnable {
    override def run(): Unit = println("试一试 Reflow.execute(new Runnable {...}。")
  })

  val out = Reflow.create(trait0).submit(Helper.Keys.add(new Kce[Integer]("int0") {}).ok())
    .start(In.empty(), new Feedback.Adapter(), null)
    .sync()

  println(out)
}

object Traits {
  lazy val trait0 = new Trait.Adapter {
    override protected def period() = Reflow.Period.SHORT

    override protected def outs() = Helper.Keys.add(new Kce[Integer]("int0") {}).ok()

    override protected def name() = "trait0"

    override def newTask() = new Task() {
      override protected def doWork(): Unit = {
        output("int0", 0)
        println("trait0 doWork() 执行<<<<<>>>>>")
      }
    }
  }
}
