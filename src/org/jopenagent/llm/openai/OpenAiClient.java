package org.jopenagent.llm.openai;

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
 * Chat-Completions-compatible client ({@code POST /v1/chat/completions}) built entirely on
 * {@code java.net.http.HttpClient}. Works against the real OpenAI API and any OpenAI-compatible
 * server, including a locally running Ollama or LM Studio instance (both expose an
 * OpenAI-wire-compatible {@code /v1/chat/completions} endpoint), making this jOpenAgent's one client
 * for "OpenAI", "Ollama", and "LM Studio" alike.
 *
 * <p>How the jOpenAgent request/response model maps onto the Chat Completions wire format:
 * <ul>
 * <li>{@link LlmRequest#getSystemPrompt()} becomes a leading {@code system}-role message
 * (unlike Anthropic's separate top-level {@code system} field), and every
 * {@link LlmMessage.Role} (including {@code SYSTEM}/{@code TOOL}) maps 1:1 onto its own message 
 * no folding or merging is needed here since Chat Completions allows one tool-result message per
 * tool call, each correlated back to its call via {@code tool_call_id}.</li>
 * <li>{@link ToolCall}s become OpenAI {@code tool_calls} entries (type {@code "function"}) with
 * JSON-encoded string {@code arguments}, and are parsed back out of
 * {@code message.tool_calls[].function} in the response, re-parsing that arguments string into a
 * JSON object; {@link ToolSpec} becomes a {@code {"type": "function", "function": {...}}} wrapper
 * with {@code parameters} = {@link ToolSpec#getParametersSchema()}.</li>
 * <li>{@link org.jopenagent.llm.media.Media} attachments become content-array blocks:
 * {@link org.jopenagent.llm.media.Audio} as {@code input_audio} (base64 data + {@link
 * org.jopenagent.llm.media.Audio#formatToken()}), {@link org.jopenagent.llm.media.FileAttachment} as
 * {@code file} (raw data URL + filename), and {@link org.jopenagent.llm.media.Image} plus
 * (best-effort) {@link org.jopenagent.llm.media.Video} as {@code image_url}.</li>
 * <li>{@code choices[0].finish_reason} and {@code usage.prompt_tokens}/
 * {@code usage.completion_tokens} map directly onto {@link LlmResponse}'s finish reason and token
 * counts; a response with no {@code choices} is treated as a hard {@link LlmException}.</li>
 * </ul>
 * This client does not stream; {@link #generate(LlmRequest)} always waits for the complete
 * response body. Local servers (Ollama/LM Studio) get a much longer request timeout than the
 * hosted API default, since local CPU/consumer-GPU inference, especially multi-turn
 * tool-calling/CodeAct loops: can be far slower than a hosted API round trip.
 */
public final class OpenAiClient implements LlmClient {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;
    private static final int LOCAL_SERVER_REQUEST_TIMEOUT_SECONDS = 600;

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final int requestTimeoutSeconds;

    /** Client against the real hosted OpenAI API, with a default 30s connect / 120s request timeout. */
    public OpenAiClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    /** Convenience factory for a local/remote Ollama server, which needs no API key. */
    public static OpenAiClient forOllama(String model, String baseUrl) {
        return forLocalServer(model, baseUrl);
    }

    /** Convenience factory for a local LM Studio server (default {@code http://localhost:1234}), which needs no API key. */
    public static OpenAiClient forLmStudio(String model, String baseUrl) {
        return forLocalServer(model, baseUrl != null ? baseUrl : "http://localhost:1234");
    }

    /**
     * Generic factory for any local, keyless OpenAI-compatible server. Uses a longer request
     * timeout than the hosted-API default since local inference (especially multi-turn
     * tool-calling/CodeAct loops on a CPU/consumer-GPU box) can be much slower than a hosted API.
     */
    public static OpenAiClient forLocalServer(String model, String baseUrl) {
        return new OpenAiClient(null, model, baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(), LOCAL_SERVER_REQUEST_TIMEOUT_SECONDS);
    }

    /** Client against a custom base URL and/or caller-supplied {@link HttpClient}, using the default hosted-API request timeout. */
    public OpenAiClient(String apiKey, String model, String baseUrl, HttpClient httpClient) {
        this(apiKey, model, baseUrl, httpClient, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    /** Full constructor: {@code apiKey} may be {@code null}/empty for keyless local servers, in which case no {@code Authorization} header is sent. */
    public OpenAiClient(String apiKey, String model, String baseUrl, HttpClient httpClient, int requestTimeoutSeconds) {
        this.apiKey = apiKey;
        this.model = Objects.requireNonNull(model, "model");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        JSONObject body = buildRequestBody(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/v1/chat/completions")).timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("content-type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmException("OpenAI-compatible request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("OpenAI-compatible request interrupted", e);
        }
        if (httpResponse.statusCode() / 100 != 2) {
            throw new LlmException("OpenAI-compatible API error " + httpResponse.statusCode() + ": " + httpResponse.body());
        }
        JSONObject json;
        try {
            json = new JSONObject(httpResponse.body());
        } catch (RuntimeException e) {
            throw new LlmException("Could not parse response: " + e.getMessage() + "\n" + httpResponse.body(), e);
        }
        return parseResponse(json);
    }

    /** Assembles the full Chat Completions request body from an {@link LlmRequest}; package-visible for direct unit testing of the wire mapping. */
    JSONObject buildRequestBody(LlmRequest request) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        body.put("messages", buildMessages(request));
        if (request.hasTools()) {
            body.put("tools", buildTools(request.getTools()));
        }
        return body;
    }

    /**
     * Translates jOpenAgent's flat {@link LlmMessage} list into Chat Completions' {@code messages}
     * array: a leading {@code system} message for {@link LlmRequest#getSystemPrompt()}, then one
     * message per {@link LlmMessage} with no folding/merging (unlike Anthropic, see
     * {@code AnthropicClient} for the contrast).
     */
    private JSONArray buildMessages(LlmRequest request) {
        JSONArray result = new JSONArray();
        if (request.getSystemPrompt() != null) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", request.getSystemPrompt());
            result.add(sys);
        }
        for (LlmMessage msg : request.getMessages()) {
            JSONObject m = new JSONObject();
            if (msg.getRole() == LlmMessage.Role.SYSTEM) {
                m.put("role", "system");
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
            } else if (msg.getRole() == LlmMessage.Role.USER) {
                m.put("role", "user");
                m.put("content", msg.hasMedia() ? userContentWithMedia(msg) : (Object) (msg.getContent() != null ? msg.getContent() : ""));
            } else if (msg.getRole() == LlmMessage.Role.ASSISTANT) {
                m.put("role", "assistant");
                if (msg.getContent() != null) {
                    m.put("content", msg.getContent());
                }
                if (!msg.getToolCalls().isEmpty()) {
                    JSONArray calls = new JSONArray();
                    for (ToolCall call : msg.getToolCalls()) {
                        JSONObject c = new JSONObject();
                        c.put("id", call.getId());
                        c.put("type", "function");
                        JSONObject fn = new JSONObject();
                        fn.put("name", call.getName());
                        fn.put("arguments", call.getArguments().toString());
                        c.put("function", fn);
                        calls.add(c);
                    }
                    m.put("tool_calls", calls);
                }
            } else {
                m.put("role", "tool");
                m.put("tool_call_id", msg.getToolCallId());
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
            }
            result.add(m);
        }
        return result;
    }

    /** Builds a user turn's content array (text block plus one block per attached {@link org.jopenagent.llm.media.Media}) when the message carries attachments. */
    private JSONArray userContentWithMedia(LlmMessage msg) {
        JSONArray content = new JSONArray();
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", msg.getContent());
            content.add(textBlock);
        }
        for (org.jopenagent.llm.media.Media media : msg.getMedia()) {
            content.add(mediaBlock(media));
        }
        return content;
    }

    /** Converts one {@link org.jopenagent.llm.media.Media} attachment into its Chat Completions content block (see class javadoc for the per-type mapping). */
    private JSONObject mediaBlock(org.jopenagent.llm.media.Media media) {
        if (media instanceof org.jopenagent.llm.media.Audio) {
            org.jopenagent.llm.media.Audio audio = (org.jopenagent.llm.media.Audio) media;
            JSONObject block = new JSONObject();
            block.put("type", "input_audio");
            JSONObject inputAudio = new JSONObject();
            inputAudio.put("data", audio.base64Data() != null ? audio.base64Data() : "");
            inputAudio.put("format", audio.formatToken());
            block.put("input_audio", inputAudio);
            return block;
        }
        if (media instanceof org.jopenagent.llm.media.FileAttachment) {
            org.jopenagent.llm.media.FileAttachment file = (org.jopenagent.llm.media.FileAttachment) media;
            JSONObject block = new JSONObject();
            block.put("type", "file");
            JSONObject fileObj = new JSONObject();
            fileObj.put("file_data", file.getDataUrl());
            fileObj.put("filename", file.getFilename() != null ? file.getFilename() : "attachment");
            block.put("file", fileObj);
            return block;
        }
        // Image and (best-effort) Video both use the image_url content block.
        JSONObject block = new JSONObject();
        block.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", media.getDataUrl());
        block.put("image_url", imageUrl);
        return block;
    }

    /** Maps each {@link ToolSpec} to an OpenAI {@code {"type": "function", "function": {...}}} tool entry. */
    private JSONArray buildTools(List<ToolSpec> tools) {
        JSONArray result = new JSONArray();
        for (ToolSpec tool : tools) {
            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", tool.getName());
            fn.put("description", tool.getDescription());
            fn.put("parameters", tool.getParametersSchema());
            wrapper.put("function", fn);
            result.add(wrapper);
        }
        return result;
    }

    /**
     * Parses a Chat Completions response body into an {@link LlmResponse}, taking the first
     * {@code choices} entry, re-parsing each {@code tool_calls[].function.arguments} JSON string
     * back into a {@link ToolCall}'s structured arguments. Throws {@link LlmException} if
     * {@code choices} is missing or empty.
     */
    private LlmResponse parseResponse(JSONObject json) {
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("Response had no choices: " + json);
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String content = message.has("content") && !message.isNull("content") ? message.optString("content", "") : "";
        List<ToolCall> toolCalls = new ArrayList<>();
        JSONArray rawToolCalls = message.optJSONArray("tool_calls");
        if (rawToolCalls != null) {
            for (int i = 0; i < rawToolCalls.size(); i++) {
                JSONObject call = rawToolCalls.getJSONObject(i);
                JSONObject fn = call.getJSONObject("function");
                String argsText = fn.optString("arguments", "{}");
                JSONObject args;
                try {
                    args = new JSONObject(argsText.isEmpty() ? "{}" : argsText);
                } catch (RuntimeException e) {
                    args = new JSONObject();
                }
                toolCalls.add(new ToolCall(call.optString("id", ""), fn.optString("name", ""), args));
            }
        }
        String finishReason = choice.optString("finish_reason", null);
        int promptTokens = 0;
        int completionTokens = 0;
        JSONObject usage = json.optJSONObject("usage");
        if (usage != null) {
            promptTokens = usage.optInt("prompt_tokens", 0);
            completionTokens = usage.optInt("completion_tokens", 0);
        }
        return new LlmResponse(content, toolCalls, finishReason, promptTokens, completionTokens);
    }
}
