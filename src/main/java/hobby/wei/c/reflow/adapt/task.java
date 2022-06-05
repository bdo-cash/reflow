/*
 * Copyright (C) 2022-present, Chenai Nakam(chenai.nakam@gmail.com)
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

package hobby.wei.c.reflow.adapt;

import hobby.wei.c.reflow.Task.Context;
import hobby.wei.c.reflow.implicits;
import hobby.wei.c.reflow.lite.Input;
import hobby.wei.c.reflow.lite.Lite;
import hobby.wei.c.reflow.lite.Serial;
import hobby.wei.c.reflow.lite.Task;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;
import scala.runtime.AbstractFunction1;
import scala.runtime.AbstractFunction2;
import scala.runtime.AbstractFunction3;

/**
 * @author Chenai Nakam(chenai.nakam@gmail.com)
 * @version 1.0, 05/06/2022
 */
public class task {

    // TODO:
    //  Java 调用示例：
    Serial<String, Integer> e_g_() {
        Input<String> input = new Input<String>(ClassTag$.MODULE$.<String>apply(String.class));

        Lite<String, Integer> cut = implicits.$plus$bar$minus(new AbstractFunction1<String, Integer>() {
            @Override
            public Integer apply(String v1) {
                return Integer.parseInt(v1);
            }
        }, ClassTag$.MODULE$.<String>apply(String.class), ClassTag$.MODULE$.<Integer>apply(Integer.class));

        Lite<String, Integer> lite = Task.apply(implicits.TRANSIENT(), implicits.P_HIGH(), "", "", true, new AbstractFunction2<String, Context, Integer>() {
            @Override
            public Integer apply(String v1, Context v2) {
                return null;
            }
        }, ClassTag$.MODULE$.<String>apply(String.class), ClassTag$.MODULE$.<Integer>apply(Integer.class));

        ClassTag<String> tagIn = ClassTag$.MODULE$.apply(String.class);
        ClassTag<Integer> tagOut = ClassTag$.MODULE$.apply(Integer.class);
        return input.next(implicits.lite2Par(cut, tagIn, tagOut).$plus$greater$greater(lite, tagOut).$times$times$greater(new AbstractFunction3<Integer, Integer, Context, Integer>() {
            @Override
            public Integer apply(Integer v1, Integer v2, Context v3) {
                return null;
            }
        }, tagOut), tagOut);
    }

    public static void main(String[] args) {
        new task().e_g_().pulse(/*
        TODO: 还无法适配！
        new Pulse.Feedback.Lite<Integer>(0) {
            @Override
            public void liteOnComplete(long serialNum, Option<Integer> value) {
            }
        }*/ null, false, 128, 3, false, null, null);
    }
}
