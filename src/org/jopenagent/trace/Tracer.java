package org.jopenagent.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Parent-child span tracking. Nesting is tracked per-thread via an explicit stack (jOpenAgent has no
 * async/coroutine model to mirror NOOA's ContextVar-based active-span map, so a plain
 * ThreadLocal stack gives the same nesting guarantee for the synchronous call graph jOpenAgent uses).
 *
 * <p>{@code start(kind, name)} pushes a new span whose parent is whatever is currently on top of
 * this thread's stack; {@code end(span)} pops it and, once the stack empties back to a root span,
 * hands the completed tree to every registered {@link TraceExporter}.
 */
public final class Tracer {

    private static final ThreadLocal<Deque<Span>> STACK = new ThreadLocal<Deque<Span>>() {
        @Override
        protected Deque<Span> initialValue() {
            return new ArrayDeque<Span>();
        }
    };

    private static final List<Span> ROOT_SPANS = Collections.synchronizedList(new ArrayList<Span>());
    private static final List<TraceExporter> EXPORTERS = new CopyOnWriteArrayList<TraceExporter>();
    private static volatile boolean enabled = true;

    private Tracer() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Registers {@code exporter} to receive every future root span, process-wide, until removed via {@link #removeAllExporters()}. */
    public static void addExporter(TraceExporter exporter) {
        EXPORTERS.add(exporter);
    }

    /** Unregisters every currently-registered {@link TraceExporter} (mainly for test isolation between cases). */
    public static void removeAllExporters() {
        EXPORTERS.clear();
    }

    /**
     * Pushes a new span of kind {@code kind}/name {@code name} onto the <em>calling thread's</em>
     * span stack, parented to whatever span (if any) is currently on top of that stack. This is
     * what gives nested {@code generation}/{@code llm_call}/{@code tool_execution}/
     * {@code code_execution} spans their parent-child shape with no explicit parent parameter to
     * thread through call sites. Callers are expected to eventually pass the returned span to
     * {@link #end(Span)}; see {@link Traced#begin(String, String)} for the null-tolerant wrapper
     * used at most call sites instead of calling this directly.
     */
    public static Span start(String kind, String name) {
        Deque<Span> stack = STACK.get();
        Span parent = stack.peek();
        Span span = new Span(UUID.randomUUID().toString(), parent != null ? parent.getId() : null, kind, name);
        stack.push(span);
        return span;
    }

    /**
     * Pops {@code span} off the calling thread's span stack (marking it ended) and either attaches
     * it as a child of the new stack top, or, once the stack has unwound all the way back to a
     * root span: records it as a completed root and hands the full tree to every registered
     * {@link TraceExporter} in registration order. Exporters therefore only ever see whole trees,
     * never a span mid-flight.
     */
    public static void end(Span span) {
        span.end();
        Deque<Span> stack = STACK.get();
        if (!stack.isEmpty() && stack.peek() == span) {
            stack.pop();
        }
        Span parent = stack.peek();
        if (parent != null) {
            parent.addChild(span);
            return;
        }
        ROOT_SPANS.add(span);
        for (TraceExporter exporter : EXPORTERS) {
            exporter.export(span);
        }
    }

    /** The span currently on top of this thread's stack, or null if no span is active. */
    public static Span current() {
        return STACK.get().peek();
    }

    public static List<Span> getRootSpans() {
        synchronized (ROOT_SPANS) {
            return Collections.unmodifiableList(new ArrayList<Span>(ROOT_SPANS));
        }
    }

    public static void clear() {
        ROOT_SPANS.clear();
    }
}
