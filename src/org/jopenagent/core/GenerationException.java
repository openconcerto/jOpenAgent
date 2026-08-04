package org.jopenagent.core;

import java.util.Collections;
import java.util.List;

/** Raised when a {@code @Generative} method fails to produce a valid result after all retry attempts. */
public class GenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> failedAttempts;

    /**
     * @param failedAttempts a description of each prior attempt that failed (e.g. validation
     *                       errors), appended to {@code message} as a bulleted list; may be null/empty
     * @param cause          the underlying failure of the last attempt, or null
     */
    public GenerationException(String message, List<String> failedAttempts, Throwable cause) {
        super(buildMessage(message, failedAttempts), cause);
        this.failedAttempts = failedAttempts != null ? failedAttempts : Collections.<String>emptyList();
    }

    public List<String> getFailedAttempts() {
        return failedAttempts;
    }

    private static String buildMessage(String message, List<String> failedAttempts) {
        if (failedAttempts == null || failedAttempts.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        for (String attempt : failedAttempts) {
            sb.append("\n  - ").append(attempt);
        }
        return sb.toString();
    }
}
