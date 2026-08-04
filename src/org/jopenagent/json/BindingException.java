package org.jopenagent.json;

/**
 * Raised when a JSON value cannot be bound to (or produced from) a declared Java type.
 * The message is designed to be readable by an LLM so it can be fed back verbatim
 * as retry-correction feedback (see org.jopenagent.core.strategy.PredictStrategy).
 */
public class BindingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BindingException(String message) {
        super(message);
    }

    public BindingException(String message, Throwable cause) {
        super(message, cause);
    }
}
