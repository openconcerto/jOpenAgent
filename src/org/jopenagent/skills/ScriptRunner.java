package org.jopenagent.skills;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jopenagent.util.ProcessLaunch;

/** Launches a skill's script as a real subprocess, captures its output, and enforces a hard timeout (a genuine process kill, unlike an in-JVM sandbox stop). */
final class ScriptRunner {

    private ScriptRunner() {
    }

    /**
     * Launches {@code command} in {@code workingDir}, draining stdout/stderr on daemon reader
     * threads (so a full pipe buffer can never deadlock the wait below), and waits up to
     * {@code timeoutMillis}. On timeout, force-kills the process and blocks briefly for the OS to
     * actually finish reclaiming it, see the comment below on why a mere "kill requested" state
     * isn't enough on Windows.
     */
    static ScriptResult run(List<String> command, Path workingDir, long timeoutMillis) {
        ProcessBuilder builder = new ProcessBuilder(ProcessLaunch.wrapForWindowsIfNeeded(command));
        builder.directory(workingDir.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SkillException("Failed to launch script: " + e.getMessage(), e);
        }

        StreamCapture stdoutCapture = new StreamCapture(process.getInputStream());
        StreamCapture stderrCapture = new StreamCapture(process.getErrorStream());
        Thread stdoutThread = new Thread(stdoutCapture, "jooa-script-stdout");
        Thread stderrThread = new Thread(stderrCapture, "jooa-script-stderr");
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SkillException("Script execution interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            // destroyForcibly() only requests termination; on Windows in particular the OS
            // reclaims the process (and releases any file handles it held, e.g. its working
            // directory) asynchronously. Waiting here for the actual exit means callers observe a
            // fully-dead process, not one merely "kill requested", otherwise e.g. deleting the
            // working directory right after this returns can fail as still-in-use.
            waitForDeathQuietly(process);
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            return new ScriptResult(-1, stdoutCapture.text(), stderrCapture.text(), true);
        }
        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);
        return new ScriptResult(process.exitValue(), stdoutCapture.text(), stderrCapture.text(), false);
    }

    private static void waitForDeathQuietly(Process process) {
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drains an {@link InputStream} into an in-memory buffer on its own thread. */
    private static final class StreamCapture implements Runnable {
        private final InputStream input;
        private final StringBuilder buffer = new StringBuilder();

        StreamCapture(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                char[] chunk = new char[4096];
                int read;
                while ((read = reader.read(chunk)) != -1) {
                    buffer.append(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // the process closed its stream; whatever was captured so far stands
            }
        }

        String text() {
            return buffer.toString();
        }
    }
}
