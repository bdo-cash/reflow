/*
 * Copyright (C) 2016-present, Wei.Chou(weichou2010@gmail.com)
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

package hobby.wei.c.tool;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import hobby.wei.c.reflow.Assist;

/**
 * 基于{@link ReentrantLock}的<code>synchronized</code>锁实现{@link #sync(Codes, Object)}。
 * 优势在于, 当{@link Thread#interrupt()}请求到达时, 如果还处于等待获取锁状态, 则可以立即中断。
 * <p>
 * 以及基于{@link #sync(Codes, Object)}的{@link #lazyGet(Codes, Codes, ReentrantLock)}懒加载实现。
 *
 * @author Wei.Chou (weichou2010@gmail.com)
 * @version 1.0, 27/07/2016
 */
public class Locker {
    private static final WeakHashMap<Object, WeakReference<ReentrantLock>> sLocks = new WeakHashMap<>();
    private static final ReentrantLock sLock = new ReentrantLock();

    /**
     * 一个等同于synchronized关键字功能的实现, 区别是本方法使用{@link ReentrantLock}锁机制,
     * 当{@link Thread#interrupt()}请求到达时, 如果还处于等待获取锁状态, 则可以立即中断。
     *
     * @param codes     要执行的代码段, 应包裹在{@link CodeZ#exec()}或{@link CodeC#exec(Condition[])}中。
     * @param lockScope 在哪个范围进行串行化, 可以是普通对象也可以是Class实例。
     * @param <T>       返回值类型。
     * @return {@link CodeZ#exec()}或{@link CodeC#exec(Condition[])}的返回值。
     * @throws InterruptedException 锁中断, codes并未开始执行。
     */
    public static <T> T sync(Codes<T> codes, Object lockScope) throws InterruptedException {
        return sync(codes, getLock(lockScope));
    }

    public static <T> T sync(Codes<T> codes, ReentrantLock lock) throws InterruptedException {
        // 如果中断了, 则并没有获取到锁, 不需要unlock(), 同时抛出异常中止本sync方法。
        lock.lockInterruptibly();
        try {
            return call(codes, lock);
        } finally {
            lock.unlock();
        }
    }

    private static <T> T call(Codes<T> codes, ReentrantLock lock) throws InterruptedException {
        if (codes instanceof CodeC) {
            return ((CodeC<T>) codes).exec$(lock);
        } else {
            return ((CodeZ<T>) codes).exec();
        }
    }

    /**
     * {@link #sync(Codes, Object)}的无{@link InterruptedException 中断}版。
     */
    public static <T> T syncr(CodeZ<T> codes, Object lockScope) {
        return syncr(codes, getLockr(lockScope));
    }

    /**
     * {@link #sync(Codes, ReentrantLock)}的无{@link InterruptedException 中断}版。
     */
    public static <T> T syncr(CodeZ<T> codes, ReentrantLock lock) {
        lock.lock();
        try {
            return callr(codes, lock);
        } finally {
            lock.unlock();
        }
    }

    private static <T> T callr(CodeZ<T> codes, ReentrantLock lock) {
        return codes.exec();
    }

    /**
     * 懒加载。
     *
     * @param get    仅仅用来取值的方法。
     * @param create 仅仅用来创建值的方法(不用判断值是否存在)。
     * @param lock   同步锁。
     * @param <T>    返回值类型。
     * @return 需要加载的内容, 是否为null取决于create结果。
     * @throws InterruptedException 被中断。
     */
    public static <T> T lazyGet(final Codes<T> get, final Codes<T> create, final ReentrantLock lock) throws InterruptedException {
        T instance = call(get, lock);
        if (instance == null) {
            instance = sync(new CodeC<T>(0) {
                @Override
                public T exec(Condition[] cons) throws InterruptedException {
                    T ins = call(get, lock);
                    if (ins == null) {
                        ins = call(create, lock);
                    }
                    return ins;
                }
            }, lock);
        }
        return instance;
    }

    public static ReentrantLock getLock(final Object lockScope) throws InterruptedException {
        return lazyGet(new CodeZ<ReentrantLock>() {
            @Override
            public ReentrantLock exec() {
                return Assist.getRef(sLocks.get(lockScope));
            }
        }, new CodeZ<ReentrantLock>() {
            @Override
            public ReentrantLock exec() {
                final ReentrantLock lock = new ReentrantLock(true); // 公平锁
                sLocks.put(lockScope, new WeakReference<>(lock));
                return lock;
            }
        }, sLock);
    }

    /**
     * {@link #lazyGet(Codes, Codes, ReentrantLock)}的无{@link InterruptedException 中断}版。
     */
    public static <T> T lazyGetr(final CodeZ<T> get, final CodeZ<T> create, final ReentrantLock lock) {
        T instance = callr(get, lock);
        if (instance == null) {
            instance = syncr(new CodeZ<T>() {
                @Override
                public T exec() {
                    T ins = callr(get, lock);
                    if (ins == null) {
                        ins = callr(create, lock);
                    }
                    return ins;
                }
            }, lock);
        }
        return instance;
    }

    /**
     * {@link #getLock(Object)}的无{@link InterruptedException 中断}版。
     */
    public static ReentrantLock getLockr(final Object lockScope) {
        return lazyGetr(new CodeZ<ReentrantLock>() {
                            @Override
                            public ReentrantLock exec() {
                                return Assist.getRef(sLocks.get(lockScope));
                            }
                        },
                new CodeZ<ReentrantLock>() {
                    @Override
                    public ReentrantLock exec() {
                        final ReentrantLock lock = new ReentrantLock(true); // 公平锁
                        sLocks.put(lockScope, new WeakReference<>(lock));
                        return lock;
                    }
                }, sLock);
    }

    private interface Codes<T> {
    }

    /**
     * 仅返回结果而不支持中断的{@link Codes}.
     *
     * @param <T>
     */
    public interface CodeZ<T> extends Codes<T> {
        T exec();
    }

    /**
     * 支持{@link Condition}和中断的{@link Codes}.
     *
     * @param <T>
     */
    public static abstract class CodeC<T> implements Codes<T> {
        private static final WeakHashMap<ReentrantLock, Condition[]> sLockCons = new WeakHashMap<>();
        private static final Condition[] EMPTY = new Condition[0];

        private final int mConNum;

        /**
         * @param num {@link Condition}需要的数量。
         */
        protected CodeC(int num) {
            mConNum = num;
        }

        private T exec$(final ReentrantLock lock) throws InterruptedException {
            return exec(lazyGet(new CodeZ<Condition[]>() {
                @Override
                public Condition[] exec() {
                    return mConNum == 0 ? EMPTY : sLockCons.get(lock);
                }
            }, new CodeZ<Condition[]>() {
                @Override
                public Condition[] exec() {
                    final Condition[] cons = new Condition[mConNum];
                    for (int i = 0; i < cons.length; i++) {
                        cons[i] = lock.newCondition();
                    }
                    sLockCons.put(lock, cons);
                    return cons;
                }
            }, lock));
        }

        protected abstract T exec(Condition[] cons) throws InterruptedException;
    }
}
