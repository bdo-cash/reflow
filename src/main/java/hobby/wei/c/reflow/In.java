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

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import hobby.wei.c.tools.Locker;

import static hobby.wei.c.reflow.Assist.*;
import static hobby.wei.c.reflow.Assist.getRef;
import static hobby.wei.c.reflow.Assist.requireKey$kDiff;
import static hobby.wei.c.reflow.Assist.requireNonNullElem;
import static hobby.wei.c.reflow.Assist.requireTransInTypeSame;

/**
 * @author Wei.Chou (weichou2010@gmail.com)
 * @version 1.0, 14/08/2016
 */
public abstract class In {
    private static WeakReference<In> sEmptyRef;

    final Set<Key$> keys;
    Set<Transformer> trans;

    protected In(Set<Key$> keys) {
        this.keys = requireKey$kDiff(requireNonNullElem(keys));
    }

    public In transition(Transformer trans) {
        return transition(Collections.singleton(trans));
    }

    public In transition(Set<Transformer> tranSet) {
        this.trans = requireTransInTypeSame(requireNonNullElem(tranSet));
        return this;
    }

    void fillValues(Out out) {
        out.keysDef().stream().filter(keys::contains).forEach(
                key -> out.put(key.key, loadValue(key.key))
        );
    }

    protected abstract Object loadValue(String key);

    ////////////////////////////////////////////////////////////////////////////
    //***************************** static tools *****************************//

    public static In map(String key, Object value) {
        return map(Collections.singletonMap(key, value));
    }

    public static In map(Map<String, Object> map) {
        return new M(generate(map), map);
    }

    public static Builder add(String key, Object value) {
        return new Builder().add(key, value);
    }

    public static class Builder {
        private Map<String, Object> map;
        private Helper.Transformers.Builder tb;

        private Builder() {
        }

        public Builder add(String key, Object value) {
            if (map == null) map = new HashMap<>();
            map.put(key, value);
            return this;
        }

        public Builder add(Transformer trans) {
            if (tb == null) tb = Helper.Transformers.add(trans);
            else tb.add(trans);
            return this;
        }

        public In ok() {
            return map(map).transition(tb == null ? null : tb.ok());
        }
    }

    private static Set<Key$> generate(Map<String, Object> map) {
        final Set<Key$> set = new HashSet<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            set.add(new Key$(entry.getKey(), entry.getValue().getClass()) {
            });
        }
        return set;
    }

    private static class M extends In {
        private final Map<String, Object> map;

        private M(Set<Key$> keys, Map<String, Object> map) {
            super(keys);
            this.map = map;
        }

        @Override
        protected final Object loadValue(String key) {
            return map.get(key);
        }
    }

    public static In empty() {
        return Locker.lazyGetr(
                () -> getRef(sEmptyRef),
                () -> {
                    final In in = new In(Helper.Keys.empty()) {
                        @Override
                        void fillValues(Out out) {
                        }

                        @Override
                        protected Object loadValue(String key) {
                            return null;
                        }
                    };
                    sEmptyRef = new WeakReference<>(in);
                    return in;
                }, Locker.getLockr(In.class));
    }
}
