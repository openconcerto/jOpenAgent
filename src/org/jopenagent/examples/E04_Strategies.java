package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;

/**
 * Mirrors nooa/examples/quickstart/04_strategies.py: the same kind of task solved with each of
 * jOpenAgent's three strategies. PREDICT (jOpenAgent's default) is a single structured-output call; CODE_ACT
 * lets the LLM compute the answer via generated code calling {@code self} methods; TOOL_CALLING
 * uses native function-calling against {@code self}'s ordinary methods. See nooa/AGENTS.md
 * "Strategy Selection" for NOOA's own guidance on when to use which.
 */
@SystemPrompt("You are a careful arithmetic assistant.")
public class E04_Strategies extends Agent {

    public E04_Strategies(AgentConfig config) {
        super(config);
    }

    @Doc("Returns the unit price in cents for the given SKU.")
    public int unitPriceCents(String sku) {
        return "WIDGET-1".equals(sku) ? 499 : 199;
    }

    /** Solved with the default {@code PREDICT} strategy: a single structured-output call, no tool use or generated code involved. */
    @Generative("Classify whether the order description sounds urgent or routine.")
    public String classifyUrgency(String orderDescription) {
        return generate(orderDescription);
    }

    /**
     * Solved with {@code CODE_ACT}: the model writes Java that calls {@link #unitPriceCents(String)}
     * on the live {@code self} inside the sandbox, iterating until it returns a result.
     */
    @Generative(value = "Compute the total price in cents for 3 units of SKU WIDGET-1, using self.unitPriceCents(...).", strategy = StrategyKind.CODE_ACT)
    public int totalPriceViaCode() {
        return generate();
    }

    /**
     * Solved with {@code TOOL_CALLING}: {@link #unitPriceCents(String)} is exposed to the model as
     * a native JSON-schema tool rather than being called from generated code.
     */
    @Generative(value = "Figure out the unit price of SKU WIDGET-1 using the available tools, then report it.", strategy = StrategyKind.TOOL_CALLING)
    public String priceReportViaTools() {
        return generate();
    }

    public static void main(String[] args) {
        E04_Strategies agent = new E04_Strategies(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println("PREDICT: " + agent.classifyUrgency("Customer needs a replacement part shipped overnight, production line is down."));
        System.out.println("CODE_ACT: " + agent.totalPriceViaCode());
        System.out.println("TOOL_CALLING: " + agent.priceReportViaTools());
    }
}
