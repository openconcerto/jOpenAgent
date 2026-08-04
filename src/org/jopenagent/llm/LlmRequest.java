package org.jopenagent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A single request to an {@link LlmClient}: system prompt, message history, optional tools. */
public final class LlmRequest {

    private final String systemPrompt;
    private final List<LlmMessage> messages;
    private final List<ToolSpec> tools;
    private final Double temperature;
    private final int maxTokens;

    private LlmRequest(Builder builder) {
        this.systemPrompt = builder.systemPrompt;
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    public List<ToolSpec> getTools() {
        return tools;
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public Double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link LlmRequest}; {@code maxTokens} defaults to 4096 if never set. */
    public static final class Builder {
        private String systemPrompt;
        private final List<LlmMessage> messages = new ArrayList<>();
        private final List<ToolSpec> tools = new ArrayList<>();
        private Double temperature;
        private int maxTokens = 4096;

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder addMessage(LlmMessage message) {
            this.messages.add(message);
            return this;
        }

        public Builder addMessages(List<LlmMessage> toAdd) {
            this.messages.addAll(toAdd);
            return this;
        }

        public Builder addTool(ToolSpec tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder addTools(List<ToolSpec> toAdd) {
            this.tools.addAll(toAdd);
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public LlmRequest build() {
            return new LlmRequest(this);
        }
    }
}
