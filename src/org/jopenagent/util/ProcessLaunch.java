package org.jopenagent.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared {@code ProcessBuilder} launch helper, used by both {@link org.jopenagent.mcp.McpClient}
 * (spawning MCP servers) and {@link org.jopenagent.skills.TextSkill} (running a skill's bundled
 * scripts): anywhere jOpenAgent launches an external command.
 */
public final class ProcessLaunch {

    private ProcessLaunch() {
    }

    /**
     * On Windows, {@code ProcessBuilder} can launch a native {@code .exe} directly (Windows'
     * {@code CreateProcess} resolves an extension-less bare name against {@code PATH} and appends
     * {@code .exe} itself) but cannot launch a {@code .cmd}/{@code .bat} script the same way. It
     * has no shell-style file-association handling. Most Node-based tooling (`npx`, `npm`) is
     * exactly such a shim, so those need routing through {@code cmd.exe /c} (the same workaround
     * Node's own {@code child_process} documentation recommends).
     *
     * <p>The two cases must be told apart rather than wrapping everything that doesn't literally
     * end in {@code .exe}: wrapping a genuine native executable (e.g. {@code powershell}, which is
     * commonly invoked by its bare name) through {@code cmd.exe} makes the {@link Process} handle
     * returned by {@code ProcessBuilder} refer to {@code cmd.exe} itself, not the real program 
     * so killing that handle (e.g. on a timeout) kills the wrapper while leaving the actual
     * process it spawned as an orphan, still running and still holding open file handles. This is
     * resolved by probing for a matching {@code .exe} (as given, or on {@code PATH}) before
     * deciding to wrap; only bare names with no such native executable are wrapped.
     */
    public static List<String> wrapForWindowsIfNeeded(List<String> command) {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return command;
        }
        if (isDirectlyLaunchable(command.get(0))) {
            return command;
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    private static boolean isDirectlyLaunchable(String program) {
        String lower = program.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".com")) {
            return true;
        }
        if (lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            return false;
        }
        if (new File(program + ".exe").isFile()) {
            return true;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            if (new File(dir, program + ".exe").isFile()) {
                return true;
            }
        }
        return false;
    }
}
