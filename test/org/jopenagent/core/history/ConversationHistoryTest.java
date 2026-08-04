package org.jopenagent.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.jopenagent.llm.LlmMessage;

class ConversationHistoryTest {

    @Test
    void asMessagesRendersUserAndAssistantRoles() {
        ConversationHistory history = new ConversationHistory();
        history.append(new HistoryEntry("user", "hello"));
        history.append(new HistoryEntry("assistant", "hi there"));

        List<LlmMessage> messages = history.asMessages();

        assertEquals(2, messages.size());
        assertEquals(LlmMessage.Role.USER, messages.get(0).getRole());
        assertEquals(LlmMessage.Role.ASSISTANT, messages.get(1).getRole());
    }

    @Test
    void replaceRangeCollapsesEntriesIntoOne() {
        ConversationHistory history = new ConversationHistory();
        history.append(new HistoryEntry("user", "a"));
        history.append(new HistoryEntry("assistant", "b"));
        history.append(new HistoryEntry("user", "c"));
        history.append(new HistoryEntry("assistant", "d"));

        history.replaceRange(0, 2, new HistoryEntry("summary", "a+b summarized"));

        List<HistoryEntry> remaining = history.snapshot();
        assertEquals(3, remaining.size());
        assertEquals("summary", remaining.get(0).getRole());
        assertEquals("a+b summarized", remaining.get(0).getContent());
        assertEquals("c", remaining.get(1).getContent());
        assertEquals("d", remaining.get(2).getContent());
    }
}
