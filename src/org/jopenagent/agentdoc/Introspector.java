package org.jopenagent.agentdoc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;

import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;

/**
 * {@code doc(self)} equivalent: renders an agent's visible fields, generative methods (with their
 * instruction text), and ordinary methods into a text block the LLM can read for progressive
 * disclosure of its own API (see nooa/AGENTS.md "Understanding doc(self)").
 */
public final class Introspector {

    private Introspector() {
    }

    /**
     * Renders {@code agent}'s visible fields, generative methods, and ordinary methods (per
     * {@link MemberScanner}/{@link Visibility}) into the {@code doc(self)} text block: a class
     * header followed by {@code fields:}, {@code generative methods:}, and {@code methods:}
     * sections, each entry annotated with its {@link Doc} description when present.
     */
    public static String describe(Object agent) {
        Class<?> cls = agent.getClass();
        StringBuilder sb = new StringBuilder();
        sb.append("class ").append(cls.getSimpleName()).append(":\n");

        List<Field> fields = MemberScanner.visibleFields(cls);
        if (!fields.isEmpty()) {
            sb.append("  fields:\n");
            for (Field field : fields) {
                sb.append("    ").append(field.getName()).append(": ").append(typeName(field.getGenericType()));
                Doc doc = field.getAnnotation(Doc.class);
                if (doc != null) {
                    sb.append("  # ").append(doc.value());
                }
                sb.append("\n");
            }
        }

        List<Method> generative = MemberScanner.generativeMethods(cls);
        if (!generative.isEmpty()) {
            sb.append("  generative methods:\n");
            for (Method method : generative) {
                sb.append("    ").append(signature(method)).append("\n");
                Generative annotation = method.getAnnotation(Generative.class);
                sb.append("      \"").append(annotation.value()).append("\"\n");
            }
        }

        List<Method> ordinary = MemberScanner.ordinaryVisibleMethods(cls);
        if (!ordinary.isEmpty()) {
            sb.append("  methods:\n");
            for (Method method : ordinary) {
                sb.append("    ").append(signature(method));
                Doc doc = method.getAnnotation(Doc.class);
                if (doc != null) {
                    sb.append("  # ").append(doc.value());
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Renders {@code method} as {@code name(param: Type, ...): ReturnType} using simple type names
     * where possible.
     */
    public static String signature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append("(");
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String name = params[i].isNamePresent() ? params[i].getName() : ("arg" + i);
            sb.append(name).append(": ").append(typeName(params[i].getParameterizedType()));
        }
        sb.append("): ").append(typeName(method.getGenericReturnType()));
        return sb.toString();
    }

    private static String typeName(Type type) {
        if (type instanceof Class<?>) {
            return ((Class<?>) type).getSimpleName();
        }
        return type.getTypeName();
    }
}
