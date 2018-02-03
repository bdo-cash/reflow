package mock.hobby.wei.c.reflow

import hobby.wei.c.tool.Snatcher

import scala.collection._

/**
  * @author Chenai Nakam(chenai.nakam@gmail.com)
  * @version 1.0, 31/01/2018
  */
trait Env {
  val trat: Trait
  val tracker: Tracker

  def superCache: Cache = tracker.getCache
  /** 在reinforce阶段，从缓存中取回。 **/
  def obtainCache: Option[Cache] = {
    // assert(isReinforcing)
    superCache.subs.get(trat.name$)
  }
  def myCache: Out = superCache.caches.getOrElseUpdate(trat.name$, new Out)
  //  def cache[V](key: String, value: => V): Out = cache.put
}

abstract class Tracker(val outer: Option[Env] = None, @volatile var inited: Boolean = false) {
  private lazy final val snatcher = new Snatcher
  // 这两个变量，在浏览运行阶段会根据需要自行创建（任务可能需要缓存临时参数到cache中）；
  // 而在Reinforce阶段，会从外部传入。
  // 因此有这样的设计。
  final lazy val cache = outer.fold(new Cache) { env =>
    //    if (isReinforcing)
    env.obtainCache.getOrElse(new Cache)
    //    else new Cache
  }

  private final def getOrInitWithSuperCache(trat: String = null, sub: Option[Cache] = None): Cache = {
    if (!inited) {
      snatcher.tryOn {
        if (!inited) {
          outer.foreach { env =>
            env.tracker.getOrInitWithSuperCache(env.trat.name$, Option(cache))
          }
          inited = true
        }
      }
    }
    sub.foreach(cache.subs.putIfAbsent(trat, _))
    cache
  }

  final def getCache = getOrInitWithSuperCache()
}

class Cache {
  /** 子Trait的Task缓存用到的Out。 **/
  lazy val caches = new concurrent.TrieMap[String, Out]
  /** 子Trait的Task启动的Reflow对应的Tracker的Cache。Key为 **/
  lazy val subs = new concurrent.TrieMap[String, Cache]
}

class Out

trait Trait {
  val name$: String
}
