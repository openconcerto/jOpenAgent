package org.jopenagent.sandbox;

/** Raised when the sandbox itself cannot be prepared (e.g. failed to bind {@code self}). */
public class SandboxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
