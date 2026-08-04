package org.jopenagent.memory;

import java.util.List;
import java.util.Optional;

/**
 * Storage backend for {@link Memory} records, used exclusively through {@link MemoryManager}
 * (never called directly by agent code). Implementations differ only in persistence strategy 
 * {@link InMemoryMemoryStore} keeps records for the JVM's lifetime only; {@link JsonFileMemoryStore}
 * persists them to a single JSON file, so all mutation methods here must be safe to call from
 * {@link MemoryManager}'s synchronized-at-the-manager-level call sites and {@code save} must be
 * idempotent for both inserts and updates (keyed on {@link Memory#getId()}).
 */
public interface MemoryStore {

    /** Inserts a new record or overwrites the existing one with the same {@link Memory#getId()}. */
    void save(Memory memory);

    /** Looks up a record by id, or {@link Optional#empty()} if none (or a previously {@link #delete}d one) matches. */
    Optional<Memory> findById(String id);

    /** Every stored record, including archived (soft-deleted via {@link MemoryManager#forget}) ones, callers filter archived state themselves. */
    List<Memory> findAll();

    /** Physically removes a record by id; a no-op if it doesn't exist. Note this differs from {@link MemoryManager#forget}, which only archives. */
    void delete(String id);
}
