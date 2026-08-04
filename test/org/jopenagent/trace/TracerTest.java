package org.jopenagent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TracerTest {

    @AfterEach
    void cleanup() {
        Tracer.clear();
        Tracer.removeAllExporters();
        Tracer.setEnabled(true);
    }

    @Test
    void nestedSpansFormParentChildTree() {
        Span outer = Tracer.start("generation", "outer");
        Span inner = Tracer.start("llm_call", "inner");
        Tracer.end(inner);
        Tracer.end(outer);

        List<Span> roots = Tracer.getRootSpans();
        assertEquals(1, roots.size());
        Span root = roots.get(0);
        assertEquals("outer", root.getName());
        assertEquals(1, root.getChildren().size());
        assertEquals("inner", root.getChildren().get(0).getName());
        assertEquals(outer.getId(), root.getChildren().get(0).getParentId());
    }

    @Test
    void exportersAreCalledOnlyWhenRootSpanCompletes() {
        JsonTraceExporter exporter = new JsonTraceExporter();
        Tracer.addExporter(exporter);

        Span outer = Tracer.start("generation", "outer");
        Span inner = Tracer.start("llm_call", "inner");
        Tracer.end(inner);
        assertTrue(exporter.getCaptured().isEmpty(), "exporter should not fire until the root span ends");

        Tracer.end(outer);
        assertEquals(1, exporter.getCaptured().size());
        JSONObject json = exporter.getCaptured().get(0);
        assertEquals("outer", json.getString("name"));
    }

    @Test
    void tracedHelperNoOpsWhenDisabled() {
        Tracer.setEnabled(false);
        Span span = Traced.begin("generation", "should-not-exist");
        assertNull(span);
        Traced.finish(span);
        Traced.fail(span, new RuntimeException("ignored"));
    }

    @Test
    void errorStatusIsRecorded() {
        Span span = Tracer.start("llm_call", "failing");
        Traced.fail(span, new IllegalStateException("boom"));
        List<Span> roots = Tracer.getRootSpans();
        assertEquals("ERROR", roots.get(0).getStatus());
        assertEquals("boom", roots.get(0).getErrorMessage());
    }
}
