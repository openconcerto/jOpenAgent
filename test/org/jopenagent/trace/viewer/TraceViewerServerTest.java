package org.jopenagent.trace.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jopenagent.eval.EvalCase;
import org.jopenagent.eval.EvalRunner;
import org.jopenagent.eval.EvalSuite;
import org.jopenagent.eval.EvalTask;
import org.jopenagent.eval.ExactMatchScorer;
import org.jopenagent.memory.HashingEmbedder;
import org.jopenagent.memory.InMemoryMemoryStore;
import org.jopenagent.memory.Memory;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Tracer;

/** Real HTTP round trip against a live server on an ephemeral port, not mocked. */
class TraceViewerServerTest {

    private TraceViewerServer viewer;

    @AfterEach
    void cleanup() {
        if (viewer != null) {
            viewer.stop();
        }
        Tracer.clear();
        Tracer.removeAllExporters();
    }

    @Test
    void servesCapturedTracesOverHttp() throws IOException, InterruptedException {
        viewer = new TraceViewerServer();
        Tracer.addExporter(viewer);
        viewer.start(0); // ephemeral port

        Span parent = Tracer.start("generation", "MyAgent.classify");
        Span child = Tracer.start("llm_call", "attempt 1");
        Tracer.end(child);
        Tracer.end(parent);

        assertEquals(1, viewer.traceCount());
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + viewer.port();

        HttpResponse<String> listResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/traces")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());
        JSONArray list = (JSONArray) org.jopenkit.json.JSON.parse(listResponse.body());
        assertEquals(1, list.size());
        String id = list.getJSONObject(0).getString("id");
        assertEquals("MyAgent.classify", list.getJSONObject(0).getString("name"));

        HttpResponse<String> detailResponse = http
                .send(HttpRequest.newBuilder(URI.create(base + "/api/trace?id=" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, detailResponse.statusCode());
        JSONObject detail = new JSONObject(detailResponse.body());
        assertEquals(1, detail.getJSONArray("children").size());

        HttpResponse<String> pageResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, pageResponse.statusCode());
        assertTrue(pageResponse.body().contains("jOpenAgent Trace Viewer"));
    }

    @Test
    void otlpIngestEndpointAcceptsPosts() throws IOException, InterruptedException {
        viewer = new TraceViewerServer();
        viewer.start(0);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + viewer.port();

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/v1/traces")).POST(HttpRequest.BodyPublishers.ofString("{\"resourceSpans\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(1, viewer.traceCount());
    }

    @Test
    void memoryRoutesReturn404WithoutAnAttachedStoreAndDataOnceAttached() throws IOException, InterruptedException {
        viewer = new TraceViewerServer();
        viewer.start(0);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + viewer.port();

        HttpResponse<String> before = http.send(HttpRequest.newBuilder(URI.create(base + "/api/memory/records")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, before.statusCode());

        InMemoryMemoryStore store = new InMemoryMemoryStore();
        HashingEmbedder embedder = new HashingEmbedder();
        store.save(new Memory(Memory.newId(), Memory.Type.INFO, "customer prefers email", 0.7, java.util.Set.of("contact"), embedder.embed("customer prefers email")));
        viewer.attachMemory(store);

        HttpResponse<String> records = http.send(HttpRequest.newBuilder(URI.create(base + "/api/memory/records")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, records.statusCode());
        JSONArray recordsArray = (JSONArray) org.jopenkit.json.JSON.parse(records.body());
        assertEquals(1, recordsArray.size());
        assertEquals("customer prefers email", recordsArray.getJSONObject(0).getString("content"));
        assertTrue(recordsArray.getJSONObject(0).getJSONArray("tags").contains("contact"));

        HttpResponse<String> stats = http.send(HttpRequest.newBuilder(URI.create(base + "/api/memory/stats")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stats.statusCode());
        JSONObject statsJson = new JSONObject(stats.body());
        assertEquals(1, statsJson.getInt("total"));
        assertEquals(0, statsJson.getInt("archived"));
    }

    @Test
    void annotationsCanBePostedThenListedByTraceIdAndTagsAreAggregated() throws IOException, InterruptedException {
        viewer = new TraceViewerServer();
        viewer.start(0);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + viewer.port();

        JSONObject annotation = new JSONObject();
        annotation.put("traceId", "trace-1");
        annotation.put("text", "looks slow, investigate");
        JSONArray tags = new JSONArray();
        tags.add("perf");
        annotation.put("tags", tags);

        HttpResponse<String> postResponse = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/annotations")).POST(HttpRequest.BodyPublishers.ofString(annotation.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, postResponse.statusCode());
        assertTrue(new JSONObject(postResponse.body()).has("timestamp"));

        HttpResponse<String> listResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/annotations?traceId=trace-1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listResponse.statusCode());
        JSONArray list = (JSONArray) org.jopenkit.json.JSON.parse(listResponse.body());
        assertEquals(1, list.size());
        assertEquals("looks slow, investigate", list.getJSONObject(0).getString("text"));

        HttpResponse<String> emptyListResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/annotations?traceId=unknown-trace")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(0, ((JSONArray) org.jopenkit.json.JSON.parse(emptyListResponse.body())).size());

        HttpResponse<String> tagsResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/tags")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, tagsResponse.statusCode());
        JSONArray tagsList = (JSONArray) org.jopenkit.json.JSON.parse(tagsResponse.body());
        assertEquals(1, tagsList.size());
        assertEquals("perf", tagsList.getString(0));
    }

    @Test
    void evalRoutesExposeExperimentsBuiltFromCapturedEvalCaseSpans() throws IOException, InterruptedException {
        viewer = new TraceViewerServer();
        Tracer.addExporter(viewer);
        viewer.start(0);

        EvalSuite<Integer> suite = EvalSuite.<Integer>builder("ticket-triage").addCase(EvalCase.builder("case-pass", constant(42)).expected(42).build())
                .addCase(EvalCase.builder("case-fail", constant(1)).expected(2).build()).build();
        EvalRunner.run(suite, new ExactMatchScorer<Integer>(), "gpt-x");

        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + viewer.port();

        HttpResponse<String> experimentsResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/eval/experiments")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, experimentsResponse.statusCode());
        JSONArray experiments = (JSONArray) org.jopenkit.json.JSON.parse(experimentsResponse.body());
        assertEquals(1, experiments.size());
        assertEquals("ticket-triage", experiments.getJSONObject(0).getString("id"));
        assertEquals(2, experiments.getJSONObject(0).getInt("testCount"));
        assertEquals(1, experiments.getJSONObject(0).getInt("passedCount"));

        HttpResponse<String> detailResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/eval/experiment?id=ticket-triage")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, detailResponse.statusCode());
        JSONObject detail = new JSONObject(detailResponse.body());
        assertEquals(2, detail.getJSONArray("results").size());

        HttpResponse<String> summaryResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/eval/summary?id=ticket-triage")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, summaryResponse.statusCode());
        JSONObject summary = new JSONObject(summaryResponse.body());
        assertEquals(2, summary.getInt("total"));
        assertEquals(1, summary.getInt("passed"));
        assertEquals(0.5, summary.getDouble("successRate"));
        assertTrue(summary.getJSONObject("byModel").has("gpt-x"));

        HttpResponse<String> missingResponse = http.send(HttpRequest.newBuilder(URI.create(base + "/api/eval/experiment?id=does-not-exist")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missingResponse.statusCode());
    }

    private static EvalTask<Integer> constant(int value) {
        return new EvalTask<Integer>() {
            @Override
            public Integer run() {
                return value;
            }
        };
    }
}
