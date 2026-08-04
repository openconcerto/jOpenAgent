package org.jopenagent.json;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Small helpers for reasoning about java.lang.reflect.Type at runtime, without
 * pulling in a reflection library. Used by ObjectBinder and SchemaGenerator to
 * resolve generic containers (List&lt;T&gt;, Set&lt;T&gt;, Map&lt;K,V&gt;, Optional&lt;T&gt;)
 * declared on @Generative method return types and record/POJO fields.
 */
public final class TypeUtils {

    private TypeUtils() {
    }

    /** Returns the raw erased class backing a Type (Class, ParameterizedType, GenericArrayType). */
    public static Class<?> rawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawClass(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = rawClass(((GenericArrayType) type).getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            if (upper.length > 0) {
                return rawClass(upper[0]);
            }
            return Object.class;
        }
        throw new BindingException("Cannot resolve raw class for type: " + type);
    }

    /** Returns the single type argument of a parameterized type (e.g. T in List&lt;T&gt;), or Object.class. */
    public static Type firstTypeArgument(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (args.length > 0) {
                return args[0];
            }
        }
        return Object.class;
    }

    /** Returns the second type argument of a parameterized type (e.g. V in Map&lt;K,V&gt;), or Object.class. */
    public static Type secondTypeArgument(Type type) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (args.length > 1) {
                return args[1];
            }
        }
        return Object.class;
    }

    public static boolean isList(Class<?> raw) {
        return List.class.isAssignableFrom(raw);
    }

    public static boolean isSet(Class<?> raw) {
        return Set.class.isAssignableFrom(raw);
    }

    public static boolean isMap(Class<?> raw) {
        return Map.class.isAssignableFrom(raw);
    }

    public static boolean isOptional(Class<?> raw) {
        return Optional.class.isAssignableFrom(raw);
    }

    public static boolean isEnum(Class<?> raw) {
        return raw.isEnum();
    }

    public static boolean isRecord(Class<?> raw) {
        return raw.isRecord();
    }

    /** True for a primitive type or any of its boxed wrapper classes (excludes {@code String}/{@code CharSequence}). */
    public static boolean isBoxedOrPrimitive(Class<?> raw) {
        return raw.isPrimitive() || raw == Boolean.class || raw == Byte.class || raw == Short.class || raw == Integer.class || raw == Long.class || raw == Float.class
                || raw == Double.class || raw == Character.class;
    }

    /** True for {@code String} or {@code CharSequence}. */
    public static boolean isTextual(Class<?> raw) {
        return raw == String.class || raw == CharSequence.class;
    }

    /**
     * True if a value of this type cannot itself be the root of a JSON object
     * (scalars, enums, arrays, and List/Set-shaped collections), meaning the
     * response must be wrapped as {"value": ...} to satisfy providers that
     * require an object-rooted JSON schema.
     */
    public static boolean requiresRootWrapping(Type type) {
        Class<?> raw = rawClass(type);
        if (raw.isArray()) {
            return true;
        }
        if (isBoxedOrPrimitive(raw) || isTextual(raw) || isEnum(raw)) {
            return true;
        }
        if (isList(raw) || isSet(raw)) {
            return true;
        }
        if (isOptional(raw)) {
            return requiresRootWrapping(firstTypeArgument(type));
        }
        return false;
    }
}
