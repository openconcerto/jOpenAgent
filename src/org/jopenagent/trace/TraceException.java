package org.jopenagent.trace;

/** Thrown by a {@link TraceExporter} when a trace cannot be exported (e.g. file write failure). */
public class TraceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TraceException(String message, Throwable cause) {
        super(message, cause);
    }
}
