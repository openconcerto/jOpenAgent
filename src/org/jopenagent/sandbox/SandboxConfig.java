package org.jopenagent.sandbox;

/**
 * Configures the {@code CODE_ACT} execution backend. {@link SandboxMode#IN_PROCESS} (the default)
 * shares the host JVM: cheap to start, but a hard timeout can only ask JShell to stop, which
 * cannot preempt a tight infinite loop. {@link SandboxMode#OUT_OF_PROCESS} runs generated code in
 * a separate child JVM (see {@link org.jopenagent.sandbox.remote.OutOfProcessCodeSandbox}), so a timeout
 * is a real {@link Process#destroyForcibly()} kill: the only way to actually contain untrusted or
 * unpredictable generated code.
 */
public final class SandboxConfig {

    private final SandboxMode mode;
    private final long timeoutMillis;
    private final int heapMb;

    private SandboxConfig(Builder builder) {
        this.mode = builder.mode;
        this.timeoutMillis = builder.timeoutMillis;
        this.heapMb = builder.heapMb;
    }

    public SandboxMode getMode() {
        return mode;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getHeapMb() {
        return heapMb;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link SandboxConfig}; defaults to {@link SandboxMode#IN_PROCESS}, a 5s per-call timeout, and a 256MB child-JVM heap cap. */
    public static final class Builder {
        private SandboxMode mode = SandboxMode.IN_PROCESS;
        private long timeoutMillis = 5000L;
        private int heapMb = 256;

        public Builder mode(SandboxMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        /** Heap cap for the child JVM; only meaningful when {@link #mode} is {@link SandboxMode#OUT_OF_PROCESS}. */
        public Builder heapMb(int heapMb) {
            this.heapMb = heapMb;
            return this;
        }

        public SandboxConfig build() {
            return new SandboxConfig(this);
        }
    }
}
