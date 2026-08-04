package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;

/**
 * Demonstrates {@code REFLEXION} (see {@link org.jopenagent.core.strategy.ReflexionStrategy}, a port of
 * NOOA's {@code ReflexionStrategy} from nooa/src/nooa/strategies/reflexion.py): the model's
 * answer is critiqued by a separate structured LLM call, and retried with the critique's
 * issues/suggestions folded in until satisfactory or 3 attempts are used.
 */
@SystemPrompt("You are a precise arithmetic word-problem solver. Show your final numeric answer only.")
public class E15_Reflexion extends Agent {

    public E15_Reflexion(AgentConfig config) {
        super(config);
    }

    /**
     * The self-correcting capability: under {@code REFLEXION} the initial answer is critiqued by
     * a separate structured call and, if found wanting, retried with the critique folded in, up
     * to 3 attempts total (see {@link org.jopenagent.core.strategy.ReflexionStrategy}).
     */
    @Generative(value = "Solve the word problem and return just the final integer answer.", strategy = StrategyKind.REFLEXION)
    public int solve(String wordProblem) {
        return generate(wordProblem);
    }

    public static void main(String[] args) {
        E15_Reflexion agent = new E15_Reflexion(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        int answer = agent.solve(
                "A warehouse had 84 pallets. It shipped out 3 trucks with 12 pallets each, then received a delivery of 25 more pallets. How many pallets are in the warehouse now?");
        System.out.println("Answer: " + answer + " (expected: 84 - 36 + 25 = 73)");
    }
}
