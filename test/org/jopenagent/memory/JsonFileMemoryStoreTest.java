package org.jopenagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileMemoryStoreTest {

    @Test
    void persistsAcrossStoreInstances(@TempDir Path tempDir) {
        File file = tempDir.resolve("memory.json").toFile();
        HashingEmbedder embedder = new HashingEmbedder();

        JsonFileMemoryStore store1 = new JsonFileMemoryStore(file);
        Memory memory = new Memory(Memory.newId(), Memory.Type.EPISODE, "customer called about a billing error", 0.7, Set.of("billing"), embedder.embed("customer called about a billing error"));
        store1.save(memory);

        JsonFileMemoryStore store2 = new JsonFileMemoryStore(file);
        List<Memory> loaded = store2.findAll();

        assertEquals(1, loaded.size());
        assertEquals("customer called about a billing error", loaded.get(0).getContent());
        assertEquals(Memory.Type.EPISODE, loaded.get(0).getType());
        assertTrue(loaded.get(0).getTags().contains("billing"));
    }

    @Test
    void deletePersistsToo(@TempDir Path tempDir) {
        File file = tempDir.resolve("memory.json").toFile();
        HashingEmbedder embedder = new HashingEmbedder();
        JsonFileMemoryStore store = new JsonFileMemoryStore(file);
        Memory memory = new Memory(Memory.newId(), Memory.Type.INFO, "temp fact", 0.5, null, embedder.embed("temp fact"));
        store.save(memory);

        store.delete(memory.getId());

        JsonFileMemoryStore reloaded = new JsonFileMemoryStore(file);
        assertEquals(0, reloaded.findAll().size());
    }

    @Test
    void accessHistoryAndEdgesRoundTripAcrossStoreInstances(@TempDir Path tempDir) {
        File file = tempDir.resolve("memory.json").toFile();
        HashingEmbedder embedder = new HashingEmbedder();
        JsonFileMemoryStore store1 = new JsonFileMemoryStore(file);

        Memory from = new Memory(Memory.newId(), Memory.Type.INFO, "source memory", 0.5, null, embedder.embed("source memory"));
        Memory to = new Memory(Memory.newId(), Memory.Type.INFO, "target memory", 0.5, null, embedder.embed("target memory"));
        from.recordAccess();
        from.recordAccess();
        from.addEdge(new MemoryEdge(to.getId(), "related_topic", 0.75));
        store1.save(from);
        store1.save(to);

        JsonFileMemoryStore store2 = new JsonFileMemoryStore(file);
        Memory reloadedFrom = store2.findById(from.getId()).get();

        assertEquals(from.getAccessCount(), reloadedFrom.getAccessCount());
        assertEquals(from.getLastAccessedAt(), reloadedFrom.getLastAccessedAt());
        assertEquals(from.getAccessLog().size(), reloadedFrom.getAccessLog().size());
        assertEquals(1, reloadedFrom.getEdges().size());
        assertEquals(to.getId(), reloadedFrom.getEdges().get(0).getTargetId());
        assertEquals("related_topic", reloadedFrom.getEdges().get(0).getRelationType());
        assertEquals(0.75, reloadedFrom.getEdges().get(0).getWeight());
    }
}
