package org.jopenagent.core.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jopenagent.llm.LlmMessage;

/**
 * An agent's persistent, cross-call conversation log, jOpenAgent's equivalent of NOOA's per-agent
 * event history (the substrate {@code TokenBudgetSummarizer}/{@code MethodSummarizer} compress).
 * Unlike NOOA's flat, arbitrarily-typed event bus, this stores plain role+text entries: enough
 * for a later call to see what happened in earlier calls, and enough for
 * {@link HistorySummarizer} to compress, without needing to replay exact tool-call wire objects
 * (which reference call ids meaningless outside their original turn anyway).
 *
 * <p>Only {@code CODE_ACT} and {@code TOOL_CALLING} strategies read/write this by default. They
 * are NOOA's "agentic loop" strategies where cross-call continuity matters; {@code PREDICT} stays
 * single-shot/stateless, matching its purpose.
 */
public final class ConversationHistory {

    private final List<HistoryEntry> entries = new ArrayList<>();
    /** The most recent provider-reported prompt token count, set via {@link #setLastReportedPromptTokens(int)}; compared against {@link HistoryConfig#getMaxTokens()} to decide whether to summarize. */
    private volatile int lastReportedPromptTokens;

    /** Appends one entry to the log. Thread-safe. */
    public synchronized void append(HistoryEntry entry) {
        entries.add(entry);
    }

    /** A defensive copy of the current entries, safe to iterate without holding this object's lock. */
    public synchronized List<HistoryEntry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Replaces entries [fromInclusive, toExclusive) with a single entry (typically a summary). */
    public synchronized void replaceRange(int fromInclusive, int toExclusive, HistoryEntry replacement) {
        List<HistoryEntry> rebuilt = new ArrayList<>(entries.subList(0, fromInclusive));
        rebuilt.add(replacement);
        rebuilt.addAll(entries.subList(toExclusive, entries.size()));
        entries.clear();
        entries.addAll(rebuilt);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Records the provider-reported prompt token count from the most recent LLM call, feeding {@link HistorySummarizer#shouldSummarize}. */
    public void setLastReportedPromptTokens(int tokens) {
        this.lastReportedPromptTokens = tokens;
    }

    /** The provider-reported prompt token count last recorded via {@link #setLastReportedPromptTokens(int)}. */
    public int getLastReportedPromptTokens() {
        return lastReportedPromptTokens;
    }

    /** Renders the history as plain conversation messages, to prefix a new call's message list. */
    public List<LlmMessage> asMessages() {
        List<LlmMessage> messages = new ArrayList<>();
        for (HistoryEntry entry : snapshot()) {
            if ("user".equals(entry.getRole())) {
                messages.add(LlmMessage.user(entry.getContent()));
            } else {
                // "assistant" and "summary" entries both read back as prior assistant turns.
                messages.add(LlmMessage.assistant(entry.getContent()));
            }
        }
        return messages;
    }
}
