package org.jopenagent.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code remember}/{@code recall}/{@code update}/{@code forget}/{@code associate}/{@code traverse}
 * API: a scoped-down but behavior-faithful port of NOOA's {@code MemoryManager}
 * (nooa/packages/nooa-memory/src/nooa_memory/manager.py): persistent, cross-session memory with
 * similarity-based dedup-on-write, a typed graph of edges between memories
 * ({@link #associate}/{@link #traverse}, see {@link MemoryEdge}), and NOOA's actual ACT-R-style
 * retrieval scoring: base-level activation from each memory's access history, blended with
 * relevance and importance, then spread across graph edges (see
 * {@code nooa_memory.retrieval.RetrievalEngine} and {@link MemoryRetrievalConfig}, whose defaults
 * match NOOA's own). Not ported: pass-by-reference pointers, the "injected vs. recalled" access
 * channel distinction, and the automatic LLM-driven reflection/consolidation pass (see design
 * notes): those sit above the store+recall+remember+forget+associate core that covers the
 * actually-observable "does the agent remember things, and how they relate" behavior.
 */
public final class MemoryManager {

    private final MemoryStore store;
    private final Embedder embedder;
    private final double dedupThreshold;
    private final MemoryRetrievalConfig retrievalConfig;

    public MemoryManager(MemoryStore store, Embedder embedder) {
        this(store, embedder, 0.92);
    }

    public MemoryManager(MemoryStore store, Embedder embedder, double dedupThreshold) {
        this(store, embedder, dedupThreshold, MemoryRetrievalConfig.builder().build());
    }

    public MemoryManager(MemoryStore store, Embedder embedder, double dedupThreshold, MemoryRetrievalConfig retrievalConfig) {
        this.store = store;
        this.embedder = embedder;
        this.dedupThreshold = dedupThreshold;
        this.retrievalConfig = retrievalConfig;
    }

    /** Stores content as a new memory, or reinforces (bumps importance on) an existing near-duplicate instead of creating one. */
    public Memory remember(String content, Memory.Type type, double importance, Set<String> tags) {
        float[] embedding = embedder.embed(content);
        Memory best = null;
        double bestScore = -1;
        for (Memory candidate : store.findAll()) {
            if (candidate.isArchived()) {
                continue;
            }
            double score = VectorMath.cosineSimilarity(embedding, candidate.getEmbedding());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && bestScore >= dedupThreshold) {
            best.setImportance(Math.min(1.0, best.getImportance() + 0.1));
            best.recordAccess();
            store.save(best);
            return best;
        }
        Memory memory = new Memory(Memory.newId(), type, content, importance, tags, embedding);
        store.save(memory);
        return memory;
    }

    /** Convenience overload of {@link #remember(String, Memory.Type, double, Set)} defaulting to {@code Memory.Type.INFO}, importance {@code 0.5}, and no tags. */
    public Memory remember(String content) {
        return remember(content, Memory.Type.INFO, 0.5, null);
    }

    /**
     * Creates a typed, directed, weighted edge from {@code fromId} to {@code toId}, e.g.
     * {@code associate(followUpId, originalId, "follows_up_on")}. Mirrors NOOA's graph edges
     * (see {@code nooa_memory.schema}), which {@link #recall} spreads activation over and
     * {@link #traverse} follows explicitly. The target need not already exist (an edge to a
     * memory that's later forgotten/never created is simply never followed) but {@code fromId}
     * must, since that's the memory whose edge list is mutated.
     */
    public void associate(String fromId, String toId, String relationType) {
        associate(fromId, toId, relationType, 1.0);
    }

    /** As {@link #associate(String, String, String)}, but with an explicit edge {@code weight} (default {@code 1.0}) that scales its contribution in {@link #recall}'s spread-activation step. */
    public void associate(String fromId, String toId, String relationType, double weight) {
        Memory from = store.findById(fromId).orElseThrow(new MemoryNotFoundSupplier(fromId));
        from.addEdge(new MemoryEdge(toId, relationType, weight));
        store.save(from);
    }

    /** The direct outgoing edges of one memory, or empty if it doesn't exist. */
    public List<MemoryEdge> getEdges(String id) {
        Optional<Memory> memory = store.findById(id);
        return memory.isPresent() ? memory.get().getEdges() : Collections.<MemoryEdge>emptyList();
    }

    /** Breadth-first graph traversal from {@code startId}, following edges up to {@code maxDepth} hops; excludes archived and unreachable memories. */
    public List<Memory> traverse(String startId, int maxDepth) {
        List<Memory> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(startId);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(startId);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            Deque<String> next = new ArrayDeque<>();
            for (String currentId : frontier) {
                Optional<Memory> current = store.findById(currentId);
                if (current.isEmpty()) {
                    continue;
                }
                for (MemoryEdge edge : current.get().getEdges()) {
                    if (visited.contains(edge.getTargetId())) {
                        continue;
                    }
                    visited.add(edge.getTargetId());
                    Optional<Memory> target = store.findById(edge.getTargetId());
                    if (target.isPresent() && !target.get().isArchived()) {
                        result.add(target.get());
                        next.add(edge.getTargetId());
                    }
                }
            }
            frontier = next;
        }
        return result;
    }

    /**
     * Associative recall: the top {@code k} non-archived memories ranked by an ACT-R-style
     * activation score: relevance (embedding cosine similarity), recency/frequency (base-level
     * activation from each memory's access history), and importance are each min-max normalized
     * across the candidate set, combined via {@link MemoryRetrievalConfig}'s weights, then spread
     * across {@link MemoryEdge}s for {@link MemoryRetrievalConfig#getHops()} hops, exactly
     * NOOA's {@code RetrievalEngine._pipeline}/{@code _spread}, minus its approximate dense/sparse
     * candidate-pool stage (jOpenAgent scores every non-archived memory directly).
     */
    public List<Memory> recall(String query, int k) {
        float[] queryEmbedding = embedder.embed(query);
        Instant now = Instant.now();

        Map<String, Memory> byId = new LinkedHashMap<>();
        for (Memory memory : store.findAll()) {
            if (!memory.isArchived()) {
                byId.put(memory.getId(), memory);
            }
        }
        if (byId.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> relevance = new LinkedHashMap<>();
        Map<String, Double> recency = new LinkedHashMap<>();
        Map<String, Double> importance = new LinkedHashMap<>();
        for (Memory memory : byId.values()) {
            relevance.put(memory.getId(), VectorMath.cosineSimilarity(queryEmbedding, memory.getEmbedding()));
            recency.put(memory.getId(), sigmoid(baseLevelActivation(memory.getAccessLog(), now)));
            importance.put(memory.getId(), memory.getImportance());
        }
        Map<String, Double> relevanceNorm = minMaxNormalize(relevance);
        Map<String, Double> recencyNorm = minMaxNormalize(recency);
        Map<String, Double> importanceNorm = minMaxNormalize(importance);

        Map<String, Double> baseScore = new LinkedHashMap<>();
        for (String id : byId.keySet()) {
            double s = retrievalConfig.getRelevanceWeight() * relevanceNorm.get(id) + retrievalConfig.getRecencyWeight() * recencyNorm.get(id)
                    + retrievalConfig.getImportanceWeight() * importanceNorm.get(id);
            baseScore.put(id, s);
        }

        Map<String, Double> spread = spreadActivation(baseScore, byId);
        Map<String, Double> activation = new LinkedHashMap<>();
        for (String id : byId.keySet()) {
            Double extra = spread.get(id);
            activation.put(id, baseScore.get(id) + retrievalConfig.getSpreadGamma() * (extra != null ? extra : 0.0));
        }

        List<String> rankedIds = new ArrayList<>(byId.keySet());
        rankedIds.sort(new ActivationComparator(activation));

        List<Memory> top = new ArrayList<>();
        int limit = Math.min(k, rankedIds.size());
        for (int i = 0; i < limit; i++) {
            Memory memory = byId.get(rankedIds.get(i));
            memory.recordAccess();
            store.save(memory);
            top.add(memory);
        }
        return top;
    }

    /** Replaces a memory's content in place (identity, tags, embedding, and access history are untouched). Throws {@link MemoryException} if {@code id} doesn't exist. */
    public void update(String id, String newContent) {
        Memory memory = store.findById(id).orElseThrow(new MemoryNotFoundSupplier(id));
        memory.setContent(newContent);
        store.save(memory);
    }

    /** Soft-deletes a memory: excluded from recall/dedup/traversal going forward, but not physically removed. */
    public void forget(String id) {
        Optional<Memory> found = store.findById(id);
        if (found.isEmpty()) {
            return;
        }
        Memory memory = found.get();
        memory.setArchived(true);
        store.save(memory);
    }

    public Optional<Memory> get(String id) {
        return store.findById(id);
    }

    public MemoryStore getStore() {
        return store;
    }

    public Embedder getEmbedder() {
        return embedder;
    }

    public MemoryRetrievalConfig getRetrievalConfig() {
        return retrievalConfig;
    }

    /** ACT-R base-level activation: {@code ln(sum_k (now - t_k)^-d)} over a memory's (bounded) access log. Higher = more recent/frequent. */
    private double baseLevelActivation(List<Instant> accessLog, Instant now) {
        double total = 0.0;
        for (Instant timestamp : accessLog) {
            double dtSeconds = Math.max(Duration.between(timestamp, now).toMillis() / 1000.0, 1.0);
            total += Math.pow(dtSeconds, -retrievalConfig.getBaseLevelDecay());
        }
        if (total <= 0.0) {
            return -10.0;
        }
        return Math.log(total);
    }

    private static double sigmoid(double x) {
        if (x >= 0) {
            return 1.0 / (1.0 + Math.exp(-x));
        }
        double z = Math.exp(x);
        return z / (1.0 + z);
    }

    private static Map<String, Double> minMaxNormalize(Map<String, Double> values) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values.values()) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (hi - lo < 1e-12) {
            for (String key : values.keySet()) {
                result.put(key, 0.0);
            }
            return result;
        }
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            result.put(entry.getKey(), (entry.getValue() - lo) / (hi - lo));
        }
        return result;
    }

    /** Propagates each candidate's base score outward over its edges, decaying {@code perHopDecay^hop} per hop, within the already-scored candidate set. */
    private Map<String, Double> spreadActivation(Map<String, Double> baseScore, Map<String, Memory> byId) {
        Map<String, Double> totalSpread = new LinkedHashMap<>();
        Map<String, Double> frontier = new LinkedHashMap<>(baseScore);
        for (int hop = 1; hop <= retrievalConfig.getHops() && !frontier.isEmpty(); hop++) {
            double hopDecay = Math.pow(retrievalConfig.getPerHopDecay(), hop);
            Map<String, Double> next = new LinkedHashMap<>();
            for (Map.Entry<String, Double> entry : frontier.entrySet()) {
                Memory node = byId.get(entry.getKey());
                if (node == null) {
                    continue;
                }
                for (MemoryEdge edge : node.getEdges()) {
                    if (!byId.containsKey(edge.getTargetId())) {
                        continue;
                    }
                    double contribution = hopDecay * entry.getValue() * edge.getWeight();
                    addTo(totalSpread, edge.getTargetId(), contribution);
                    addTo(next, edge.getTargetId(), contribution);
                }
            }
            frontier = next;
        }
        return totalSpread;
    }

    private static void addTo(Map<String, Double> map, String key, double delta) {
        Double current = map.get(key);
        map.put(key, (current != null ? current : 0.0) + delta);
    }

    /** Sorts memory ids by descending final activation score, for {@link #recall}'s top-k ranking. */
    private static final class ActivationComparator implements Comparator<String> {
        private final Map<String, Double> activation;

        ActivationComparator(Map<String, Double> activation) {
            this.activation = activation;
        }

        @Override
        public int compare(String a, String b) {
            return Double.compare(activation.get(b), activation.get(a));
        }
    }

    /** Lazily builds the {@link MemoryException} thrown by {@link #associate}/{@link #update} when the referenced memory id doesn't exist, for use with {@link Optional#orElseThrow(java.util.function.Supplier)}. */
    private static final class MemoryNotFoundSupplier implements java.util.function.Supplier<MemoryException> {
        private final String id;

        MemoryNotFoundSupplier(String id) {
            this.id = id;
        }

        @Override
        public MemoryException get() {
            return new MemoryException("No memory with id " + id);
        }
    }
}
