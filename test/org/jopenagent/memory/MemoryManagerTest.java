package org.jopenagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MemoryManagerTest {

    private MemoryManager newManager() {
        return new MemoryManager(new InMemoryMemoryStore(), new HashingEmbedder());
    }

    /** A fully-controllable {@link Embedder} test double: fixed vectors per known content string, so relevance scores are exact rather than hash-dependent. */
    private static final class FixedVectorEmbedder implements Embedder {
        private final Map<String, float[]> vectorsByContent = new HashMap<>();
        private final int dimensions;

        FixedVectorEmbedder(int dimensions) {
            this.dimensions = dimensions;
        }

        void put(String content, float[] vector) {
            vectorsByContent.put(content, vector);
        }

        @Override
        public float[] embed(String text) {
            float[] vector = vectorsByContent.get(text);
            if (vector == null) {
                throw new IllegalArgumentException("FixedVectorEmbedder has no vector configured for: " + text);
            }
            return vector;
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }

    @Test
    void rememberThenRecallFindsIt() {
        MemoryManager manager = newManager();
        manager.remember("the customer prefers email contact over phone calls");

        List<Memory> results = manager.recall("how does the customer prefer to be contacted", 5);

        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getContent().contains("email contact"));
    }

    @Test
    void duplicateContentReinforcesInsteadOfDuplicating() {
        MemoryManager manager = newManager();
        Memory first = manager.remember("the invoice was disputed by the customer");
        Memory second = manager.remember("the invoice was disputed by the customer");

        assertEquals(first.getId(), second.getId());
        assertEquals(1, manager.getStore().findAll().size());
        assertTrue(second.getImportance() > 0.5);
    }

    @Test
    void forgetExcludesFromRecall() {
        MemoryManager manager = newManager();
        Memory memory = manager.remember("a very specific one-off detail about widget pricing");

        manager.forget(memory.getId());

        List<Memory> results = manager.recall("widget pricing detail", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void recallRanksHigherImportanceAboveLowerImportanceForSimilarContent() {
        MemoryManager manager = newManager();
        manager.remember("customer asked about refund policy details", Memory.Type.INFO, 0.2, null);
        Memory important = manager.remember("customer escalation about refund policy violation", Memory.Type.INFO, 0.95, null);

        List<Memory> results = manager.recall("refund policy", 2);

        assertEquals(important.getId(), results.get(0).getId());
    }

    @Test
    void updateChangesContent() {
        MemoryManager manager = newManager();
        Memory memory = manager.remember("original content");
        manager.update(memory.getId(), "updated content");

        assertEquals("updated content", manager.get(memory.getId()).get().getContent());
    }

    @Test
    void associateCreatesAnOutgoingTypedEdge() {
        MemoryManager manager = newManager();
        Memory from = manager.remember("the customer escalated a billing dispute");
        Memory to = manager.remember("the original invoice that was disputed");

        manager.associate(from.getId(), to.getId(), "follows_up_on", 0.8);

        List<MemoryEdge> edges = manager.getEdges(from.getId());
        assertEquals(1, edges.size());
        assertEquals(to.getId(), edges.get(0).getTargetId());
        assertEquals("follows_up_on", edges.get(0).getRelationType());
        assertEquals(0.8, edges.get(0).getWeight());
        assertTrue(manager.getEdges(to.getId()).isEmpty());
    }

    @Test
    void associateThrowsWhenTheSourceMemoryDoesNotExist() {
        MemoryManager manager = newManager();
        assertThrows(MemoryException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                manager.associate("does-not-exist", "also-does-not-exist", "related");
            }
        });
    }

    @Test
    void traverseFollowsEdgesBreadthFirstUpToMaxDepthAndExcludesArchived() {
        MemoryManager manager = newManager();
        Memory a = manager.remember("memory a");
        Memory b = manager.remember("memory b");
        Memory c = manager.remember("memory c");
        Memory d = manager.remember("memory d, archived and unreachable in practice");
        manager.associate(a.getId(), b.getId(), "related");
        manager.associate(b.getId(), c.getId(), "related");
        manager.associate(a.getId(), d.getId(), "related");
        manager.forget(d.getId());

        List<Memory> oneHop = manager.traverse(a.getId(), 1);
        assertEquals(1, oneHop.size());
        assertEquals(b.getId(), oneHop.get(0).getId());

        List<Memory> twoHops = manager.traverse(a.getId(), 2);
        assertEquals(2, twoHops.size());
        boolean containsC = false;
        for (Memory memory : twoHops) {
            if (memory.getId().equals(c.getId())) {
                containsC = true;
            }
        }
        assertTrue(containsC);
    }

    @Test
    void recallSpreadsActivationAcrossEdgesSoAConnectedIrrelevantMemoryOutranksAnUnconnectedOne() {
        FixedVectorEmbedder embedder = new FixedVectorEmbedder(3);
        embedder.put("query", new float[] { 1f, 0f, 0f });
        embedder.put("relevant memory", new float[] { 1f, 0f, 0f });
        embedder.put("connected but irrelevant memory", new float[] { 0f, 1f, 0f });
        embedder.put("unconnected and irrelevant memory", new float[] { 0f, 0f, 1f });

        MemoryManager manager = new MemoryManager(new InMemoryMemoryStore(), embedder);
        Memory relevant = manager.remember("relevant memory");
        Memory connected = manager.remember("connected but irrelevant memory");
        manager.remember("unconnected and irrelevant memory");
        manager.associate(relevant.getId(), connected.getId(), "related_topic");

        List<Memory> results = manager.recall("query", 3);

        assertEquals(relevant.getId(), results.get(0).getId());
        assertEquals(connected.getId(), results.get(1).getId());
    }
}
