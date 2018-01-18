///*
// * Copyright (C) 2017-present, Wei Chou(weichou2010@gmail.com)
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package hobby.wei.c.reflow;
//
//import java.util.concurrent.BlockingQueue;
//import java.util.concurrent.LinkedTransferQueue;
//import java.util.concurrent.PriorityBlockingQueue;
//import java.util.concurrent.ThreadFactory;
//import java.util.concurrent.ThreadPoolExecutor;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * 优化的线程池实现。
// *
// * @author Wei Chou(weichou2010@gmail.com)
// * @version 1.0, 11/02/2017
// */
//class Worker {
//    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
//        private final AtomicInteger mIndex = new AtomicInteger(0);
//
//        public Thread newThread(Runnable runnable) {
//            final Thread thread = new Thread(runnable, "pool-thread-" + Worker.class.getName() + "#" + mIndex.getAndIncrement());
//            resetThread(thread);
//            return thread;
//        }
//    };
//
//    private static ThreadResetor sThreadResetor = new ThreadResetor() {
//        @Override
//        public void reset(Thread thread) {
//            if (thread.getPriority() != Thread.NORM_PRIORITY) {
//                thread.setPriority(Thread.NORM_PRIORITY);
//            }
//        }
//    };
//
//    private static void resetThread(Thread thread) {
//        sThreadResetor.reset(thread);
//        Thread.interrupted();
//        if (thread.isDaemon()) thread.setDaemon(false);
//    }
//
//    private static ThreadPoolExecutor sThreadPoolExecutor;
//    private static final BlockingQueue<Runnable> sPoolWorkQueue = new LinkedTransferQueue<Runnable>() {
//        @Override
//        public boolean offer(Runnable r) {
//            /* 如果不放入队列并返回false，会迫使增加线程。但是这样又会导致总是增加线程，而空闲线程得不到重用。
//            因此在有空闲线程的情况下就直接放入队列。若大量长任务致使线程数增加到上限，
//            则threadPool启动reject流程(见ThreadPoolExecutor构造器的最后一个参数)，此时再插入到本队列。
//            这样即完美实现[先增加线程数到最大，再入队列，空闲释放线程]这个基本逻辑。*/
//            final boolean b = sThreadPoolExecutor.getActiveCount() < sThreadPoolExecutor.getPoolSize() && super.offer(r);
//            Assist.Monitor.threadPool(sThreadPoolExecutor, b, false);
//            return b;
//        }
//    };
//
//    private static ThreadPoolExecutor executor() {
//        if (sThreadPoolExecutor == null) {
//            synchronized (Worker.class) {
//                if (sThreadPoolExecutor == null) {
//                    final Config config = Reflow.config();
//                    sThreadPoolExecutor = new ThreadPoolExecutor(config.corePoolSize(), config.maxPoolSize(),
//                            config.keepAliveTime(), TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory, (r, executor) -> {
//                        Assist.Monitor.threadPool(sThreadPoolExecutor, false, true);
//                        try {
//                            sPoolWorkQueue.offer(r, 0, TimeUnit.MILLISECONDS);
//                        } catch (InterruptedException ignore/*不可能出现*/) {
//                        }
//                    });
//                }
//            }
//        }
//        return sThreadPoolExecutor;
//    }
//
//    static synchronized void setThreadResetor(ThreadResetor resetor) {
//        sThreadResetor = resetor;
//    }
//
//    static void updateConfig(Config config) {
//        synchronized (Worker.class) {
//            if (sThreadPoolExecutor == null) {
//                return;
//            }
//        }
//        sThreadPoolExecutor.setCorePoolSize(config.corePoolSize());
//        sThreadPoolExecutor.setMaximumPoolSize(config.maxPoolSize());
//        sThreadPoolExecutor.setKeepAliveTime(config.keepAliveTime(), TimeUnit.SECONDS);
//    }
//
//    static class sPreparedBuckets {
//        static final BlockingQueue<Runner> sTransient = new PriorityBlockingQueue<>();
//        static final BlockingQueue<Runner> sShort = new PriorityBlockingQueue<>();
//        static final BlockingQueue<Runner> sLong = new PriorityBlockingQueue<>();
//        static final BlockingQueue<Runner> sInfinite = new PriorityBlockingQueue<>();
//        static final BlockingQueue<Runner>[] sQueues = new BlockingQueue[]{
//                sTransient, sShort, sLong, sInfinite
//        };
//
//        static BlockingQueue<Runner> queue4(Reflow.Period period) {
//            switch (period) {
//                case TRANSIENT:
//                    return sTransient;
//                case SHORT:
//                    return sShort;
//                case LONG:
//                    return sLong;
//                case INFINITE:
//                    return sInfinite;
//                default:
//                    return sLong;
//            }
//        }
//    }
//
//    private static class sExecuting {
//        static final AtomicInteger sTransient = new AtomicInteger(0);
//        static final AtomicInteger sShort = new AtomicInteger(0);
//        static final AtomicInteger sLong = new AtomicInteger(0);
//        static final AtomicInteger sInfinite = new AtomicInteger(0);
//        static final AtomicInteger[] sCounters = new AtomicInteger[]{
//                sTransient, sShort, sLong, sInfinite
//        };
//    }
//
//    private static final AtomicBoolean sScheduling = new AtomicBoolean(false);
//    private static final AtomicBoolean sSignature = new AtomicBoolean(false);
//
//    static void scheduleBuckets() {
//        sSignature.set(true);   // 必须放在前面。标识新的调度请求，防止遗漏。
//        if (sScheduling.compareAndSet(false, true)) {
//            sSignature.set(false);
//        } else return;
//        final ThreadPoolExecutor executor = executor();
//        while (true) {
//            final int maxPoolSize = executor.getMaximumPoolSize();
//            final int allowRunLevel;
//            {
//                // sTransient和sShort至少会有一个线程，策略就是拼优先级了。不过如果线程已经满载，
//                // 此时即使有更高优先级的任务到来，那也得等着，谁叫你来的晚呢！
//                    /*if (sExecuting.sShort.get() + sExecuting.sLong.get()
//                            + sExecuting.sInfinite.get() >= maxPoolSize) {
//                        allowRunLevel = 1;
//                    } else*/
//                // 给短任务至少留一个线程，因为后面可能还会有高优先级的短任务。
//                // 但假如只有3个以内的线程，其中2个被sInfinite占用，怎么办呢？
//                // 1. 有可能某系统根本就没有sLong任务，那么剩下的一个刚好留给短任务；
//                // 2. 增加一个最大线程数通常不会对系统造成灾难性的影响，那么应该修改配置Config.
//                if (sExecuting.sLong.get() + sExecuting.sInfinite.get() >= maxPoolSize - 1) {
//                    allowRunLevel = 1;
//                }
//                // 除了长连接等少数长任务外，其它几乎都可以拆分成短任务，因此这里必须限制数量。
//                else if (sExecuting.sInfinite.get() >= maxPoolSize * 2 / 3) {
//                    allowRunLevel = 2;
//                } else allowRunLevel = 3;
//            }
//            Runner runner = null;
//            int index = -1;
//            for (int i = 0, level = Math.min(allowRunLevel, sPreparedBuckets.sQueues.length - 1); i <= level; i++) {
//                final Runner r = sPreparedBuckets.sQueues[i].peek();
//                if (r != null && (runner == null || // 值越小优先级越大
//                        (r.trait.priority$() + r.trait.period$().weight/*采用混合优先级*/)
//                                < runner.trait.priority$() + runner.trait.period$().weight)) {
//                    runner = r;
//                    index = i;
//                }
//            }
//            if (runner == null) {
//                // 必须放在sSignature前面，确保不会有某个瞬间丢失调度(外部线程拿不到锁，而本线程认为没有任务了)。
//                sScheduling.set(false);
//                // 再看看是不是又插入了新任务，并重新竞争锁定。
//                // 如果不要sSignature的判断而简单再来一次是不是就解决了问题呢？
//                // 不能。这个再来一次的问题会递归。
//                if (sSignature.get() && sScheduling.compareAndSet(false, true)) {
//                    sSignature.set(false); // 等竞争到了再置为false.
//                    // continue;
//                } else break;
//            } else {
//                // 队列结构可能发生改变，不能用poll(); 而remove()是安全的：runner都是重新new出来的，不会出现重复。
//                if (sPreparedBuckets.sQueues[index].remove(runner)) {
//                    sExecuting.sCounters[index].incrementAndGet();
//                    final Runner r = runner;
//                    final int i = index;
//                    executor.execute(() -> {
//                        try {
//                            r.run();
//                        } catch (Throwable ignore) {
//                            Assist.Monitor.threadPoolError(ignore);
//                        } finally {
//                            // r.run()里的endMe()会触发本调度方法，但发生在本句递减计数之前，因此调度几乎无效，已删。
//                            sExecuting.sCounters[i].decrementAndGet();
//                            resetThread(Thread.currentThread());
//                            scheduleBuckets();
//                        }
//                    });
//                }
//            }
//        }
//    }
//
//    static class Runner implements Runnable, Comparable<Runner> {
//        final Trait<?> trait;
//        final Runnable runnable;
//
//        Runner(Trait<?> trait, Runnable runnable) {
//            this.trait = trait;
//            this.runnable = runnable;
//        }
//
//        @Override
//        public final int compareTo(Runner o) {
//            return Integer.compare(trait.priority$(), o.trait.priority$());
//        }
//
//        @Override
//        public void run() {
//            runnable.run();
//        }
//    }
//}
