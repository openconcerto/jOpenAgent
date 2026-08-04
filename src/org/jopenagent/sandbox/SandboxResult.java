package org.jopenagent.sandbox;

import java.util.Collections;
import java.util.List;

/** The outcome of one {@link CodeSandbox#execute(String)} call, rendered back to the LLM as a tool result. */
public final class SandboxResult {

    /** Outcome classification of one {@link CodeSandbox#execute(String)} call. */
    public enum Status {
        /** All snippets evaluated without error; {@link #getLastValue()} holds the final expression's value, if any. */
        OK,
        /** A snippet failed to compile; see {@link #getMessages()} for the diagnostics. Currently folded into {@link #RUNTIME_ERROR} by the executors, but kept distinct for callers that want to tell the two apart. */
        COMPILE_ERROR,
        /** A snippet compiled but threw, or was rejected, during evaluation; see {@link #getMessages()}. */
        RUNTIME_ERROR,
        /** {@link CodeValidator} rejected the code before it was ever run; see {@link #getMessages()} for the violated rules. */
        REJECTED,
        /** Execution exceeded its time budget and was force-stopped (in-process) or the whole worker process was killed (out-of-process). */
        TIMED_OUT
    }

    private final Status status;
    private final String stdout;
    private final String lastValue;
    private final List<String> messages;

    private SandboxResult(Status status, String stdout, String lastValue, List<String> messages) {
        this.status = status;
        this.stdout = stdout;
        this.lastValue = lastValue;
        this.messages = messages;
    }

    /** A successful run: captured stdout plus the final snippet's value (or {@code null} if the last snippet had none). */
    public static SandboxResult ok(String stdout, String lastValue) {
        return new SandboxResult(Status.OK, stdout, lastValue, Collections.<String>emptyList());
    }

    /** Code never ran: {@link CodeValidator} rejected it up front, with {@code violations} as the messages. */
    public static SandboxResult rejected(List<String> violations) {
        return new SandboxResult(Status.REJECTED, "", null, violations);
    }

    /** Code ran but a snippet failed to compile or threw; {@code messages} carries the diagnostics/exception text, in evaluation order. */
    public static SandboxResult runtimeError(String stdout, List<String> messages) {
        return new SandboxResult(Status.RUNTIME_ERROR, stdout, null, messages);
    }

    /** Execution was force-stopped after exceeding {@code timeoutMillis}; no partial stdout/value is reported. */
    public static SandboxResult timedOut(long timeoutMillis) {
        return new SandboxResult(Status.TIMED_OUT, "", null, Collections.singletonList("Execution exceeded " + timeoutMillis + "ms and was stopped."));
    }

    public Status getStatus() {
        return status;
    }

    public String getStdout() {
        return stdout;
    }

    public String getLastValue() {
        return lastValue;
    }

    public List<String> getMessages() {
        return messages;
    }

    /** Plain-text rendering fed back to the LLM as the execute-tool's result. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(status).append("\n");
        if (stdout != null && !stdout.isEmpty()) {
            sb.append("stdout:\n").append(stdout).append("\n");
        }
        if (lastValue != null) {
            sb.append("value: ").append(lastValue).append("\n");
        }
        if (!messages.isEmpty()) {
            sb.append("messages:\n");
            for (String message : messages) {
                sb.append("- ").append(message).append("\n");
            }
        }
        return sb.toString();
    }
}
