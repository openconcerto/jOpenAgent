package org.jopenagent.trace.explorer;

import java.io.PrintStream;
import java.util.Map;

import org.jopenagent.trace.Span;

/**
 * Pretty-prints a span tree to a terminal: jOpenAgent's equivalent of NOOA's {@code trace_explorer}
 * (nooa/src/nooa/trace_explorer/explorer.py), scoped to what a plain {@code PrintStream} can do
 * (NOOA's is an interactive TUI; this is a one-shot renderer, consistent with jOpenAgent having no
 * bundled CLI/TUI framework, see design notes).
 */
public final class TerminalTraceExplorer {

    private TerminalTraceExplorer() {
    }

    /** Prints {@code rootSpan}'s tree to {@link System#out}; see {@link #print(Span, PrintStream)}. */
    public static void print(Span rootSpan) {
        print(rootSpan, System.out);
    }

    /** Recursively renders {@code rootSpan} and its descendants as an indented tree (kind, name, duration, error/attribute lines) to {@code out}. */
    public static void print(Span rootSpan, PrintStream out) {
        printSpan(rootSpan, out, "", true);
    }

    private static void printSpan(Span span, PrintStream out, String prefix, boolean isLast) {
        String branch = prefix.isEmpty() ? "" : (isLast ? "\\-- " : "|-- ");
        String statusMarker = "ERROR".equals(span.getStatus()) ? " [ERROR]" : "";
        out.println(prefix + branch + "[" + span.getKind() + "] " + span.getName() + " (" + formatDuration(span) + "ms)" + statusMarker);
        if ("ERROR".equals(span.getStatus()) && span.getErrorMessage() != null) {
            out.println(childPrefix(prefix, isLast) + "    ! " + span.getErrorMessage());
        }
        for (Map.Entry<String, Object> attribute : span.getAttributes().entrySet()) {
            out.println(childPrefix(prefix, isLast) + "    " + attribute.getKey() + " = " + summarize(attribute.getValue()));
        }
        String nextPrefix = childPrefix(prefix, isLast);
        for (int i = 0; i < span.getChildren().size(); i++) {
            boolean lastChild = (i == span.getChildren().size() - 1);
            printSpan(span.getChildren().get(i), out, nextPrefix, lastChild);
        }
    }

    private static String childPrefix(String prefix, boolean isLast) {
        if (prefix.isEmpty()) {
            return "    ";
        }
        return prefix + (isLast ? "    " : "|   ");
    }

    private static String formatDuration(Span span) {
        double duration = span.durationMillis();
        return duration < 0 ? "?" : String.format("%.1f", duration);
    }

    private static String summarize(Object value) {
        String text = String.valueOf(value);
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
