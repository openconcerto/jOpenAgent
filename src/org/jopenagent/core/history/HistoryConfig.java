package org.jopenagent.core.history;

/**
 * Configures automatic history summarization: jOpenAgent's equivalent of NOOA's
 * {@code TokenBudgetSummarizer}/{@code MethodSummarizer} (see nooa/src/nooa/agents/summarization.py).
 * Two triggers are combined (either firing causes a summarization pass): a provider-reported
 * prompt-token threshold ({@link #getMaxTokens()}, mirroring {@code TokenBudgetSummarizer}), and a
 * raw entry-count threshold ({@link #getMaxEntries()}, a simpler proxy standing in for NOOA's
 * {@code MethodSummarizer} turn-count trigger).
 */
public final class HistoryConfig {

    private final boolean enabled;
    private final int maxTokens;
    private final int maxEntries;
    private final int preserveRecent;
    private final int targetChars;

    private HistoryConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.maxTokens = builder.maxTokens;
        this.maxEntries = builder.maxEntries;
        this.preserveRecent = builder.preserveRecent;
        this.targetChars = builder.targetChars;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    /** How many of the most recent entries are always kept verbatim (never folded into a summary) when {@link HistorySummarizer#summarizeIfNeeded} runs. */
    public int getPreserveRecent() {
        return preserveRecent;
    }

    /** The approximate target length, in characters, for the generated summary text. */
    public int getTargetChars() {
        return targetChars;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link HistoryConfig}; all fields have workable defaults, so only settings a caller wants to override need to be called. */
    public static final class Builder {
        private boolean enabled = true;
        private int maxTokens = 8000;
        private int maxEntries = 40;
        private int preserveRecent = 10;
        private int targetChars = 1000;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
            return this;
        }

        public Builder preserveRecent(int preserveRecent) {
            this.preserveRecent = preserveRecent;
            return this;
        }

        public Builder targetChars(int targetChars) {
            this.targetChars = targetChars;
            return this;
        }

        public HistoryConfig build() {
            return new HistoryConfig(this);
        }
    }
}
