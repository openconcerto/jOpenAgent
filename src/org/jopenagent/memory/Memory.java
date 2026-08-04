package org.jopenagent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A single stored memory record: a scoped-down port of NOOA's {@code Memory} Pydantic model
 * (nooa/packages/nooa-memory/src/nooa_memory/schema.py). Keeps the type taxonomy,
 * importance/access-tracking fields, and, see {@link MemoryEdge} and {@link #getAccessLog()} 
 * a typed graph of outgoing edges plus the bounded access-timestamp log NOOA's ACT-R-style
 * retrieval scoring (see {@link MemoryManager}) is computed from. Does not port NOOA's
 * reference-pointer fields or its "injected vs. recalled" access-channel distinction (see design
 * notes for the full list of scope decisions).
 */
public final class Memory {

    /** Mirrors NOOA's memory type taxonomy: general {@code INFO}, learned {@code SKILL}, a recalled {@code EPISODE}, a standing {@code INTENT}, a {@code TODO} item, a self-authored {@code REFLECTION}, and transient {@code SCRATCH} content, purely descriptive metadata, not consulted by {@link MemoryManager}'s scoring itself. */
    public enum Type {
        INFO, SKILL, EPISODE, INTENT, TODO, REFLECTION, SCRATCH
    }

    /** Matches NOOA's default {@code access_log_cap} (see nooa_memory.retrieval.RetrievalEngine). */
    private static final int ACCESS_LOG_CAP = 64;

    private final String id;
    private final Type type;
    private String content;
    private double importance;
    private final Set<String> tags;
    private final float[] embedding;
    private final Instant createdAt;
    private Instant lastAccessedAt;
    private int accessCount;
    private boolean archived;
    private final List<Instant> accessLog = new ArrayList<>();
    private final List<MemoryEdge> edges = new ArrayList<>();

    /**
     * Creates a new memory stamped with the current time as both {@link #getCreatedAt()} and the
     * first entry of its access log. {@code tags} may be {@code null} (treated as empty);
     * {@code embedding} is normally precomputed by the {@link Embedder} in use before construction,
     * since {@link MemoryManager} needs it immediately for dedup-on-write comparison.
     */
    public Memory(String id, Type type, String content, double importance, Set<String> tags, float[] embedding) {
        this.id = id;
        this.type = type;
        this.content = content;
        this.importance = importance;
        this.tags = tags != null ? new LinkedHashSet<>(tags) : new LinkedHashSet<String>();
        this.embedding = embedding;
        this.createdAt = Instant.now();
        this.lastAccessedAt = this.createdAt;
        this.accessCount = 0;
        this.archived = false;
        this.accessLog.add(this.createdAt);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = importance;
    }

    public Set<String> getTags() {
        return tags;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** Bumps the access count, refreshes {@link #getLastAccessedAt()}, and appends to the (capped at {@value #ACCESS_LOG_CAP}) access log that {@link MemoryManager#recall}'s ACT-R activation scoring reads. */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessedAt = Instant.now();
        accessLog.add(this.lastAccessedAt);
        while (accessLog.size() > ACCESS_LOG_CAP) {
            accessLog.remove(0);
        }
    }

    /** The bounded (most recent {@value #ACCESS_LOG_CAP}) timestamps used to compute ACT-R base-level activation; see {@link MemoryManager}. */
    public List<Instant> getAccessLog() {
        return Collections.unmodifiableList(accessLog);
    }

    /**
     * Replaces the constructor-seeded state with persisted values; only meant for
     * {@link MemoryStore} implementations restoring a {@link Memory} from disk, where the real
     * {@code accessCount}/{@code lastAccessedAt}/access log/edges must win over whatever the
     * constructor guessed at construction time.
     */
    void restorePersistedState(int accessCount, Instant lastAccessedAt, List<Instant> accessLog, List<MemoryEdge> edges) {
        this.accessCount = accessCount;
        this.lastAccessedAt = lastAccessedAt;
        this.accessLog.clear();
        this.accessLog.addAll(accessLog);
        this.edges.clear();
        this.edges.addAll(edges);
    }

    public List<MemoryEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    void addEdge(MemoryEdge edge) {
        edges.add(edge);
    }
}
