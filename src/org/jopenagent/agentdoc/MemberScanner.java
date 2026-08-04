package org.jopenagent.agentdoc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jopenagent.core.Agent;
import org.jopenagent.core.Generative;

/**
 * Walks a user's Agent subclass hierarchy (stopping before {@link Agent} itself, whose own
 * fields/methods are framework plumbing, not agent-authored capabilities/state) to collect the
 * members {@code doc(self)}, the code-as-action sandbox, and tools-via-self all need to see.
 */
public final class MemberScanner {

    private MemberScanner() {
    }

    /**
     * All non-static, non-synthetic, non-bridge methods declared anywhere between {@code cls} and
     * (exclusive) {@link Agent}, de-duplicated by name+parameter-types so an override is only
     * reported once (the subclass's declaration wins, since it is visited first).
     */
    public static List<Method> userMethods(Class<?> cls) {
        Map<String, Method> bySignature = new LinkedHashMap<>();
        Class<?> current = cls;
        while (current != null && current != Agent.class && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String signature = method.getName() + Arrays.toString(method.getParameterTypes());
                if (!bySignature.containsKey(signature)) {
                    bySignature.put(signature, method);
                }
            }
            current = current.getSuperclass();
        }
        return new ArrayList<>(bySignature.values());
    }

    /**
     * All non-static, non-synthetic instance fields declared anywhere between {@code cls} and
     * (exclusive) {@link Agent}, de-duplicated by name (the subclass's declaration wins).
     */
    public static List<Field> userFields(Class<?> cls) {
        Map<String, Field> byName = new LinkedHashMap<>();
        Class<?> current = cls;
        while (current != null && current != Agent.class && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!byName.containsKey(field.getName())) {
                    byName.put(field.getName(), field);
                }
            }
            current = current.getSuperclass();
        }
        return new ArrayList<>(byName.values());
    }

    /** The subset of {@link #userMethods(Class)} annotated {@link Generative}. The agent's LLM-delegated capabilities. */
    public static List<Method> generativeMethods(Class<?> cls) {
        List<Method> result = new ArrayList<>();
        for (Method method : userMethods(cls)) {
            if (method.isAnnotationPresent(Generative.class)) {
                result.add(method);
            }
        }
        return result;
    }

    /** Ordinary (non-@Generative) methods visible to doc(self)/tools/sandbox by default-visibility rules. */
    public static List<Method> ordinaryVisibleMethods(Class<?> cls) {
        List<Method> result = new ArrayList<>();
        for (Method method : userMethods(cls)) {
            if (method.isAnnotationPresent(Generative.class)) {
                continue;
            }
            if (Visibility.isHiddenMethod(method)) {
                continue;
            }
            result.add(method);
        }
        return result;
    }

    /** The subset of {@link #userFields(Class)} not hidden per {@link Visibility#isHiddenField(Field)}. */
    public static List<Field> visibleFields(Class<?> cls) {
        List<Field> result = new ArrayList<>();
        for (Field field : userFields(cls)) {
            if (Visibility.isHiddenField(field)) {
                continue;
            }
            result.add(field);
        }
        return result;
    }
}
