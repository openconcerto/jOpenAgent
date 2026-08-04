package org.jopenagent.core.strategy;

import java.util.ArrayList;
import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.CallContext;
import org.jopenagent.core.GenerationException;
import org.jopenagent.core.PromptBuilder;
import org.jopenagent.json.BindingException;
import org.jopenagent.json.ObjectBinder;
import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.llm.media.Media;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Single-shot structured output with retry: jOpenAgent's default strategy, equivalent to NOOA's
 * {@code PredictStrategy}: ask the LLM for one JSON object matching the return type's schema; on
 * a JSON-parse or validation failure, feed the raw response and the error back to the LLM and
 * retry, up to {@link CallContext#getMaxRetries()} attempts.
 */
public final class PredictStrategy implements Strategy {

    @Override
    public Object execute(CallContext ctx) {
        Agent agent = ctx.getAgent();
        LlmClient llm = agent.getLlmClient();
        String systemPrompt = PromptBuilder.systemPrompt(agent);
        String basePrompt = PromptBuilder.taskPrompt(ctx);
        List<Media> media = PromptBuilder.collectMedia(ctx);

        List<String> failedAttempts = new ArrayList<>();
        String currentPrompt = basePrompt;

        for (int attempt = 1; attempt <= ctx.getMaxRetries(); attempt++) {
            // Media only needs to accompany the first attempt; retry prompts are plain corrective text.
            LlmResponse response = callLlm(llm, systemPrompt, currentPrompt, attempt == 1 ? media : null, ctx, attempt);
            try {
                return ObjectBinder.bindResponse(response.getContent(), ctx.getReturnType());
            } catch (BindingException e) {
                failedAttempts.add("Attempt " + attempt + ": " + e.getMessage());
                if (attempt == ctx.getMaxRetries()) {
                    throw new GenerationException("Failed to get valid structured output for " + ctx.getMethod() + " after " + ctx.getMaxRetries() + " attempt(s).",
                            failedAttempts, e);
                }
                currentPrompt = basePrompt + "\n\n## Your previous response was invalid\nRaw response:\n" + response.getContent() + "\n\nError: " + e.getMessage()
                        + "\n\nCorrect the mistake and return ONLY the fixed JSON object.";
            }
        }
        throw new GenerationException("maxRetries must be >= 1 on " + ctx.getMethod(), failedAttempts, null);
    }

    private LlmResponse callLlm(LlmClient llm, String systemPrompt, String userPrompt, List<Media> media, CallContext ctx, int attempt) {
        Span span = Traced.begin("llm_call", ctx.getMethod().getName() + " (attempt " + attempt + ")");
        try {
            LlmMessage userMessage = (media != null && !media.isEmpty()) ? LlmMessage.userWithMedia(userPrompt, media) : LlmMessage.user(userPrompt);
            LlmRequest request = LlmRequest.builder().systemPrompt(systemPrompt).addMessage(userMessage).build();
            LlmResponse response = llm.generate(request);
            if (span != null) {
                span.setAttribute("model", llm.modelName());
                span.setAttribute("inputTokens", response.getInputTokens());
                span.setAttribute("outputTokens", response.getOutputTokens());
            }
            Traced.finish(span);
            return response;
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            throw new GenerationException("LLM call failed on attempt " + attempt + " for " + ctx.getMethod() + ": " + e.getMessage(), new ArrayList<String>(), e);
        }
    }
}
