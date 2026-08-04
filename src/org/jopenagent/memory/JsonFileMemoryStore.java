package org.jopenagent.memory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jopenkit.json.JSON;
import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

/**
 * Cross-session persistence for {@link Memory} records, backed by a single JSON file, jOpenAgent's
 * stand-in for NOOA's SQLite-backed {@code MemoryStore} (no SQLite JDBC driver is bundled with
 * this project; see design notes). Simplicity over throughput: the whole store is loaded into
 * memory on construction and rewritten in full on every mutation, which is entirely adequate for
 * the memory volumes a single agent accumulates.
 */
public final class JsonFileMemoryStore implements MemoryStore {

    private final File file;
    private final Map<String, Memory> memories = new LinkedHashMap<>();

    /** Opens (or, if it doesn't yet exist, prepares to create) {@code file} and eagerly loads any existing JSON array of memory records into memory. */
    public JsonFileMemoryStore(File file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        if (!file.exists()) {
            return;
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            JSONArray array = (JSONArray) JSON.parse(text);
            for (int i = 0; i < array.size(); i++) {
                Memory memory = fromJson(array.getJSONObject(i));
                memories.put(memory.getId(), memory);
            }
        } catch (IOException e) {
            throw new MemoryException("Failed to load memory store from " + file, e);
        }
    }

    private synchronized void persist() {
        JSONArray array = new JSONArray();
        for (Memory memory : memories.values()) {
            array.add(toJson(memory));
        }
        try {
            Files.writeString(file.toPath(), array.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MemoryException("Failed to persist memory store to " + file, e);
        }
    }

    @Override
    public synchronized void save(Memory memory) {
        memories.put(memory.getId(), memory);
        persist();
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
        persist();
    }

    private JSONObject toJson(Memory memory) {
        JSONObject json = new JSONObject();
        json.put("id", memory.getId());
        json.put("type", memory.getType().name());
        json.put("content", memory.getContent());
        json.put("importance", memory.getImportance());
        JSONArray tags = new JSONArray();
        for (String tag : memory.getTags()) {
            tags.add(tag);
        }
        json.put("tags", tags);
        JSONArray embedding = new JSONArray();
        if (memory.getEmbedding() != null) {
            for (float value : memory.getEmbedding()) {
                embedding.add((double) value);
            }
        }
        json.put("embedding", embedding);
        json.put("createdAt", memory.getCreatedAt().toString());
        json.put("lastAccessedAt", memory.getLastAccessedAt().toString());
        json.put("accessCount", memory.getAccessCount());
        json.put("archived", memory.isArchived());
        JSONArray accessLog = new JSONArray();
        for (java.time.Instant timestamp : memory.getAccessLog()) {
            accessLog.add(timestamp.toString());
        }
        json.put("accessLog", accessLog);
        JSONArray edges = new JSONArray();
        for (MemoryEdge edge : memory.getEdges()) {
            JSONObject edgeJson = new JSONObject();
            edgeJson.put("targetId", edge.getTargetId());
            edgeJson.put("relationType", edge.getRelationType());
            edgeJson.put("weight", edge.getWeight());
            edges.add(edgeJson);
        }
        json.put("edges", edges);
        return json;
    }

    private Memory fromJson(JSONObject json) {
        Set<String> tags = new LinkedHashSet<>();
        JSONArray tagsArray = json.optJSONArray("tags");
        if (tagsArray != null) {
            for (int i = 0; i < tagsArray.size(); i++) {
                tags.add(tagsArray.getString(i));
            }
        }
        JSONArray embeddingArray = json.optJSONArray("embedding");
        float[] embedding = null;
        if (embeddingArray != null && embeddingArray.size() > 0) {
            embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = (float) embeddingArray.getDouble(i);
            }
        }
        Memory memory = new Memory(json.getString("id"), Memory.Type.valueOf(json.getString("type")), json.getString("content"), json.optDouble("importance", 0.5), tags,
                embedding);
        memory.setArchived(json.optBoolean("archived", false));

        java.time.Instant lastAccessedAt = json.has("lastAccessedAt") ? java.time.Instant.parse(json.getString("lastAccessedAt")) : memory.getCreatedAt();
        List<java.time.Instant> accessLog = new ArrayList<>();
        JSONArray accessLogArray = json.optJSONArray("accessLog");
        if (accessLogArray != null) {
            for (int i = 0; i < accessLogArray.size(); i++) {
                accessLog.add(java.time.Instant.parse(accessLogArray.getString(i)));
            }
        } else {
            accessLog.add(memory.getCreatedAt());
        }
        List<MemoryEdge> edges = new ArrayList<>();
        JSONArray edgesArray = json.optJSONArray("edges");
        if (edgesArray != null) {
            for (int i = 0; i < edgesArray.size(); i++) {
                JSONObject edgeJson = edgesArray.getJSONObject(i);
                edges.add(new MemoryEdge(edgeJson.getString("targetId"), edgeJson.getString("relationType"), edgeJson.optDouble("weight", 1.0)));
            }
        }
        memory.restorePersistedState(json.optInt("accessCount", 0), lastAccessedAt, accessLog, edges);
        return memory;
    }
}
