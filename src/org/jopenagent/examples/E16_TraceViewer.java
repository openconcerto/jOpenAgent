package org.jopenagent.examples;

import java.io.IOException;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.trace.Tracer;
import org.jopenagent.trace.viewer.TraceViewerServer;

/**
 * jOpenAgent's equivalent of {@code nooa start-dev}: a small embedded web server (see
 * {@link TraceViewerServer}, built on the JDK's own {@code com.sun.net.httpserver}, no FastAPI,
 * no React build step) that lists captured traces and renders each as a span tree in the browser.
 * Runs a couple of generation calls, then waits for you to open the browser and press Enter.
 */
@SystemPrompt("You write one-line jokes.")
public class E16_TraceViewer extends Agent {

    public E16_TraceViewer(AgentConfig config) {
        super(config);
    }

    /** A plain {@code PREDICT} call; the point of the example is what {@link #main} does with its resulting trace, not the capability itself. */
    @Generative("Write a short, family-friendly one-line joke about the given topic.")
    public String joke(String topic) {
        return generate(topic);
    }

    public static void main(String[] args) throws IOException {
        TraceViewerServer viewer = new TraceViewerServer();
        Tracer.addExporter(viewer);
        viewer.start(5001);

        E16_TraceViewer agent = new E16_TraceViewer(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println(agent.joke("databases"));
        System.out.println(agent.joke("Mondays"));

        System.out.println("Trace viewer running at http://localhost:" + viewer.port() + " -- open it, then press Enter here to stop.");
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        viewer.stop();
    }
}
