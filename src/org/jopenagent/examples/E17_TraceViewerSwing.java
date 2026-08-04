package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.trace.Tracer;
import org.jopenagent.trace.viewer.swing.TraceViewerSwingApp;

/**
 * A native-desktop alternative to {@link E16_TraceViewer}'s browser UI: same underlying span
 * data, but rendered in a Swing {@code JFrame} (JDK built-in, no dependency, no browser needed).
 * Registers {@link TraceViewerSwingApp} directly as a {@code TraceExporter} \u2014 traces appear in
 * the list live, as each call completes.
 *
 * <p>{@link TraceViewerSwingApp} can also run in a "remote" mode
 * (<code>new TraceViewerSwingApp("http://localhost:5001")</code>) to browse a
 * {@link org.jopenagent.trace.viewer.TraceViewerServer} running in a different process \u2014 see its javadoc.
 */
@SystemPrompt("You write one-line jokes.")
public class E17_TraceViewerSwing extends Agent {

    public E17_TraceViewerSwing(AgentConfig config) {
        super(config);
    }

    /** A plain {@code PREDICT} call; as in {@link E16_TraceViewer}, the point of the example is how its trace is browsed in {@link #main}, not the capability itself. */
    @Generative("Write a short, family-friendly one-line joke about the given topic.")
    public String joke(String topic) {
        return generate(topic);
    }

    public static void main(String[] args) throws Exception {
        TraceViewerSwingApp viewer = new TraceViewerSwingApp();
        Tracer.addExporter(viewer);
        viewer.show();

        E17_TraceViewerSwing agent = new E17_TraceViewerSwing(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println(agent.joke("databases"));
        System.out.println(agent.joke("Mondays"));

        System.out.println("Trace viewer window opened \u2014 select a trace on the left, inspect its span tree on the right.");
        System.out.println("Press Enter here to close it.");
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        viewer.dispose();
    }
}
