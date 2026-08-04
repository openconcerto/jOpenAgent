package org.jopenagent.skills;

/** The outcome of one {@link TextSkill#runScript} call. */
public final class ScriptResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;

    ScriptResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }

    @Override
    public String toString() {
        return "ScriptResult{exitCode=" + exitCode + ", timedOut=" + timedOut + ", stdout='" + stdout + "', stderr='" + stderr + "'}";
    }
}
