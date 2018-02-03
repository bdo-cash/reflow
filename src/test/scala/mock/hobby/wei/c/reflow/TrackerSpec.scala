package mock.hobby.wei.c.reflow

import org.scalatest.Spec

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 31/01/2018
  */
class TrackerSpec extends Spec {
  lazy val tracker1 = new Tracker {
    override val outer = None
  }

  lazy val env2 = new Env {
    override val trat = new Trait {
      override val name$ = "trat2"
    }
    override val tracker = tracker1
  }

  lazy val tracker2 = new Tracker {
    override val outer = Option(env2)
  }

  lazy val env3 = new Env {
    override val trat = new Trait {
      override val name$ = "trat3"
    }
    override val tracker = tracker2
  }

  lazy val tracker3 = new Tracker {
    override val outer = Option(env3)
  }

  lazy val env4 = new Env {
    override val trat = new Trait {
      override val name$ = "trat4"
    }
    override val tracker = tracker3
  }

  lazy val env2$ = new Env {
    override val trat = env2.trat
    override val tracker = tracker1
  }

  lazy val tracker2$ = new Tracker(Option(env2$), true) {}

  lazy val env3$ = new Env {
    override val trat = env3.trat
    override val tracker = tracker2$
  }

  lazy val tracker3$ = new Tracker(Option(env3$), true) {
    println("最后看看整个数据结构。tracker1.cache: " + tracker1.cache)
  }

  lazy val env4$ = new Env {
    override val trat = env4.trat
    override val tracker = tracker3$

    println("最后看看整个数据结构。tracker1.cache: " + tracker1.cache)
  }

  object `Tracker & Env` {
    println("env4.getCache: " + env4.superCache)
    println("tracker1.cache.caches: " + tracker1.cache.caches)

    def `tracker1.cache.subs contains env2.trat.name$` {
      println("tracker1.cache.subs: " + tracker1.cache.subs)
      assert(tracker1.cache.subs.contains(env2.trat.name$))
    }

    def `tracker2.cache.subs contains env3.trat.name$` {
      println("tracker2.cache.subs: " + tracker2.cache.subs)
      assert(tracker2.cache.subs.contains(env3.trat.name$))
    }

    def `tracker2$.cache.subs contains env3$.trat.name$` {
      println("tracker2$.cache.subs: " + tracker2$.cache.subs)
      assert(tracker2$.cache.subs.contains(env3$.trat.name$))
    }

    def `tracker3.cache.subs === tracker3$.cache.subs` {
      println("tracker3.cache.subs: " + tracker3.cache.subs)
      println("tracker3$.cache.subs: " + tracker3$.cache.subs)
      assert(tracker3.cache.subs === tracker3$.cache.subs)
    }

    def `env4.getCache === env4$.getCache` {
      println("env4.getCache: " + env4.superCache)
      println("env4$.getCache: " + env4$.superCache)
      assert(env4.superCache === env4$.superCache)
    }
  }
}
