package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;

/**
 * Mirrors nooa/examples/quickstart/01_first_generation_method.py: the smallest possible agent
 * with one LLM-backed capability. The {@code @Generative} annotation's text plays the role of
 * NOOA's method docstring (unavailable at runtime in Java, see the design notes); the body's
 * single {@code return generate(name);} statement is the interception point, jOpenAgent's analogue of
 * a bare {@code ...} body.
 */
@SystemPrompt("You are a warm, concise greeter.")
public class E01_FirstGenerationMethod extends Agent {

    public E01_FirstGenerationMethod(AgentConfig config) {
        super(config);
    }

    /**
     * The example's single LLM-backed capability: the {@code @Generative} text supplies the
     * prompt, and the {@link #generate(Object...)} call is the interception point that dispatches
     * to the configured {@link org.jopenagent.core.strategy.Strategy} (here the default {@code PREDICT}).
     */
    @Generative("Write a one-sentence, friendly greeting for the given person.")
    public String greet(String name) {
        return generate(name);
    }

    public static void main(String[] args) {
        E01_FirstGenerationMethod agent = new E01_FirstGenerationMethod(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        String greeting = agent.greet("Ada");
        System.out.println(greeting);
    }
}
