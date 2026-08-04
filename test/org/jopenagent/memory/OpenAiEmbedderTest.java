package org.jopenagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Exercises OpenAiEmbedder against a real local HTTP server (not mocked), the same "test for real" approach used for the other HTTP clients in this codebase. */
class OpenAiEmbedderTest {

    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startFakeEmbeddingsServer(FakeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", handler);
        server.setExecutor(null);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void embedSendsModelAndInputAndParsesTheReturnedVector() throws IOException {
        FakeHandler handler = new FakeHandler(new float[] { 3f, 4f });
        String baseUrl = startFakeEmbeddingsServer(handler);

        OpenAiEmbedder embedder = new OpenAiEmbedder("sk-test", "text-embedding-3-small", baseUrl, java.net.http.HttpClient.newHttpClient());
        float[] vector = embedder.embed("hello world");

        assertEquals("text-embedding-3-small", handler.capturedRequestBody.getString("model"));
        assertEquals("hello world", handler.capturedRequestBody.getString("input"));
        assertEquals("Bearer sk-test", handler.capturedAuthHeader);

        // 3,4 normalized is 0.6,0.8
        assertEquals(0.6, vector[0], 0.0001);
        assertEquals(0.8, vector[1], 0.0001);
        assertEquals(2, embedder.dimensions());
    }

    @Test
    void localServerFactoriesSendNoAuthorizationHeader() throws IOException {
        FakeHandler handler = new FakeHandler(new float[] { 1f, 0f });
        String baseUrl = startFakeEmbeddingsServer(handler);

        OpenAiEmbedder embedder = OpenAiEmbedder.forLocalServer("nomic-embed-text", baseUrl);
        embedder.embed("no api key needed");

        assertNull(handler.capturedAuthHeader);
    }

    @Test
    void nonSuccessStatusThrowsMemoryException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] body = "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.setExecutor(null);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        OpenAiEmbedder embedder = new OpenAiEmbedder("bad-key", "text-embedding-3-small", baseUrl, java.net.http.HttpClient.newHttpClient());
        MemoryException exception = assertThrows(MemoryException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                embedder.embed("anything");
            }
        });
        assertTrue(exception.getMessage().contains("401"));
    }

    @Test
    void forOllamaReachesTheGivenBaseUrlWithNoAuthHeader() throws IOException {
        FakeHandler handler = new FakeHandler(new float[] { 0f, 1f });
        String baseUrl = startFakeEmbeddingsServer(handler);

        OpenAiEmbedder embedder = OpenAiEmbedder.forOllama("nomic-embed-text", baseUrl);
        embedder.embed("reaches the fake ollama server");

        assertEquals("nomic-embed-text", handler.capturedRequestBody.getString("model"));
        assertNull(handler.capturedAuthHeader);
    }

    private static final class FakeHandler implements HttpHandler {
        private final float[] vectorToReturn;
        private JSONObject capturedRequestBody;
        private String capturedAuthHeader;

        FakeHandler(float[] vectorToReturn) {
            this.vectorToReturn = vectorToReturn;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRequestBody = new JSONObject(requestBody);
            capturedAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");

            JSONArray embeddingArray = new JSONArray();
            for (float value : vectorToReturn) {
                embeddingArray.add(value);
            }
            JSONObject dataItem = new JSONObject();
            dataItem.put("embedding", embeddingArray);
            JSONArray data = new JSONArray();
            data.add(dataItem);
            JSONObject responseJson = new JSONObject();
            responseJson.put("data", data);

            byte[] bytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
