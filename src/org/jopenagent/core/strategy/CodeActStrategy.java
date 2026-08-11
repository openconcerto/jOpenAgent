package org.jopenagent.core.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

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
import org.jopenagent.sandbox.CodeExecutor;
import org.jopenagent.sandbox.CodeSandbox;
import org.jopenagent.sandbox.CodeValidator;
import org.jopenagent.sandbox.SandboxConfig;
import org.jopenagent.sandbox.SandboxMode;
import org.jopenagent.sandbox.SandboxResult;
import org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Code-as-action: gives the LLM two tools, {@code execute_java} and {@code return_result}
 * (jOpenAgent's equivalent of NOOA's {@code execute_python}/{@code return_result} CodeAct pair, see
 * nooa/src/nooa/strategies/codeact.py), and loops until it calls {@code return_result}. Executed
 * code has a live {@code self} bound to the agent (via {@link CodeExecutor}, {@link CodeSandbox}
 * in-process by default, or {@link OutOfProcessCodeSandbox} when
 * {@link SandboxMode#OUT_OF_PROCESS} is configured), so it calls the agent's own visible methods
 * directly ({@code self.someMethod(...)}) rather than through a separate tool-schema layer,
 * matching NOOA's "no tool-registration abstraction for self" design.
 *
 * <p>On success, the exchange is appended to {@link Agent#history} and auto-summarized if
 * configured (see {@link HistorySummarizer}).
 */
public final class CodeActStrategy implements Strategy {

    private static final String EXECUTE_TOOL = "execute_java";
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

        String systemPrompt = PromptBuilder.systemPrompt(agent) + "\n\n" + codeActInstructions(agent, ctx);
        String taskPrompt = PromptBuilder.taskPrompt(ctx);

        List<Media> media = PromptBuilder.collectMedia(ctx);
        List<LlmMessage> conversation = new ArrayList<>();
        if (history != null) {
            conversation.addAll(history.asMessages());
        }
        conversation.add(media.isEmpty() ? LlmMessage.user(taskPrompt) : LlmMessage.userWithMedia(taskPrompt, media));

        List<ToolSpec> tools = new ArrayList<>();
        tools.add(executeTool());
        tools.add(new ToolSpec(RETURN_RESULT_TOOL, "Call this with the final answer once you have computed it.", SchemaGenerator.forReturnType(ctx.getReturnType())));

        List<String> errors = new ArrayList<>();
        int maxIterations = agent.getConfig().getMaxCodeActIterations();

        try (CodeExecutor sandbox = createSandbox(agent)) {
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                LlmResponse response = callLlm(llm, systemPrompt, conversation, tools, ctx, iteration);

                if (!response.hasToolCalls()) {
                    conversation.add(LlmMessage.assistant(response.getContent()));
                    conversation.add(LlmMessage.user("Call " + EXECUTE_TOOL + " to run code, or " + RETURN_RESULT_TOOL + " to finish."));
                    continue;
                }
                conversation.add(LlmMessage.assistantToolCalls(response.getContent(), response.getToolCalls()));

                for (ToolCall call : response.getToolCalls()) {
                    if (call.getName().equals(RETURN_RESULT_TOOL)) {
                        try {
                            return ObjectBinder.bindWrapped(call.getArguments(), ctx.getReturnType());
                        } catch (BindingException e) {
                            errors.add("Iteration " + iteration + ": " + e.getMessage());
                            conversation.add(LlmMessage.toolResult(call.getId(), "Invalid final answer: " + e.getMessage() + ". Call " + RETURN_RESULT_TOOL + " again."));
                            continue;
                        }
                    }
                    if (call.getName().equals(EXECUTE_TOOL)) {
                        String code = call.getArguments().optString("code", "");
                        SandboxResult result = runInSandbox(sandbox, code, iteration, ctx.getMethod().getName());
                        transcript.append("executed: ").append(code).append(" -> ").append(result.getStatus()).append("\n");
                        conversation.add(LlmMessage.toolResult(call.getId(), result.describe()));
                        continue;
                    }
                    conversation.add(LlmMessage.toolResult(call.getId(), "Error: unknown tool '" + call.getName() + "'"));
                }
            }
        }
        throw new GenerationException("CodeAct loop for " + ctx.getMethod() + " did not produce a final answer within " + maxIterations + " iterations.", errors, null);
    }

    private CodeExecutor createSandbox(Agent agent) {
        SandboxConfig config = agent.getConfig().getSandboxConfig();
        if (config.getMode() == SandboxMode.OUT_OF_PROCESS) {
            return new OutOfProcessCodeSandbox(agent, config.getTimeoutMillis(), config.getHeapMb());
        }
        return new CodeSandbox(agent, config.getTimeoutMillis());
    }

    private SandboxResult runInSandbox(CodeExecutor sandbox, String code, int iteration, String activeMethod) {
        Span span = Traced.begin("code_execution", "iteration " + iteration);
        SandboxResult result;
        if (CodeValidator.referencesSelfMethod(code, activeMethod)) {
            // NOOA only *warns* against calling the active generative method (see the task message
            // in nooa/src/nooa/strategies/codeact.py, "would recurse"); blocking it outright is a
            // deliberate divergence (DESIGN_NOTES.md section 23): a nested CODE_ACT run inside a
            // JShell eval does its own LLM calls, which the in-process watchdog's cooperative
            // jshell.stop() cannot preempt, so a small model that ignores the warning would turn
            // one bad call into a timeout instead of a recoverable tool error.
            result = SandboxResult.rejected(Collections.singletonList(
                    "Code references self." + activeMethod + "(...), the generative method currently executing; calling it would recursively "
                            + "re-enter this generation. Use the other methods on self to compute the result, then call " + RETURN_RESULT_TOOL + "."));
        } else {
            result = sandbox.execute(code);
        }
        if (span != null) {
            span.setAttribute("status", result.getStatus().name());
            span.setAttribute("code", code);
        }
        if (result.getStatus() == SandboxResult.Status.OK) {
            Traced.finish(span);
        } else if (span != null) {
            span.setError(result.describe());
            Traced.finish(span);
        }
        return result;
    }

    private ToolSpec executeTool() {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONObject codeProperty = new JSONObject();
        codeProperty.put("type", "string");
        properties.put("code", codeProperty);
        schema.put("properties", properties);
        JSONArray required = new JSONArray();
        required.add("code");
        schema.put("required", required);
        return new ToolSpec(EXECUTE_TOOL,
                "Executes one or more Java statements/expressions. A variable `self` referring to this agent is bound and persists "
                        + "across calls, along with any other variables you declare, for the rest of this session.",
                schema);
    }

    private String codeActInstructions(Agent agent, CallContext ctx) {
        // The anti-recursion sentence mirrors NOOA's task-message warning ("You are executing
        // `X` — Calling `self.X(...)` would recurse.", nooa/src/nooa/strategies/codeact.py):
        // describe() lists every generative method including the one currently executing, so
        // without it the prompt reads as an invitation to call self.<activeMethod>(...).
        String activeMethod = ctx.getMethod().getName();
        return "You can solve this task by writing and executing Java code via the " + EXECUTE_TOOL + " tool. A variable `self` of type "
                + agent.getClass().getSimpleName() + " is bound in the execution environment; call its visible methods directly, e.g. self.someMethod(...). "
                + "Available API on self:\n" + agent.describe()
                + "\nYou are executing `" + activeMethod + "` — calling self." + activeMethod + "(...) from generated code would recurse and is blocked; "
                + "compute the result with the other methods on self instead."
                + "\nWhen you have the final answer, call " + RETURN_RESULT_TOOL
                + " (do not just print it or return it from execute_java).";
    }

    private LlmResponse callLlm(LlmClient llm, String systemPrompt, List<LlmMessage> conversation, List<ToolSpec> tools, CallContext ctx, int iteration) {
        Span span = Traced.begin("llm_call", ctx.getMethod().getName() + " (codeact iteration " + iteration + ")");
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
            throw new GenerationException("LLM call failed on CodeAct iteration " + iteration + " for " + ctx.getMethod() + ": " + e.getMessage(), new ArrayList<String>(),
                    e);
        }
    }
}
