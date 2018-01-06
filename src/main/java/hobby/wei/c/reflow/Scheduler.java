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

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static hobby.wei.c.reflow.Dependency.requireInputsEnough;

/**
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 02/07/2016
 */
public interface Scheduler {
    interface Starter {
        /**
         * 启动任务。可并行启动多个。
         *
         * @param inputs   输入内容的加载器。
         * @param feedback 事件反馈回调接口。
         * @param poster   转移<code>feedback</code>的调用线程, 可为null.
         * @return true 启动成功, false 正在运行。
         */
        Scheduler start(In inputs, Feedback feedback, Poster poster);

        class Impl implements Starter {
            private final Dependency.Basis basis;
            private final Map<String, Key$> inputRequired;

            Impl(Dependency.Basis basis, Map<String, Key$> inputRequired) {
                this.basis = basis;
                this.inputRequired = inputRequired;
            }

            @Override
            public Scheduler start(In inputs, Feedback feedback, Poster poster) {
                requireInputsEnough(inputs, inputRequired, inputs.trans);
                final Trait<?> traitIn = new Trait.Input(inputs, new HashSet<>(inputRequired.values()),
                        basis.first(true).priority$());
                // 第一个任务是不需要trim的，至少从第二个以后。
                // 不可以将参数放进basis的任何数据结构里，因为basis需要被反复重用。
                return new Scheduler.Impl(basis, traitIn, inputs.trans, feedback, poster).start$();
            }
        }
    }

    /**
     * @see #sync(boolean, long)
     * @deprecated 好用但应慎用。会block住当前线程，几乎是不需要的。
     */
    @Deprecated
    Out sync();

    /**
     * 等待任务运行完毕并输出最终结果。如果没有拿到结果(已经{@link #isDone()}, 则会重新{@link Starter#start(In,
     * Feedback, Poster)} 启动}.
     *
     * @param reinforce    是否等待reinforce阶段。
     * @param milliseconds 延迟的deadline, 单位：毫秒。
     * @return 任务的最终结果，不会为null。
     * @throws InterruptedException 到达deadline了或者被中断。
     * @deprecated 好用但应慎用。会block住当前线程，几乎是不需要的。
     */
    @Deprecated
    Out sync(boolean reinforce, long milliseconds) throws InterruptedException;

    void abort();

    State getState();

    /**
     * 判断整个任务流是否运行结束。
     * 注意: 此时的{@link #getState()}值可能是{@link State#COMPLETED}、{@link State#FAILED}、
     * {@link State#ABORTED}或{@link State#UPDATED}中的某一种。
     *
     * @return true 已结束。
     */
    boolean isDone();

    /**
     * @author Wei Chou(weichou2010@gmail.com)
     * @version 1.0, 07/08/2016
     */
    class Impl implements Scheduler {
        private final State$ state = new State$();
        private volatile WeakReference<Scheduler> delegatorRef;
        private final Dependency.Basis basis;
        private final Trait<?> traitIn;
        private final Set<Transformer> inputTrans;
        private final Feedback feedback;
        private final Poster poster;

        Impl(Dependency.Basis basis, Trait<?> traitIn, Set<Transformer> inputTrans, Feedback feedback, Poster poster) {
            this.basis = basis;
            this.traitIn = traitIn;
            this.inputTrans = inputTrans;
            this.feedback = feedback;
            this.poster = poster;
        }

        private Tracker start$() {
            final boolean permit;
            synchronized (this) {
                if (isDone()) {
                    state.reset();
                    permit = true;
                } else {
                    permit = !state.overrided();
                }
            }
            if (permit && state.forward(State.PENDING)/*可看作原子锁*/) {
                final Tracker tracker = new Tracker(basis, traitIn, inputTrans, state, feedback, poster);
                // tracker启动之后被线程引用, 任务完毕之后被线程释放, 同时被gc。
                // 这里增加一层软引用, 避免在任务完毕之后得不到释放。
                synchronized (this) {
                    delegatorRef = new WeakReference<>(tracker);
                }
                tracker.start();
                return tracker;
            }
            return null;
        }

        private synchronized Scheduler getDelegator() {
            return Assist.getRef(delegatorRef);
        }

        @Override
        public Out sync() {
            try {
                return sync(false, -1);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Out sync(final boolean reinforce, final long milliseconds) throws InterruptedException {
            final long start = System.currentTimeMillis();
            Scheduler delegator;
            while (true) {
                delegator = getDelegator();
                if (delegator == null) delegator = start$();
                // 如果还没拿到, 说明其他线程也在同时start().
                if (delegator == null) {
                    Thread.yield(); // 那就等一下下再看看
                } else {
                    break;
                }
            }
            return delegator.sync(reinforce, milliseconds == -1 ? -1
                    : milliseconds - (System.currentTimeMillis() - start));
        }

        @Override
        public void abort() {
            final Scheduler delegator = getDelegator();
            if (delegator != null) delegator.abort();
        }

        @Override
        public State getState() {
            return state.get();
        }

        @Override
        public boolean isDone() {
            final State state = this.state.get();
            final Scheduler delegator = getDelegator();
            return state == State.COMPLETED &&
                    (delegator == null/*若引用释放,说明任务已不被线程引用,即运行完毕。*/ ||
                            !((Tracker) delegator).reinforceRequired.get())
                    || state == State.FAILED
                    || state == State.ABORTED || state == State.UPDATED;
        }

        static class State$ {
            private State state = State.IDLE;
            private State state$ = State.IDLE;
            private boolean overrided;

            public synchronized boolean forward(State state) {
                if (this.state.canOverrideWith(state)) {
                    this.state$ = this.state = state;
                    if (!overrided) overrided = true;
                    return true;
                }
                return false;
            }

            /**
             * 更新中断后的状态。
             *
             * @return 返回值与forward(State)方法互补的值。
             */
            public synchronized boolean abort() {
                state$ = State.ABORTED;
                switch (state) {
                    case REINFORCE_PENDING:
                    case REINFORCING:
                        state = State.COMPLETED;
                    case COMPLETED:
                    case UPDATED:
                        return true;
                    default:
                        return false;
                }
            }

            public synchronized State get() {
                return state;
            }

            synchronized State get$() {
                return state$;
            }

            private synchronized void reset() {
                state$ = state = State.IDLE;
            }

            /**
             * 可用于标识是否启动过。
             */
            private synchronized boolean overrided() {
                return overrided;
            }
        }
    }
}
