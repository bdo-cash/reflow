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
//import java.util.Map;
//import java.util.Set;
//
///**
// * @author Wei Chou(weichou2010@gmail.com)
// * @version 1.0, 26/06/2016
// */
//public class Out {
//    // 仅读取
//    final Map<String, Key$> keys = new HashMap<>();
//    // 作了synchronized同步
//    final Map<String, Object> map = new HashMap<>();
//    final Set<Key$> nullValueKeys = new HashSet<>();
//
//    Out(Set<Key$> keys) {
//        for (Key$ k : keys) {
//            this.keys.put(k.key, k);
//        }
//    }
//
//    Out(Map<String, Key$> map) {
//        keys.putAll(map);
//    }
//
//    void fillWith(Out out) {
//        putWith(out.map, out.nullValueKeys, true, true);
//    }
//
//    void verify() {
//        putWith(Collections.emptyMap(), Collections.emptySet(), true, true);
//    }
//
//    /**
//     * 若调用本方法, 则必须一次填满, 否则报异常。
//     *
//     * @param map
//     * @param nullValueKeys  因为value为null导致无法插入到map的key的集合。
//     * @param ignoreDiffType 是否忽略不同值类型（{@link Key$}.
//     * @param fullVerify     检查{@link #keys}是否全部输出。
//     */
//    synchronized void putWith(Map<String, ?> map, Set<Key$> nullValueKeys, boolean ignoreDiffType, boolean fullVerify) {
//        for (Key$ k : keys.values()) {
//            if (map.containsKey(k.key)) {
//                if (k.putValue(this.map, map.get(k.key), ignoreDiffType)) {
//                    this.nullValueKeys.remove(k);
//                }
//            } else if (!this.map.containsKey(k.key)) {
//                if (nullValueKeys.contains(k)) {
//                    this.nullValueKeys.add(k);
//                } else if (fullVerify) {
//                    Assist.Throws.lackIOKey(k, false);
//                }
//            }
//        }
//    }
//
//    synchronized <T> boolean put(String key, T value) {
//        if (keys.containsKey(key)) {
//            final Key$ k = keys.get(key);
//            if (value == null && !map.containsKey(key)) {
//                nullValueKeys.add(k);
//            } else {
//                k.putValue(map, value);
//                nullValueKeys.remove(k);
//            }
//            return true;
//        }
//        return false;
//    }
//
//    void cache(Out out) {
//        for (Map.Entry<String, Object> entry : out.map.entrySet()) {
//            cache(entry.getKey(), entry.getValue());
//        }
//    }
//
//    /**
//     * 有reinforce需求的任务, 可以将中间结果缓存在这里。
//     * 注意: 如果在输入中({@link #keys Out(Set)}构造器参数)含有本key, 则无法将其缓存。
//     *
//     * @param key
//     * @param value
//     * @param <T>
//     * @return true 成功; false 失败, 说明key重复, 应该换用其它的key.
//     */
//    synchronized <T> void cache(String key, T value) {
//        if (keys.containsKey(key)) {
//            Assist.Throws.sameCacheKey(keys.get(key));
//        } else if (value != null) {
//            map.put(key, value);
//        }
//    }
//
//    /**
//     * 取得key对应的value.
//     *
//     * @param key
//     * @param <T>
//     * @return
//     */
//    public <T> T get(String key) {
//        return get((Key$<T>) keys.get(key));
//    }
//
//    /**
//     * 取得key对应的value.
//     *
//     * @param key
//     * @param <T>
//     * @return
//     */
//    public synchronized <T> T get(Key$<T> key) {
//        return key.takeValue(map);
//    }
//
//    /**
//     * 取得预定义的keys及类型。即: 希望输出的keys.
//     *
//     * @return
//     */
//    public synchronized Set<Key$> keysDef() {
//        return new HashSet<>(keys.values());
//    }
//
//    /**
//     * 取得实际输出的keys.
//     *
//     * @return
//     */
//    public synchronized Set<Key$> keys() {
//        final Set<Key$> keys = new HashSet<>();
//        for (Key$ k : this.keys.values()) {
//            if (map.containsKey(k.key)) {
//                keys.add(k);
//            } else if (nullValueKeys.contains(k)) {
//                keys.add(k);
//            }
//        }
//        return keys;
//    }
//
//    @Override
//    public String toString() {
//        return "keys:" + keys + ", values:" + map + (nullValueKeys.isEmpty() ? "" : ", null:" + nullValueKeys);
//    }
//}
