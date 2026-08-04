package org.jopenagent.skills;

/** Raised on skill loading/lookup/file-access failures. */
public class SkillException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SkillException(String message) {
        super(message);
    }

    public SkillException(String message, Throwable cause) {
        super(message, cause);
    }
}
