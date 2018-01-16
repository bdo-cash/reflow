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

package hobby.wei.c.reflow.step;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 05/10/2016
 */
public class Tracker<T> {
    private final AtomicInteger count = new AtomicInteger(0);
    private final AtomicInteger progress = new AtomicInteger(0);
    private final AtomicBoolean top = new AtomicBoolean(false);
    private Stack<Set<T>> stackRequires = new Stack<>();
    private Stack<Set<T>> stackOuts = new Stack<>();

    final void increaseCount() {
        count.incrementAndGet();
    }

    /**
     * {@link Unit#exec$(Object)}被调用时, {@link #topReached()}必定为<code>true</code>.
     *
     * @return
     */
    public final int count() {
        return count.get();
    }

    final void onTopReached() {
        top.set(true);
    }

    final boolean topReached() {
        return top.get();
    }

    final void increaseProgress() {
        progress.incrementAndGet();
    }

    public final int progress() {
        return progress.get();
    }

    public final int keyStep() {
        return topReached() ? count() - progress() : count();
    }

    final void pushRequireKeys(Set<T> set) {
        stackRequires.push(set);
    }

    final void pushOutKeys(Set<T> set) {
        stackOuts.push(set);
    }

    final void popRequireKeys() {
        stackRequires.pop();
    }

    final void popOutKeys() {
        stackOuts.pop();
    }

    final Set<T> requireKeys() {
        final Set<T> set = new HashSet<>();
        for (int i = 0, len = stackRequires.size(); i < len; i++) {
            set.removeAll(stackOuts.get(i));
            set.addAll(stackRequires.get(i));
        }
        return set;
    }
}
