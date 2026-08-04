package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.memory.Memory;

/**
 * Mirrors nooa/examples/quickstart/12_memory.py: {@code self.memory} (present on every agent by
 * default: an in-memory store + a deterministic offline embedder, see
 * {@link org.jopenagent.memory.MemoryManager}) persists across calls on the same instance. Ordinary
 * methods delegate to it so both {@code TOOL_CALLING} and {@code CODE_ACT} can use it; NOOA's
 * equivalent {@code MemoryToolsMixin} does the same thing (thin wrapper methods), since neither
 * framework auto-injects arbitrary sub-object methods as tools.
 *
 * <p>The default {@link org.jopenagent.memory.HashingEmbedder} needs no network call, which is why every
 * other example leaves it as-is. This example additionally shows the external-API option: set
 * {@code jOpenAgent_EMBEDDING_MODEL} (see {@link ExampleSetup#createEmbedder()}) to switch
 * {@code self.memory}'s similarity search to a real {@link org.jopenagent.memory.OpenAiEmbedder} 
 * OpenAI's hosted API, or a local LM Studio/Ollama embedding model, with zero other code changes.
 */
@SystemPrompt("You are a support agent with long-term memory of past interactions.")
public class E12_Memory extends Agent {

    public E12_Memory(AgentConfig config) {
        super(config);
    }

    /** Stores a new fact via {@link org.jopenagent.memory.MemoryManager#remember}, deduplicating against similar existing memories automatically. */
    @Doc("Remembers a fact about a customer for future reference.")
    public String rememberFact(String fact) {
        Memory memory = this.memory.remember(fact);
        return "stored as " + memory.getId();
    }

    /**
     * Formats the top 3 memories returned by {@link org.jopenagent.memory.MemoryManager#recall}
     * (similarity-ranked, recency/importance-weighted) as a bullet list for the model to read, or
     * a placeholder if nothing relevant has been remembered yet.
     */
    @Doc("Recalls the most relevant remembered facts for a query.")
    public String recallFacts(String query) {
        StringBuilder sb = new StringBuilder();
        for (Memory memory : this.memory.recall(query, 3)) {
            sb.append("- ").append(memory.getContent()).append("\n");
        }
        return sb.length() == 0 ? "(nothing relevant remembered)" : sb.toString();
    }

    /**
     * The conversational capability: {@code TOOL_CALLING} lets the model call {@link #recallFacts}
     * and {@link #rememberFact} as needed across turns, both backed by {@link Agent#memory}.
     */
    @Generative(value = "Using self.recallFacts if useful, answer the customer's question. Remember any new fact they share via self.rememberFact.",
            strategy = StrategyKind.TOOL_CALLING)
    public String converse(String customerMessage) {
        return generate(customerMessage);
    }

    public static void main(String[] args) {
        E12_Memory agent = new E12_Memory(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).embedder(ExampleSetup.createEmbedder()).build());
        System.out.println("Embedder: " + agent.memory.getEmbedder().getClass().getSimpleName());

        System.out.println(agent.converse("Hi, I'm on the Enterprise plan and prefer email over phone."));
        System.out.println(agent.converse("How do you prefer to reach me again?"));
    }
}
