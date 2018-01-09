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
//import java.util.concurrent.CopyOnWriteArraySet;
//
//import static java.util.Objects.requireNonNull;
//
///**
// * @author Wei Chou(weichou2010@gmail.com)
// * @version 1.0, 02/07/2016
// */
//public interface Feedback {
//    void onStart();
//
//    /**
//     * @param name        正在执行(中途会更新进度)或完成的任务名称。来源于{@link Trait#name()}.
//     * @param out         进度的时刻已经获得的输出。
//     * @param count       任务计数。
//     * @param sum         任务流总数。用于计算主进度(%): count * 1f / sum, 和总进度(%): (count + sub) / sum.
//     * @param sub         任务内部进度值。
//     * @param description 任务描述{@link Trait#description()}.
//     */
//    void onProgress(String name, Out out, int count, int sum, float sub, String description);
//
//    void onComplete(Out out);
//
//    /**
//     * 强化运行完毕之后的最终结果。
//     *
//     * @see Task#requireReinforce()
//     */
//    void onUpdate(Out out);
//
//    void onAbort();
//
//    /**
//     * 任务失败。
//     *
//     * @param name 见{@link Trait#name()}.
//     * @param e    分为两类:
//     *             第一类是客户代码自定义的Exception, 即显式传给{@link Task#failed(Exception)}方法的参数, 可能为null;
//     *             第二类是由客户代码质量问题导致的RuntimeException, 如{@link NullPointerException}等,
//     *             这些异常被包装在{@link CodeException}里, 可以通过{@link CodeException#getCause()
//     *             getCause()}方法取出具体异常对象。
//     */
//    void onFailed(String name, Exception e);
//
//    class Adapter implements Feedback {
//        @Override
//        public void onStart() {
//        }
//
//        @Override
//        public void onProgress(String name, Out out, int count, int sum, float sub, String description) {
//        }
//
//        @Override
//        public void onComplete(Out out) {
//        }
//
//        @Override
//        public void onUpdate(Out out) {
//        }
//
//        @Override
//        public void onAbort() {
//        }
//
//        @Override
//        public void onFailed(String name, Exception e) {
//        }
//    }
//
//    class Observable extends Adapter {
//        private final CopyOnWriteArraySet<Feedback> obs = new CopyOnWriteArraySet<>();
//
//        public void addObserver(Feedback fb) {
//            obs.add(requireNonNull(fb));
//        }
//
//        public void removeObserver(Feedback fb) {
//            obs.remove(requireNonNull(fb));
//        }
//
//        @Override
//        public final void onStart() {
//            obs.forEach(Feedback::onStart);
//        }
//
//        @Override
//        public final void onProgress(String name, Out out, int count, int sum, float sub, String description) {
//            // foreach编译成obs.iterator(), 而CopyOnWriteArraySet的iterator是immutable的。
//            for (Feedback fb : obs) {
//                fb.onProgress(name, out, count, sum, sub, description);
//            }
//        }
//
//        @Override
//        public final void onComplete(Out out) {
//            for (Feedback fb : obs) {
//                fb.onComplete(out);
//            }
//        }
//
//        @Override
//        public final void onUpdate(Out out) {
//            for (Feedback fb : obs) {
//                fb.onUpdate(out);
//            }
//        }
//
//        @Override
//        public final void onAbort() {
//            obs.forEach(Feedback::onAbort);
//        }
//
//        @Override
//        public final void onFailed(String name, Exception e) {
//            for (Feedback fb : obs) {
//                fb.onFailed(name, e);
//            }
//        }
//    }
//}
