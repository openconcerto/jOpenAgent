package org.jopenagent.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Expands {@code {self.field}} / {@code {self.method()}} placeholders in {@link SystemPrompt} and
 * {@link Generative} instruction text against the live agent instance.
 *
 * <p>NOOA expands docstring templates with a real Python {@code eval(expr, {"self": self})} 
 * arbitrary expressions. The JVM has no embeddable expression evaluator in the standard library,
 * and adding one (or wiring this through the JShell-based sandbox) would make every prompt build
 * pay sandbox-startup cost. Instead this implements exactly the forms nooa/AGENTS.md calls out as
 * the common cases: {@code {self.attr}} instance-state access and {@code {self.expr()}} computed
 * values via a no-arg helper method, including dotted chains like {@code {self.stats().count}}
 *, plus the literal {@code {doc(self)}} form (progressive API disclosure, see
 * {@link org.jopenagent.agentdoc.Introspector}). Anything else raises a clear error pointing at this limitation.
 */
public final class PromptTemplate {

    private PromptTemplate() {
    }

    /**
     * Scans {@code template} for placeholders delimited by curly braces and replaces each with the
     * evaluated result of its expression against {@code self} (see class javadoc for the supported
     * forms). Text outside a placeholder is copied through verbatim; an unterminated opening brace
     * with no matching close is left as-is rather than raising an error. Returns null unchanged if
     * {@code template} is null.
     */
    public static String expand(String template, Object self) {
        if (template == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = template.length();
        while (i < len) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i);
                if (close < 0) {
                    result.append(template.substring(i));
                    break;
                }
                String expr = template.substring(i + 1, close).trim();
                result.append(evaluate(expr, self));
                i = close + 1;
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    private static String evaluate(String expr, Object self) {
        if (expr.isEmpty()) {
            return "";
        }
        if (expr.equals("doc(self)")) {
            return org.jopenagent.agentdoc.Introspector.describe(self);
        }
        if (!expr.equals("self") && !expr.startsWith("self.")) {
            throw new IllegalArgumentException("Unsupported prompt template expression '{" + expr + "}': only {self.field}, {self.method()}, dotted chains of "
                    + "those, and the literal {doc(self)} are supported (Java has no runtime eval()). Expose computed values as a no-arg method instead.");
        }
        if (expr.equals("self")) {
            return String.valueOf(self);
        }
        String path = expr.substring("self".length());
        Object current = self;
        int pos = 0;
        while (pos < path.length()) {
            if (path.charAt(pos) != '.') {
                throw new IllegalArgumentException("Malformed prompt template expression '{" + expr + "}'");
            }
            pos++;
            int start = pos;
            while (pos < path.length() && Character.isJavaIdentifierPart(path.charAt(pos))) {
                pos++;
            }
            String member = path.substring(start, pos);
            if (member.isEmpty()) {
                throw new IllegalArgumentException("Malformed prompt template expression '{" + expr + "}'");
            }
            boolean isCall = pos < path.length() && path.charAt(pos) == '(';
            if (isCall) {
                if (pos + 1 >= path.length() || path.charAt(pos + 1) != ')') {
                    throw new IllegalArgumentException("Only no-argument method calls are supported in template expressions: '{" + expr + "}'");
                }
                pos += 2;
                current = invokeNoArg(current, member, expr);
            } else {
                current = readField(current, member, expr);
            }
            if (current == null) {
                return "null";
            }
        }
        return String.valueOf(current);
    }

    private static Object invokeNoArg(Object target, String name, String expr) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Method method = cls.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException e) {
                cls = cls.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed evaluating template expression '{" + expr + "}'", e);
            }
        }
        throw new IllegalArgumentException("No no-arg method '" + name + "()' found for template expression '{" + expr + "}'");
    }

    private static Object readField(Object target, String name, String expr) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed evaluating template expression '{" + expr + "}'", e);
            }
        }
        throw new IllegalArgumentException("No field '" + name + "' found for template expression '{" + expr + "}'");
    }
}
