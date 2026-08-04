package org.jopenagent.core.strategy;

import java.util.ArrayList;
import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.CallContext;
import org.jopenagent.core.GenerationException;
import org.jopenagent.core.PromptBuilder;
import org.jopenagent.core.history.ConversationHistory;
import org.jopenagent.core.history.HistoryEntry;
import org.jopenagent.core.history.HistorySummarizer;
import org.jopenagent.json.BindingException;
import org.jopenagent.json.ObjectBinder;
import org.jopenagent.json.SchemaGenerator;
import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.llm.ToolSpec;
import org.jopenagent.llm.media.Media;
import org.jopenagent.tools.ToolRegistry;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Classic function-calling (ReAct-style) loop: the LLM calls the agent's ordinary methods
 * (exposed as JSON-schema tools by {@link ToolRegistry}) until it calls the synthetic
 * {@code return_result} tool with its final answer, which is validated against the return type
 * exactly like {@link PredictStrategy} does, with the same feed-the-error-back retry behavior.
 *
 * <p>On success, the exchange is appended to {@link Agent#history} and auto-summarized if
 * configured (see {@link HistorySummarizer}), so later calls on the same agent instance see what
 * happened here.
 */
public final class ToolCallingStrategy implements Strategy {

    private static final String RETURN_RESULT_TOOL = "return_result";

    @Override
    public Object execute(CallContext ctx) {
        Agent agent = ctx.getAgent();
        ConversationHistory history = agent.history;
        boolean historyEnabled = agent.getConfig().getHistoryConfig().isEnabled();
        StringBuilder transcript = new StringBuilder();

        Object result = runLoop(ctx, historyEnabled ? history : null, transcript);

        if (historyEnabled) {
            history.append(new HistoryEntry("user", PromptBuilder.taskPromptSummary(ctx)));
            if (transcript.length() > 0) {
                history.append(new HistoryEntry("assistant", transcript.toString().trim()));
            }
            history.append(new HistoryEntry("assistant", "Result: " + ObjectBinder.toJson(result)));
            HistorySummarizer.summarizeIfNeeded(history, agent.getLlmClient(), agent.getConfig().getHistoryConfig());
        }
        return result;
    }

    private Object runLoop(CallContext ctx, ConversationHistory history, StringBuilder transcript) {
        Agent agent = ctx.getAgent();
        LlmClient llm = agent.getLlmClient();
        ToolRegistry registry = new ToolRegistry(agent);

        String systemPrompt = PromptBuilder.systemPrompt(agent) + "\n\nWhen you have the final answer, call " + RETURN_RESULT_TOOL + " with it instead of replying in text.";
        String taskPrompt = PromptBuilder.taskPrompt(ctx);

        List<Media> media = PromptBuilder.collectMedia(ctx);
        List<LlmMessage> conversation = new ArrayList<>();
        if (history != null) {
            conversation.addAll(history.asMessages());
        }
        conversation.add(media.isEmpty() ? LlmMessage.user(taskPrompt) : LlmMessage.userWithMedia(taskPrompt, media));

        List<ToolSpec> tools = new ArrayList<>(registry.toolSpecs());
        tools.add(new ToolSpec(RETURN_RESULT_TOOL, "Call this with the final answer once you are done.", SchemaGenerator.forReturnType(ctx.getReturnType())));

        List<String> errors = new ArrayList<>();
        int maxIterations = Math.max(ctx.getMaxRetries(), agent.getConfig().getMaxCodeActIterations());

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            LlmResponse response = callLlm(llm, systemPrompt, conversation, tools, ctx, iteration);

            if (!response.hasToolCalls()) {
                conversation.add(LlmMessage.assistant(response.getContent()));
                conversation.add(LlmMessage.user("Please call a tool, or call " + RETURN_RESULT_TOOL + " with your final answer."));
                continue;
            }
            conversation.add(LlmMessage.assistantToolCalls(response.getContent(), response.getToolCalls()));

            for (ToolCall call : response.getToolCalls()) {
                if (call.getName().equals(RETURN_RESULT_TOOL)) {
                    try {
                        return ObjectBinder.bindWrapped(call.getArguments(), ctx.getReturnType());
                    } catch (BindingException e) {
                        errors.add("Iteration " + iteration + ": " + e.getMessage());
                        conversation.add(LlmMessage.toolResult(call.getId(),
                                "Invalid final answer: " + e.getMessage() + ". Call " + RETURN_RESULT_TOOL + " again with a corrected value."));
                        continue;
                    }
                }
                String toolResult = invokeTool(registry, call);
                transcript.append("called ").append(call.getName()).append("(").append(call.getArguments()).append(") -> ").append(toolResult).append("\n");
                conversation.add(LlmMessage.toolResult(call.getId(), toolResult));
            }
        }
        throw new GenerationException("Tool-calling loop for " + ctx.getMethod() + " did not produce a final answer within " + maxIterations + " iterations.", errors,
                null);
    }

    private String invokeTool(ToolRegistry registry, ToolCall call) {
        Span span = Traced.begin("tool_execution", call.getName());
        if (span != null) {
            span.setAttribute("arguments", call.getArguments().toString());
        }
        try {
            Object result = registry.invoke(call);
            String rendered = String.valueOf(ObjectBinder.toJson(result));
            if (span != null) {
                span.setAttribute("result", rendered);
            }
            Traced.finish(span);
            return rendered;
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            return "Error: " + e.getMessage();
        }
    }

    private LlmResponse callLlm(LlmClient llm, String systemPrompt, List<LlmMessage> conversation, List<ToolSpec> tools, CallContext ctx, int iteration) {
        Span span = Traced.begin("llm_call", ctx.getMethod().getName() + " (tool-calling iteration " + iteration + ")");
        try {
            LlmRequest request = LlmRequest.builder().systemPrompt(systemPrompt).addMessages(conversation).addTools(tools).build();
            LlmResponse response = llm.generate(request);
            ctx.getAgent().history.setLastReportedPromptTokens(response.getInputTokens());
            if (span != null) {
                span.setAttribute("model", llm.modelName());
                span.setAttribute("inputTokens", response.getInputTokens());
                span.setAttribute("outputTokens", response.getOutputTokens());
                span.setAttribute("toolCalls", response.getToolCalls().size());
            }
            Traced.finish(span);
            return response;
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            throw new GenerationException("LLM call failed on tool-calling iteration " + iteration + " for " + ctx.getMethod() + ": " + e.getMessage(), new ArrayList<String>(),
                    e);
        }
    }
}
