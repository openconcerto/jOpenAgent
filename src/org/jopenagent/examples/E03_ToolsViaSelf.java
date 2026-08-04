package org.jopenagent.examples;

import java.util.HashMap;
import java.util.Map;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;

/**
 * Mirrors nooa/examples/quickstart/03_codeact_tools.py's "methods as tools" idea. This uses
 * {@code @UseStrategy... strategy = StrategyKind.TOOL_CALLING}: {@link org.jopenagent.tools.ToolRegistry}
 * turns every visible ordinary method into a JSON-schema tool the provider calls natively, in
 * addition to the default code-as-action mode shown in {@link E08_CodeAsAction}.
 */
@SystemPrompt("You are an inventory assistant.")
public class E03_ToolsViaSelf extends Agent {

    private final Map<String, Integer> inventory = new HashMap<>();

    public E03_ToolsViaSelf(AgentConfig config) {
        super(config);
        inventory.put("widget", 12);
        inventory.put("gadget", 0);
        inventory.put("gizmo", 5);
    }

    @Doc("Returns the current stock count for an item, or 0 if unknown.")
    public int getStock(String item) {
        return inventory.getOrDefault(item, 0);
    }

    @Doc("Returns the full list of known item names.")
    public java.util.List<String> knownItems() {
        return new java.util.ArrayList<>(inventory.keySet());
    }

    /**
     * Answers using {@code TOOL_CALLING}: {@link org.jopenagent.tools.ToolRegistry} exposes
     * {@link #getStock} and {@link #knownItems} to the model as native JSON-schema tools, so the
     * model decides which (if any) to call before producing its final answer.
     */
    @Generative(value = "Answer the user's question about inventory using the available tools.", strategy = StrategyKind.TOOL_CALLING)
    public String answer(String question) {
        return generate(question);
    }

    public static void main(String[] args) {
        E03_ToolsViaSelf agent = new E03_ToolsViaSelf(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println(agent.answer("Do we have any gadgets in stock, and what else do we carry?"));
    }
}
