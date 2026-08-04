package org.jopenagent.sandbox.remote;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** Renders a {@link Type} as compilable Java source text, for {@link SelfStubGenerator}. */
final class JavaTypeSource {

    private JavaTypeSource() {
    }

    /**
     * Renders {@code type} as a source-text type reference usable in a generated stub's field/
     * parameter/return position: a plain class renders as its canonical name (arrays recursively as
     * {@code T[]}), a {@link ParameterizedType} renders its raw type followed by recursively
     * rendered {@code <...>} type arguments, and anything without a direct source form (wildcards,
     * type variables) falls back to {@code Object}, safe because {@link RemoteSelfDispatch#invoke}
     * re-binds the real JSON payload against the actual reflected return type at call time, not
     * against whatever text this method produced.
     */
    static String render(Type type) {
        if (type instanceof Class<?>) {
            Class<?> cls = (Class<?>) type;
            if (cls.isArray()) {
                return render(cls.getComponentType()) + "[]";
            }
            String canonical = cls.getCanonicalName();
            return canonical != null ? canonical : "Object";
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            StringBuilder sb = new StringBuilder(render(parameterized.getRawType()));
            Type[] args = parameterized.getActualTypeArguments();
            sb.append("<");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(render(args[i]));
            }
            sb.append(">");
            return sb.toString();
        }
        if (type instanceof GenericArrayType) {
            return render(((GenericArrayType) type).getGenericComponentType()) + "[]";
        }
        // Wildcards/type variables have no direct source form usable here; Object is always a
        // valid substitute since ObjectBinder binds the real JSON payload against the *actual*
        // reflected return type at call time (see RemoteSelfDispatch), not this rendered text.
        return "Object";
    }
}
