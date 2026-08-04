package org.jopenagent.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.jopenagent.testsupport.MockLlmClient;

class HistorySummarizerTest {

    @Test
    void doesNotTriggerBelowThresholds() {
        ConversationHistory history = new ConversationHistory();
        history.append(new HistoryEntry("user", "hi"));
        HistoryConfig config = HistoryConfig.builder().maxEntries(40).maxTokens(8000).preserveRecent(10).build();

        assertFalse(HistorySummarizer.shouldSummarize(history, config));
    }

    @Test
    void triggersOnEntryCountAndCollapsesOldestIntoSummary() {
        ConversationHistory history = new ConversationHistory();
        for (int i = 0; i < 10; i++) {
            history.append(new HistoryEntry("user", "message " + i));
        }
        HistoryConfig config = HistoryConfig.builder().maxEntries(5).preserveRecent(3).targetChars(200).maxTokens(0).build();
        MockLlmClient llm = new MockLlmClient();
        llm.enqueueText("**Summary:** condensed history\n**Topics:** testing\n**Outcomes:** none\n**State:** ok");

        HistorySummarizer.summarizeIfNeeded(history, llm, config);

        assertEquals(1, llm.callCount());
        java.util.List<HistoryEntry> remaining = history.snapshot();
        // 7 oldest entries collapsed into 1 summary + 3 preserved-recent = 4 total.
        assertEquals(4, remaining.size());
        assertEquals("summary", remaining.get(0).getRole());
        assertTrue(remaining.get(0).getContent().contains("condensed history"));
        assertEquals("message 7", remaining.get(1).getContent());
    }

    @Test
    void disabledConfigNeverTriggers() {
        ConversationHistory history = new ConversationHistory();
        for (int i = 0; i < 100; i++) {
            history.append(new HistoryEntry("user", "message " + i));
        }
        HistoryConfig config = HistoryConfig.builder().enabled(false).maxEntries(1).build();

        assertFalse(HistorySummarizer.shouldSummarize(history, config));
    }
}
