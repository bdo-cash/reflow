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

import java.lang.reflect.Type;
import java.util.Map;

import hobby.wei.c.tool.Reflect;

import static hobby.wei.c.reflow.Assist.requireNonEmpty;
import static java.util.Objects.requireNonNull;

/**
 * 定义key及value类型。value类型由泛型指定。
 * 注意: 本类的子类必须在运行时创建, 即匿名子类, 否则泛型信息可能在Proguard时被删除, 从而导致解析失败。
 *
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 21/07/2016
 */
public abstract class Key$<T> {
    public final String key;
    /**
     * 泛型参数的类型, 类似于这种结构: java.util.List<java.util.List<int[]>>.
     */
    public final Type tpe;
    /**
     * 第一级泛型参数的Class表示。
     */
    private final Class<? super T> rawType;
    /**
     * 第一级泛型参数的子泛型参数, 可能不存在。作用或结构与tpe类似。
     */
    private final Type[] subTypes;

    protected Key$(String key) {
        this.key = requireNonEmpty(key);
        this.tpe = Reflect.getSuperclassTypeParameter(getClass(), true)[0];
        //noinspection unchecked
        this.rawType = (Class<? super T>) Reflect.getRawType(tpe);
        this.subTypes = Reflect.getSubTypes(tpe);
    }

    Key$(String key, Type tpe) {
        this.key = requireNonEmpty(key);
        this.tpe = requireNonNull(tpe);
        //noinspection unchecked
        this.rawType = (Class<? super T>) Reflect.getRawType(tpe);
        this.subTypes = Reflect.getSubTypes(tpe);
    }

    /**
     * 走这个方法作类型转换, 确保value类型与定义的一致性。
     *
     * @param value
     * @return 返回Task在执行时当前key对应值的目标类型。
     */
    public T asType(Object value) {
        //noinspection unchecked
        return (T) value;
    }

    public boolean putValue(Map<String, Object> map, Object value) {
        return putValue(map, value, false);
    }

    /**
     * 将输出值按指定类型(作类型检查)插入Map.
     *
     * @param map            输出到的Map.
     * @param value          要输出的值。
     * @param ignoreDiffType 如果value参数类型不匹配，是否忽略。
     * @return true成功，else失败。
     */
    public boolean putValue(Map<String, Object> map, Object value, boolean ignoreDiffType) {
        value = requireSameType(value, ignoreDiffType);
        if (value != null) {
            map.put(key, value);
            return true;
        }
        return false;
    }

    /**
     * 走这个方法取值, 确保value类型与定义的一致性。
     *
     * @param map Task的输入参数。
     * @return 返回Task在执行时当前key对应值的目标类型。
     */
    public T takeValue(Map<String, ?> map) {
        // 强制类型转换比较宽松, 只会检查对象类型, 而不会检查泛型。
        // 但是由于value值对象无法获得泛型类型, 因此这里不再作泛型检查。也避免了性能问题。
        return (T) map.get(key);
    }

    private Object requireSameType(Object value, boolean ignoreDiffType) {
        if (value != null) {
            final Class clazz = value.getClass();
            if (!rawType.isAssignableFrom(clazz)) {
                if (ignoreDiffType) return null;
                else Assist.Throws.tpeNotMatch(this, clazz);
            }
            // 数值对象通常已经失去了泛型参数, 因此不作检查
        }
        return value;
    }

    /**
     * 参数的value类型是否与本value类型相同或者子类型。
     * 同{@link Class#isAssignableFrom(Class)}
     *
     * @param key
     * @return
     */
    public boolean isAssignableFrom(Key$ key) {
        if (!rawType.isAssignableFrom(key.rawType)) {
            return false;
        }
        if (subTypes.length != key.subTypes.length) {
            return false;
        }
        for (int i = 0; i < subTypes.length; i++) {
            if (!subTypes[i].equals(key.subTypes[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public final boolean equals(Object o) {
        return o instanceof Key$
                && ((Key$) o).key.equals(key)
                && ((Key$) o).tpe.equals(tpe);
    }

    @Override
    public final int hashCode() {
        return key.hashCode() * 41 + tpe.hashCode();
    }

    @Override
    public String toString() {
        return String.format("[%s -> %s]", key, tpe);
    }
}
