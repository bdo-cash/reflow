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
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.LinkedList;
//import java.util.List;
//import java.util.Map;
//import java.util.Queue;
//import java.util.Set;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.CopyOnWriteArraySet;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicInteger;
//import java.util.concurrent.locks.Condition;
//
//import hobby.wei.c.tools.Locker;
//
//import static hobby.wei.c.reflow.Assist.*;
//import static hobby.wei.c.reflow.Assist.DEBUG;
//import static hobby.wei.c.reflow.Assist.assertx;
//import static hobby.wei.c.reflow.Assist.between;
//import static hobby.wei.c.reflow.Assist.eatExceptions;
//import static hobby.wei.c.reflow.Dependency.copy;
//import static hobby.wei.c.reflow.Dependency.doTransform;
//
//final class Tracker implements Scheduler {
//    private static final String TAG = Tracker.class.getSimpleName();
//
//    static class Runner extends Worker.Runner {
//        final Tracker tracker;
//
//        private volatile Task task;
//        private volatile boolean abort;
//        private long timeBegin;
//
//        Runner(Tracker tracker, Trait<?> trait) {
//            super(trait, null);
//            this.tracker = tracker;
//        }
//
//        @Override
//        public final boolean equals(Object obj) {
//            return super.equals(obj);
//        }
//
//        // 这个场景没有使用synchronized, 跟Task.abort()场景不同。
//        void abort() {
//            // 防止循环调用
//            if (abort) return;
//            abort = true;
//            if (task != null) {
//                task.abort();
//            }
//        }
//
//        @Override
//        public void run() {
//            boolean working = false;
//            try {
//                task = trait.newTask();
//                // 判断放在mTask的创建后面, 配合abort()中的顺序。
//                if (abort) {
//                    onAbort(false);
//                } else {
//                    onStart();
//                    final Out input = new Out(trait.requires$());
//                    L.i(trait.name$(), "input:%s", input);
//                    input.fillWith(tracker.prevOutFlow);
//                    final Out cached = tracker.cache(trait, false);
//                    if (cached != null) input.cache(cached);
//                    final Out out = new Out(trait.outs$());
//                    task.env(tracker, trait, input, out);
//                    working = true;
//                    L.i(trait.name$(), "111111111111");
//                    task.exec();
//                    L.i(trait.name$(), "222222222222");
//                    L.i(trait.name$(), "out:%s", out);
//                    final Map<String, Key$> dps = tracker.basis.dependencies.get(trait.name$());
//                    L.i(trait.name$(), "dps:%s", dps);
//                    final Out flow = new Out(dps == null ? Collections.emptyMap() : dps);
//                    L.i(trait.name$(), "flow0:%s", flow);
//                    final Map<String, Object> map = out.map.isEmpty() ? Collections.emptyMap() : new HashMap<>(out.map);
//                    final Set<Key$> nulls = out.nullValueKeys.isEmpty() ? Collections.emptySet() : new HashSet<>(out.nullValueKeys);
//                    doTransform(tracker.basis.transformers.get(trait.name$()), map, nulls, false);
//                    flow.putWith(map, nulls, false, true);
//                    L.i(trait.name$(), "flow1:%s", flow);
//                    working = false;
//                    onComplete(out, flow);
//                    if (abort) {
//                        onAbort(true);
//                    }
//                }
//            } catch (Exception e) {
//                L.i(trait.name$(), "exception:%s", e);
//                if (working) {
//                    if (e instanceof AbortException) {   // 框架抛出的, 表示成功中断
//                        onAbort(false);
//                    } else {
//                        if (e instanceof FailedException) {
//                            onFailed((Exception) e.getCause());
//                        } else if (e instanceof CodeException) {    // 客户代码问题
//                            onException((CodeException) e);
//                        } else {
//                            onException(new CodeException(e));
//                        }
//                    }
//                } else {
//                    innerError(e);
//                }
//            } finally {
//                endMe();
//            }
//        }
//
//        private void onStart() {
//            tracker.onTaskStart(trait);
//            timeBegin = System.currentTimeMillis();
//        }
//
//        private void onComplete(Out out, Out flow) {
//            Monitor.duration(trait.name$(), timeBegin, System.currentTimeMillis(), trait.period$());
//            tracker.onTaskComplete(trait, out, flow);
//        }
//
//        // 人为触发，表示任务失败
//        private void onFailed(Exception e) {
//            L.i(TAG, e);
//            abortAction(e).run();
//        }
//
//        // 客户代码异常
//        private void onException(CodeException e) {
//            L.w(TAG, e);
//            abortAction(e).run();
//        }
//
//        private void onAbort(boolean completed) {
//            tracker.performAbort(this, false, trait, null);
//        }
//
//        private Runnable abortAction(Exception e) {
//            return () -> {
//                if (!abort) abort = true;
//                tracker.performAbort(this, true, trait, e);
//            };
//        }
//
//        private void innerError(Exception e) {
//            L.e(TAG, e);
//            tracker.innerError(this, e);
//            throw new InnerError(e);
//        }
//
//        private void endMe() {
//            tracker.endRunner(this);
//        }
//    }
//
//    ///////////////////////////////////////////////////////////////////////////////////////
//    //************************************ Scheduler ************************************//
//
//    private final Dependency.Basis basis;
//    private final Trait<?> traitIn;
//    private final Set<Transformer> inputTrans;
//    private final Set<Runner> runnersParallel = new CopyOnWriteArraySet<>();
//    // 本数据结构是同步操作的, 不需要ConcurrentLinkedQueue
//    private final Queue<Trait<?>> remaining = new LinkedList<>();
//    // 用volatile而不在赋值的时候用synchronized是因为读写操作不会同时发生。
//    private volatile Queue<Trait<?>> reinforce;
//    final AtomicBoolean reinforceRequired = new AtomicBoolean(false);
//    private final Map<String, Out> reinforceCaches = new ConcurrentHashMap<>();
//    private final Map<String, Float> progress = new HashMap<>();
//    private final Impl.State$ state;
//    private final Reporter reporter;
//    //    private final AtomicInteger step = new AtomicInteger(-1/*第一个任务是系统input任务*/);
//    private final AtomicInteger sumParallel = new AtomicInteger();
//    private final int sum;
//    private volatile int sumReinforce;
//    private volatile boolean normalDone, reinforceDone;
//    private volatile Out outFlowTrimmed = new Out(Collections.emptyMap());
//    private volatile Out prevOutFlow, reinforceInput;
//
//    Tracker(Dependency.Basis basis, Trait<?> traitIn, Set<Transformer> inputTrans,
//            Impl.State$ state, Feedback feedback, Poster poster) {
//        this.basis = basis;
//        this.traitIn = traitIn;
//        this.inputTrans = inputTrans;
//        this.state = state;
//        copy(basis.traits, remaining);
//        sum = remaining.size();
//        reporter = new Reporter(feedback, poster, sum);
//    }
//
//    boolean start() {
//        if (tryScheduleNext()) {
//            Worker.scheduleBuckets();
//            return true;
//        }
//        return false;
//    }
//
//    /**
//     * 先于{@link #endRunner(Runner)}执行。
//     */
//    private void innerError(Runner runner, Exception e) {
//        L.i(runner.trait.name$(), "innerError");
//        // 正常情况下是不会走的，仅用于测试。
//        performAbort(runner, true, runner.trait, e);
//    }
//
//    private synchronized void endRunner(Runner runner) {
//        L.i(runner.trait.name$(), "endRunner");
//        runnersParallel.remove(runner);
//        if (runnersParallel.isEmpty() && state.get$() != State.ABORTED && state.get$() != State.FAILED) {
//            assertx(sumParallel.get() == 0);
//            progress.clear();
//            step.incrementAndGet();
//            doTransform();
//            tryScheduleNext();
//        }
//    }
//
//    // 必须在进度反馈完毕之后再下一个, 否则可能由于线程优先级问题, 导致低优先级的进度没有反馈完,
//    // 而新进入的高优先级任务又要争用同步锁, 造成死锁的问题。
//    private synchronized boolean tryScheduleNext() {
//        if (step.get() >= 0) remaining.poll();    // 原因见下文的peek().
//        if (remaining.isEmpty()) {
//            if (reinforceRequired.get()) {
//                Dependency.copy(reinforce/*注意写的时候没有synchronized*/, remaining);
//                outFlowTrimmed = reinforceInput;
//                reinforce.clear();
//            } else return false;
//        }
//        assertx(state.get() == State.PENDING/*start()的时候已经PENDING了*/
//                || state.forward(State.PENDING)
//                || state.forward(State.REINFORCE_PENDING));
//        // 不poll(), 以备requireReinforce()的copy().
//        final Trait trait = remaining.peek();
//        if (trait instanceof Trait.Parallel) {
//            Trait.Parallel parallel = (Trait.Parallel) trait;
//            final List<Runner> runners = new LinkedList<>();
//            for (Trait tt : parallel.traits()) {
//                runners.add(new Runner(this, tt));
//                // 把并行的任务put进去，不然计算子进度会有问题。
//                progress.put(tt.name$(), 0f);
//            }
//            runnersParallel.addAll(runners);
//        } else {
//            // progress.put(trait.name$(), 0f);
//            runnersParallel.add(new Runner(this, trait));
//        }
//        sumParallel.set(runnersParallel.size());
//        resetOutFlow(new Out(basis.outsFlowTrimmed.get(trait.name$())));
//        // 在调度之前获得结果比较保险
//        final boolean hasMore = sumParallel.get() > 0;
//        for (Runner runner : runnersParallel) {
//            switch (runner.trait.period$()) {
//                case INFINITE:
//                    Worker.sPreparedBuckets.sInfinite.offer(runner);
//                    break;
//                case LONG:
//                    Worker.sPreparedBuckets.sLong.offer(runner);
//                    break;
//                case SHORT:
//                    Worker.sPreparedBuckets.sShort.offer(runner);
//                    break;
//                case TRANSIENT:
//                    Worker.sPreparedBuckets.sTransient.offer(runner);
//                    break;
//            }
//        }
//        // 不写在这里，原因见下面方法本身：写在这里几乎无效。
//        // scheduleBuckets();
//        return hasMore;
//    }
//
//    boolean requireReinforce() {
//        if (!reinforceRequired.getAndSet(true)) {
//            reinforce = new LinkedList<>();
//            copy(remaining, reinforce);
//            sumReinforce = reinforce.size();
//            reinforceInput = prevOutFlow;
//            return false;
//        }
//        return true;
//    }
//
//    Out cache(Trait<?> trait, boolean create) {
//        Out cache = reinforceCaches.get(trait.name$());
//        if (cache == null && create) {
//            cache = new Out(Helper.Keys.empty());
//            reinforceCaches.put(trait.name$(), cache);
//        }
//        return cache;
//    }
//
//    private void resetOutFlow(Out out) {
//        Locker.syncr(() -> {
//            prevOutFlow = outFlowTrimmed;
//            outFlowTrimmed = out;
//            joinOutFlow(prevOutFlow);
//            return null;
//        }, this);
//    }
//
//    private void joinOutFlow(Out out) {
//        Locker.syncr(() -> {
//            outFlowTrimmed.putWith(out.map, out.nullValueKeys, true, false);
//            return null;
//        }, this);
//    }
//
//    private void verifyOutFlow() {
//        if (DEBUG) outFlowTrimmed.verify();
//    }
//
//    private void performAbort(Runner trigger, boolean forError, Trait trait, Exception e) {
//        if (state.forward(forError ? State.FAILED : State.ABORTED)) {
//            for (Runner runner : runnersParallel) {
//                runner.abort();
//                Monitor.abortion(trigger == null ? null : trigger.trait.name$(), runner.trait.name$(), forError);
//            }
//            if (forError) {
//                reporter.reportOnFailed(trait, e);
//            } else {
//                reporter.reportOnAbort();
//            }
//        } else if (state.abort()) {
//            // 已经到达COMPLETED/REINFORCE阶段了
//        } else {
//            // 如果本方法被多次被调用，则会进入本case. 虽然逻辑上并不存在本case, 但没有影响。
//            // Throws.abortForwardError();
//        }
//        interruptSync(true/*既然是中断，应该让reinforce级别的sync请求也终止*/);
//    }
//
//    /**
//     * @deprecated 已在
//     * {@link Scheduler.Impl}中实现, 本方法不会被调用。
//     */
//    @Deprecated
//    @Override
//    public Out sync() {
//        return null;
//    }
//
//    @Override
//    public Out sync(final boolean reinforce, final long milliseconds) throws InterruptedException {
//        final long start = System.currentTimeMillis();
//        return Locker.sync(new Locker.CodeC<Out>(1) {
//            @Override
//            protected Out exec(Condition[] cons) throws InterruptedException {
//                // 不去判断mState是因为任务流可能会失败
//                while (!(reinforce ? reinforceDone : normalDone)) {
//                    if (milliseconds == -1) {
//                        cons[0].await();
//                    } else {
//                        final long delta = milliseconds - (System.currentTimeMillis() - start);
//                        if (delta <= 0 || !cons[0].await(delta, TimeUnit.MILLISECONDS)) {
//                            throw new InterruptedException();
//                        }
//                    }
//                }
//                return outFlowTrimmed;
//            }
//        }, this);
//    }
//
//    private void interruptSync(boolean reinforce) {
//        normalDone = true;
//        if (reinforce) {
//            reinforceDone = true;
//        }
//        try {
//            Locker.sync(new Locker.CodeC<Void>(1) {
//                @Override
//                protected Void exec(Condition[] cons) throws InterruptedException {
//                    cons[0].signalAll();
//                    return null;
//                }
//            }, this);
//        } catch (Exception e) {
//            // 不可能抛异常
//        }
//    }
//
//    @Override
//    public void abort() {
//        performAbort(null, false, null, null);
//    }
//
//    @Override
//    public State getState() {
//        return state.get();
//    }
//
//    /**
//     * @deprecated 不要调用。
//     */
//    @Deprecated
//    @Override
//    public boolean isDone() {
//        return false;
//    }
//
//    /**
//     * 每个Task都执行。
//     */
//    private void onTaskStart(Trait trait) {
//        Locker.syncr(() -> {
//            final int step = basis.stepOf(trait);
//            if (step >= sum) { // reinforce阶段(最后一个任务的step是sum - 1)
//                state.forward(State.REINFORCING);
//            } else if (step >= 0) {
//                if (state.forward(State.EXECUTING))
//                    // 但反馈有且只有一次
//                    if (step == 0) {
//                        reporter.reportOnStart();
//                    } else {
//                        // progress会在任务开始、进行中及结束时report，这里do nothing.
//                    }
//            }
//            return null;
//        }, this);
//    }
//
//    void onTaskProgress(Trait trait, float progress, Out out) {
//        Locker.syncr(() -> {
//            final int step = basis.stepOf(trait);
//            // 不用assertx(step >= 0 && step < sum),
//            // 因为对于State.REINFORCING, Task还是会进行反馈，但是这里需要过滤掉。
//            if (step >= 0 && step < sum) {
//                reporter.reportOnProgress(trait, step, subProgress(trait, progress), out);
//            }
//            return null;
//        }, this);
//    }
//
//    private float subProgress(Trait trait, float progress) {
//        if (this.progress.size() <= 1) return between(0, progress, 1);
//        if (progress > 0) {
//            assertx(this.progress.containsKey(trait.name$()));
//            synchronized (this.progress) {  // put操作和下面for求值应互斥
//                this.progress.put(trait.name$(), between(0, progress, 1));
//            }
//        }
//        float sum = 0;
//        synchronized (this.progress) {
//            for (float p : this.progress.values()) {
//                sum += p;
//            }
//        }
//        return sum / this.progress.size();
//    }
//
//    private void onTaskComplete(Trait trait, Out out, Out flow) {
//        Locker.syncr(() -> {
//            final int step = basis.stepOf(trait);
//            if (step < 0) resetOutFlow(flow);
//            else joinOutFlow(flow);
//            // 由于不是在这里移除(runnersParallel.remove(runner)), 所以不能用这个判断条件：
//            // (runnersParallel.size() == 1 && runnersParallel.contains(runner))
//            if (sumParallel.decrementAndGet() == 0) {
//                verifyOutFlow();
//                Monitor.complete(step, out, flow, outFlowTrimmed);
//                if (step == sum - 1) {
//                    Monitor.assertStateOverride(state.get(),
//                            State.COMPLETED, state.forward(State.COMPLETED));
//                    reporter.reportOnComplete(outFlowTrimmed); // 注意参数，因为这里是complete.
//                    interruptSync(!reinforceRequired.get());
//                } else if (step == sum + sumReinforce - 1) {
//                    Monitor.assertStateOverride(state.get(),
//                            State.UPDATED, state.forward(State.UPDATED));
//                    reporter.reportOnUpdate(outFlowTrimmed);
//                    interruptSync(true);
//                } else {
//                    // 单步任务的完成仅走进度(已反馈，不在这里反馈), 而不反馈完成事件。
//                }
//            }
//            return null;
//        }, this);
//    }
//
//    //////////////////////////////////////////////////////////////////////////////////////
//    //************************************ Reporter ************************************//
//
//    /**
//     * 该结构的目标是保证进度反馈的递增性。同时保留关键点，丢弃密集冗余。
//     */
//    private static class Reporter {
//        private final Feedback feedback;
//        private final Poster poster;
//        private final int sum;
//        private int step;
//        private float subProgress;
//        private boolean stateResetted = true;
//
//        Reporter(Feedback feedback, Poster poster, int sum) {
//            this.feedback = feedback;
//            this.poster = poster;
//            this.sum = sum;
//        }
//
//        private void reportOnStart() {
//            synchronized (this) {
//                assertx(stateResetted);
//                stateResetted = false;
//            }
//            wrapReport(Feedback::onStart);
//        }
//
//        private void reportOnProgress(Trait trait, int step, float subProgress, Out out) {
//            synchronized (this) {
//                if (stateResetted) {
//                    assertx(step == this.step + 1);
//                    this.step = step;
//                    this.subProgress = 0;
//                } else {
//                    assertx(step == this.step);
//                }
//                if (subProgress <= this.subProgress) {
//                    return;
//                }
//                this.subProgress = subProgress;
//                // 一定会有1的, Task#exec()里有progress(1), 会使单/并行任务到达1.
//                if (subProgress == 1) {
//                    stateResetted = true;
//                }
//            }
//            wrapReport((Feedback feedback) -> feedback.onProgress(trait.name$(),
//                    out, step, sum, subProgress, trait.desc$()));
//        }
//
//        private void reportOnComplete(Out out) {
//            assertx(step == sum - 1 && subProgress == 1);
//            wrapReport((Feedback feedback) -> feedback.onComplete(out));
//        }
//
//        private void reportOnUpdate(Out out) {
//            wrapReport((Feedback feedback) -> feedback.onUpdate(out));
//        }
//
//        private void reportOnAbort() {
//            wrapReport(Feedback::onAbort);
//        }
//
//        private void reportOnFailed(Trait trait, Exception e) {
//            wrapReport((Feedback feedback) -> feedback.onFailed(trait.name$(), e));
//        }
//
//        private void wrapReport(Exec<Feedback> r) {
//            final Feedback feedback = this.feedback;
//            if (feedback != null) {
//                final Poster poster = this.poster;
//                if (poster != null) {
//                    // 我们认为poster已经按规格实现了, 那么runner将在目标线程串行运行,
//                    // 不存在可见性问题, 不需要synchronized同步。
//                    eatExceptions(() -> poster.post(() -> r.exec(feedback)), null);
//                } else {
//                    eatExceptions(() -> {
//                        // 这个同步是为了保证可见性
//                        synchronized (this) {
//                            r.exec(feedback);
//                        }
//                    }, null);
//                }
//            }
//        }
//
//        private interface Exec<T> {
//            void exec(T args);
//        }
//    }
//}
