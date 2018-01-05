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

import java.lang.reflect.Type;
import java.util.Map;

import hobby.wei.c.tools.Reflect;

/**
 * 任务输出转换器。包括key和value的转换，
 * 可定义仅转换value、或仅转换key、或key-value全都转换。
 *
 * @author Wei.Chou (weichou2010@gmail.com)
 * @version 1.0, 31/07/2016
 */
public abstract class Transformer<IN, OUT> {
    public final Key$<IN> in;
    public final Key$<OUT> out;

    /**
     * 对于只转换某Key的值类型的, 应使用本构造方法。
     *
     * @param key
     */
    protected Transformer(String key) {
        final Type[] type = Reflect.getSuperclassTypeParameter(getClass(), true);
        this.in = new Key$<IN>(key, type[0]) {
        };
        this.out = new Key$<OUT>(key, type[1]) {
        };
    }

    /**
     * 对于要将某key-value转换为其它key'-value'的, 应使用本构造方法。
     *
     * @param in
     * @param out
     */
    protected Transformer(Key$<IN> in, Key$<OUT> out) {
        this.in = in;
        this.out = out;
    }

    public final OUT transform(Map<String, ?> input) {
        final IN in = this.in.takeValue(input);
        return in == null ? null : transform(in);
    }

    protected abstract OUT transform(IN in);

    @Override
    public final boolean equals(Object o) {
        return o instanceof Transformer
                && ((Transformer) o).in.equals(in)
                && ((Transformer) o).out.equals(out);
    }

    @Override
    public final int hashCode() {
        return in.hashCode() * 41 + out.hashCode();
    }

    @Override
    public String toString() {
        return String.format("%s -> %s", in, out);
    }
}
