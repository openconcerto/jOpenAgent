package org.jopenagent.trace;

/** Convenience wrapper around {@link Tracer} so call sites don't repeat null/enabled checks. */
public final class Traced {

    private Traced() {
    }

    /** Starts a span, or returns null if tracing is disabled (all other methods here tolerate null). */
    public static Span begin(String kind, String name) {
        if (!Tracer.isEnabled()) {
            return null;
        }
        return Tracer.start(kind, name);
    }

    /** Ends {@code span} via {@link Tracer#end(Span)}, or does nothing if {@code span} is null (tracing disabled). */
    public static void finish(Span span) {
        if (span != null) {
            Tracer.end(span);
        }
    }

    /** Records {@code error} on {@code span} (via {@link Span#setError(String)}) and then ends it, or does nothing if {@code span} is null (tracing disabled). */
    public static void fail(Span span, Throwable error) {
        if (span != null) {
            span.setError(error.getMessage() != null ? error.getMessage() : error.toString());
            Tracer.end(span);
        }
    }
}
