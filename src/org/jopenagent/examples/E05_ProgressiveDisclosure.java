package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.Hidden;
import org.jopenagent.core.SystemPrompt;

/**
 * Mirrors nooa/examples/quickstart/05_progressive_disclosure.py: {@link Agent#describe()} is the
 * {@code doc(self)} equivalent, letting an LLM (or a developer) discover an agent's fields and
 * methods at runtime. Visible-by-default rules from nooa/AGENTS.md "Visibility" apply: public
 * members show up automatically, {@code @Hidden} opts a public member out.
 */
@SystemPrompt("You are a project-tracking agent for {self.projectName}.")
public class E05_ProgressiveDisclosure extends Agent {

    /**
     * Consumed by the {@code {self.projectName}} placeholder in the class's {@link SystemPrompt}
     * and surfaced by {@link Agent#describe()}, a plain public field showing up automatically
     * under the visible-by-default rule.
     */
    public String projectName = "Apollo";

    /** Marked {@link Hidden} so it is excluded from both {@link Agent#describe()} and the LLM-visible surface, despite being public. */
    @Hidden
    public String internalNotes = "not shown to the LLM or to describe()";

    public E05_ProgressiveDisclosure(AgentConfig config) {
        super(config);
    }

    @Doc("Returns the number of open tasks.")
    public int openTaskCount() {
        return 7;
    }

    /**
     * Demonstrates {@code {doc(self)}} prompt templating: the instruction references the agent's
     * own discoverable capabilities, which {@link Agent#describe()} renders identically to what's
     * printed directly in {@link #main}.
     */
    @Generative("Write a one-paragraph status update for the project, using {doc(self)}-discovered capabilities if useful.")
    public String statusUpdate() {
        return generate();
    }

    public static void main(String[] args) {
        E05_ProgressiveDisclosure agent = new E05_ProgressiveDisclosure(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println("--- agent.describe() ---");
        System.out.println(agent.describe());
        System.out.println("--- generated status update ---");
        System.out.println(agent.statusUpdate());
    }
}
