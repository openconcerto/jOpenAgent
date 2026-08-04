package org.jopenagent.sandbox;

/**
 * Selects {@code CODE_ACT}'s execution backend.
 *
 * <p>NOOA's own default is in-process {@code exec()}, with an opt-in out-of-process worker
 * (Linux-only Landlock/seccomp) for real containment; jOpenAgent mirrors that same shape:
 * {@link #IN_PROCESS} (default) runs generated code in the host JVM via {@link CodeSandbox} 
 * simple, fast, but sharing full trust with the host process, and a timeout that cannot preempt a
 * tight infinite loop. {@link #OUT_OF_PROCESS} runs it in a separate child JVM
 * ({@link org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox}), giving a genuine hard-kill on
 * timeout and an independent heap limit, at the cost of a JVM-startup-per-session and a small
 * per-call RPC round trip for every {@code self.method(...)} call generated code makes.
 */
public enum SandboxMode {
    /** Run generated code in the host JVM via {@link org.jopenagent.sandbox.CodeSandbox}. Default. */
    IN_PROCESS,
    /** Run generated code in a separate child JVM via {@link org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox}. */
    OUT_OF_PROCESS
}
