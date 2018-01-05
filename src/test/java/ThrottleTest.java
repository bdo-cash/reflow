import java.util.HashMap;
import java.util.Map;

import hobby.wei.c.tools.throttle.*;

public class ThrottleTest {
    static Counter globalCounter = new Counter(3);
    static SorterR<IntD> sorterR = new SorterR<>();
    static SorterO<Object> sorterO = new SorterO<>();
    static ThrottleO<Object, String> throttleO = new ThrottleO<>(globalCounter, new ThrottleO.Executor<Object, String>() {
        Map<Object, Thread> map = new HashMap<>();

        @Override
        protected void execAsync(Object obj, String tag, Runnable onDone) {
            System.out.println("o----------------------------->[start]" + obj + ", tag:" + tag + ", globalCounter:" + globalCounter.count());
            Thread t = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        System.out.println("o---------------------------------------------------------------->[cancel]" + obj + ", tag:" + tag);
                        return;
                    } finally {
                        onDone.run();
                        System.out.println("o>>>>[done]" + obj + ", tag:" + tag + ", globalCounter:" + globalCounter.count());
                    }
                    System.out.println("o+++++++++++++++++++++++++++++>[success]" + obj + ", tag:" + tag + ", globalCounter:" + globalCounter.count());
                }
            };
            map.put(obj, t);
            t.start();
        }

        @Override
        protected void cancel(Object obj, String tag) {
            System.out.println("o[cancel]" + obj);
//            if (obj instanceof Integer) return;
            final Thread t = map.get(obj);
            if (t != null) t.interrupt();
        }
    });
    static ThrottleR<IntD, String> throttleR = new ThrottleR<>(globalCounter, 100, false, new ThrottleR.Executor<IntD, String>() {
        Map<String, Thread> map = new HashMap<>();

        @Override
        protected void execAsync(Range<IntD> range, String tag, Runnable onDone) {
            System.out.println("----------------------------->[start]" + range + ", tag:" + tag + ", globalCounter:" + globalCounter.count());
            Thread t = new Thread() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        System.out.println("---------------------------------------------------------------->[cancel]" + range + ", tag:" + tag);
                        return;
                    } finally {
                        onDone.run();
                        System.out.println(">>>>[done]" + range + ", tag:" + tag + ", globalCounter:" + globalCounter.count());
                    }
                    System.out.println("+++++++++++++++++++++++++++++>[success]" + range + ", tag:" + tag + ", globalCounter:" + globalCounter.count());
                }
            };
            map.put(range.unique(), t);
            t.start();
        }

        @Override
        protected void cancel(Range<IntD> range, String tag) {
            System.out.println("[cancel]" + range);
            final Thread t = map.get(range.unique());
            if (t != null) t.interrupt();
        }
    });

    static class IntD implements Discrete<IntD> {
        private final int n;

        IntD(int n) {
            this.n = n;
        }

        @Override
        public int delta(IntD intD) {
            return n - intD.n;
        }

        @Override
        public IntD offset(int delta) {
            return new IntD(n + delta);
        }

        @Override
        public String unique() {
            return String.valueOf(n);
        }

        @Override
        public String toString() {
            return unique();
        }
    }


    public static void main(String[] args) {
        /*LinkedHashMap<String, String> map = new LinkedHashMap<>(0, 0.75f, true);
        map.put("a", "a");
        map.put("b", "b");
        map.put("c", "c");
        map.put("d", "d");
        map.put("e", "e");
        map.put("f", "f");
        map.put("a", "a");
        map.get("b");
        System.out.println(map.values());

        if (true) return;*/


        /*sorterR.put(new IntD(5));
        sorterR.put(new IntD(5), new IntD(20));
        sorterR.put(new IntD(25), new IntD(200));
        sorterR.put(new IntD(500), new IntD(20900));
        sorterR.put(new IntD(20000), new IntD(123400));

        System.out.println(sorterR.get(Integer.MAX_VALUE, false));
        System.out.println(sorterR.get(new IntD(500), Integer.MAX_VALUE, true));
        System.out.println(sorterR.get(Integer.MAX_VALUE, true));
        System.out.println(sorterR.take(new IntD(55), 125, false));
        System.out.println(sorterR.get(Integer.MAX_VALUE, true));
        System.out.println(sorterR.getSerial(new IntD(150), Integer.MAX_VALUE, true));

        if (true) return;*/

        throttleR.put(new IntD(9), new IntD(220), "", false);
        throttleR.put(new IntD(100), "b", false);
        throttleR.put(new IntD(28), "c", false);
        throttleR.put(new IntD(6), null, true);
        throttleR.put(new IntD(11), null, false);
        throttleR.put(new IntD(35), null, false);
        throttleR.put(new IntD(99), "a", false);
        throttleR.put(new IntD(9), "a", true);
        throttleR.put(new IntD(3), null, false);
        throttleR.put(new IntD(9), null, false);
        throttleR.put(new IntD(7), null, true);
        throttleR.put(new IntD(8), "a", true);
        throttleR.put(new IntD(12), null, false);
        throttleR.put(new IntD(10), null, false);
        throttleR.put(new IntD(1000), null, false);
//
//        try {
//            Thread.sleep(2000);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//        throttleR.destroy(true);

//        if (true) return;


        Object o = new Object();
        /*sorterO.put(1);
        sorterO.put(o);
        sorterO.put(300);
        sorterO.put(2);
        sorterO.put("hello, guys!~");
        sorterO.put(5);
        sorterO.put(2);
        sorterO.put(2);
        sorterO.put(2);
        sorterO.put(2);
        sorterO.put("hello, guys!~");
        sorterO.put(new Object());
        sorterO.put(o);
        sorterO.put("hello, guys!");
        System.out.println(sorterO.get(10000, false));
        System.out.println(sorterO.take(5, 3, false));
        System.out.println(sorterO.take(true));
        System.out.println(sorterO.get(10000, true));
        System.out.println(sorterO.take(100, true));

        if (true) return;*/

        throttleO.put(1, null, false);
        throttleO.put(o, null, false);
        throttleO.put(300, null, false);
        throttleO.put(2, null, false);
        throttleO.put("hello, guys!~", null, true);
//        throttleO.put(5);
//        throttleO.put(2);
//        throttleO.put("hello, guys!~");
        System.out.println(sorterO.take(100, false));
//        throttleO.put(new Object());
//        throttleO.put(o);
//        throttleO.put("hello, guys!");

        throttleO.drop(300, null, true);
        throttleO.cancel(o, null, false);
        throttleO.cancel("hello, guys!~", null, false);

        if (true) return;

//        System.out.println(globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        System.out.println(globalCounter.hold(throttleO) + "," + globalCounter.count());
//        globalCounter.drop(throttleO);
//        System.out.println(globalCounter.count());
//        globalCounter.drop(throttleO);
//        System.out.println(globalCounter.count());
//        globalCounter.drop(throttleO);
//        System.out.println(globalCounter.count());
//        globalCounter.drop(throttleO);
//        System.out.println(globalCounter.count());
//        globalCounter.drop(throttleO);
//        System.out.println(globalCounter.count());
    }
}
