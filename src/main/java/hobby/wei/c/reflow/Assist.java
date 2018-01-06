///*
// * Copyright (C) 2016-present, Wei Chou(weichou2010@gmail.com)
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
//import java.lang.ref.WeakReference;
//import java.util.Collection;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.Locale;
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.ThreadPoolExecutor;
//
//import hobby.chenai.nakam.basis.TAG;
//import hobby.wei.c.anno.proguard.Burden;
//import hobby.wei.c.log.LoggerJ;
//
///**
// * @author Wei Chou(weichou2010@gmail.com)
// * @version 1.0, 02/07/2016
// */
//public class Assist {
//    static final TAG.LogTag TAG = new TAG.LogTag(Assist.class.getName());
//    static boolean DEBUG;
//    static LoggerJ logger = new LoggerJ();
//
//    public static <T> T getRef(WeakReference<T> ref) {
//        return ref == null ? null : ref.get();
//    }
//
//    public static float between(float min, float value, float max) {
//        return Math.max(min, Math.min(value, max));
//    }
//
//    public static int between(int min, int value, int max) {
//        return Math.max(min, Math.min(value, max));
//    }
//
//    @Burden
//    public static void assertx(boolean b) {
//        assertf(b, null, false);
//    }
//
//    public static void assertf(boolean b) {
//        assertf(b, null, true);
//    }
//
//    @Burden
//    public static void assertx(boolean b, String msg) {
//        assertf(b, msg, false);
//    }
//
//    public static void assertf(boolean b, String msg) {
//        assertf(b, msg, true);
//    }
//
//    private static void assertf(boolean b, String msg, boolean force) {
//        if ((force || DEBUG) && !b) Throws.assertError(msg);
//    }
//
//    public static String requireNonEmpty(String s) {
//        assertf(s != null && s.length() > 0);
//        return s;
//    }
//
//    public static <C extends Collection<?>> C requireNonNullElem(C col) {
//        for (Object t : col) {
//            assertf(t != null, "元素不能为null.");
//        }
//        return col;
//    }
//
//    /**
//     * 由于{@link Key$#equals(Object)}是比较了所有参数，所以这里还得重新检查。
//     */
//    public static <C extends Collection<Key$>> C requireKey$kDiff(C keys) {
//        if (keys.isEmpty()) return keys;
//        final Set<String> ks = new HashSet<>();
//        for (Key$ k : keys) {
//            if (ks.contains(k.key)) Throws.sameKey$k(k);
//            ks.add(k.key);
//        }
//        return keys;
//    }
//
//    /**
//     * 要求相同的输入key的type也相同，但不要求不能有相同的输出key，因为输出key由需求决定，而需求已经限制了不同。
//     */
//    public static <C extends Collection<Transformer>> C requireTransInTypeSame(C tranSet) {
//        if (!tranSet.isEmpty()) {
//            final Map<String, Transformer> map = new HashMap<>();
//            for (Transformer t : tranSet) {
//                if (map.containsKey(t.in.key)) {
//                    final Transformer trans = map.get(t.in.key);
//                    if (!t.in.equals(trans.in)) Throws.tranSameKeyButDiffType(t.in, trans.in);
//                } else {
//                    map.put(t.in.key, t);
//                }
//            }
//        }
//        return tranSet;
//    }
//
//    public static void eatExceptions(Runnable work, Runnable onError) {
//        try {
//            work.run();
//        } catch (Exception e) {
//            logger.w(TAG, "eatExceptions", e);
//            if (onError != null) onError.run();
//        }
//    }
//
//    static class Throws {
//        static void sameName(String name) {
//            throw new IllegalArgumentException(String.format(Locale.CHINA, "队列中不可以有相同的任务名称。" +
//                    "名称为\"%s\"的Task已存在, 请确认或尝试重写其name()方法。", name));
//        }
//
//        static void sameOutKeyParallel(Key$ key, Trait trait) {
//            throw new IllegalArgumentException(String.format(Locale.CHINA,
//                    "并行的任务不可以有相同的输出。key: \"%s\", Task: \"%s\".", key.key, trait.name$()));
//        }
//
//        static <T> T sameCacheKey(Key$ key) {
//            throw new IllegalArgumentException(String.format(Locale.CHINA,
//                    "Task.cache(key, value)不可以和与该Task相关联的Trait.requires()有相同的key: \"%s\"", key.key));
//        }
//
//        static <T> T sameKey$k(Key$ key) {
//            throw new IllegalArgumentException(String.format(Locale.CHINA,
//                    "集合中的Key$.key不可以重复: \"%s\"", key));
//        }
//
//        static void lackIOKey(Key$ key, boolean in$out) {
//            throw new IllegalStateException(String.format(Locale.CHINA, "缺少%s参数: %s.", in$out ? "输入" : "输出", key));
//        }
//
//        static void lackOutKeys() {
//            throw new IllegalStateException("所有任务的输出都没有提供最终输出, 请检查。");
//        }
//
//        static void typeNotMatch(Key$ key, Class<?> clazz) {
//            throw new IllegalArgumentException(String.format(
//                    Locale.CHINA, "key为\"%s\"的参数值类型与定义不一致: 应为\"%s\", 实际为\"%s\".",
//                    key.key, key.type, clazz));
//        }
//
//        static void typeNotMatch4Trans(Key$ from, Key$ to) {
//            typeNotMatch(to, from, "转换");
//        }
//
//        static void typeNotMatch4Consume(Key$ from, Key$ to) {
//            typeNotMatch(to, from, "消化需求");
//        }
//
//        static void typeNotMatch4Required(Key$ from, Key$ to) {
//            typeNotMatch(to, from, "新增初始输入");
//        }
//
//        static void typeNotMatch4RealIn(Key$ from, Key$ to) {
//            typeNotMatch(to, from, "实际输入");
//        }
//
//        private static void typeNotMatch(Key$ from, Key$ to, String opt) {
//            throw new IllegalArgumentException(String.format(Locale.CHINA, "赋值类型不匹配: \"%s\" but \"%s\". 操作: \"%s\"",
//                    to.type, from.type, opt));
//        }
//
//        static void tranSameKeyButDiffType(Key$ one, Key$ another) {
//            throw new IllegalArgumentException(String.format(Locale.CHINA, "多个转换使用同一输入key但类型不一致:" +
//                    " key: \"%s\", types: \"%s\"、\"%s\"", one.key, one.type, another.type));
//        }
//
//        static void assertError(String msg) {
//            throw new AssertionError(msg);
//        }
//    }
//
//    static class Monitor {
//        private static final String TAG = Monitor.class.getName();
//
//        private static TAG.LogTag tag(String name) {
//            return new TAG.LogTag(TAG + "." + name);
//        }
//
//        static void duration(String name, long begin, long end, Reflow.Period period) {
//            final long duration = end - begin;
//            final long avg = period.average(duration);
//            if (avg == 0 || duration <= avg) {
//                logger.i(tag("duration"), "task:%s, period:%s, duration:%fs, average:%fs", name, period, duration / 1000f, avg / 1000f);
//            } else {
//                logger.w(tag("duration"), "task:%s, period:%s, duration:%fs, average:%fs", name, period, duration / 1000f, avg / 1000f);
//            }
//        }
//
//        static void abortion(String triggerFrom, String name, boolean forError) {
//            logger.i(tag("abortion"), "triggerFrom:%1$s, task:%2$s, forError:%3$s", triggerFrom, name, forError);
//        }
//
//        @Burden
//        static void assertStateOverride(State prev, State state, boolean success) {
//            if (!success) {
//                logger.e(tag("abortion"), "illegal state override! prev:%s, state:%s", prev, state);
//                assertx(success);
//            }
//        }
//
//        static void complete(int step, Out out, Out flow, Out trimmed) {
//            logger.i(tag("complete"), "step:%d, out:%s, flow:%s, trimmed:%s", step, out, flow, trimmed);
//        }
//
//        static void threadPool(ThreadPoolExecutor pool, boolean addThread, boolean reject) {
//            logger.i(tag("threadPool"), "{ThreadPool}%s, active/core:(%d/%d/%d), taskCount:%d, largestPool:%d",
//                    reject ? "reject runner" : addThread ? "add thread" : "offer queue",
//                    pool.getActiveCount(), pool.getPoolSize(), pool.getMaximumPoolSize(),
//                    pool.getTaskCount(), pool.getLargestPoolSize());
//        }
//
//        static void threadPoolError(Throwable t) {
//            logger.e(tag("threadPoolError"), t);
//        }
//    }
//
//    static class FailedException extends Exception {
//        FailedException(Throwable e) {
//            super(e);
//        }
//    }
//
//    static class AbortException extends Exception {
//        AbortException(Throwable e) {
//            super(e);
//        }
//    }
//
//    static class FailedError extends Error {
//        FailedError(Exception e) {
//            super(e);
//        }
//    }
//
//    static class AbortError extends Error {
//        AbortError() {
//            super();
//        }
//
//        AbortError(Exception e) {
//            super(e);
//        }
//    }
//
//    static class InnerError extends Error {
//        InnerError(Throwable e) {
//            super(e);
//        }
//    }
//}
