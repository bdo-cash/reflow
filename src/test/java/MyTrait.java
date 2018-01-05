import java.util.Set;

import hobby.wei.c.reflow.Helper;
import hobby.wei.c.reflow.Key$;
import hobby.wei.c.reflow.TaskFlow;
import hobby.wei.c.reflow.Trait;

/**
 * @author Wei.Chou (weichou2010@gmail.com)
 * @version 1.0, 15/05/2016
 */
public class MyTrait extends Trait<Client.MyTask> {

    @Override
    public String name() {
        return MyTrait.class.getSimpleName();
    }

    @Override
    public Client.MyTask newTask() {
        return new Client.MyTask();
    }

    @Override
    public Set<Key$> requires() {
        return Helper.Keys.add(new Key$<Integer>("num") {
        }).add(new Key$<String>("str") {
        }).ok();
    }

    @Override
    public Set<Key$> outs() {
        return Helper.Keys.empty();
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public TaskFlow.Period period() {
        return TaskFlow.Period.TRANSIENT;
    }

    @Override
    protected String description() {
        return "";
    }
}
