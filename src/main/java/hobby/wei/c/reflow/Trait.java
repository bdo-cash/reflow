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
//import java.util.Collection;
//import java.util.LinkedList;
//import java.util.List;
//import java.util.Set;
//import java.util.concurrent.atomic.AtomicInteger;
//
//import static hobby.wei.c.reflow.Assist.*;
//import static hobby.wei.c.reflow.Assist.assertf;
//import static hobby.wei.c.reflow.Assist.requireKey$kDiff;
//import static hobby.wei.c.reflow.Assist.requireNonEmpty;
//import static hobby.wei.c.reflow.Assist.requireNonNullElem;
//import static java.util.Objects.requireNonNull;
//
///**
// * 用于发布{@link Task}的I/O接口及调度策略信息。
// * 而{@link Task}本身仅用于定义任务实现。
// * <p>
// * 注意: 实例不应保留状态。
// *
// * @param <T>
// * @author Wei Chou(weichou2010@gmail.com)
// * @version 1.0, 12/04/2015
// */
//public abstract class Trait<T extends Task> {
//    /**
//     * 任务名称。
//     */
//    protected abstract String name();
//
//    /**
//     * 创建任务。
//     */
//    protected abstract T newTask();
//
//    /**
//     * 必须输入的参数keys及value类型(可由初始参数传入, 或者在本Task前面执行的Tasks输出{@link #outs()}而获得)。
//     */
//    protected abstract Set<Key$> requires();
//
//    /**
//     * 该任务输出的所有key-value类型。
//     */
//    protected abstract Set<Key$> outs();
//
//    /**
//     * 优先级。范围 [ {@link Reflow#P_HIGH P_HIGH} ~ {@link Reflow#P_LOW P_LOW} ]。
//     */
//    protected abstract int priority();
//
//    /**
//     * 任务大概时长。
//     */
//    protected abstract Reflow.Period period();
//
//    /**
//     * 任务描述, 将作为进度反馈的部分信息。
//     */
//    protected abstract String description();
//
//    protected Trait() {
//    }
//
//    String name$() {
//        if (name == null) {
//            synchronized (this) {
//                if (name == null) {
//                    name = requireNonEmpty(name());
//                }
//            }
//        }
//        return name;
//    }
//
//    Set<Key$> requires$() {
//        if (inKeys == null) {
//            synchronized (this) {
//                if (inKeys == null) {
//                    inKeys = requireKey$kDiff(requireNonNullElem(requires()));
//                }
//            }
//        }
//        return inKeys;
//    }
//
//    Set<Key$> outs$() {
//        if (outKeys == null) {
//            synchronized (this) {
//                if (outKeys == null) {
//                    outKeys = requireKey$kDiff(requireNonNullElem(outs()));
//                }
//            }
//        }
//        return outKeys;
//    }
//
//    int priority$() {
//        return Assist.between(Reflow.P_HIGH, priority(), Reflow.P_LOW);
//    }
//
//    Reflow.Period period$() {
//        return requireNonNull(period());
//    }
//
//    String desc$() {
//        if (desc == null) {
//            synchronized (this) {
//                if (desc == null) {
//                    desc = requireNonNull(description());
//                }
//            }
//        }
//        return desc;
//    }
//
//    private String name;
//    private Set<Key$> inKeys;
//    private Set<Key$> outKeys;
//    private String desc;
//
//    @Override
//    public final boolean equals(Object obj) {
//        return super.equals(obj);
//    }
//
//    @Override
//    public final int hashCode() {
//        return super.hashCode();
//    }
//
//    @Override
//    public String toString() {
//        return String.format("name:%s, requires:%s, out:%s, priority:%s, period:%s, description: %s",
//                name$(), requires$(), outs$(), priority$(), period$(), desc$());
//    }
//
//    static final class Parallel extends Trait<Task> {
//        // 提交调度器之后具有不变性
//        private final List<Trait<?>> traits = new LinkedList<>();
//
//        Parallel(Trait<?> trait) {
//            traits.add(trait);
//        }
//
//        Parallel(Collection<Trait<?>> traits) {
//            this.traits.addAll(traits);
//        }
//
//        List<Trait<?>> traits() {
//            return traits;
//        }
//
//        void add(Trait<?> trait) {
//            assertf(!(trait instanceof Parallel));
//            traits.add(trait);
//        }
//
//        Trait<?> first() {
//            return traits.get(0);
//        }
//
//        Trait<?> last() {
//            return traits.get(traits.size() - 1);
//        }
//
//        @Override
//        public String name() {
//            // 由于不允许同一个队列里面有相同的名字，所以取第一个的名字即可区分。
//            return Parallel.class.getName() + "#" + traits.get(0).name$();
//        }
//
//        @Override
//        public Task newTask() {
//            return null;
//        }
//
//        @Override
//        public Set<Key$> requires() {
//            return Helper.Keys.empty();
//        }
//
//        @Override
//        public Set<Key$> outs() {
//            return Helper.Keys.empty();
//        }
//
//        @Override
//        public int priority() {
//            return Reflow.P_NORMAL;
//        }
//
//        @Override
//        public Reflow.Period period() {
//            return null;
//        }
//
//        @Override
//        protected String description() {
//            return null;
//        }
//    }
//
//    static abstract class Empty extends Trait<Task> {
//        private static final AtomicInteger sCount = new AtomicInteger(0);
//
//        @Override
//        protected String name() {
//            return Empty.class.getName() + "#" + sCount.getAndIncrement();
//        }
//
//        @Override
//        protected Set<Key$> requires() {
//            return Helper.Keys.empty();
//        }
//
//        @Override
//        protected Set<Key$> outs() {
//            return Helper.Keys.empty();
//        }
//
//        @Override
//        protected int priority() {
//            return Reflow.P_NORMAL;
//        }
//
//        @Override
//        protected String description() {
//            return name$();
//        }
//    }
//
//    static final class Input extends Empty {
//        private static final AtomicInteger sCount = new AtomicInteger(0);
//        private final In in;
//        private final Set<Key$> requires;
//        private final int priority;
//
//        public Input(In in, Set<Key$> requires, int priority) {
//            this.in = in;
//            this.requires = requires;
//            this.priority = priority;
//        }
//
//        @Override
//        protected String name() {
//            return Input.class.getName() + "#" + sCount.getAndIncrement();
//        }
//
//        @Override
//        protected Task newTask() {
//            return new Task() {
//                @Override
//                protected void doWork() {
//                    final Out input = new Out(requires);
//                    in.fillValues(input);
//                    out.putWith(input.map, outs$(), false, true);
//                }
//            };
//        }
//
//        @Override
//        protected Set<Key$> outs() {
//            return in.keys;
//        }
//
//        @Override
//        protected int priority() {
//            return priority;
//        }
//
//        @Override
//        protected Reflow.Period period() {
//            return Reflow.Period.TRANSIENT;
//        }
//    }
//}
