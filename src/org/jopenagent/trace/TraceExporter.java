package org.jopenagent.trace;

/**
 * Receives completed root spans (a full call tree) from {@link Tracer}. Registered via
 * {@link Tracer#addExporter(TraceExporter)}, an exporter is invoked exactly once per top-level
 * span: i.e. once {@link Tracer#end(Span)} pops the last frame off the calling thread's
 * per-thread span stack, with that span's {@link Span#getChildren()} already fully populated, so
 * implementations never see a partially-built tree. Concrete implementations translate this tree
 * into a specific wire format/destination: {@link JsonTraceExporter} (plain JSON), {@link
 * org.jopenagent.trace.atif.AtifExporter} (ATIF-v1.7), and {@link org.jopenagent.trace.otlp.OtlpHttpExporter}
 * (OTLP/HTTP, including its {@link org.jopenagent.trace.otlp.LangfuseExporter} preset).
 */
public interface TraceExporter {

    /** Called once, after {@code rootSpan} and its entire subtree have finished. Implementations should not block the caller for longer than acceptable inside {@link Tracer#end(Span)}. */
    void export(Span rootSpan);
}
