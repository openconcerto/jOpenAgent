package org.jopenagent.sandbox;

/**
 * A synchronized handoff for the live agent instance a {@link CodeSandbox} binds as {@code self}.
 * JShell's {@code eval()} API has no direct "inject this object reference" call, so a snippet
 * that declares {@code self} reads it back out of this static holder instead.
 *
 * <p>Even JShell's "local" execution engine (same JVM, not a child process, see
 * {@link CodeSandbox}) runs submitted snippets on its own internal execution thread rather than
 * the caller's thread, so a {@code ThreadLocal} handoff does not survive the JShell boundary; a
 * plain field guarded by {@link #LOCK} does, because {@link CodeSandbox} holds the lock for the
 * whole synchronous "set field, eval the declaration, clear field" sequence.
 */
public final class SandboxBindings {

    /** Held by {@link CodeSandbox} for the entire synchronous "set field, eval the declaration, clear field" handoff described above. */
    public static final Object LOCK = new Object();
    private static volatile Object current;

    private SandboxBindings() {
    }

    /** Publishes {@code value} for a subsequent {@code self} declaration snippet to read via {@link #current()}. Caller must hold {@link #LOCK}. */
    public static void set(Object value) {
        current = value;
    }

    /** Read back by the declaration snippet running on JShell's own execution thread. */
    public static Object current() {
        return current;
    }

    /** Clears the handoff slot once the declaration snippet has read it, so it doesn't linger and leak into an unrelated session. */
    public static void clear() {
        current = null;
    }
}
