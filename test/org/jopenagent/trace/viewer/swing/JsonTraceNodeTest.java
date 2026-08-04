package org.jopenagent.trace.viewer.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.Test;

import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Tracer;

class JsonTraceNodeTest {

    @Test
    void adaptsSpanJsonFieldsAndChildren() {
        Span parent = Tracer.start("generation", "MyAgent.classify");
        Span child = Tracer.start("llm_call", "attempt 1");
        child.setAttribute("inputTokens", 42);
        Tracer.end(child);
        Tracer.end(parent);
        Tracer.clear();

        JSONObject json = JsonTraceExporter.toJson(parent);
        JsonTraceNode node = new JsonTraceNode(json);

        assertEquals("generation", node.getKind());
        assertEquals("MyAgent.classify", node.getName());
        assertEquals("OK", node.getStatus());
        assertFalse(node.isError());
        assertNull(node.getErrorMessage());
        assertEquals(1, node.getChildren().size());

        JsonTraceNode childNode = node.getChildren().get(0);
        assertEquals("llm_call", childNode.getKind());
        assertEquals("42", childNode.getAttributes().get("inputTokens"));
    }

    @Test
    void marksErrorStatus() {
        Span span = Tracer.start("llm_call", "failing");
        span.setError("boom");
        Tracer.end(span);
        Tracer.clear();

        JsonTraceNode node = new JsonTraceNode(JsonTraceExporter.toJson(span));

        assertTrue(node.isError());
        assertEquals("boom", node.getErrorMessage());
        assertTrue(node.label().contains("boom") || node.label().contains("⚠"));
    }

    @Test
    void handlesMissingChildrenGracefully() {
        JSONObject bare = new JSONObject();
        bare.put("kind", "tool_execution");
        bare.put("name", "noop");
        bare.put("children", new JSONArray());

        JsonTraceNode node = new JsonTraceNode(bare);

        assertTrue(node.getChildren().isEmpty());
        assertTrue(node.getAttributes().isEmpty());
    }
}
