package org.jopenagent.core.strategy;

import java.util.ArrayList;
import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.CallContext;
import org.jopenagent.core.PromptTemplate;
import org.jopenagent.json.ObjectBinder;
import org.jopenagent.json.SchemaGenerator;
import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Generate -&gt; reflect -&gt; improve loop, ported from NOOA's {@code ReflexionStrategy}
 * (nooa/src/nooa/strategies/reflexion.py): run a base strategy, ask a separate structured LLM
 * call whether the result is satisfactory, and if not, retry the base strategy with the
 * critique's issues/suggestions folded into the instruction: up to {@link #maxIterations}
 * attempts, returning the best-effort result if it never becomes satisfactory.
 *
 * <p>NOOA defaults the base strategy to its Python-only {@code PurePythonStrategy} (raw-text code,
 * no tool-calling API: jOpenAgent has no equivalent, see design notes); jOpenAgent defaults to
 * {@link PredictStrategy} instead, which covers the common "self-correct a structured-output
 * task" use case directly.
 */
public final class ReflexionStrategy implements Strategy {

    private final StrategyKind baseKind;
    private final int maxIterations;

    /** Equivalent to {@code ReflexionStrategy(StrategyKind.PREDICT, 3)}. */
    public ReflexionStrategy() {
        this(StrategyKind.PREDICT, 3);
    }

    /**
     * @param baseKind which strategy runs each attempt; besides {@code PREDICT}, also accepts
     *     {@code CODE_ACT} or {@code TOOL_CALLING} to wrap the critique loop around a sandboxed or
     *     tool-calling run instead of a single structured-output call
     * @param maxIterations number of attempts before giving up and returning the best-effort
     *     result, clamped to at least 1
     */
    public ReflexionStrategy(StrategyKind baseKind, int maxIterations) {
        this.baseKind = baseKind;
        this.maxIterations = Math.max(1, maxIterations);
    }

    @Override
    public Object execute(CallContext ctx) {
        Strategy base = resolveBase(baseKind);
        List<String> feedback = new ArrayList<>();
        Object result = null;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            CallContext iterationCtx = feedback.isEmpty() ? ctx : withFeedback(ctx, feedback);
            result = base.execute(iterationCtx);

            if (iteration == maxIterations) {
                return result;
            }
            ReflectionOutcome outcome = reflect(ctx, result, iteration);
            if (outcome.isSatisfactory()) {
                return result;
            }
            feedback.add(renderFeedback(iteration, outcome));
        }
        return result;
    }

    private static Strategy resolveBase(StrategyKind kind) {
        switch (kind) {
            case CODE_ACT:
                return new CodeActStrategy();
            case TOOL_CALLING:
                return new ToolCallingStrategy();
            case PREDICT:
            default:
                return new PredictStrategy();
        }
    }

    private CallContext withFeedback(CallContext ctx, List<String> feedback) {
        StringBuilder sb = new StringBuilder(ctx.getInstruction());
        sb.append("\n\n## Prior attempt feedback (address every point before answering again)\n");
        for (String note : feedback) {
            sb.append(note).append("\n");
        }
        return ctx.withInstruction(sb.toString());
    }

    private String renderFeedback(int iteration, ReflectionOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("Attempt ").append(iteration).append(":\n");
        for (String issue : outcome.issues()) {
            sb.append("- Issue: ").append(issue).append("\n");
        }
        for (String suggestion : outcome.suggestions()) {
            sb.append("- Suggestion: ").append(suggestion).append("\n");
        }
        return sb.toString();
    }

    private ReflectionOutcome reflect(CallContext ctx, Object candidateResult, int iteration) {
        Agent agent = ctx.getAgent();
        LlmClient llm = agent.getLlmClient();
        Span span = Traced.begin("llm_call", ctx.getMethod().getName() + " (reflexion critique " + iteration + ")");
        try {
            String systemPrompt = "You are a meticulous, skeptical reviewer of another AI's work. Be strict.";
            String prompt = "# Original task\n" + PromptTemplate.expand(ctx.getInstruction(), agent) + "\n\n# Candidate result\n" + ObjectBinder.toJson(candidateResult)
                    + "\n\n# Your job\nDecide whether the candidate result fully and correctly satisfies the original task. "
                    + "Return ONLY a JSON object matching this schema:\n" + SchemaGenerator.forReturnType(ReflectionOutcome.class).toString(2);
            LlmRequest request = LlmRequest.builder().systemPrompt(systemPrompt).addMessage(LlmMessage.user(prompt)).build();
            LlmResponse response = llm.generate(request);
            ReflectionOutcome outcome = ObjectBinder.bindResponse(response.getContent(), ReflectionOutcome.class);
            if (span != null) {
                span.setAttribute("isSatisfactory", outcome.isSatisfactory());
            }
            Traced.finish(span);
            return outcome;
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            // A broken critique call shouldn't crash the whole generation, treat it as "satisfactory" and keep the result.
            return new ReflectionOutcome(true, List.of(), List.of(), "reflection call failed: " + e.getMessage());
        }
    }
}
