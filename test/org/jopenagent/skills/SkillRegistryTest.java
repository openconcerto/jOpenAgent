package org.jopenagent.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.PromptBuilder;
import org.jopenagent.testsupport.MockLlmClient;
import org.jopenagent.testsupport.SupportAgent;

class SkillRegistryTest {

    private SupportAgent newAgent() {
        return new SupportAgent(AgentConfig.builder().llmClient(new MockLlmClient()).build());
    }

    @Test
    void loadedSkillAppearsInSystemPrompt(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("triage-tips"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: triage-tips\ndescription: Tips for triaging tickets fast.\n---\nDo X then Y.\n");

        SupportAgent agent = newAgent();
        agent.skills.load(TextSkill.load(skillDir));

        String systemPrompt = PromptBuilder.systemPrompt(agent);
        assertTrue(systemPrompt.contains("triage-tips"));
        assertTrue(systemPrompt.contains("Tips for triaging tickets fast."));
        // Only the compact description goes into the system prompt, not the full body, by default.
        assertFalse(systemPrompt.contains("Do X then Y."));
    }

    @Test
    void fullInstructionsAreReachableOnDemand(@TempDir Path tempDir) throws IOException {
        Path skillDir = Files.createDirectory(tempDir.resolve("triage-tips"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: triage-tips\ndescription: Tips.\n---\nDo X then Y.\n");

        SupportAgent agent = newAgent();
        agent.skills.load(TextSkill.load(skillDir));

        TextSkill skill = (TextSkill) agent.skills.get("triage-tips");
        assertTrue(skill.getInstructions().contains("Do X then Y."));
    }

    @Test
    void discoverAndLoadAllScansDirectoryOfSkills(@TempDir Path tempDir) throws IOException {
        Path skillA = Files.createDirectory(tempDir.resolve("skill-a"));
        Files.writeString(skillA.resolve("SKILL.md"), "---\nname: skill-a\ndescription: A.\n---\nbody\n");
        Path skillB = Files.createDirectory(tempDir.resolve("skill-b"));
        Files.writeString(skillB.resolve("SKILL.md"), "---\nname: skill-b\ndescription: B.\n---\nbody\n");
        Files.createFile(tempDir.resolve("not-a-skill.txt"));

        SupportAgent agent = newAgent();
        agent.skills.discoverAndLoadAll(tempDir);

        assertTrue(agent.skills.has("skill-a"));
        assertTrue(agent.skills.has("skill-b"));
        assertEquals(2, agent.skills.all().size());
    }

    @Test
    void gettingUnknownSkillThrows() {
        final SupportAgent agent = newAgent();
        assertThrows(SkillException.class, new Executable() {
            @Override
            public void execute() {
                agent.skills.get("nope");
            }
        });
    }

    @Test
    void contextBlockIsAutoRegisteredOnLoad() {
        SupportAgent agent = newAgent();
        agent.skills.load(new Skill() {
            @Override
            public String getId() {
                return "dynamic-skill";
            }

            @Override
            public String getDescription() {
                return "reports live state";
            }

            @Override
            public String[] contextBlock() {
                return new String[] { "backlogFromSkill", "self.ticketBacklog()" };
            }
        });

        assertEquals("42", String.valueOf(agent.context.get("backlogFromSkill")));
    }
}
