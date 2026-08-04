package org.jopenagent.sandbox;

/**
 * Common shape for {@code CODE_ACT}'s code-execution backend, so {@link org.jopenagent.core.strategy.CodeActStrategy}
 * doesn't need to know whether it's talking to the default in-process {@link CodeSandbox} or the
 * opt-in {@link org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox}.
 */
public interface CodeExecutor extends AutoCloseable {

    /**
     * Runs one snippet of generated Java source and reports the outcome. Implementations differ
     * only in trust model and containment strength: {@link CodeSandbox} runs {@code code} in the
     * host JVM via a persistent JShell session, while {@link org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox}
     * forwards it to a separate child-JVM worker, but both validate against {@link CodeValidator}
     * first and return the same {@link SandboxResult} shape either way.
     */
    SandboxResult execute(String code);

    /** Releases whatever execution resources the backend holds (a JShell session, or a worker process and its socket). */
    @Override
    void close();
}
