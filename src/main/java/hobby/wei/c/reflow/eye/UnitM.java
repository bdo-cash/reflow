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

package hobby.wei.c.reflow.eye;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 05/10/2016
 */
public abstract class UnitM extends Unit<Map<String, Object>, Map<String, Object>, Tracker<String>, String> {
    @Override
    public Map<String, Object> clone(Map<String, Object> map) {
        return new HashMap<>(map);
    }

    @Override
    protected Set<String> outKeys(Map<String, Object> map) {
        return map.keySet();
    }

    @Override
    protected Map<String, Object> join(Map<String, Object> prev) {
        return prev;
    }

    @Override
    protected Map<String, Object> join(Map<String, Object> prev, Map<String, Object> map) {
        if (prev == null || prev.isEmpty()) {
            return map;
        } else {
            prev.putAll(map);
            return prev;
        }
    }

    @Override
    protected Map<String, Object> trim(Map<String, Object> map, Set<String> requireKeys) {
        for (String k : new HashSet<>(map.keySet())) {
            if (!requireKeys.contains(k)) {
                map.remove(k);
            }
        }
        return map;
    }

    @Override
    protected Tracker<String> newTracker() {
        return new Tracker<>();
    }
}
