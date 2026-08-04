package org.jopenagent.trace.atif;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.trace.Span;
import org.jopenagent.trace.TraceExporter;
import org.jopenagent.trace.TraceException;

/**
 * Exports a completed span tree as an <b>ATIF-v1.7</b> trajectory JSON document, the schema
 * NOOA's own {@code AtifExporter} produces (nooa/src/nooa/atif/schema.py,
 * nooa/src/nooa/atif/exporter.py): {@code {schema_version, trajectory_id, agent, steps: [...],
 * ...}}, where each step has a {@code step_id}, {@code source} (system/user/agent),
 * {@code message}, optional {@code tool_calls}/{@code observation}/{@code metrics}, and
 * {@code llm_call_count} (NOOA's documented convention: {@code 0} = deterministic dispatch/no LLM
 * call, {@code 1} = a normal LLM turn).
 *
 * <p>NOOA builds this incrementally from its flat, arbitrarily-typed event bus (a wildcard
 * subscription dispatching {@code BeforeTurn}/{@code LLMComplete}/{@code ToolCallEvent}/etc. into
 * an open step). jOpenAgent's tracing model is a span tree, not an event bus, so this exporter instead
 * walks one completed root {@link Span} (one top-level {@code @Generative} call) and flattens its
 * children into steps in chronological order: each {@code llm_call} span becomes an
 * {@code "agent"}-source step ({@code llm_call_count=1}); each {@code tool_execution}/
 * {@code code_execution} span becomes a deterministic-dispatch observation step
 * ({@code llm_call_count=0}, using exactly the convention ATIF documents for this case); a nested
 * {@code generation} span (e.g. Reflexion's critique call, or a genuine sub-agent call) becomes an
 * embedded entry in {@code subagent_trajectories}. This matches ATIF's <em>wire schema</em>
 * faithfully: it does not attempt to replicate NOOA's internal event-to-step state machine
 * (see the design notes for the full discussion of this simplification).
 */
public final class AtifExporter implements TraceExporter {

    private static final String SCHEMA_VERSION = "ATIF-v1.7";

    private final File outputFile;
    private final List<JSONObject> captured = Collections.synchronizedList(new ArrayList<JSONObject>());
    private String sessionId;

    /** In-memory-only mode: captured trajectories are available via {@link #getCaptured()} but nothing is written to disk. */
    public AtifExporter() {
        this.outputFile = null;
    }

    /** Also appends one ATIF trajectory JSON document (JSONL) per exported root span to {@code outputFile}, in addition to keeping it in {@link #getCaptured()}. */
    public AtifExporter(File outputFile) {
        this.outputFile = outputFile;
    }

    /** Sets the optional {@code session_id} field stamped onto every trajectory this exporter produces from here on (fluent, for chaining at the call site). */
    public AtifExporter withSessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /** Converts {@code rootSpan} to an ATIF trajectory via {@link #toTrajectory(Span)}, keeps it in {@link #getCaptured()}, and, if configured, appends it as one JSONL line to the output file. */
    @Override
    public void export(Span rootSpan) {
        JSONObject trajectory = toTrajectory(rootSpan);
        captured.add(trajectory);
        if (outputFile == null) {
            return;
        }
        synchronized (this) {
            try (FileWriter writer = new FileWriter(outputFile, true)) {
                writer.write(trajectory.toString());
                writer.write(System.lineSeparator());
            } catch (IOException e) {
                throw new TraceException("Failed writing ATIF trajectory to " + outputFile, e);
            }
        }
    }

    public List<JSONObject> getCaptured() {
        return Collections.unmodifiableList(new ArrayList<JSONObject>(captured));
    }

    /**
     * Builds one ATIF-v1.7 trajectory document from {@code rootSpan}: a synthetic {@code "user"}
     * step recording the invocation, followed by {@link #flatten(Span, JSONArray, JSONArray, int[])
     * flattening} the span's children into ordered {@code llm_call}/observation steps (nested
     * {@code generation} spans become entries under {@code subagent_trajectories} instead of
     * inline steps). If {@code rootSpan} ended in error, its message is attached under
     * {@code extra.error}. See the class-level doc for the full step-kind mapping.
     */
    public JSONObject toTrajectory(Span rootSpan) {
        JSONObject trajectory = new JSONObject();
        trajectory.put("schema_version", SCHEMA_VERSION);
        if (sessionId != null) {
            trajectory.put("session_id", sessionId);
        }
        trajectory.put("trajectory_id", rootSpan.getId());
        trajectory.put("agent", agentInfo(rootSpan));

        JSONArray steps = new JSONArray();
        JSONArray subagentTrajectories = new JSONArray();
        int[] stepCounter = new int[] { 1 };

        JSONObject userStep = new JSONObject();
        userStep.put("step_id", stepCounter[0]++);
        userStep.put("timestamp", Instant.now().toString());
        userStep.put("source", "user");
        userStep.put("message", "Invoked " + rootSpan.getName());
        userStep.put("llm_call_count", 0);
        steps.add(userStep);

        flatten(rootSpan, steps, subagentTrajectories, stepCounter);

        trajectory.put("steps", steps);
        if (!subagentTrajectories.isEmpty()) {
            trajectory.put("subagent_trajectories", subagentTrajectories);
        }
        if ("ERROR".equals(rootSpan.getStatus())) {
            JSONObject extra = new JSONObject();
            extra.put("error", rootSpan.getErrorMessage());
            trajectory.put("extra", extra);
        }
        return trajectory;
    }

    private void flatten(Span span, JSONArray steps, JSONArray subagentTrajectories, int[] stepCounter) {
        for (Span child : span.getChildren()) {
            if ("llm_call".equals(child.getKind())) {
                steps.add(llmCallStep(child, stepCounter[0]++));
            } else if ("tool_execution".equals(child.getKind()) || "code_execution".equals(child.getKind())) {
                steps.add(observationStep(child, stepCounter[0]++));
            } else if ("generation".equals(child.getKind())) {
                subagentTrajectories.add(toTrajectory(child));
            }
            // Recurse regardless of this span's own kind, so grandchildren (e.g. a code_execution
            // span nested under something unexpected) are still captured.
            flatten(child, steps, subagentTrajectories, stepCounter);
        }
    }

    private JSONObject llmCallStep(Span span, int stepId) {
        JSONObject step = new JSONObject();
        step.put("step_id", stepId);
        step.put("source", "agent");
        Object model = span.getAttributes().get("model");
        if (model != null) {
            step.put("model_name", String.valueOf(model));
        }
        Object toolCallCount = span.getAttributes().get("toolCalls");
        int calls = toolCallCount instanceof Number ? ((Number) toolCallCount).intValue() : 0;
        step.put("message", calls > 0 ? ("(issued " + calls + " tool call(s))") : "(text response)");
        step.put("metrics", metrics(span));
        step.put("llm_call_count", 1);
        if ("ERROR".equals(span.getStatus())) {
            JSONObject extra = new JSONObject();
            extra.put("error", span.getErrorMessage());
            step.put("extra", extra);
        }
        return step;
    }

    private JSONObject observationStep(Span span, int stepId) {
        JSONObject step = new JSONObject();
        step.put("step_id", stepId);
        step.put("source", "agent");
        step.put("llm_call_count", 0); // ATIF convention: 0 = deterministic dispatch, no LLM call.

        JSONObject observation = new JSONObject();
        JSONArray results = new JSONArray();
        JSONObject result = new JSONObject();
        result.put("source_call_id", span.getId());
        Object content = span.getAttributes().get("result");
        if (content == null) {
            content = span.getErrorMessage();
        }
        result.put("content", content != null ? String.valueOf(content) : "");
        results.add(result);
        observation.put("results", results);
        step.put("observation", observation);

        JSONObject extra = new JSONObject();
        extra.put("kind", span.getKind());
        extra.put("name", span.getName());
        if (span.getAttributes().containsKey("code")) {
            extra.put("code", String.valueOf(span.getAttributes().get("code")));
        }
        if (span.getAttributes().containsKey("arguments")) {
            extra.put("arguments", String.valueOf(span.getAttributes().get("arguments")));
        }
        step.put("extra", extra);
        return step;
    }

    private JSONObject metrics(Span span) {
        JSONObject metrics = new JSONObject();
        Object inputTokens = span.getAttributes().get("inputTokens");
        Object outputTokens = span.getAttributes().get("outputTokens");
        if (inputTokens instanceof Number) {
            metrics.put("prompt_tokens", ((Number) inputTokens).intValue());
        }
        if (outputTokens instanceof Number) {
            metrics.put("completion_tokens", ((Number) outputTokens).intValue());
        }
        return metrics;
    }

    private JSONObject agentInfo(Span rootSpan) {
        JSONObject agent = new JSONObject();
        agent.put("name", rootSpan.getName());
        String model = firstModelName(rootSpan);
        if (model != null) {
            agent.put("model_name", model);
        }
        return agent;
    }

    private String firstModelName(Span span) {
        for (Span child : span.getChildren()) {
            Object model = child.getAttributes().get("model");
            if (model != null) {
                return String.valueOf(model);
            }
            String nested = firstModelName(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
