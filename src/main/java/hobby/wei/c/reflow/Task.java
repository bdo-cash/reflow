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

package hobby.wei.c.reflow;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import hobby.wei.c.tools.Locker;

import static hobby.wei.c.reflow.Assist.*;
import static hobby.wei.c.reflow.Assist.assertx;

/**
 * @author Wei.Chou (weichou2010@gmail.com)
 * @version 1.0, 26/06/2016
 */
public abstract class Task {
    private Thread thread;
    private Tracker tracker;
    private Trait trait;
    private Out input;
    /*package*/ Out out;
    private boolean aborted;
    private boolean working;

    final void env(Tracker tracker, Trait<?> trait, Out input, Out out) {
        this.tracker = tracker;
        this.trait = trait;
        this.input = input;
        this.out = out;
    }

    protected final boolean isReinforceRequired() {
        return tracker.reinforceRequired.get();
    }

    protected final boolean isReinforcing() {
        return tracker.getState() == State.REINFORCING;
    }

    /**
     * 请求强化运行。
     *
     * @return 之前的任务是否已经请求过, 同{@link #isReinforceRequired()}
     */
    protected final boolean requireReinforce() {
        return tracker.requireReinforce();
    }

    /**
     * 取得输入的value.
     *
     * @param key
     * @param <T>
     * @return
     */
    protected final <T> T input(String key) {
        return (T) input.get(key);
    }

    protected final <T> void output(String key, T value) {
        out.put(key, value);
    }

    protected final void output(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            output(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 如果 {@link #isReinforceRequired()}为true, 则缓存一些参数用于再次执行时使用。
     * 问: 为何不直接用全局变量来保存?
     * 答: 下次并不重用本对象。
     *
     * @param key
     * @param value
     */
    protected final <T> void cache(String key, T value) {
        if (isReinforceRequired()) {
            input.cache(key, null);   // 仅用来测试key是否重复。
            tracker.cache(trait, true).cache(key, value);
        }
    }

    protected final void progress(float progress) {
        tracker.onTaskProgress(trait, progress, out);
    }

    /**
     * 如果认为任务失败, 则应该主动调用本方法来强制结束任务。
     * 不设计成直接声明{@link #doWork()}方法throws异常, 是为了让客户代码尽量自己处理好异常, 以防疏忽。
     *
     * @param e
     */
    protected final <T> T failed(Exception e) {
        // 简单粗暴的抛出去, 由Runner统一处理。
        // 这里不抛出Exception的子类, 是为了防止被客户代码错误的给catch住。
        // 但是exec()方法catch了本Error并转换为正常的Assist.FailedException
        throw new FailedError(Objects.requireNonNull(e));
    }

    /**
     * 如果子类在{@link #doWork()}中检测到了中断请求(如: 在循环里判断{@link #isAborted()}),
     * 应该在处理好了当前事宜、准备好中断的时候调用本方法以中断整个任务。
     */
    protected final <T> T abortDone() {
        throw new AbortError();
    }

    final void exec() throws FailedException, AbortException, CodeException {
        synchronized (this) {
            if (aborted) return;
            thread = Thread.currentThread();
            working = true;
        }
        try {
            // 这里反馈进度有两个用途: 1. Feedback subProgress; 2. 并行任务进度统计。
            progress(0);
            doWork();
            progress(1);
        } catch (FailedError e) {
            throw new FailedException(e.getCause());
        } catch (AbortError e) {
            throw new AbortException(e.getCause());
        } catch (Exception e) {
            // 各种非正常崩溃的RuntimeException, 如NullPointerException等。
            throw new CodeException(e);
        }
    }

    final void abort() {
        synchronized (this) {
            aborted = true;
        }
        if (working) {
            thread.interrupt();
            onAbort();
        }
    }

    protected final Trait trait() {
        return trait;
    }

    protected final boolean isAborted() {
        return aborted;
    }

    protected abstract void doWork();

    protected void onAbort() {
    }

    /**
     * 当同一个任务被放到多个{@link Dependency}中运行, 而某些代码段需要Class范围的串行化时, 应该使用本方法包裹。
     * 注意: 不要使用synchronized关键字, 因为它在等待获取锁的时候不能被中断, 而本方法使用{@link ReentrantLock}锁机制,
     * 当{@link #abort()}请求到达时, 如果还处于等待获取锁状态, 则可以立即中断。
     *
     * @param codes 要执行的代码段, 应包裹在{@link Locker.CodeZ#exec()}中。
     * @param scope 锁的作用范围。通常应该写某Task子类的ClassName.class, 而不要去{@link #getClass()},
     *              因为如果该类再有一个子类, 本方法在不同的实例返回不同的Class对象, scope不同, 同步目的将失效。
     * @param <T>   返回值类型。
     * @return {@link Locker.CodeZ#exec()}的返回值。
     */
    protected final <T> T sync(Locker.CodeZ<T> codes, Class<? extends Task> scope) {
        try {
            return Locker.sync(codes, Objects.requireNonNull(scope));
        } catch (InterruptedException e) {
            assertx(isAborted());
            throw new AbortError(e);
        }
    }
}
