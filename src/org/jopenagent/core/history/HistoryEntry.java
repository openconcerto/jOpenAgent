package org.jopenagent.core.history;

/** One recorded turn in an agent's persistent {@link ConversationHistory}. */
public final class HistoryEntry {

    private final String role;
    private final String content;
    private final long timestampMillis;

    /** @param role typically {@code "user"}, {@code "assistant"}, or {@code "summary"} (see {@link ConversationHistory#asMessages()}); timestamp is stamped as the current time. */
    public HistoryEntry(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestampMillis = System.currentTimeMillis();
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    /** A rough chars/4 proxy for token count, used only as a fallback when no provider-reported count is available. */
    public int approxTokenCount() {
        return content == null ? 0 : Math.max(1, content.length() / 4);
    }
}
