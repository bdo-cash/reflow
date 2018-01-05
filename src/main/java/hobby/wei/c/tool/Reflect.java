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

package hobby.wei.c.tool;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Locale;

/**
 * 摘自Gson源码。
 *
 * @author Wei.Chou (weichou2010@gmail.com)
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
                for (Type t : types) {
                    if (t instanceof TypeVariable) {
                        throwClassIllegal(subclass);
                    }
                }
            }
            return types;
        }
    }

    public static Class<?> getRawType(Type type) {
        if (type instanceof Class) {
            return (Class) type;
        } else if (type instanceof ParameterizedType) {
            return (Class) ((ParameterizedType) type).getRawType();
        } else if (type instanceof GenericArrayType) {
            return Array.newInstance(getRawType(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        } else if (type instanceof TypeVariable) {
            return Object.class;
        } else if (type instanceof WildcardType) {
            return getRawType(((WildcardType) type).getUpperBounds()[0]);
        } else {
            return throwTypeIllegal(type);
        }
    }

    public static Type[] getSubTypes(Type type) {
        if (type instanceof ParameterizedType) {
            return ((ParameterizedType) type).getActualTypeArguments();
        } else {
            return EMPTY_TYPES;
        }
    }

    private static Type[] throwClassIllegal(Class<?> subclass) {
        throw new IllegalArgumentException(String.format(
                Locale.CHINA, "请确保类\"%s\"是泛型类的[匿名]子类。", subclass.getName()));
    }

    private static Class<?> throwTypeIllegal(Type type) {
        throw new IllegalArgumentException(String.format(
                // Locale.CHINA, "不支持的类型\"%s\", 仅支持Class, ParameterizedType, or GenericArrayType.", type));
                Locale.CHINA, "不支持的类型\"%s\", 请确认泛型参数是否正确。", type));
    }

    private static final Type[] EMPTY_TYPES = new Type[0];
}
