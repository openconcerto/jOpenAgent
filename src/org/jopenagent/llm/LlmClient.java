package org.jopenagent.llm;

/**
 * Model-agnostic LLM client abstraction: jOpenAgent's equivalent of NOOA's LiteLLM-backed
 * {@code UnifiedLLM}. Concrete implementations (org.jopenagent.llm.anthropic, org.jopenagent.llm.openai)
 * talk to a provider's HTTP API using only java.net.http.HttpClient.
 *
 * <p>Contract an implementation must satisfy: {@link #generate(LlmRequest)} is a single
 * synchronous, blocking round trip with no client-side conversation state, the entire turn
 * history lives in {@link LlmRequest#getMessages()} and must be re-sent every call. Every
 * {@link LlmMessage.Role} value must be translated to whatever the provider's wire format uses in
 * its place (a system prompt "field" vs. a system-role message, a single vs. multiple tool-result
 * turns per assistant turn, etc.); {@link ToolSpec}s must be exposed as native
 * function/tool-calling schema (not folded into prose) so the model can return {@link ToolCall}s;
 * and any {@link org.jopenagent.llm.media.Media} attached to a {@link LlmMessage} must be translated
 * into the provider's real multimodal content blocks, not dropped or stringified. Implementations
 * should throw {@link LlmException} (never a raw checked/unchecked I/O exception) for transport
 * failure, a non-2xx response, or provider output that cannot be parsed into an
 * {@link LlmResponse}.
 */
public interface LlmClient {

    /**
     * Sends one request and returns the model's response. Blocks for the full HTTP round trip;
     * implementations do not retry internally (retry-on-invalid-output, if any, is the caller's
     * responsibility, see {@code org.jopenagent.core.strategy.PredictStrategy}). Throws
     * {@link LlmException} on transport/API failure or unparseable provider output.
     */
    LlmResponse generate(LlmRequest request);

    /** The model identifier this client is configured for (e.g. "claude-sonnet-4-5", "gpt-4o-mini"). */
    String modelName();
}
