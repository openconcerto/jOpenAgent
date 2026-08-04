package org.jopenagent.tools;

/** Raised when a tool call cannot be dispatched or the invoked method throws. */
public class ToolInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolInvocationException(String message) {
        super(message);
    }

    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
