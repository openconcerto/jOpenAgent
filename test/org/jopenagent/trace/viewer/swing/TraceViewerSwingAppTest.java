package org.jopenagent.trace.viewer.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.GraphicsEnvironment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import org.jopenagent.trace.Span;
import org.jopenagent.trace.Tracer;
import org.jopenagent.trace.viewer.TraceViewerServer;

/**
 * Real (non-mocked) UI smoke tests: builds and tears down an actual {@code JFrame} on the AWT
 * event dispatch thread. Skipped automatically in headless environments (CI containers without a
 * display) via {@link Assumptions#assumeFalse}, since a real display is required to construct
 * Swing components at all.
 */
class TraceViewerSwingAppTest {

    private TraceViewerSwingApp app;
    private TraceViewerServer server;

    @AfterEach
    void cleanup() {
        if (app != null) {
            app.dispose();
        }
        if (server != null) {
            server.stop();
        }
        Tracer.clear();
        Tracer.removeAllExporters();
    }

    @Test
    void liveModeCapturesTracesAsTheyComplete() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display available");

        app = new TraceViewerSwingApp();
        Tracer.addExporter(app);

        Span parent = Tracer.start("generation", "SmokeAgent.classify");
        Span child = Tracer.start("llm_call", "attempt 1");
        Tracer.end(child);
        Tracer.end(parent);

        app.show();
        // Let pending Swing UI-thread tasks (frame construction, list update) drain.
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // no-op: reaching here means the EDT processed everything queued before it.
            }
        });

        assertEquals(1, app.fullTraceByIdSizeForTest());
    }

    @Test
    void remoteModePollsAServerWithoutCrashing() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display available");

        server = new TraceViewerServer();
        Tracer.addExporter(server);
        server.start(0);

        Span span = Tracer.start("generation", "RemoteSmokeAgent.classify");
        Tracer.end(span);

        app = new TraceViewerSwingApp("http://localhost:" + server.port());
        app.show();
        Thread.sleep(500);
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                // no-op: drains the EDT queue after the initial refresh.
            }
        });
        // Reaching here without an exception is the assertion: construction, the HTTP fetch, and
        // Swing model updates all completed on the EDT/SwingWorker without throwing.
    }
}
