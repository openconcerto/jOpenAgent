package org.jopenagent.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class TextSkillTest {

    @Test
    void parsesFrontmatterAndBody(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("frontend-design"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\n" + "name: frontend-design\n" + "description: Guidance for frontend design tasks.\n" + "---\n\n"
                + "# Frontend Design\n\nUse consistent spacing.\n");

        TextSkill skill = TextSkill.load(skillDir);

        assertEquals("frontend-design", skill.getId());
        assertEquals("Guidance for frontend design tasks.", skill.getDescription());
        assertTrue(skill.getInstructions().contains("Use consistent spacing."));
    }

    @Test
    void missingRequiredFrontmatterFieldsThrows(@TempDir Path tempDir) throws IOException {
        final Path skillDir = Files.createDirectory(tempDir.resolve("broken"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: broken\n---\nbody only, no description\n");

        assertThrows(SkillException.class, new Executable() {
            @Override
            public void execute() {
                TextSkill.load(skillDir);
            }
        });
    }

    @Test
    void readFileCannotEscapeSkillDirectory(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("sandboxed"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: sandboxed\ndescription: test\n---\nbody\n");
        Files.writeString(tempDir.resolve("secret.txt"), "should not be readable");

        final TextSkill skill = TextSkill.load(skillDir);

        assertThrows(SkillException.class, new Executable() {
            @Override
            public void execute() {
                skill.readFile("../secret.txt");
            }
        });
    }

    @Test
    void readFileWithinSkillDirectoryWorks(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("with-scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: with-scripts\ndescription: test\n---\nbody\n");
        Files.createDirectory(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("scripts").resolve("hello.txt"), "hello from script");

        TextSkill skill = TextSkill.load(skillDir);

        assertEquals("hello from script", skill.readFile("scripts/hello.txt"));
    }
}
