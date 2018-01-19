import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import hobby.wei.c.reflow.step.Step;
import hobby.wei.c.reflow.step.Tracker;
import hobby.wei.c.reflow.step.Unit;
import hobby.wei.c.reflow.step.UnitM;

/**
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 06/10/2016
 */
public class StepTest {
    public static void main(String[] args) throws Exception {
        System.out.println("----->");
        final Step<?, ?, ?, ?> step = new UnitM() {
            @Override
            protected Map<String, Object> exec(Map<String, Object> map) throws Exception {
                final Map<String, Object> output = new HashMap<>();
                output.put("a", 0);
                System.out.println("[exec]<step>:" + tracker().progress() + "/" + tracker().count() + ", " + output);
                return output;
            }

            @Override
            protected Map<String, Object> def() throws Exception {
                System.out.println("[def]<step>:" + tracker().progress() + "/" + tracker().count());
                return Collections.emptyMap();
            }
        }.then(new UnitM() {
            @Override
            protected Map<String, Object> exec(Map<String, Object> map) throws Exception {
                final Map<String, Object> output = new HashMap<>();
                output.put("b", "1");
                output.put("c", 289);
                System.out.println("[exec]<step>:" + tracker().progress() + "/" + tracker().count() + ", " + output.toString());
                return output;
            }

            @Override
            protected Map<String, Object> def() throws Exception {
                System.out.println("[def]<step>:" + tracker().progress() + "/" + tracker().count() + ", " + getClass().getName());
                return Collections.emptyMap();
            }
        }).then(new Unit<Map<String, Object>, String, Tracker<String>, String>() {
            @Override
            protected String exec(Map<String, Object> map) throws Exception {
                System.out.println("[exec]<step>:" + tracker().progress() + "/" + tracker().count() + ", " + map);
                return map.toString();
            }

            @Override
            protected String def() throws Exception {
                return null;
            }

            @Override
            protected Set<String> requireKeys() {
                final Set<String> set = new HashSet<>();
                set.add("a");
//                set.add("b");
                set.add("c");
                return set;
            }

            @Override
            protected Tracker<String> newTracker() {
                return new Tracker<>();
            }
        }).then(new Unit<String, String, Tracker<String>, String>() {
            @Override
            protected String exec(String s) throws Exception {
                System.out.println("[exec]<step>:" + tracker().progress() + "/" + tracker().count() + ", " + getClass().getName());
                return s;
            }

            @Override
            protected String def() throws Exception {
                return null;
            }

            @Override
            protected Tracker<String> newTracker() {
                return new Tracker<>();
            }
        });
        System.out.println("---->result:" + step.exec(new Step.Callback() {
            @Override
            public void onProgress(int progress, int count) {
                System.out.println("----->[onProgress]:" + progress + "/" + count);
            }
        }));


        if (true) return;

        Map map = new HashMap();
        map.put("a", "abce");
        System.out.println(map.get("a"));
        map.put("b", new Object());
        System.out.println(map.get("b"));
        map.put("a", new Object());
        System.out.println(map.get("a"));

        Object[] arr = new Object[10];
        arr[0] = 1;
        System.out.println(arr[0]);
        arr[0] = new Object();
        System.out.println(arr[0]);
    }
}
