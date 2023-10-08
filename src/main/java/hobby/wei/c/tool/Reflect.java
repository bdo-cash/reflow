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

package hobby.wei.c.tool;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Locale;

/**
 * 摘自`Gson`源码。
 *
 * @author Wei Chou(weichou2010@gmail.com)
 * @version 1.0, 02/07/2016
 */
public class Reflect {
    public static Type[] getSuperclassTypeParameter(Class<?> subclass, boolean checkTypeVariable) {
        final Type superclass = subclass.getGenericSuperclass();
        if (superclass instanceof Class) {
            return throwClassIllegal(subclass);
        } else {
            final Type[] types = ((ParameterizedType) superclass).getActualTypeArguments();
            if (checkTypeVariable) {
                for (Type tpe : types) {
                    checkTypeVariable(tpe);
                }
            }
            return types;
        }
    }

    /**
     * 借鉴 {@code Gson 2.10.1}, 实测与上面效果一样（问题不在 scala/java, 而在 Android, 与 R8 有关）。
     */
    public static Type[] getSuperclassTypeParameterSafe(Class<?> subclass, boolean checkTypeVariable) {
        final Type superclass = subclass.getGenericSuperclass();
        if (superclass instanceof ParameterizedType) {
            final Type[] types = ((ParameterizedType) superclass).getActualTypeArguments();
            if (checkTypeVariable) {
                for (Type tpe : types) {
                    checkTypeVariable(tpe);
                }
            }
            return types;
        }
        return throwClassIllegal(subclass);
    }

    public static Type checkTypeVariable(Type tpe) {
        // `TypeVariable`表示`T`占位符而不是具体类型，如`Xx<T>`而不是`Xx<java.util.ArrayList<?>>`。
        // 区别于`WildcardType`。
        if (tpe instanceof TypeVariable) {
            // ((TypeVariable<?>)tpe).getBounds(); // 表示获取`T`参数表示的具体类，如：
            // `java.util.ArrayList<?>`，这需要递归，具体看在第几层。
            throwTypeIllegal(tpe);
        }
        return tpe;
    }

    public static Class<?> getRawType(Type tpe) {
        if (tpe instanceof Class) {
            return (Class<?>) tpe;
        } else if (tpe instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) tpe).getRawType();
        } else if (tpe instanceof GenericArrayType) {
            return Array.newInstance(getRawType(((GenericArrayType) tpe).getGenericComponentType()), 0).getClass();
        } else if (tpe instanceof TypeVariable) {
            // we could use the variable's bounds, but that won't work if there are multiple.
            // having a raw type that's more general than necessary is okay
            return Object.class;
        } else if (tpe instanceof WildcardType) {
            return getRawType(((WildcardType) tpe).getUpperBounds()[0]);
        } else {
            return throwTypeIllegal(tpe);
        }
    }

    public static Type[] getSubTypes(Type tpe) {
        if (tpe instanceof ParameterizedType) {
            return ((ParameterizedType) tpe).getActualTypeArguments();
        } else {
            return EMPTY_TYPES;
        }
    }

    private static Type[] throwClassIllegal(Class<?> subclass) {
        throw new IllegalArgumentException(String.format(
                Locale.CHINA, "请确保类\"%s\"是泛型类的[匿名]子类。", subclass.getName()));
    }

    private static Class<?> throwTypeIllegal(Type tpe) {
        throw new IllegalArgumentException(String.format(
                Locale.CHINA, "不支持的类型\"%s\", 请确认泛型参数是否正确。", tpe));
    }

    private static final Type[] EMPTY_TYPES = new Type[0];
}
