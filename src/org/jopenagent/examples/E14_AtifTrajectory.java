package org.jopenagent.examples;

import java.io.File;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.trace.Tracer;
import org.jopenagent.trace.atif.AtifExporter;

/**
 * Mirrors nooa/examples/quickstart/14_atif_trajectory.py: exports one generation call as an
 * ATIF-v1.7 trajectory JSON document (see {@link AtifExporter}): a common interchange format
 * that other tooling (an eval harness, a trace viewer) could consume, distinct from jOpenAgent's own
 * plain span-tree JSON export shown in {@link E06_Tracing}.
 */
@SystemPrompt("You are a warehouse assistant.")
public class E14_AtifTrajectory extends Agent {

    public E14_AtifTrajectory(AgentConfig config) {
        super(config);
    }

    @Doc("Returns the current pallet count in bay 3.")
    public int palletCountBay3() {
        return 17;
    }

    /**
     * The traced capability whose resulting {@code generation} span (and its
     * {@code tool_execution} child, from calling {@link #palletCountBay3()} under
     * {@code TOOL_CALLING}) is exported as a single ATIF trajectory document in {@link #main}.
     */
    @Generative(value = "Report the pallet count in bay 3 using the available tool.", strategy = StrategyKind.TOOL_CALLING)
    public String reportPalletCount() {
        return generate();
    }

    public static void main(String[] args) {
        AtifExporter exporter = new AtifExporter(new File("jooa-trajectory.atif.jsonl")).withSessionId("demo-session");
        Tracer.addExporter(exporter);

        E14_AtifTrajectory agent = new E14_AtifTrajectory(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println(agent.reportPalletCount());

        System.out.println("--- ATIF trajectory (also appended to jooa-trajectory.atif.jsonl) ---");
        System.out.println(exporter.getCaptured().get(0).toString(2));
    }
}
