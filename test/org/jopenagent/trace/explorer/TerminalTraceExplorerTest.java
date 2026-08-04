package org.jopenagent.trace.explorer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jopenagent.trace.Span;
import org.jopenagent.trace.Tracer;

class TerminalTraceExplorerTest {

    @AfterEach
    void cleanup() {
        Tracer.clear();
    }

    @Test
    void printsNestedSpansWithAttributesAndErrors() {
        Span parent = Tracer.start("generation", "MyAgent.classify");
        Span child = Tracer.start("llm_call", "attempt 1");
        child.setAttribute("inputTokens", 42);
        child.setError("boom");
        Tracer.end(child);
        Tracer.end(parent);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TerminalTraceExplorer.print(parent, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String output = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(output.contains("MyAgent.classify"));
        assertTrue(output.contains("attempt 1"));
        assertTrue(output.contains("inputTokens = 42"));
        assertTrue(output.contains("boom"));
        assertTrue(output.contains("ERROR"));
    }
}
