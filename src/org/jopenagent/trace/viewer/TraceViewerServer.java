package org.jopenagent.trace.viewer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.eval.EvalTraceView;
import org.jopenagent.memory.Memory;
import org.jopenagent.memory.MemoryStore;
import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.TraceExporter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal embedded trace-viewer web app: jOpenAgent's equivalent of {@code nooa start-dev} /
 * {@code nooa/src/nooa/viewer/} (a FastAPI + React SPA in NOOA; see design notes for why that
 * full stack isn't ported). Built entirely on the JDK's own {@code com.sun.net.httpserver}
 * (module {@code jdk.httpserver}, resolved by default for classpath code, no extra dependency,
 * no build step for a frontend), serving:
 * <ul>
 * <li>{@code GET /}, a single self-contained HTML/JS page (no CDN, no build step) that lists
 * captured traces and renders the selected one as a span tree.</li>
 * <li>{@code GET /api/traces}, JSON summary list of captured traces.</li>
 * <li>{@code GET /api/trace?id=...}, full span tree JSON for one trace.</li>
 * <li>{@code POST /v1/traces}, a best-effort OTLP/HTTP JSON ingestion endpoint, so externally
 * exported OTLP traces (e.g. from {@link org.jopenagent.trace.otlp.OtlpHttpExporter}) show up here too.</li>
 * <li>{@code GET /api/memory/records} / {@code GET /api/memory/stats}, browse an attached
 * {@link MemoryStore} (see {@link #attachMemory(MemoryStore)}), NOOA's memory-browsing routes.</li>
 * <li>{@code GET /api/annotations?traceId=...}, {@code POST /api/annotations},
 * {@code GET /api/tags}, free-text annotations/tags on traces, kept in memory.</li>
 * <li>{@code GET /api/eval/experiments}, {@code GET /api/eval/experiment?id=...},
 * {@code GET /api/eval/summary?id=...}, jOpenAgent's equivalent of NOOA's
 * {@code nooa/src/nooa/viewer/eval_routes.py}: {@link org.jopenagent.eval.EvalRunner} runs each eval
 * case as its own {@code eval_case} root span carrying {@code experiment}/{@code eval.*}
 * attributes, so these routes need no separate results store. They rebuild experiment summaries
 * purely by scanning already-captured traces for that span kind.</li>
 * </ul>
 * Register an instance with {@link org.jopenagent.trace.Tracer#addExporter(TraceExporter)} to feed it
 * live traces, then call {@link #start(int)}. For a native desktop alternative to this browser
 * UI, see {@link org.jopenagent.trace.viewer.swing.TraceViewerSwingApp}.
 */
public final class TraceViewerServer implements TraceExporter {

    private final Map<String, JSONObject> tracesById = Collections.synchronizedMap(new LinkedHashMap<String, JSONObject>());
    private final Map<String, List<JSONObject>> annotationsByTraceId = Collections.synchronizedMap(new LinkedHashMap<String, List<JSONObject>>());
    private MemoryStore memoryStore;
    private HttpServer server;

    /** Stores {@code rootSpan} (converted via {@link JsonTraceExporter#toJson(Span)}) so it's servable from {@code /api/traces}/{@code /api/trace} once this instance is registered as a {@link org.jopenagent.trace.Tracer} exporter. */
    @Override
    public void export(Span rootSpan) {
        tracesById.put(rootSpan.getId(), JsonTraceExporter.toJson(rootSpan));
    }

    /** Attaches a memory store so {@code /api/memory/*} can browse it. Optional. Those routes 404 until this is called. */
    public TraceViewerServer attachMemory(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        return this;
    }

    /** Binds and starts the embedded {@code com.sun.net.httpserver} on {@code port}, wiring up every route documented on the class, using the caller's thread as the (synchronous, single-threaded) request executor. */
    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start trace viewer on port " + port, e);
        }
        server.createContext("/api/traces", new TracesListHandler());
        server.createContext("/api/trace", new TraceDetailHandler());
        server.createContext("/api/memory/records", new MemoryRecordsHandler());
        server.createContext("/api/memory/stats", new MemoryStatsHandler());
        server.createContext("/api/annotations", new AnnotationsHandler());
        server.createContext("/api/tags", new TagsHandler());
        server.createContext("/api/eval/experiments", new EvalExperimentsHandler());
        server.createContext("/api/eval/experiment", new EvalExperimentHandler());
        server.createContext("/api/eval/summary", new EvalSummaryHandler());
        server.createContext("/v1/traces", new OtlpIngestHandler());
        server.createContext("/", new StaticPageHandler());
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server != null ? server.getAddress().getPort() : -1;
    }

    public int traceCount() {
        return tracesById.size();
    }

    /** {@code GET /api/traces}, a lightweight summary (id/name/durationMillis/status) of every captured trace. */
    private final class TracesListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JSONArray list = new JSONArray();
            synchronized (tracesById) {
                for (Map.Entry<String, JSONObject> entry : tracesById.entrySet()) {
                    JSONObject summary = new JSONObject();
                    summary.put("id", entry.getKey());
                    summary.put("name", entry.getValue().optString("name", ""));
                    summary.put("durationMillis", entry.getValue().optDouble("durationMillis", 0));
                    summary.put("status", entry.getValue().optString("status", "OK"));
                    list.add(summary);
                }
            }
            writeJson(exchange, 200, list.toString());
        }
    }

    /** {@code GET /api/trace?id=...}, the full span tree JSON for one trace (404 if {@code id} is unknown). */
    private final class TraceDetailHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String id = extractParam(exchange.getRequestURI().getQuery(), "id");
            JSONObject trace = id != null ? tracesById.get(id) : null;
            if (trace == null) {
                writeJson(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            writeJson(exchange, 200, trace.toString());
        }
    }

    /** {@code GET /api/memory/records}, every {@link Memory} in the attached {@link MemoryStore}, via {@link #memoryToJson(Memory)} (404 until {@link #attachMemory(MemoryStore)} is called). */
    private final class MemoryRecordsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (memoryStore == null) {
                writeJson(exchange, 404, "{\"error\":\"no memory store attached (call attachMemory(...) first)\"}");
                return;
            }
            JSONArray records = new JSONArray();
            for (Memory memory : memoryStore.findAll()) {
                records.add(memoryToJson(memory));
            }
            writeJson(exchange, 200, records.toString());
        }
    }

    /** {@code GET /api/memory/stats}, record counts by {@link Memory} type plus an archived-count rollup, for the attached store (404 until one is attached). */
    private final class MemoryStatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (memoryStore == null) {
                writeJson(exchange, 404, "{\"error\":\"no memory store attached (call attachMemory(...) first)\"}");
                return;
            }
            List<Memory> all = memoryStore.findAll();
            Map<String, Integer> countsByType = new LinkedHashMap<>();
            int archivedCount = 0;
            for (Memory memory : all) {
                String type = memory.getType().name();
                countsByType.put(type, countsByType.getOrDefault(type, 0) + 1);
                if (memory.isArchived()) {
                    archivedCount++;
                }
            }
            JSONObject stats = new JSONObject();
            stats.put("total", all.size());
            stats.put("archived", archivedCount);
            JSONObject byType = new JSONObject();
            for (Map.Entry<String, Integer> entry : countsByType.entrySet()) {
                byType.put(entry.getKey(), entry.getValue());
            }
            stats.put("byType", byType);
            writeJson(exchange, 200, stats.toString());
        }
    }

    private JSONObject memoryToJson(Memory memory) {
        JSONObject json = new JSONObject();
        json.put("id", memory.getId());
        json.put("type", memory.getType().name());
        json.put("content", memory.getContent());
        json.put("importance", memory.getImportance());
        JSONArray tags = new JSONArray();
        for (String tag : memory.getTags()) {
            tags.add(tag);
        }
        json.put("tags", tags);
        json.put("accessCount", memory.getAccessCount());
        json.put("archived", memory.isArchived());
        json.put("createdAt", memory.getCreatedAt().toString());
        json.put("lastAccessedAt", memory.getLastAccessedAt().toString());
        return json;
    }

    /** {@code /api/annotations}, {@code GET ?traceId=...} lists free-text annotations for a trace; {@code POST} adds one ({@code traceId}+{@code text} required body fields). Kept in memory only, not persisted. */
    private final class AnnotationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String traceId = extractParam(exchange.getRequestURI().getQuery(), "traceId");
                JSONArray result = new JSONArray();
                if (traceId != null) {
                    List<JSONObject> annotations = annotationsByTraceId.get(traceId);
                    if (annotations != null) {
                        for (JSONObject annotation : annotations) {
                            result.add(annotation);
                        }
                    }
                }
                writeJson(exchange, 200, result.toString());
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JSONObject annotation;
                try {
                    annotation = new JSONObject(body);
                } catch (RuntimeException notJson) {
                    writeJson(exchange, 400, "{\"error\":\"invalid JSON body\"}");
                    return;
                }
                String traceId = annotation.optString("traceId", null);
                if (traceId == null || !annotation.has("text")) {
                    writeJson(exchange, 400, "{\"error\":\"body must include 'traceId' and 'text'\"}");
                    return;
                }
                annotation.put("timestamp", java.time.Instant.now().toString());
                synchronized (annotationsByTraceId) {
                    annotationsByTraceId.computeIfAbsent(traceId, new java.util.function.Function<String, List<JSONObject>>() {
                        @Override
                        public List<JSONObject> apply(String key) {
                            return new ArrayList<>();
                        }
                    }).add(annotation);
                }
                writeJson(exchange, 200, annotation.toString());
                return;
            }
            writeJson(exchange, 405, "{\"error\":\"method not allowed\"}");
        }
    }

    /** {@code GET /api/tags}. The distinct set of tags used across every recorded annotation. */
    private final class TagsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
            synchronized (annotationsByTraceId) {
                for (List<JSONObject> annotations : annotationsByTraceId.values()) {
                    for (JSONObject annotation : annotations) {
                        JSONArray tagArray = annotation.optJSONArray("tags");
                        if (tagArray == null) {
                            continue;
                        }
                        for (int i = 0; i < tagArray.size(); i++) {
                            tags.add(tagArray.getString(i));
                        }
                    }
                }
            }
            JSONArray result = new JSONArray();
            for (String tag : tags) {
                result.add(tag);
            }
            writeJson(exchange, 200, result.toString());
        }
    }

    // =========================================================================================
    // Eval harness routes: org.jopenagent.eval.EvalRunner marks each case's root span kind "eval_case"
    // and stamps it with an "experiment" attribute plus "eval.*" attributes (test_id, tier,
    // model, passed, score, duration_ms, and any case-level metadata). These routes need no
    // separate results store, they just filter/group the traces already captured above.
    // =========================================================================================

    private List<JSONObject> capturedTraces() {
        synchronized (tracesById) {
            return new ArrayList<>(tracesById.values());
        }
    }

    /** {@code GET /api/eval/experiments}, one summary row per experiment, built by scanning captured traces for {@code eval_case}-kind spans via {@link EvalTraceView#experimentSummaries}. */
    private final class EvalExperimentsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, EvalTraceView.experimentSummaries(capturedTraces()).toString());
        }
    }

    /** {@code GET /api/eval/experiment?id=...}, full per-case results for one experiment, via {@link EvalTraceView#experimentDetail} (400 if {@code id} is missing, 404 if unknown). */
    private final class EvalExperimentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String experimentId = extractParam(exchange.getRequestURI().getQuery(), "id");
            if (experimentId == null) {
                writeJson(exchange, 400, "{\"error\":\"missing 'id' query parameter\"}");
                return;
            }
            JSONObject detail = EvalTraceView.experimentDetail(capturedTraces(), experimentId);
            if (detail == null) {
                writeJson(exchange, 404, "{\"error\":\"experiment '" + experimentId + "' not found\"}");
                return;
            }
            writeJson(exchange, 200, detail.toString());
        }
    }

    /** {@code GET /api/eval/summary?id=...}, pass-rate/tier/model rollup for one experiment, via {@link EvalTraceView#experimentSummaryStats} (400 if {@code id} is missing, 404 if unknown). */
    private final class EvalSummaryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String experimentId = extractParam(exchange.getRequestURI().getQuery(), "id");
            if (experimentId == null) {
                writeJson(exchange, 400, "{\"error\":\"missing 'id' query parameter\"}");
                return;
            }
            JSONObject stats = EvalTraceView.experimentSummaryStats(capturedTraces(), experimentId);
            if (stats == null) {
                writeJson(exchange, 404, "{\"error\":\"experiment '" + experimentId + "' not found\"}");
                return;
            }
            writeJson(exchange, 200, stats.toString());
        }
    }

    /** {@code POST /v1/traces}, best-effort OTLP/HTTP JSON ingestion: wraps the raw payload under a synthetic trace id so externally-exported traces (e.g. from {@link org.jopenagent.trace.otlp.OtlpHttpExporter}) are listable here too; malformed bodies are silently dropped. */
    private final class OtlpIngestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                JSONObject payload = new JSONObject(body);
                String syntheticId = "otlp-" + System.nanoTime();
                JSONObject wrapper = new JSONObject();
                wrapper.put("name", "OTLP ingest");
                wrapper.put("durationMillis", 0);
                wrapper.put("status", "OK");
                wrapper.put("attributes", new JSONObject());
                wrapper.put("children", new JSONArray());
                wrapper.put("raw", payload);
                tracesById.put(syntheticId, wrapper);
            } catch (RuntimeException notJson) {
                // best-effort ingestion; malformed payloads are simply dropped
            }
            writeJson(exchange, 200, "{}");
        }
    }

    /** {@code GET /}, serves the single static {@link TraceViewerPage#HTML} page. */
    private final class StaticPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = TraceViewerPage.HTML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        }
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String extractParam(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
