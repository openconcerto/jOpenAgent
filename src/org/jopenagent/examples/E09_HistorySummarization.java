package org.jopenagent.examples;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.history.HistoryConfig;
import org.jopenagent.core.strategy.StrategyKind;

/**
 * Mirrors nooa/examples/quickstart/09_summarization.py: {@code CODE_ACT}/{@code TOOL_CALLING}
 * calls on the same agent instance accumulate in {@link Agent#history}; once it exceeds the
 * configured entry-count threshold, {@link org.jopenagent.core.history.HistorySummarizer} collapses the
 * oldest entries into a single LLM-produced summary (Summary/Topics/Outcomes/State headings,
 * directly ported from NOOA's own summarizer prompt) before the next call.
 */
@SystemPrompt("You are a running log of a customer support shift.")
public class E09_HistorySummarization extends Agent {

    public E09_HistorySummarization(AgentConfig config) {
        super(config);
    }

    @Doc("Looks up how many minutes are left in the shift.")
    public int minutesLeftInShift() {
        return 45;
    }

    /**
     * Each call appends to {@link Agent#history} via the {@code TOOL_CALLING} strategy; once the
     * configured {@link HistoryConfig} threshold is crossed, the oldest entries are collapsed by
     * {@link org.jopenagent.core.history.HistorySummarizer} before the next call runs.
     */
    @Generative(value = "Log this ticket note in one sentence, using tools if useful.", strategy = StrategyKind.TOOL_CALLING)
    public String logNote(String note) {
        return generate(note);
    }

    public static void main(String[] args) {
        // A tiny threshold so this demo actually triggers summarization after a few calls.
        HistoryConfig aggressiveConfig = HistoryConfig.builder().maxEntries(3).preserveRecent(1).targetChars(300).build();
        E09_HistorySummarization agent = new E09_HistorySummarization(
                AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).historyConfig(aggressiveConfig).build());

        System.out.println(agent.logNote("Customer A: billing question resolved."));
        System.out.println(agent.logNote("Customer B: password reset requested."));
        System.out.println(agent.logNote("Customer C: escalated to tier 2 for outage."));
        System.out.println(agent.logNote("Customer D: happy with the resolution, no follow-up needed."));

        System.out.println("--- history after 4 calls (" + agent.history.size() + " entries) ---");
        for (var entry : agent.history.snapshot()) {
            System.out.println("[" + entry.getRole() + "] " + entry.getContent());
        }
    }
}
