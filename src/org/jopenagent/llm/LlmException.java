package org.jopenagent.llm;

/** Thrown by an {@link LlmClient} on transport failure, non-2xx response, or unparseable provider output. */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
