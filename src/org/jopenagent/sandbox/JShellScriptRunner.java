package org.jopenagent.sandbox;

import java.util.ArrayList;
import java.util.List;

import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;

/**
 * Feeds a multi-statement source string to a {@link JShell} session one complete top-level
 * snippet at a time.
 *
 * <p>{@link JShell#eval(String)} does <b>not</b> behave like pasting a multi-line script into the
 * interactive {@code jshell} tool: given e.g. {@code "int x = 1; x * 2;"} in a single call, it
 * evaluates only the first complete snippet (the declaration) and silently discards the rest of
 * the input: there is no error, no rejected snippet, just fewer {@link SnippetEvent}s than
 * statements. The real {@code jshell} tool avoids this by repeatedly asking
 * {@link SourceCodeAnalysis#analyzeCompletion} for the next complete snippet and calling
 * {@code eval} on each one in turn; both {@link CodeSandbox} and the out-of-process worker
 * ({@code org.jopenagent.sandbox.remote.SandboxWorkerMain}) need generated code with more than one
 * statement to actually all run, so they share this instead of calling {@code eval} directly.
 */
public final class JShellScriptRunner {

    private JShellScriptRunner() {
    }

    /**
     * Repeatedly peels the next complete top-level snippet off {@code source} via
     * {@link SourceCodeAnalysis#analyzeCompletion} and evaluates it, until nothing complete remains
     * (a trailing incomplete fragment is silently dropped, same as the real {@code jshell} tool
     * would leave unevaluated at end of input). Returns every {@link SnippetEvent} produced, in
     * source order, across all the individual {@code eval} calls this makes.
     */
    public static List<SnippetEvent> evalAll(JShell jshell, String source) {
        List<SnippetEvent> events = new ArrayList<>();
        String remaining = source;
        while (!remaining.trim().isEmpty()) {
            SourceCodeAnalysis.CompletionInfo info = jshell.sourceCodeAnalysis().analyzeCompletion(remaining);
            if (info.source().isEmpty()) {
                break;
            }
            events.addAll(jshell.eval(info.source()));
            remaining = info.remaining();
        }
        return events;
    }
}
