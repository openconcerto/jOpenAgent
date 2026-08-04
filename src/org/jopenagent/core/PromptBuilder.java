package org.jopenagent.core;

import java.util.ArrayList;
import java.util.List;

import org.jopenagent.json.ObjectBinder;
import org.jopenagent.json.SchemaGenerator;
import org.jopenagent.llm.media.Media;

/** Builds the system prompt and per-call task prompt for a {@code @Generative} method. */
public final class PromptBuilder {

    private PromptBuilder() {
    }

    /** The agent's system prompt: nearest {@link SystemPrompt} in the class hierarchy, template-expanded, plus rendered context blocks. */
    public static String systemPrompt(Agent agent) {
        SystemPrompt annotation = findSystemPrompt(agent.getClass());
        String base = annotation != null ? PromptTemplate.expand(annotation.value(), agent) : "You are a helpful agent.";
        return base + agent.context.render() + agent.skills.render();
    }

    private static SystemPrompt findSystemPrompt(Class<?> cls) {
        Class<?> current = cls;
        while (current != null) {
            SystemPrompt annotation = current.getAnnotation(SystemPrompt.class);
            if (annotation != null) {
                return annotation;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /** The task instruction for one call: expanded docstring-equivalent + rendered parameters + required output schema. */
    public static String taskPrompt(CallContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Task\n");
        sb.append(PromptTemplate.expand(ctx.getInstruction(), ctx.getAgent()));
        sb.append("\n\n## Input parameters\n");
        List<CallContext.ParameterBinding> bindings = ctx.parameterBindings();
        if (bindings.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (CallContext.ParameterBinding binding : bindings) {
                sb.append("- ").append(binding.getName()).append(" = ").append(renderValue(binding.getValue())).append("\n");
            }
        }
        sb.append("\n## Required output format\n");
        sb.append("Return ONLY a single JSON object matching this schema (no prose, no markdown fences):\n");
        sb.append(SchemaGenerator.forReturnType(ctx.getReturnType()).toString(2));
        return sb.toString();
    }

    /** A compact, history-friendly rendering of one call: just the instruction + parameter values, no schema. Used by CODE_ACT/TOOL_CALLING to record {@link Agent#history}. */
    public static String taskPromptSummary(CallContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptTemplate.expand(ctx.getInstruction(), ctx.getAgent()));
        for (CallContext.ParameterBinding binding : ctx.parameterBindings()) {
            sb.append(" [").append(binding.getName()).append("=").append(renderValue(binding.getValue())).append("]");
        }
        return sb.toString();
    }

    /**
     * Any {@link Media} found directly among the call's parameter values (or inside a
     * {@code List<Media>} parameter) \u2014 attached to the LLM request as real multimodal content
     * instead of being base64-dumped into the text prompt by {@link #renderValue}. Mirrors NOOA's
     * {@code Media}-typed-parameter collection in {@code predict.py} (see nooa/src/nooa/media.py).
     */
    public static List<Media> collectMedia(CallContext ctx) {
        List<Media> media = new ArrayList<>();
        for (CallContext.ParameterBinding binding : ctx.parameterBindings()) {
            Object value = binding.getValue();
            if (value instanceof Media) {
                media.add((Media) value);
            } else if (value instanceof List<?>) {
                for (Object element : (List<?>) value) {
                    if (element instanceof Media) {
                        media.add((Media) element);
                    }
                }
            }
        }
        return media;
    }

    private static String renderValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Media) {
            return "(attached as multimodal content \u2014 see accompanying " + ((Media) value).getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) + ")";
        }
        return String.valueOf(ObjectBinder.toJson(value));
    }
}
