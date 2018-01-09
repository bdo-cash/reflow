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
//import java.util.HashSet;
//import java.util.Set;
//
//import static java.util.Objects.requireNonNull;
//
///**
// * @author Wei Chou(weichou2010@gmail.com)
// * @version 1.0, 14/08/2016
// */
//public abstract class Helper {
//    public static class Keys {
//        public static Set<Key$> empty() {
//            return Collections.emptySet();
//        }
//
//        public static Builder add(Key$ key) {
//            return new Builder().add(key);
//        }
//
//        public static class Builder {
//            private Set<Key$> keys;
//
//            private Builder() {
//            }
//
//            public Builder add(Key$ key) {
//                requireNonNull(key);
//                if (keys == null) keys = new HashSet<>();
//                keys.add(key);
//                return this;
//            }
//
//            public Set<Key$> ok() {
//                return new HashSet<>(keys);
//            }
//        }
//    }
//
//    public static class Transformers {
//        /**
//         * 将任务的某个输出在转换之后仍然保留。通过增加一个输出即输入转换。
//         */
//        public static <O> Transformer<O, O> retain(Key$<O> key) {
//            return new Transformer<O, O>(key, key) {
//                @Override
//                protected O transform(O o) {
//                    return o;
//                }
//            };
//        }
//
//        public static Builder add(Transformer trans) {
//            return new Builder().add(trans);
//        }
//
//        public static class Builder {
//            private Set<Transformer> trans;
//
//            private Builder() {
//            }
//
//            public Builder add(Transformer trans) {
//                requireNonNull(trans);
//                if (this.trans == null) this.trans = new HashSet<>();
//                this.trans.add(trans);
//                return this;
//            }
//
//            public <O> Builder retain(Key$<O> key) {
//                return add(Transformers.retain(key));
//            }
//
//            public Set<Transformer> ok() {
//                return new HashSet<>(trans);
//            }
//        }
//    }
//}
