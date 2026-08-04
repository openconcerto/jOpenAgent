package org.jopenagent.core;

import org.jopenagent.core.history.HistoryConfig;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.llm.LlmClient;
import org.jopenagent.memory.Embedder;
import org.jopenagent.memory.HashingEmbedder;
import org.jopenagent.memory.InMemoryMemoryStore;
import org.jopenagent.memory.MemoryManager;
import org.jopenagent.sandbox.SandboxConfig;

/**
 * Per-agent settings: jOpenAgent's equivalent of NOOA's {@code class FeedbackAgent(Agent, llm=llm)}
 * class-level configuration. Java has no keyword-argument base-class syntax, so this is injected
 * via the constructor/builder instead.
 */
public final class AgentConfig {

    private final LlmClient llmClient;
    private final int maxRetries;
    private final StrategyKind defaultStrategy;
    private final int maxCodeActIterations;
    private final HistoryConfig historyConfig;
    private final MemoryManager memoryManager;
    private final SandboxConfig sandboxConfig;

    private AgentConfig(Builder builder) {
        this.llmClient = builder.llmClient;
        this.maxRetries = builder.maxRetries;
        this.defaultStrategy = builder.defaultStrategy;
        this.maxCodeActIterations = builder.maxCodeActIterations;
        this.historyConfig = builder.historyConfig;
        this.memoryManager = builder.memoryManager != null ? builder.memoryManager
                : new MemoryManager(new InMemoryMemoryStore(), builder.embedder != null ? builder.embedder : new HashingEmbedder());
        this.sandboxConfig = builder.sandboxConfig;
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public StrategyKind getDefaultStrategy() {
        return defaultStrategy;
    }

    public int getMaxCodeActIterations() {
        return maxCodeActIterations;
    }

    public HistoryConfig getHistoryConfig() {
        return historyConfig;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public SandboxConfig getSandboxConfig() {
        return sandboxConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link AgentConfig}; all settings are defaulted, so only the ones a given agent needs to override must be called. */
    public static final class Builder {
        private LlmClient llmClient;
        private int maxRetries = 3;
        private StrategyKind defaultStrategy = StrategyKind.PREDICT;
        private int maxCodeActIterations = 8;
        private HistoryConfig historyConfig = HistoryConfig.builder().build();
        private MemoryManager memoryManager;
        private Embedder embedder;
        private SandboxConfig sandboxConfig = SandboxConfig.builder().build();

        public Builder llmClient(LlmClient llmClient) {
            this.llmClient = llmClient;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder defaultStrategy(StrategyKind defaultStrategy) {
            this.defaultStrategy = defaultStrategy;
            return this;
        }

        public Builder maxCodeActIterations(int maxCodeActIterations) {
            this.maxCodeActIterations = maxCodeActIterations;
            return this;
        }

        public Builder historyConfig(HistoryConfig historyConfig) {
            this.historyConfig = historyConfig;
            return this;
        }

        /** Defaults to an in-memory store with a deterministic, offline {@link HashingEmbedder} if not set. Takes precedence over {@link #embedder}, if both are set. */
        public Builder memoryManager(MemoryManager memoryManager) {
            this.memoryManager = memoryManager;
            return this;
        }

        /**
         * Shortcut for the common case of only wanting to swap the embedder (e.g. to
         * {@link org.jopenagent.memory.OpenAiEmbedder} for real similarity search) while keeping the
         * default in-memory store; builds {@code new MemoryManager(new InMemoryMemoryStore(), embedder)}.
         * Ignored if {@link #memoryManager} is also set: pass a fully-constructed
         * {@link MemoryManager} there instead if you need a different store too.
         */
        public Builder embedder(Embedder embedder) {
            this.embedder = embedder;
            return this;
        }

        /** Defaults to {@link org.jopenagent.sandbox.SandboxMode#IN_PROCESS} with a 5s timeout if not set. */
        public Builder sandboxConfig(SandboxConfig sandboxConfig) {
            this.sandboxConfig = sandboxConfig;
            return this;
        }

        /**
         * Validates required/constrained settings ({@code llmClient} must be set; {@code maxRetries >= 1})
         * and constructs the immutable {@link AgentConfig}.
         *
         * @throws IllegalStateException if {@code llmClient} is unset or {@code maxRetries < 1}
         */
        public AgentConfig build() {
            if (llmClient == null) {
                throw new IllegalStateException("AgentConfig requires an LlmClient (jOpenAgent's equivalent of NOOA's `class MyAgent(Agent, llm=llm)`).");
            }
            if (maxRetries < 1) {
                throw new IllegalStateException("maxRetries must be >= 1");
            }
            return new AgentConfig(this);
        }
    }
}
