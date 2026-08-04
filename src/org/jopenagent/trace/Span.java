package org.jopenagent.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One traced unit of work: an LLM generation call, a code execution, a tool execution, or an
 * ordinary traced method invocation. Spans form a parent-child tree via {@link Tracer}, jOpenAgent's
 * equivalent of NOOA's OpenTelemetry/OpenInference span hierarchy (see {@code
 * nooa/src/nooa/tracing/_hooks_impl.py}): reimplemented here without OpenTelemetry to avoid an
 * extra dependency, since a plain nested span tree is all the stated features (parent-child
 * spans, JSON export) require.
 */
public final class Span {

    private final String id;
    private final String parentId;
    private final String kind;
    private final String name;
    private final long startNanos;
    private long endNanos = -1;
    private final long startEpochNanos;
    private long endEpochNanos = -1;
    private String status = "OK";
    private String errorMessage;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final List<Span> children = new ArrayList<>();

    Span(String id, String parentId, String kind, String name) {
        this.id = id;
        this.parentId = parentId;
        this.kind = kind;
        this.name = name;
        this.startNanos = System.nanoTime();
        this.startEpochNanos = epochNanosNow();
    }

    private static long epochNanosNow() {
        java.time.Instant now = java.time.Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    /** Wall-clock start time as Unix epoch nanoseconds, for absolute-time export formats (OTLP); duration math uses the monotonic {@link #getStartNanos()} instead. */
    public long getStartEpochNanos() {
        return startEpochNanos;
    }

    public long getEndEpochNanos() {
        return endEpochNanos;
    }

    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
    }

    public String getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public long getEndNanos() {
        return endNanos;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public List<Span> getChildren() {
        return children;
    }

    /** Records a key/value on this span (fluent, for chaining at the call site); exporters render these under {@code attributes}/{@code metadata} depending on format. */
    public Span setAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    /** Marks this span as failed and records {@code message} as {@link #getErrorMessage()}; exporters surface both as the span's error/status fields. */
    public Span setError(String message) {
        this.status = "ERROR";
        this.errorMessage = message;
        return this;
    }

    void end() {
        this.endNanos = System.nanoTime();
        this.endEpochNanos = epochNanosNow();
    }

    void addChild(Span child) {
        children.add(child);
    }

    /** Elapsed time between start and {@link #end()}, in fractional milliseconds using the monotonic clock; {@code -1} if the span hasn't ended yet. */
    public double durationMillis() {
        if (endNanos < 0) {
            return -1;
        }
        return (endNanos - startNanos) / 1_000_000.0;
    }
}
