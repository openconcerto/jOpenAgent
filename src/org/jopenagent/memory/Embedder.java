package org.jopenagent.memory;

/**
 * Turns text into a fixed-size, L2-normalized embedding vector for similarity-based recall.
 * Implementations trade off cost/network access against embedding quality: {@link HashingEmbedder}
 * is the deterministic, zero-network default; {@link OpenAiEmbedder} calls a real (or
 * OpenAI-compatible local) embeddings API. Both are interchangeable via
 * {@code AgentConfig.builder().embedder(...)} since {@link MemoryManager} only ever compares
 * vectors produced by a single {@code Embedder} instance (via {@link VectorMath#cosineSimilarity}),
 * never mixes vectors from two different embedders.
 */
public interface Embedder {

    /** Embeds {@code text}, returning an L2-normalized vector of length {@link #dimensions()}. */
    float[] embed(String text);

    /** The length of vectors produced by {@link #embed}; for embedders with a server/model-dependent size, only accurate after at least one {@link #embed} call. */
    int dimensions();
}
