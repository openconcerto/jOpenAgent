package org.jopenagent.trace;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.json.ObjectBinder;

/**
 * Exports a completed span tree to JSON: one line (JSONL) per root span if a file is given,
 * otherwise kept in memory for inspection (useful in tests, or for a future viewer UI to poll).
 */
public final class JsonTraceExporter implements TraceExporter {

    private final File outputFile;
    private final List<JSONObject> captured = Collections.synchronizedList(new ArrayList<JSONObject>());

    /** In-memory-only mode: captured traces are available via {@link #getCaptured()} but nothing is written to disk. */
    public JsonTraceExporter() {
        this.outputFile = null;
    }

    /** Also appends one JSON line (JSONL) per exported root span to {@code outputFile}, in addition to keeping it in {@link #getCaptured()}. */
    public JsonTraceExporter(File outputFile) {
        this.outputFile = outputFile;
    }

    /** Converts {@code span} (and its full subtree) to JSON via {@link #toJson(Span)}, keeps it in {@link #getCaptured()}, and, if configured, appends it as one JSONL line to the output file. */
    @Override
    public void export(Span span) {
        JSONObject json = toJson(span);
        captured.add(json);
        if (outputFile == null) {
            return;
        }
        synchronized (this) {
            try (FileWriter writer = new FileWriter(outputFile, true)) {
                writer.write(json.toString());
                writer.write(System.lineSeparator());
            } catch (IOException e) {
                throw new TraceException("Failed writing trace to " + outputFile, e);
            }
        }
    }

    public List<JSONObject> getCaptured() {
        return Collections.unmodifiableList(new ArrayList<JSONObject>(captured));
    }

    /**
     * Converts one span (recursing into {@link Span#getChildren()}) into the plain JSON shape
     * {@code {id, parentId?, kind, name, durationMillis, status, error?, attributes, children}} 
     * the shape shared by the trace viewer's REST API and {@link
     * org.jopenagent.trace.viewer.swing.JsonTraceNode}, regardless of whether it originated from an
     * in-process {@link Span} or was fetched over HTTP. Attribute values are converted via
     * {@link ObjectBinder#toJson(Object)}.
     */
    public static JSONObject toJson(Span span) {
        JSONObject obj = new JSONObject();
        obj.put("id", span.getId());
        if (span.getParentId() != null) {
            obj.put("parentId", span.getParentId());
        }
        obj.put("kind", span.getKind());
        obj.put("name", span.getName());
        obj.put("durationMillis", span.durationMillis());
        obj.put("status", span.getStatus());
        if (span.getErrorMessage() != null) {
            obj.put("error", span.getErrorMessage());
        }
        JSONObject attrs = new JSONObject();
        for (Map.Entry<String, Object> entry : span.getAttributes().entrySet()) {
            attrs.put(entry.getKey(), ObjectBinder.toJson(entry.getValue()));
        }
        obj.put("attributes", attrs);
        JSONArray children = new JSONArray();
        for (Span child : span.getChildren()) {
            children.add(toJson(child));
        }
        obj.put("children", children);
        return obj;
    }
}
