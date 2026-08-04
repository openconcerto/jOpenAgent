package org.jopenagent.llm;

import java.util.Collections;
import java.util.List;

import org.jopenagent.llm.media.Media;

/** One turn in a conversation sent to an {@link LlmClient}. */
public final class LlmMessage {

    /**
     * Provider-agnostic turn role. {@code SYSTEM} beyond the first turn, and consecutive
     * {@code TOOL} turns, get provider-specific folding/merging in {@code AnthropicClient} and
     * {@code OpenAiClient} (e.g. Anthropic has a single top-level system field and requires all
     * tool results for one assistant turn to arrive as content blocks inside one following
     * {@code user} turn).
     */
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final List<Media> media;

    private LlmMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId, List<Media> media) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.<ToolCall>emptyList();
        this.toolCallId = toolCallId;
        this.media = media != null ? media : Collections.<Media>emptyList();
    }

    /** A plain-text system turn (typically the agent's rendered system prompt, plus any inline mid-conversation system notes). */
    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content, null, null, null);
    }

    /** A plain-text user turn with no attachments. */
    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, null, null, null);
    }

    /** A user turn carrying multimodal attachments (images/audio/video/files) alongside text. */
    public static LlmMessage userWithMedia(String content, List<Media> media) {
        return new LlmMessage(Role.USER, content, null, null, media);
    }

    /** A plain-text assistant turn (a prior model reply with no tool calls), for round-tripping conversation history. */
    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content, null, null, null);
    }

    /** A prior assistant turn that invoked one or more tools, so the model can see its own {@link ToolCall}s when the history is replayed. */
    public static LlmMessage assistantToolCalls(String content, List<ToolCall> calls) {
        return new LlmMessage(Role.ASSISTANT, content, calls, null, null);
    }

    /** The result of executing a previously requested {@link ToolCall}, correlated back to it via {@code toolCallId}. */
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, null, toolCallId, null);
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public List<Media> getMedia() {
        return media;
    }

    public boolean hasMedia() {
        return !media.isEmpty();
    }
}
