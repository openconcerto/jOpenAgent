package org.jopenagent.trace.otlp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.trace.Span;
import org.jopenagent.trace.TraceExporter;
import org.jopenagent.trace.TraceException;

/**
 * Exports a span tree as an OTLP/HTTP JSON {@code ExportTraceServiceRequest}, the same wire
 * format NOOA's {@code exporters.otlp()} sends (nooa/src/nooa/tracing/exporters.py), so any
 * standard OTLP collector (Jaeger, Tempo, an OpenTelemetry Collector, etc.) can ingest jOpenAgent
 * traces. Built entirely on {@code java.net.http.HttpClient}.
 *
 * <p>{@code traceId} (32 hex chars) is derived from the root span's UUID (stripped of dashes,
 * which conveniently yields exactly 128 bits); each span's own {@code spanId} (16 hex chars) is
 * the first 16 hex characters of its own UUID. These are stable and unique per span, though, as
 * with jOpenAgent's tracing generally: not a byte-for-byte reimplementation of a real distributed
 * tracing SDK's ID allocation.
 */
public class OtlpHttpExporter implements TraceExporter {

    private final String endpointUrl;
    private final String serviceName;
    private final HttpClient httpClient;
    private final Map<String, String> extraHeaders;

    /** Posts to {@code endpointUrl} with the default {@code "jooa"} service name and no extra headers (no authentication), plain OTLP collectors typically need nothing more. */
    public OtlpHttpExporter(String endpointUrl) {
        this(endpointUrl, "jooa", java.util.Collections.<String, String>emptyMap());
    }

    /** Full-control constructor: lets a subclass (e.g. {@link LangfuseExporter}) override the reported {@code service.name} resource attribute and attach auth/other headers to every export POST. */
    public OtlpHttpExporter(String endpointUrl, String serviceName, Map<String, String> extraHeaders) {
        this.endpointUrl = endpointUrl;
        this.serviceName = serviceName;
        this.extraHeaders = extraHeaders;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * Builds the {@code ExportTraceServiceRequest} JSON for {@code rootSpan} via
     * {@link #buildExportRequest(Span)} and POSTs it synchronously to {@link #endpointUrl}. Throws
     * {@link TraceException} on a non-2xx response, an I/O failure, or interruption. This call
     * blocks the exporting thread (typically inside {@link org.jopenagent.trace.Tracer#end}) for the
     * duration of the HTTP request.
     */
    @Override
    public void export(Span rootSpan) {
        JSONObject payload = buildExportRequest(rootSpan);
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(endpointUrl)).timeout(Duration.ofSeconds(30)).header("content-type", "application/json");
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new TraceException("OTLP export to " + endpointUrl + " failed with status " + response.statusCode() + ": " + response.body(), null);
            }
        } catch (IOException e) {
            throw new TraceException("OTLP export to " + endpointUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TraceException("OTLP export to " + endpointUrl + " was interrupted", e);
        }
    }

    JSONObject buildExportRequest(Span rootSpan) {
        String traceId = hexId(rootSpan.getId(), 32);

        JSONObject resource = new JSONObject();
        JSONArray resourceAttributes = new JSONArray();
        resourceAttributes.add(stringAttribute("service.name", serviceName));
        resource.put("attributes", resourceAttributes);

        JSONArray spans = new JSONArray();
        collectSpans(rootSpan, traceId, null, spans);

        JSONObject scopeSpans = new JSONObject();
        JSONObject scope = new JSONObject();
        scope.put("name", "jooa");
        scopeSpans.put("scope", scope);
        scopeSpans.put("spans", spans);

        JSONArray scopeSpansArray = new JSONArray();
        scopeSpansArray.add(scopeSpans);

        JSONObject resourceSpans = new JSONObject();
        resourceSpans.put("resource", resource);
        resourceSpans.put("scopeSpans", scopeSpansArray);

        JSONArray resourceSpansArray = new JSONArray();
        resourceSpansArray.add(resourceSpans);

        JSONObject request = new JSONObject();
        request.put("resourceSpans", resourceSpansArray);
        return request;
    }

    private void collectSpans(Span span, String traceId, String parentSpanId, JSONArray out) {
        String spanId = hexId(span.getId(), 16);
        JSONObject otlpSpan = new JSONObject();
        otlpSpan.put("traceId", traceId);
        otlpSpan.put("spanId", spanId);
        if (parentSpanId != null) {
            otlpSpan.put("parentSpanId", parentSpanId);
        }
        otlpSpan.put("name", span.getName());
        otlpSpan.put("kind", 1); // SPAN_KIND_INTERNAL
        otlpSpan.put("startTimeUnixNano", String.valueOf(span.getStartEpochNanos()));
        otlpSpan.put("endTimeUnixNano", String.valueOf(span.getEndEpochNanos() >= 0 ? span.getEndEpochNanos() : span.getStartEpochNanos()));

        JSONArray attributes = new JSONArray();
        attributes.add(stringAttribute("jooa.kind", span.getKind()));
        for (Map.Entry<String, Object> entry : span.getAttributes().entrySet()) {
            attributes.add(stringAttribute(entry.getKey(), String.valueOf(entry.getValue())));
        }
        otlpSpan.put("attributes", attributes);

        JSONObject status = new JSONObject();
        status.put("code", "ERROR".equals(span.getStatus()) ? 2 : 1); // STATUS_CODE_ERROR : STATUS_CODE_OK
        if (span.getErrorMessage() != null) {
            status.put("message", span.getErrorMessage());
        }
        otlpSpan.put("status", status);

        out.add(otlpSpan);

        for (Span child : span.getChildren()) {
            collectSpans(child, traceId, spanId, out);
        }
    }

    private JSONObject stringAttribute(String key, String value) {
        JSONObject attribute = new JSONObject();
        attribute.put("key", key);
        JSONObject wrapped = new JSONObject();
        wrapped.put("stringValue", value != null ? value : "");
        attribute.put("value", wrapped);
        return attribute;
    }

    private static String hexId(String uuid, int length) {
        String hex = uuid.replace("-", "");
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < length) {
            sb.append(hex);
        }
        return sb.substring(0, length);
    }
}
