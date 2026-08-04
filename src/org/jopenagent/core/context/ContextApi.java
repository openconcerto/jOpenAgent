package org.jopenagent.core.context;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jopenagent.core.PromptTemplate;

/**
 * {@code self.context} equivalent (see nooa/AGENTS.md "Context Blocks"). Static blocks
 * ({@link #put}) are evaluated once and cached; dynamic blocks ({@link #setDynamic}) are
 * re-evaluated against the owning agent every time {@link #render()} is called (e.g. once per
 * LLM turn), the same distinction NOOA draws between {@code self.context["k"] = v} and
 * {@code self.context.set_dynamic("k", "python_expression")}, except the "expression" here is a
 * {@link PromptTemplate} {@code self....} path rather than an arbitrary Python expression.
 */
public final class ContextApi {

    private final Object owner;
    private final Map<String, Object> staticValues = new LinkedHashMap<>();
    private final Map<String, String> dynamicExpressions = new LinkedHashMap<>();

    /** @param owner the agent instance dynamic expressions are evaluated against (the {@code self} in {@code self....} paths) */
    public ContextApi(Object owner) {
        this.owner = owner;
    }

    /** Sets a static context block: {@code value} is stored as-is and rendered unchanged on every {@link #render()}. Clears any dynamic expression previously set under {@code key}. */
    public void put(String key, Object value) {
        staticValues.put(key, value);
        dynamicExpressions.remove(key);
    }

    /** Sets a dynamic context block: {@code selfExpression} is a {@link PromptTemplate} {@code self....} path re-evaluated against the owner on every {@link #get(String)}/{@link #render()} call. Clears any static value previously set under {@code key}. */
    public void setDynamic(String key, String selfExpression) {
        dynamicExpressions.put(key, selfExpression);
        staticValues.remove(key);
    }

    /** The current value for {@code key}: re-evaluated on each call if it was set via {@link #setDynamic}, otherwise the stored static value. */
    public Object get(String key) {
        if (dynamicExpressions.containsKey(key)) {
            return PromptTemplate.expand("{" + dynamicExpressions.get(key) + "}", owner);
        }
        return staticValues.get(key);
    }

    /** Removes any static value or dynamic expression stored under {@code key}. */
    public void remove(String key) {
        staticValues.remove(key);
        dynamicExpressions.remove(key);
    }

    /** All keys with either a static value or a dynamic expression set, in insertion order (static first, then dynamic). */
    public Set<String> keys() {
        Set<String> all = new LinkedHashSet<>(staticValues.keySet());
        all.addAll(dynamicExpressions.keySet());
        return all;
    }

    /** Renders every context block as a text section for inclusion in a prompt. Empty string if none set. */
    public String render() {
        Set<String> keys = keys();
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## Context\n");
        for (String key : keys) {
            sb.append("### ").append(key).append("\n").append(String.valueOf(get(key))).append("\n");
        }
        return sb.toString();
    }
}
