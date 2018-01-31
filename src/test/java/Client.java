import java.util.Map;
import java.util.Set;

import hobby.wei.c.reflow.*;
import mock.hobby.wei.c.reflow.Out;
import mock.hobby.wei.c.reflow.Trait;

/**
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 18/07/2016
 */
public class Client {
    static {
        TaskFlow.setDebugMode(true);
    }

    public static void main(String[] args) {
        final Out out = TaskFlow.create(new MyTrait())
                .with(new Trait<Task>() {
                    @Override
                    protected String name() {
                        return "a";
                    }

                    @Override
                    protected Task newTask() {
                        return new Task() {
                            @Override
                            protected void doWork() {
                                System.out.println(toString());
                            }
                        };
                    }

                    @Override
                    protected Set<Key$> requires() {
                        return Helper.Keys.empty();
                    }

                    @Override
                    protected Set<Key$> outs() {
                        return Helper.Keys.empty();
                    }

                    @Override
                    protected int priority() {
                        return 0;
                    }

                    @Override
                    protected TaskFlow.Period period() {
                        return TaskFlow.Period.TRANSIENT;
                    }

                    @Override
                    protected String description() {
                        return "";
                    }
                })
                .with(new Trait<Task>() {
                    @Override
                    protected String name() {
                        return "b";
                    }

                    @Override
                    protected Task newTask() {
                        return new Task() {
                            @Override
                            protected void doWork() {
                                output("num", input("num"));
                            }
                        };
                    }

                    @Override
                    protected Set<Key$> requires() {
                        return Helper.Keys.add(new Key$<Integer>("num") {}).ok();
                    }

                    @Override
                    protected Set<Key$> outs() {
                        return Helper.Keys.add(new Key$<Integer>("num") {}).ok();
                    }

                    @Override
                    protected int priority() {
                        return 0;
                    }

                    @Override
                    protected TaskFlow.Period period() {
                        return TaskFlow.Period.TRANSIENT;
                    }

                    @Override
                    protected String description() { return "任务描述xxx"; }
                })
                .transition(new Transformer<Integer, String>("num") {
                    @Override
                    protected String transform(Integer i) {
                        return String.valueOf(i);
                    }
                })
                .submit(Helper.Keys // 最终需要的输出集合。定义了key-value, 以及value类型。
                        .add(new Key$<String>("num") {})
                        // 泛型表示value类型，无论结构多么复杂，都会在运行之前被正确地进行依赖检查。
                        .add(new Key$<Map<String, Map<Object, Set<int[]>>>>("test") {})
                        .ok()
                )
                .start(In                       // 输入参数集合
                        .add("str", "b")
                        .add("num", 10)
                        .add("kfc", .5)
                        .ok(),
                        new Feedback.Adapter(), // 事件反馈接口
                        null            // 将反馈事件发送到指定线程，如Android的UI线程。
                )
                .sync();                        // 阻塞同步调用，删除本行便是异步。同/异步就是这么简单。
        System.out.println("out:" + out);
        System.out.println("out.keys:" + out.keys());
        System.out.println("out.keysDef:" + out.keysDef());
        System.out.println("obj:" + out.get("str"));
    }

    public static class MyTask extends Task {

        @Override
        protected void doWork() {
        }

        @Override
        protected void onAbort() {
        }
    }
}
