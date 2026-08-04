package org.jopenagent.memory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

/**
 * Real embeddings over {@code /v1/embeddings}, built entirely on {@code java.net.http.HttpClient}.
 * Works against the real OpenAI API and any OpenAI-compatible server, including a locally running
 * Ollama or LM Studio instance serving an embedding model (both expose an OpenAI-wire-compatible
 * {@code /v1/embeddings} endpoint), see {@link #forLmStudio}/{@link #forOllama}, mirroring
 * {@link org.jopenagent.llm.openai.OpenAiClient}'s equivalent factories for chat completions. This is
 * the "external API" option for {@link org.jopenagent.memory.MemoryManager}'s similarity search;
 * {@link HashingEmbedder} remains the zero-dependency, offline default.
 */
public final class OpenAiEmbedder implements Embedder {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient httpClient;
    private volatile int dimensions;

    public OpenAiEmbedder(String apiKey, String model) {
        this(apiKey, model, DEFAULT_BASE_URL, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /** Convenience factory for a local/remote Ollama server, which needs no API key. */
    public static OpenAiEmbedder forOllama(String model, String baseUrl) {
        return forLocalServer(model, baseUrl);
    }

    /** Convenience factory for a local LM Studio server (default {@code http://localhost:1234}), which needs no API key. */
    public static OpenAiEmbedder forLmStudio(String model, String baseUrl) {
        return forLocalServer(model, baseUrl != null ? baseUrl : "http://localhost:1234");
    }

    /** Generic factory for any local, keyless OpenAI-compatible embeddings server. */
    public static OpenAiEmbedder forLocalServer(String model, String baseUrl) {
        return new OpenAiEmbedder(null, model, baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /**
     * Full constructor. {@code apiKey} may be {@code null}/empty for keyless local servers (no
     * {@code Authorization} header is sent in that case); {@code baseUrl} should have no trailing
     * slash, since {@code /v1/embeddings} is appended verbatim. {@link #dimensions()} reports a
     * provisional {@code 1536} until the first real {@link #embed} call reveals the model's actual
     * vector size.
     */
    public OpenAiEmbedder(String apiKey, String model, String baseUrl, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.dimensions = 1536;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * Posts {@code text} to {@code {baseUrl}/v1/embeddings}, extracts the first (only) result's
     * embedding vector, updates {@link #dimensions()} to match its actual length, and returns it
     * L2-normalized via {@link VectorMath#normalize} so it's directly comparable with vectors from
     * any other {@link Embedder}. Throws {@link MemoryException} on a non-2xx response, I/O
     * failure, or interruption.
     */
    @Override
    public float[] embed(String text) {
        JSONObject body = buildRequestBody(text);
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/v1/embeddings")).timeout(Duration.ofSeconds(60))
                .header("content-type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MemoryException("Embedding request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemoryException("Embedding request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new MemoryException("Embedding API error " + response.statusCode() + ": " + response.body());
        }
        JSONObject json = new JSONObject(response.body());
        JSONArray dataArray = json.getJSONArray("data");
        JSONArray embeddingArray = dataArray.getJSONObject(0).getJSONArray("embedding");
        float[] vector = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            vector[i] = (float) embeddingArray.getDouble(i);
        }
        this.dimensions = vector.length;
        return VectorMath.normalize(vector);
    }

    /** Package-private so tests can verify wire-format request construction without a network call. */
    JSONObject buildRequestBody(String text) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", text != null ? text : "");
        return body;
    }
}
