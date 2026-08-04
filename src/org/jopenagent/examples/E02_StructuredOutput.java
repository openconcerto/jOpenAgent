package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;

/**
 * Mirrors nooa/examples/quickstart/02_structured_output.py: a record return type is jOpenAgent's
 * equivalent of a NOOA Pydantic model: {@link org.jopenagent.json.ObjectBinder} validates/binds the
 * LLM's JSON against it automatically, retrying with the validation error fed back on failure
 * (see {@link org.jopenagent.core.strategy.PredictStrategy}).
 */
@SystemPrompt("You extract structured information from restaurant reviews.")
public class E02_StructuredOutput extends Agent {

    /**
     * The structured return type extracted from a review: jOpenAgent's analogue of a NOOA Pydantic
     * model. {@link org.jopenagent.json.ObjectBinder} binds the LLM's JSON response directly onto this
     * record's canonical constructor, validating types and retrying (with the error fed back to
     * the model) on mismatch.
     */
    public record ReviewSummary(int rating, String sentiment, java.util.List<String> highlights) {
    }

    public E02_StructuredOutput(AgentConfig config) {
        super(config);
    }

    /**
     * Demonstrates a {@code @Generative} method whose declared return type is a record: the LLM
     * response is parsed and bound onto {@link ReviewSummary} by {@link org.jopenagent.json.ObjectBinder}
     * rather than being returned as raw text.
     */
    @Generative("Extract a rating out of 5, an overall sentiment word, and up to 3 highlight phrases from the review.")
    public ReviewSummary summarize(String review) {
        return generate(review);
    }

    public static void main(String[] args) {
        E02_StructuredOutput agent = new E02_StructuredOutput(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        ReviewSummary summary = agent
                .summarize("The ramen was incredible and the service was fast, but the restaurant was quite loud and cramped. Still, I'd go back for the food alone.");
        System.out.println("rating: " + summary.rating());
        System.out.println("sentiment: " + summary.sentiment());
        System.out.println("highlights: " + summary.highlights());
    }
}
