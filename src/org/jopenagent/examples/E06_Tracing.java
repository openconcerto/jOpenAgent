package org.jopenagent.examples;

import java.io.File;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Tracer;

/**
 * Mirrors nooa/examples/quickstart/06_tracing.py. Every generation call is automatically wrapped
 * in a {@link org.jopenagent.trace.Span}; registering a {@link JsonTraceExporter} captures the full
 * parent-child tree as JSON: jOpenAgent has no bundled web trace viewer (see design notes), but the
 * exported JSON is enough to feed one, or to just print for inspection as done here.
 */
@SystemPrompt("You write short haikus.")
public class E06_Tracing extends Agent {

    public E06_Tracing(AgentConfig config) {
        super(config);
    }

    /**
     * The traced capability: every invocation is wrapped in a {@link org.jopenagent.trace.Span}
     * regardless of whether an exporter is registered, so attaching the {@link JsonTraceExporter}
     * in {@link #main} is enough to capture it.
     */
    @Generative("Write a haiku about the given topic.")
    public String haiku(String topic) {
        return generate(topic);
    }

    public static void main(String[] args) {
        JsonTraceExporter exporter = new JsonTraceExporter(new File("jooa-trace.jsonl"));
        Tracer.addExporter(exporter);

        E06_Tracing agent = new E06_Tracing(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println(agent.haiku("autumn rain"));

        System.out.println("--- captured trace (also appended to jooa-trace.jsonl) ---");
        for (org.jopenkit.json.JSONObject root : exporter.getCaptured()) {
            System.out.println(root.toString(2));
        }
    }
}
