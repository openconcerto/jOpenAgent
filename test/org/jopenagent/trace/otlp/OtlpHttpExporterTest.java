package org.jopenagent.trace.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.trace.Tracer;

class OtlpHttpExporterTest {

    @Test
    void buildsWellFormedExportTraceServiceRequest() {
        org.jopenagent.trace.Span parent = Tracer.start("generation", "MyAgent.classify");
        org.jopenagent.trace.Span child = Tracer.start("llm_call", "attempt 1");
        child.setAttribute("inputTokens", 10);
        Tracer.end(child);
        Tracer.end(parent);
        Tracer.clear();

        OtlpHttpExporter exporter = new OtlpHttpExporter("http://unused.invalid/v1/traces");
        JSONObject request = exporter.buildExportRequest(parent);

        JSONArray resourceSpans = request.getJSONArray("resourceSpans");
        assertEquals(1, resourceSpans.size());
        JSONObject resource = resourceSpans.getJSONObject(0).getJSONObject("resource");
        assertEquals("service.name", resource.getJSONArray("attributes").getJSONObject(0).getString("key"));
        assertEquals("jooa", resource.getJSONArray("attributes").getJSONObject(0).getJSONObject("value").getString("stringValue"));

        JSONArray spans = resourceSpans.getJSONObject(0).getJSONArray("scopeSpans").getJSONObject(0).getJSONArray("spans");
        assertEquals(2, spans.size());
        String traceId = spans.getJSONObject(0).getString("traceId");
        assertEquals(32, traceId.length());
        assertEquals(traceId, spans.getJSONObject(1).getString("traceId"));
        assertEquals(spans.getJSONObject(0).getString("spanId"), spans.getJSONObject(1).getString("parentSpanId"));
        assertTrue(spans.getJSONObject(0).getString("spanId").length() == 16);
    }
}
