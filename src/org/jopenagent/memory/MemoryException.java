package org.jopenagent.memory;

/** Raised on memory store/embedding failures. */
public class MemoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MemoryException(String message) {
        super(message);
    }

    public MemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
