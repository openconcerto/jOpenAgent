package org.jopenagent.memory;

/**
 * A typed, directed, weighted association from one {@link Memory} to another, jOpenAgent's port of the
 * edge model NOOA's {@code nooa_memory.retrieval} spreads activation over (see
 * {@code RetrievalEngine._spread} in {@code nooa/packages/nooa-memory/src/nooa_memory/retrieval.py}).
 * Edges are outgoing-only on the source {@link Memory}; {@link MemoryManager#traverse} follows
 * them to build a reachable set.
 */
public final class MemoryEdge {

    private final String targetId;
    private final String relationType;
    private final double weight;

    public MemoryEdge(String targetId, String relationType, double weight) {
        this.targetId = targetId;
        this.relationType = relationType;
        this.weight = weight;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getRelationType() {
        return relationType;
    }

    public double getWeight() {
        return weight;
    }
}
