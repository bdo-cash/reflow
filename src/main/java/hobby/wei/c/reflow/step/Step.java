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

import java.util.Objects;
import java.util.Set;

/**
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 03/10/2016
 */
public class Step<PREV, OUTPUT, TRACKER extends Tracker<T>, T> {
    public final Step<?, PREV, TRACKER, T> prev;
    public final Unit<PREV, OUTPUT, TRACKER, T> unit;

    private Step(Step<?, PREV, TRACKER, T> prev, Unit<PREV, OUTPUT, TRACKER, T> unit) {
        this.prev = prev;
        this.unit = unit;
    }

    public final OUTPUT exec(Callback callback) throws Exception {
        return exec(this, unit.newTracker(), callback);
    }

    public final <NEXT> Step<OUTPUT, NEXT, TRACKER, T> then(Unit<OUTPUT, NEXT, TRACKER, T> unit) {
        return next(this, unit);
    }

    public static <IN, OUT, TR extends Tracker<X>, X> Step<IN, OUT, TR, X> begin(Unit<IN, OUT, TR, X> unit) {
        return next(null, unit);
    }

    private static <I, O, T extends Tracker<X>, X> Step<I, O, T, X> next(Step<?, I, T, X> prev, Unit<I, O, T, X> unit) {
        return new Step<>(prev, Objects.requireNonNull(unit));
    }

    private static <I, O, T extends Tracker<X>, X> O exec(Step<I, O, T, X> step, T tracker, Callback callback) throws Exception {
        final O output;
        if (step == null) {
            tracker.onTopReached();
            output = null;
        } else {
            tracker.increaseCount();
            tracker.pushRequireKeys(step.unit.requireKeys());
            tracker.pushOutKeys(step.unit.outKeys());
            final I prev = exec(step.prev, tracker, callback);
            tracker.popOutKeys();
            tracker.popRequireKeys();
            if (callback != null) {
                callback.onProgress(tracker.progress(), tracker.count());
            }
            step.unit.setTracker(tracker);
            final O curr = step.unit.exec$(prev == null ? null : step.prev/*maybe null*/.unit.clone(prev));
            tracker.increaseProgress();
            if (callback != null && tracker.progress() == tracker.count()) {
                callback.onProgress(tracker.progress(), tracker.count());
            }
            final Set<X> requireKeys = tracker.requireKeys();
            output = prev == null
                    ? curr == null ? null : step.unit.trim(curr, requireKeys)
                    : curr == null ? step.unit.trim(step.unit.join(prev), requireKeys)
                    : step.unit.trim(step.unit.join(prev, curr), requireKeys);
            assert step.unit.outKeys(output).containsAll(requireKeys);
        }
        return output;
    }

    public interface Callback {
        void onProgress(int progress, int count);
    }
}
