package org.jopenagent.core.history;

import java.util.List;

import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Compresses the oldest run of a {@link ConversationHistory} into a single summary entry once a
 * configured trigger fires, via a dedicated LLM call, a direct port of NOOA's summarizer prompt
 * template (Summary/Topics/Outcomes/State headings, KEEP/COMPRESS/OMIT guidance) from
 * {@code nooa/src/nooa/agents/summarization.py}.
 */
public final class HistorySummarizer {

    private HistorySummarizer() {
    }

    /**
     * Whether a summarization pass should fire now: false if summarization is disabled or the
     * history is no larger than {@link HistoryConfig#getPreserveRecent()}; otherwise true if either
     * the last reported prompt-token count exceeds {@link HistoryConfig#getMaxTokens()} or the
     * entry count exceeds {@link HistoryConfig#getMaxEntries()}.
     */
    public static boolean shouldSummarize(ConversationHistory history, HistoryConfig config) {
        if (!config.isEnabled()) {
            return false;
        }
        if (history.size() <= config.getPreserveRecent()) {
            return false;
        }
        if (config.getMaxTokens() > 0 && history.getLastReportedPromptTokens() > config.getMaxTokens()) {
            return true;
        }
        return history.size() > config.getMaxEntries();
    }

    /** Summarizes the oldest entries of {@code history} in place, if the configured trigger has fired. */
    public static void summarizeIfNeeded(ConversationHistory history, LlmClient llm, HistoryConfig config) {
        if (!shouldSummarize(history, config)) {
            return;
        }
        List<HistoryEntry> all = history.snapshot();
        int keepFrom = Math.max(0, all.size() - config.getPreserveRecent());
        if (keepFrom <= 1) {
            return;
        }
        List<HistoryEntry> toSummarize = all.subList(0, keepFrom);
        String markdown = renderMarkdown(toSummarize);
        String summaryText = callSummarizer(llm, markdown, config.getTargetChars());
        history.replaceRange(0, keepFrom, new HistoryEntry("summary", summaryText));
    }

    private static String renderMarkdown(List<HistoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (HistoryEntry entry : entries) {
            sb.append("**").append(entry.getRole()).append(":** ").append(entry.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private static String callSummarizer(LlmClient llm, String historyMarkdown, int targetChars) {
        Span span = Traced.begin("llm_call", "history summarization");
        try {
            String systemPrompt = "You are a precise conversation summarizer.";
            String prompt = "Summarize the following conversation history into approximately " + targetChars + " characters.\n"
                    + "Structure your summary with these exact headings: **Summary:**, **Topics:**, **Outcomes:**, **State:**\n"
                    + "KEEP: decisions, numbers, technical terms, conclusions.\n" + "COMPRESS: discussions to their themes only, Q&A to their essence only.\n"
                    + "OMIT: greetings, process chatter, resolved errors.\n\n" + "## Conversation history\n" + historyMarkdown;
            LlmRequest request = LlmRequest.builder().systemPrompt(systemPrompt).addMessage(LlmMessage.user(prompt)).build();
            LlmResponse response = llm.generate(request);
            Traced.finish(span);
            return response.getContent();
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            throw e;
        }
    }
}
