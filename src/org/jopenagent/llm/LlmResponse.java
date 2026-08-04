package org.jopenagent.llm;

import java.util.Collections;
import java.util.List;

/** A single response from an {@link LlmClient}. */
public final class LlmResponse {

    private final String content;
    private final List<ToolCall> toolCalls;
    private final String finishReason;
    private final int inputTokens;
    private final int outputTokens;

    /**
     * @param content the assistant's text content, if any (may be empty when the turn is pure tool calls)
     * @param toolCalls tool invocations the model requested this turn, translated from the provider's native format (Anthropic {@code tool_use} blocks, OpenAI {@code tool_calls})
     * @param finishReason the provider's raw stop/finish reason string (e.g. Anthropic's {@code stop_reason}, OpenAI's {@code finish_reason}). Values are provider-specific and not normalized
     * @param inputTokens prompt/input token count reported by the provider's usage block (0 if unreported)
     * @param outputTokens completion/output token count reported by the provider's usage block (0 if unreported)
     */
    public LlmResponse(String content, List<ToolCall> toolCalls, String finishReason, int inputTokens, int outputTokens) {
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.<ToolCall>emptyList();
        this.finishReason = finishReason;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public String getFinishReason() {
        return finishReason;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }
}
