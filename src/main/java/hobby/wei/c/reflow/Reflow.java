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

package hobby.wei.c.reflow;

import hobby.wei.c.log.Logger;

import static hobby.wei.c.reflow.Assist.requireNonEmpty;
import static hobby.wei.c.reflow.Worker.sPreparedBuckets.queue4;
import static hobby.wei.c.reflow.Worker.scheduleBuckets;

/**
 * 任务[串并联]组合调度框架。
 * <p><b><i>
 * 概述
 * </i></b>
 * <p>
 * 本框架为简化[多{@link Task 任务}之间的数据流转和事件处理]的编码复杂度而生，通过[要求{@link Trait
 * 显式定义}任务的{@link Trait#requires() I}/{@link Trait#outs() O}]、基于{@link Key$
 * [关键字]和[数据类型]}的[预先&运行时输出]检查(深层泛型解析)策略、一致的{@link Feedback
 * 事件反馈}及错误处理接口的设计，实现了预期目标。
 * <p>
 * 此外还有优化的{@link Config 可配置} {@link Worker 线程池}、基于{@link #P_NORMAL 优先级}和{@link Period
 * 预估时长}的[按需的]任务装载机制、便捷的[{@link Scheduler#sync() 同}/异(默认)步]调用接口、巧妙的{@link Scheduler#abort()
 * 中断}策略、[浏览/{@link Task#requireReinforce() 强化}]运行模式、无依赖输出丢弃等设计，可实现线程的无阻塞高效利用、
 * 全局精准的任务管理、任务的自由串并联组合、内存的有效利用(丢弃)、以及数据的快速加载(浏览模式)，以满足实际项目需求。
 * <p><b><i>
 * 其它
 * </i></b>
 * <p>
 * 本框架的主要功能类似<a href="http://github.com/BoltsFramework/Bolts-Android">Facebook
 * Bolts</a>和<a href="https://github.com/ReactiveX/RxJava">RxJava</a>的部分功能，
 * 可以视为对它们[任务组合能力的]细粒度的扩展。
 * <p>
 * 本框架基于{@link java.util.concurrent.ThreadPoolExecutor
 * 线程池}实现而非{@link java.util.concurrent.ForkJoinPool
 * Fork/Join框架(JDK 1.7)}，并对前者作了改进以符合[先增加线程数到{@link Config#maxPoolSize()
 * 最大}，再入队列，空闲释放线程]这个基本逻辑；
 * 后者适用于计算密集型任务，但不适用于本框架的调度策略，也不适用于资源受限的设备(如：手机等)。
 *
 * @author Wei.Chou(weichou2010 @ gmail.com)
 * @version 1.0, 12/04/2015
 */
public class Reflow {
    /**
     * 高优先级的任务将被优先执行(注意: 不是线程优先级)。在{@link Period}相同的情况下。
     *
     * @see #P_NORMAL
     */
    public static final int P_HIGH = -10;
    /**
     * 相同优先级的任务将根据提交顺序依次执行(注意: 不是线程优先级)。在{@link Period}相同的情况下。
     * <p>
     * 注意：并不是优先级高就必然能获得优先调度权，还取决于{@link Period}以及系统资源占用情况。
     *
     * @see Period#INFINITE
     * @see Config#maxPoolSize()
     */
    public static final int P_NORMAL = 0;
    /**
     * 低优先级的任务将被延后执行(注意: 不是线程优先级)。在{@link Period}相同的情况下。
     *
     * @see #P_NORMAL
     */
    public static final int P_LOW = 10;

    /**
     * 任务大概时长。
     */
    public enum Period {
        /**
         * 任务执行时间：瞬间。
         */
        TRANSIENT(1),
        /**
         * 任务执行时间：很短。
         */
        SHORT(2),
        /**
         * 任务执行时间：很长。
         */
        LONG(5),
        /**
         * 任务执行时间：无限长。
         * <p>
         * 注意：只有在当前时长任务的优先级{@link #P_HIGH 最高}而{@link
         * #TRANSIENT}任务的优先级{@link #P_LOW 最低}时，才能真正实现优先于{@link
         * #TRANSIENT}执行。
         *
         * @see #weight
         */
        INFINITE(20);

        /**
         * 辅助任务{@link Trait#priority() 优先级}的调度策略参考。
         */
        public final int weight;
        private long average = 0;
        private long count = 0;

        Period(int weight) {
            this.weight = weight;
        }

        public synchronized long average(long duration) {
            if (duration <= 0) return average;
            final long prevAvg = average;
            final long prevCnt = count;
            average = (prevAvg * prevCnt + duration) / ++count;
            return prevAvg;
        }
    }

    private static Config sConfig = Config.DEF;

    /**
     * 设置自定义的{@link Config}. 注意: 只能设置一次。
     */
    public static void setConfig(Config config) {
        if (sConfig == Config.DEF && config != Config.DEF) {
            sConfig = config;
            Worker.updateConfig(config);
        }
    }

    public static Config config() {
        return sConfig;
    }

    public static void setThreadResetor(ThreadResetor resetor) {
        Worker.setThreadResetor(resetor);
    }

    public static void setLogger(Logger logger) {
        Assist.logger = logger;
    }

    public static Logger logger() {
        return Assist.logger;
    }

    public static void setDebugMode(boolean b) {
        // 用synchronized而不给DEBUG加volatile
        Assist.DEBUG = b;
    }

    public static boolean debug() {
        return Assist.DEBUG;
    }

    private Reflow() {
    }

    /**
     * 创建以参数开始的新任务流。
     *
     * @param trait 打头的{@link Trait}
     * @return 新的任务流。
     */
    public static Dependency create(Trait<?> trait) {
        return builder().then(trait);
    }

    /**
     * 复制参数指定的队列。
     *
     * @param dependency
     * @return
     */
    public static Dependency create(Dependency dependency) {
        return builder().then(dependency);
    }

    /**
     * @see #execute(Runnable, Period, int, String)
     * @deprecated 应该使用标准
     * {@link Task}方式，至少应该使用
     * {@link #execute(Runnable, Period, int, String)}.
     */
    @Deprecated
    public static void execute(final Runnable runner) {
        execute$(runner, Period.SHORT, P_NORMAL, null);
    }

    /**
     * 为{@link Runnable}提供运行入口，以便将其纳入框架的调度管理。
     *
     * @param runner   包装要运行代码的{@link Runnable}.
     * @param period   同{@link Trait#period()}.
     * @param priority 同{@link Trait#priority()}.
     * @param desc     同{@link Trait#description()}.
     */
    public static void execute(final Runnable runner, Period period, int priority, String desc) {
        execute$(runner, period, priority, requireNonEmpty(desc));
    }

    private static void execute$(final Runnable runner, final Period period, final int priority, final String desc) {
        queue4(period).offer(new Worker.Runner(new Trait.Empty() {
            @Override
            protected int priority() {
                return priority;
            }

            @Override
            protected Period period() {
                return period;
            }

            @Override
            protected Task newTask() {
                return null;
            }

            @Override
            protected String description() {
                return desc == null ? name$() : desc;
            }
        }, runner));
        scheduleBuckets();
    }

    private static Dependency builder() {
        return new Dependency();
    }
}
