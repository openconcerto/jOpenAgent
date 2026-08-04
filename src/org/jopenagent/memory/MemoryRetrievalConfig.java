package org.jopenagent.memory;

/**
 * Tunables for {@link MemoryManager#recall}'s scoring: a merged, scoped-down port of NOOA's
 * {@code ScoringWeights}/{@code RetrievalConfig} (see
 * {@code nooa/packages/nooa-memory/src/nooa_memory/config.py}). Defaults match NOOA's own
 * defaults exactly, so recall behaves the same way out of the box; jOpenAgent drops the fields tied to
 * NOOA's approximate dense/sparse candidate-pool retrieval (jOpenAgent scores every non-archived memory
 * directly, so there's no candidate pool to size).
 */
public final class MemoryRetrievalConfig {

    private final double relevanceWeight;
    private final double recencyWeight;
    private final double importanceWeight;
    private final double baseLevelDecay;
    private final double spreadGamma;
    private final int hops;
    private final double perHopDecay;

    private MemoryRetrievalConfig(Builder builder) {
        this.relevanceWeight = builder.relevanceWeight;
        this.recencyWeight = builder.recencyWeight;
        this.importanceWeight = builder.importanceWeight;
        this.baseLevelDecay = builder.baseLevelDecay;
        this.spreadGamma = builder.spreadGamma;
        this.hops = builder.hops;
        this.perHopDecay = builder.perHopDecay;
    }

    /** alpha_rel in NOOA: weight on embedding-cosine relevance. */
    public double getRelevanceWeight() {
        return relevanceWeight;
    }

    /** alpha_rec in NOOA: weight on ACT-R base-level activation (recency/frequency). */
    public double getRecencyWeight() {
        return recencyWeight;
    }

    /** alpha_imp in NOOA: weight on stored importance. */
    public double getImportanceWeight() {
        return importanceWeight;
    }

    /** ACT-R decay parameter {@code d} in {@code ln(sum_k (now - t_k)^-d)}. */
    public double getBaseLevelDecay() {
        return baseLevelDecay;
    }

    /** Gain applied to associative-spread activation gathered from {@link #getHops()} graph hops. */
    public double getSpreadGamma() {
        return spreadGamma;
    }

    /** How many {@link MemoryEdge} hops to spread activation over; 0 disables spreading entirely. */
    public int getHops() {
        return hops;
    }

    /** Activation decay applied per additional hop (raised to the hop number). */
    public double getPerHopDecay() {
        return perHopDecay;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link MemoryRetrievalConfig}; every field defaults to NOOA's own tuning, so {@code MemoryRetrievalConfig.builder().build()} reproduces NOOA's out-of-box recall behavior. */
    public static final class Builder {
        private double relevanceWeight = 1.0;
        private double recencyWeight = 0.5;
        private double importanceWeight = 0.5;
        private double baseLevelDecay = 0.5;
        private double spreadGamma = 0.5;
        private int hops = 1;
        private double perHopDecay = 0.6;

        public Builder relevanceWeight(double relevanceWeight) {
            this.relevanceWeight = relevanceWeight;
            return this;
        }

        public Builder recencyWeight(double recencyWeight) {
            this.recencyWeight = recencyWeight;
            return this;
        }

        public Builder importanceWeight(double importanceWeight) {
            this.importanceWeight = importanceWeight;
            return this;
        }

        public Builder baseLevelDecay(double baseLevelDecay) {
            this.baseLevelDecay = baseLevelDecay;
            return this;
        }

        public Builder spreadGamma(double spreadGamma) {
            this.spreadGamma = spreadGamma;
            return this;
        }

        public Builder hops(int hops) {
            this.hops = hops;
            return this;
        }

        public Builder perHopDecay(double perHopDecay) {
            this.perHopDecay = perHopDecay;
            return this;
        }

        public MemoryRetrievalConfig build() {
            return new MemoryRetrievalConfig(this);
        }
    }
}
