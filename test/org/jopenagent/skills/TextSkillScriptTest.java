package org.jopenagent.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real (non-mocked) subprocess tests: every script here is genuinely executed by
 * {@link ScriptRunner} via {@code ProcessBuilder}. Guarded to Windows (this project's target
 * platform) since the fixtures are PowerShell scripts; skipped elsewhere rather than failing.
 */
class TextSkillScriptTest {

    private Path skillDir;
    private Path scriptsDir;

    @BeforeEach
    void checkPlatform() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"), "script fixtures are PowerShell, Windows-only");
    }

    private TextSkill loadSkill(Path tempDir) throws IOException {
        skillDir = Files.createDirectory(tempDir.resolve("with-scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: with-scripts\ndescription: test\n---\nbody\n");
        scriptsDir = Files.createDirectory(skillDir.resolve("scripts"));
        return TextSkill.load(skillDir);
    }

    @Test
    void runsScriptAndCapturesStdout(@TempDir Path tempDir) throws IOException {
        TextSkill skill = loadSkill(tempDir);
        Files.writeString(scriptsDir.resolve("hello.ps1"), "Write-Output 'hello from script'\n");

        ScriptResult result = skill.runScript("hello.ps1");

        assertTrue(result.isSuccess(), result.toString());
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("hello from script"), result.getStdout());
        assertTrue(!result.isTimedOut());
    }

    @Test
    void passesArgumentsThrough(@TempDir Path tempDir) throws IOException {
        TextSkill skill = loadSkill(tempDir);
        Files.writeString(scriptsDir.resolve("echo-args.ps1"), "param($a, $b)\nWrite-Output \"got: $a $b\"\n");

        ScriptResult result = skill.runScript("echo-args.ps1", "one", "two");

        assertTrue(result.getStdout().contains("got: one two"), result.getStdout());
    }

    @Test
    void capturesNonZeroExitCode(@TempDir Path tempDir) throws IOException {
        TextSkill skill = loadSkill(tempDir);
        Files.writeString(scriptsDir.resolve("fail.ps1"), "exit 3\n");

        ScriptResult result = skill.runScript("fail.ps1");

        assertEquals(3, result.getExitCode());
        assertTrue(!result.isSuccess());
    }

    @Test
    void enforcesTimeoutWithARealProcessKill(@TempDir Path tempDir) throws IOException {
        TextSkill skill = loadSkill(tempDir);
        Files.writeString(scriptsDir.resolve("sleep.ps1"), "Start-Sleep -Seconds 15\n");

        long start = System.currentTimeMillis();
        ScriptResult result = skill.runScript("sleep.ps1", null, 800L);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.isTimedOut());
        // A genuine kill returns almost immediately after the timeout, nowhere near the script's
        // own 15-second sleep, proving the process was actually terminated, not merely outlived.
        assertTrue(elapsed < 10_000, "expected a fast timeout, took " + elapsed + "ms");
    }

    @Test
    void rejectsScriptPathEscapingTheScriptsDirectory(@TempDir Path tempDir) throws IOException {
        final TextSkill skill = loadSkill(tempDir);
        Files.writeString(tempDir.resolve("evil.ps1"), "Write-Output 'should not run'\n");

        assertThrows(SkillException.class, new Executable() {
            @Override
            public void execute() {
                skill.runScript("../evil.ps1");
            }
        });
    }

    @Test
    void throwsWhenScriptDoesNotExist(@TempDir Path tempDir) throws IOException {
        final TextSkill skill = loadSkill(tempDir);

        assertThrows(SkillException.class, new Executable() {
            @Override
            public void execute() {
                skill.runScript("does-not-exist.ps1");
            }
        });
    }
}
