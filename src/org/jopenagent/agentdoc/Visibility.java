package org.jopenagent.agentdoc;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.jopenagent.core.Hidden;
import org.jopenagent.core.Shown;

/**
 * Visible-by-default rules (mirrors nooa/AGENTS.md "Visibility"): public members are visible
 * unless annotated {@link Hidden}; non-public members are hidden unless annotated {@link Shown}.
 */
public final class Visibility {

    private Visibility() {
    }

    /** Whether {@code method} should be excluded from {@code doc(self)}, the sandbox exec scope, and tool-schema generation. */
    public static boolean isHiddenMethod(Method method) {
        return isHidden(method, method.getModifiers());
    }

    /** Whether {@code field} should be excluded from {@code doc(self)} and the sandbox exec scope. */
    public static boolean isHiddenField(Field field) {
        return isHidden(field, field.getModifiers());
    }

    private static boolean isHidden(Member member, int modifiers) {
        java.lang.reflect.AnnotatedElement element = (java.lang.reflect.AnnotatedElement) member;
        if (element.isAnnotationPresent(Hidden.class)) {
            return true;
        }
        if (element.isAnnotationPresent(Shown.class)) {
            return false;
        }
        return !Modifier.isPublic(modifiers);
    }
}
