package org.jopenagent.sandbox;

import java.io.File;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jdk.jshell.Diag;
import jdk.jshell.EvalException;
import jdk.jshell.JShell;
import jdk.jshell.JShellException;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;

/**
 * Code-as-action execution: a persistent JShell REPL session with a live {@code self} variable
 * bound to the agent instance, so generated code can call {@code self.someMethod(...)} directly
 *, jOpenAgent's equivalent of NOOA's {@code exec()}-based execution with {@code self} injected into
 * {@code exec_globals} (see nooa/src/nooa/runtime/actor.py). State (declared variables) persists
 * across calls to {@link #execute(String)} within one session, just like NOOA's CodeAct REPL.
 *
 * <p>Uses JShell's "local" execution engine, i.e. snippets run <b>in the same JVM</b> as the host
 * application (not a child process): this is what makes a genuinely live, mutable {@code self}
 * reference possible without serialization, but it also means this is the same trust model as
 * NOOA's default in-process {@code exec()} path: {@link CodeValidator} is a guardrail, not a
 * security boundary. Running this from Eclipse requires the VM argument
 * {@code --add-modules jdk.jshell} (jdk.jshell is a JDK tool module, not part of the default
 * module set resolved for classpath/unnamed-module code).
 */
public final class CodeSandbox implements CodeExecutor {

    private final JShell jshell;
    private final ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
    private final PrintStream captured;
    private final long timeoutMillis;

    /**
     * Starts a fresh JShell session, adds the host application's own classpath to it (so generated
     * code can reference any class the host can), and binds {@code boundSelf} as the session's
     * {@code self} variable (see {@link #bindSelf(Object)}).
     *
     * @param boundSelf     the live agent instance generated code will call {@code self.method(...)} on
     * @param timeoutMillis per-call wall-clock budget enforced by the watchdog in {@link #execute(String)}
     */
    public CodeSandbox(Object boundSelf, long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        this.captured = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        this.jshell = JShell.builder().out(captured).err(captured).executionEngine("local").build();
        addHostClasspath();
        bindSelf(boundSelf);
    }

    private void addHostClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                jshell.addToClasspath(entry);
            }
        }
    }

    private void bindSelf(Object self) {
        String typeName = self.getClass().getCanonicalName();
        if (typeName == null) {
            throw new SandboxException("Cannot bind 'self': " + self.getClass() + " has no canonical name (local/anonymous classes are not supported as agents).");
        }
        // Holding the lock for this whole synchronous sequence is what makes the handoff safe:
        // jshell.eval() blocks until the declaration snippet (executed on JShell's own internal
        // execution thread, even under the "local" engine) has read the field back out.
        synchronized (SandboxBindings.LOCK) {
            SandboxBindings.set(self);
            try {
                runOrThrow("import org.jopenagent.sandbox.SandboxBindings;");
                runOrThrow(typeName + " self = (" + typeName + ") SandboxBindings.current();");
            } finally {
                SandboxBindings.clear();
            }
        }
    }

    private void runOrThrow(String snippet) {
        List<SnippetEvent> events = jshell.eval(snippet);
        for (SnippetEvent event : events) {
            if (event.status() == Snippet.Status.REJECTED) {
                throw new SandboxException("Failed to prepare sandbox: " + diagnosticsOf(event) + " (snippet: " + snippet + ")");
            }
        }
    }

    /** Executes one snippet of Java statements/expressions, validating it against {@link CodeValidator} first. */
    @Override
    public SandboxResult execute(String code) {
        CodeValidator.ValidationResult validation = CodeValidator.validate(code);
        if (!validation.isValid()) {
            return SandboxResult.rejected(validation.getViolations());
        }

        outBuffer.reset();
        AtomicBoolean stopped = new AtomicBoolean(false);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
        watchdog.schedule(new StopTask(jshell, stopped), timeoutMillis, TimeUnit.MILLISECONDS);

        // The "local" execution engine runs snippets in this very JVM, so evaluated code's
        // System.out.println(...) goes to the real System.out, JShell.Builder.out()/err() only
        // capture JShell's own feedback stream, not the executed code's output, unless we swap
        // the real System.out/System.err for the duration of the call.
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        List<SnippetEvent> events;
        try {
            System.setOut(captured);
            System.setErr(captured);
            events = JShellScriptRunner.evalAll(jshell, code);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            watchdog.shutdownNow();
        }

        String stdout = outBuffer.toString(StandardCharsets.UTF_8);
        if (stopped.get()) {
            return SandboxResult.timedOut(timeoutMillis);
        }

        List<String> messages = new ArrayList<>();
        String lastValue = null;
        boolean hasError = false;
        for (SnippetEvent event : events) {
            if (event.status() == Snippet.Status.REJECTED) {
                hasError = true;
                messages.add(diagnosticsOf(event));
            } else if (event.exception() != null) {
                hasError = true;
                messages.add(describeException(event.exception()));
            } else if (event.value() != null && !event.value().isEmpty()) {
                // Statement snippets (including a trailing top-level `return x;`, which JShell
                // compiles as a plain statement and discards the value of) report "" rather than
                // null; letting them through would clobber the last real expression value.
                lastValue = event.value();
            }
        }
        if (hasError) {
            return SandboxResult.runtimeError(stdout, messages);
        }
        return SandboxResult.ok(stdout, lastValue);
    }

    private String diagnosticsOf(SnippetEvent event) {
        List<String> texts = jshell.diagnostics(event.snippet()).map(new DiagToString()).collect(Collectors.toList());
        return texts.isEmpty() ? "compile error" : String.join("; ", texts);
    }

    /** Renders one {@link Diag} as the plain-text message shown to the LLM in a compile-error result. */
    private static final class DiagToString implements java.util.function.Function<Diag, String> {
        @Override
        public String apply(Diag diag) {
            return diag.getMessage(null);
        }
    }

    private static String describeException(JShellException e) {
        if (e instanceof EvalException) {
            EvalException evalException = (EvalException) e;
            return evalException.getExceptionClassName() + ": " + evalException.getMessage();
        }
        return e.toString();
    }

    /** Watchdog task, scheduled once per {@link #execute(String)} call, that force-stops a snippet still running past its timeout. */
    private static final class StopTask implements Runnable {
        private final JShell jshell;
        private final AtomicBoolean stopped;

        StopTask(JShell jshell, AtomicBoolean stopped) {
            this.jshell = jshell;
            this.stopped = stopped;
        }

        @Override
        public void run() {
            stopped.set(true);
            jshell.stop();
        }
    }

    /** Marks the per-call watchdog thread as a daemon so a pending timeout never keeps the JVM alive on its own. */
    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "jooa-sandbox-watchdog");
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Shuts down the underlying JShell session, ending the persistent variable/state scope. */
    @Override
    public void close() {
        jshell.close();
    }
}
