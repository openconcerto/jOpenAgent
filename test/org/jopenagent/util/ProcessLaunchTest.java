package org.jopenagent.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks in the fix for a real orphaned-process bug: wrapping a genuine native executable (like
 * {@code powershell}) through {@code cmd.exe} made the returned {@code Process} handle refer to
 * the wrapper, not the real program, so killing it left the actual process running as an orphan.
 */
class ProcessLaunchTest {

    @BeforeEach
    void windowsOnly() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"), "this behavior is Windows-specific; other platforms never wrap");
    }

    @Test
    void nativeExecutableFoundOnPathIsNotWrapped() {
        // powershell.exe ships in System32, which is always on PATH on Windows.
        List<String> result = ProcessLaunch.wrapForWindowsIfNeeded(List.of("powershell", "-Command", "exit 0"));
        assertEquals(List.of("powershell", "-Command", "exit 0"), result);
    }

    @Test
    void explicitExeSuffixIsNeverWrapped() {
        List<String> result = ProcessLaunch.wrapForWindowsIfNeeded(List.of("something.exe", "arg"));
        assertEquals(List.of("something.exe", "arg"), result);
    }

    @Test
    void explicitCmdSuffixIsAlwaysWrapped() {
        List<String> result = ProcessLaunch.wrapForWindowsIfNeeded(List.of("npx.cmd", "-y", "some-package"));
        assertEquals(List.of("cmd.exe", "/c", "npx.cmd", "-y", "some-package"), result);
    }

    @Test
    void bareNameWithNoMatchingExeOnPathIsWrapped() {
        List<String> result = ProcessLaunch.wrapForWindowsIfNeeded(List.of("npx", "-y", "@modelcontextprotocol/server-everything"));
        assertTrue(result.get(0).equals("cmd.exe"), result.toString());
        assertEquals("/c", result.get(1));
    }

    @Test
    void fullPathToRealExecutableWithoutExtensionIsNotWrapped() {
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
        List<String> result = ProcessLaunch.wrapForWindowsIfNeeded(List.of(javaExecutable, "-version"));
        assertEquals(List.of(javaExecutable, "-version"), result);
    }
}
