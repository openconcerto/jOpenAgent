package org.jopenagent.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ephemeral, process-lifetime memory store (no persistence), the default, and what NOOA's own quickstart uses via {@code path=":memory:"}. */
public final class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, Memory> memories = new LinkedHashMap<>();

    @Override
    public synchronized void save(Memory memory) {
        memories.put(memory.getId(), memory);
    }

    @Override
    public synchronized Optional<Memory> findById(String id) {
        return Optional.ofNullable(memories.get(id));
    }

    @Override
    public synchronized List<Memory> findAll() {
        return new ArrayList<>(memories.values());
    }

    @Override
    public synchronized void delete(String id) {
        memories.remove(id);
    }
}
