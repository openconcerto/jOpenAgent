package org.jopenagent.examples;

import java.util.ArrayList;
import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;

/**
 * Mirrors nooa/examples/quickstart's CodeAct usage (see also examples/advanced/sandbox): the
 * default-analogous code-as-action mode. The LLM writes Java statements executed by
 * {@link org.jopenagent.sandbox.CodeSandbox} against a live {@code self}, iterating (via the
 * {@code execute_java} tool) until it calls {@code return_result}. {@link org.jopenagent.sandbox.CodeValidator}
 * rejects dangerous constructs before anything runs: defense-in-depth only, not a security
 * boundary; see the design notes and {@code CodeValidator}'s javadoc for the full caveat.
 */
@SystemPrompt("You are a data-processing agent.")
public class E08_CodeAsAction extends Agent {

    private final List<Integer> orderTotalsCents = new ArrayList<>();

    public E08_CodeAsAction(AgentConfig config) {
        super(config);
        orderTotalsCents.add(1299);
        orderTotalsCents.add(499);
        orderTotalsCents.add(2599);
    }

    @Doc("Returns the list of order totals, in cents.")
    public List<Integer> orderTotalsCents() {
        return new ArrayList<>(orderTotalsCents);
    }

    /**
     * The {@code CODE_ACT} capability: the model writes and executes Java that calls
     * {@link #orderTotalsCents()} on the live {@code self} inside
     * {@link org.jopenagent.sandbox.CodeSandbox}, rather than being handed the data directly in the prompt.
     */
    @Generative(value = "Compute the average order total in whole dollars (rounded), by writing code that reads self.orderTotalsCents().",
            strategy = StrategyKind.CODE_ACT)
    public int averageOrderDollars() {
        return generate();
    }

    public static void main(String[] args) {
        E08_CodeAsAction agent = new E08_CodeAsAction(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        System.out.println("Average order total (dollars): " + agent.averageOrderDollars());
    }
}
