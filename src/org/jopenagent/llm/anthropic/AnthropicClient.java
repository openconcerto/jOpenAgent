package org.jopenagent.llm.anthropic;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmException;
import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.llm.ToolCall;
import org.jopenagent.llm.ToolSpec;

/**
 * Anthropic Messages API ({@code POST /v1/messages}) client built entirely on
 * {@code java.net.http.HttpClient}, jOpenAgent's concrete Claude backend, playing the role NOOA gets
 * from LiteLLM's Anthropic adapter.
 *
 * <p>How the jOpenAgent request/response model maps onto Anthropic's wire format:
 * <ul>
 * <li>{@link LlmRequest#getSystemPrompt()} becomes the top-level {@code system} field rather than
 * a message turn (Anthropic has no {@code system}-role message).</li>
 * <li>{@link LlmMessage.Role#SYSTEM} turns beyond the first (there should be none in normal use,
 * but the mapping must not silently drop data) are folded into a {@code user} turn prefixed
 * {@code "[system note] "}, since Anthropic only accepts one system field per request.</li>
 * <li>Consecutive {@link LlmMessage.Role#TOOL} messages are merged into a single following
 * {@code user} turn whose content is a list of {@code tool_result} blocks, Anthropic requires
 * every tool result answering one assistant turn's tool calls to arrive together in one user
 * turn, unlike OpenAI's one-message-per-tool-result-turn model.</li>
 * <li>{@link ToolCall}s round-trip as {@code tool_use} content blocks (request side, when
 * replaying prior assistant turns) and are parsed back out of {@code tool_use} blocks in the
 * response; {@link ToolSpec} becomes a top-level {@code tools} entry with
 * {@code input_schema} = {@link ToolSpec#getParametersSchema()}.</li>
 * <li>{@link org.jopenagent.llm.media.Media} attachments become {@code image}/{@code document} content
 * blocks with a {@code base64} or {@code url} source. Anthropic's Messages API has no native
 * inline block type for audio/video, so {@link org.jopenagent.llm.media.Audio}/
 * {@link org.jopenagent.llm.media.Video} are sent as an {@code image}-shaped block, harmless for
 * genuine image bytes, but not real audio/video support (see design notes: that would require a
 * provider-specific upload API, out of scope here).</li>
 * <li>{@code stop_reason} and the {@code usage.input_tokens}/{@code usage.output_tokens} block map
 * directly onto {@link LlmResponse}'s finish reason and token counts.</li>
 * </ul>
 * This client does not stream; {@link #generate(LlmRequest)} always waits for the complete
 * response body.
 */
public final class AnthropicClient implements LlmClient {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;

    /** Client against the real hosted Anthropic API, with a default 30s connect timeout. */
    public AnthropicClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /** Client against a custom base URL (e.g. a proxy) and/or a caller-supplied {@link HttpClient} (e.g. for custom timeouts or a test double). */
    public AnthropicClient(String apiKey, String model, String baseUrl, HttpClient httpClient) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = Objects.requireNonNull(model, "model");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        JSONObject body = buildRequestBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/v1/messages")).timeout(Duration.ofSeconds(120)).header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("Anthropic request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Anthropic request interrupted", e);
        }
        if (httpResponse.statusCode() / 100 != 2) {
            throw new LlmException("Anthropic API error " + httpResponse.statusCode() + ": " + httpResponse.body());
        }
        JSONObject json;
        try {
            json = new JSONObject(httpResponse.body());
        } catch (RuntimeException e) {
            throw new LlmException("Could not parse Anthropic response: " + e.getMessage() + "\n" + httpResponse.body(), e);
        }
        return parseResponse(json);
    }

    /** Assembles the full Messages API request body from an {@link LlmRequest}; package-visible for direct unit testing of the wire mapping. */
    JSONObject buildRequestBody(LlmRequest request) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        if (request.getSystemPrompt() != null) {
            body.put("system", request.getSystemPrompt());
        }
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        body.put("messages", buildMessages(request.getMessages()));
        if (request.hasTools()) {
            body.put("tools", buildTools(request.getTools()));
        }
        return body;
    }

    /**
     * Translates jOpenAgent's flat {@link LlmMessage} list into Anthropic's {@code messages} array,
     * folding extra {@code SYSTEM} turns into {@code user} notes and merging consecutive
     * {@code TOOL} turns into one {@code tool_result}-block-bearing {@code user} turn (see class
     * javadoc).
     */
    private JSONArray buildMessages(List<LlmMessage> messages) {
        JSONArray result = new JSONArray();
        int i = 0;
        while (i < messages.size()) {
            LlmMessage msg = messages.get(i);
            if (msg.getRole() == LlmMessage.Role.SYSTEM) {
                // Anthropic has a single top-level "system" field (already sent separately);
                // any additional inline SYSTEM turns are folded into a user turn so they aren't dropped.
                result.add(textMessage("user", "[system note] " + msg.getContent()));
                i++;
            } else if (msg.getRole() == LlmMessage.Role.USER) {
                result.add(msg.hasMedia() ? userMessageWithMedia(msg) : textMessage("user", msg.getContent()));
                i++;
            } else if (msg.getRole() == LlmMessage.Role.ASSISTANT) {
                result.add(assistantMessage(msg));
                i++;
            } else {
                // TOOL: Anthropic requires every tool_result for one assistant turn to be content
                // blocks inside a single following "user" turn, so merge consecutive TOOL messages.
                JSONArray toolResultBlocks = new JSONArray();
                while (i < messages.size() && messages.get(i).getRole() == LlmMessage.Role.TOOL) {
                    LlmMessage toolMsg = messages.get(i);
                    JSONObject block = new JSONObject();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", toolMsg.getToolCallId());
                    block.put("content", toolMsg.getContent() != null ? toolMsg.getContent() : "");
                    toolResultBlocks.add(block);
                    i++;
                }
                JSONObject userTurn = new JSONObject();
                userTurn.put("role", "user");
                userTurn.put("content", toolResultBlocks);
                result.add(userTurn);
            }
        }
        return result;
    }

    private JSONObject textMessage(String role, String text) {
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        JSONArray content = new JSONArray();
        JSONObject block = new JSONObject();
        block.put("type", "text");
        block.put("text", text != null ? text : "");
        content.add(block);
        msg.put("content", content);
        return msg;
    }

    private JSONObject userMessageWithMedia(LlmMessage msg) {
        JSONObject out = new JSONObject();
        out.put("role", "user");
        JSONArray content = new JSONArray();
        for (org.jopenagent.llm.media.Media media : msg.getMedia()) {
            content.add(mediaBlock(media));
        }
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", msg.getContent());
            content.add(textBlock);
        }
        out.put("content", content);
        return out;
    }

    /** Converts one {@link org.jopenagent.llm.media.Media} attachment into an Anthropic {@code image}/{@code document} content block (see class javadoc for the audio/video caveat). */
    private JSONObject mediaBlock(org.jopenagent.llm.media.Media media) {
        JSONObject block = new JSONObject();
        JSONObject source = new JSONObject();
        if (media.isRemoteUrl()) {
            source.put("type", "url");
            source.put("url", media.getDataUrl());
        } else {
            source.put("type", "base64");
            source.put("media_type", media.getMediaType());
            source.put("data", media.base64Data());
        }
        if (media instanceof org.jopenagent.llm.media.FileAttachment) {
            block.put("type", "document");
        } else {
            // Anthropic's Messages API only defines "image"/"document" content blocks; audio/video
            // have no native inline block type, so they're sent as an image-shaped block (harmless
            // for image data), real audio/video support against Anthropic should go through a
            // provider-specific upload API, which is out of scope here (see design notes).
            block.put("type", "image");
        }
        block.put("source", source);
        return block;
    }

    private JSONObject assistantMessage(LlmMessage msg) {
        JSONObject out = new JSONObject();
        out.put("role", "assistant");
        JSONArray content = new JSONArray();
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", msg.getContent());
            content.add(textBlock);
        }
        for (ToolCall call : msg.getToolCalls()) {
            JSONObject block = new JSONObject();
            block.put("type", "tool_use");
            block.put("id", call.getId());
            block.put("name", call.getName());
            block.put("input", call.getArguments());
            content.add(block);
        }
        out.put("content", content);
        return out;
    }

    /** Maps each {@link ToolSpec} to an Anthropic top-level tool entry ({@code name}/{@code description}/{@code input_schema}). */
    private JSONArray buildTools(List<ToolSpec> tools) {
        JSONArray result = new JSONArray();
        for (ToolSpec tool : tools) {
            JSONObject t = new JSONObject();
            t.put("name", tool.getName());
            t.put("description", tool.getDescription());
            t.put("input_schema", tool.getParametersSchema());
            result.add(t);
        }
        return result;
    }

    /** Parses a Messages API response body into an {@link LlmResponse}, concatenating {@code text} blocks and collecting {@code tool_use} blocks as {@link ToolCall}s. */
    private LlmResponse parseResponse(JSONObject json) {
        StringBuilder textContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        JSONArray content = json.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                JSONObject block = content.getJSONObject(i);
                String type = block.optString("type", "");
                if ("text".equals(type)) {
                    textContent.append(block.optString("text", ""));
                } else if ("tool_use".equals(type)) {
                    JSONObject input = block.optJSONObject("input");
                    toolCalls.add(new ToolCall(block.optString("id", ""), block.optString("name", ""), input != null ? input : new JSONObject()));
                }
            }
        }
        String finishReason = json.optString("stop_reason", null);
        int inputTokens = 0;
        int outputTokens = 0;
        JSONObject usage = json.optJSONObject("usage");
        if (usage != null) {
            inputTokens = usage.optInt("input_tokens", 0);
            outputTokens = usage.optInt("output_tokens", 0);
        }
        return new LlmResponse(textContent.toString(), toolCalls, finishReason, inputTokens, outputTokens);
    }
}
