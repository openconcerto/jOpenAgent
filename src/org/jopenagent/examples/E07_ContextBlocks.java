package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;

/**
 * Mirrors nooa/examples/quickstart/08_context_blocks.py: {@code self.context} holds static blocks
 * (set once, cached, {@link #main}'s "plan" entry) and dynamic blocks (re-evaluated every time
 * the prompt is built: the "backlog" entry here, via {@link org.jopenagent.core.context.ContextApi#setDynamic}),
 * both rendered into the system prompt automatically by {@link org.jopenagent.core.PromptBuilder}.
 */
@SystemPrompt("You are a sprint-planning assistant.")
public class E07_ContextBlocks extends Agent {

    private int backlogSize = 5;

    public E07_ContextBlocks(AgentConfig config) {
        super(config);
    }

    public String formatBacklog() {
        return backlogSize + " item(s) remaining";
    }

    public void addBacklogItem() {
        backlogSize++;
    }

    /**
     * Relies entirely on {@code self.context}'s rendered blocks (both the static "plan" and
     * dynamic "backlog" entries set up in {@link #main}) rather than on any explicit parameter,
     * since context blocks are folded into the system prompt automatically by
     * {@link org.jopenagent.core.PromptBuilder}.
     */
    @Generative("Given the sprint plan and current backlog in context, suggest what to tackle next.")
    public String suggestNextStep() {
        return generate();
    }

    public static void main(String[] args) {
        E07_ContextBlocks agent = new E07_ContextBlocks(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());

        agent.context.put("plan", "Sprint goal: ship the billing export feature by Friday.");
        agent.context.setDynamic("backlog", "self.formatBacklog()");

        System.out.println(agent.suggestNextStep());

        agent.addBacklogItem();
        System.out.println("backlog block after adding an item: " + agent.context.get("backlog"));
    }
}
