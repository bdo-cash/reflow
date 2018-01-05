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

package hobby.wei.c.reflow.eye;

import java.util.Collections;
import java.util.Set;

/**
 * @author Wei.Chou (weichou2010@gmail.com)
 * @version 1.0, 03/10/2016
 */
public abstract class Unit<INPUT, OUTPUT, TRACKER extends Tracker<T>, T> {
    private TRACKER tracker;

    final OUTPUT exec$(INPUT input) throws Exception {
        return input == null ? def() : exec(input);
    }

    protected abstract OUTPUT exec(INPUT input) throws Exception;

    protected abstract OUTPUT def() throws Exception;

    public OUTPUT clone(OUTPUT output) {
        return output;
    }

    final void setTracker(TRACKER tracker) {
        this.tracker = tracker;
    }

    protected final TRACKER tracker() {
        return tracker;
    }

    protected abstract TRACKER newTracker();

    protected Set<T> requireKeys() {
        return Collections.emptySet();
    }

    protected Set<T> outKeys() {
        return Collections.emptySet();
    }

    protected Set<T> outKeys(OUTPUT output) {
        return Collections.emptySet();
    }

    protected OUTPUT join(INPUT prev) {
        return null;
    }

    protected OUTPUT join(INPUT prev, OUTPUT output) {
        return output;
    }

    protected OUTPUT trim(OUTPUT output, Set<T> requireKeys) {
        return output;
    }

    public final <NEXT> Step<OUTPUT, NEXT, TRACKER, T> then(Unit<OUTPUT, NEXT, TRACKER, T> unit) {
        return Step.begin(this).then(unit);
    }
}
