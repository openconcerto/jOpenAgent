package org.jopenagent.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.skills.TextSkill;

/**
 * Mirrors nooa/examples/quickstart/10_skills.py: a {@code SKILL.md} bundle attached to an agent
 * is visible to the LLM (name + description, for progressive disclosure, see
 * {@link org.jopenagent.skills.SkillRegistry#render()}), with its full instructions pulled in on demand.
 * This writes the SKILL.md to a temp directory at startup purely so the example is runnable with
 * no setup; normally you'd point {@link TextSkill#load} at a real checked-in skills directory.
 */
@SystemPrompt("You are a support agent that follows house style guidance.")
public class E10_Skills extends Agent {

    public E10_Skills(AgentConfig config) {
        super(config);
    }

    /**
     * Whatever skill(s) are attached via {@link Agent#skills} (loaded in {@link #main} from a
     * temporary {@code SKILL.md}) are rendered into the system prompt for this call automatically
     *, no explicit reference to the skill is needed in the instruction text.
     */
    @Generative("Write a two-sentence reply to the ticket, following any active skills' guidance.")
    public String reply(String ticketText) {
        return generate(ticketText);
    }

    public static void main(String[] args) throws IOException {
        Path skillDir = Files.createTempDirectory("jooa-skill-demo").resolve("reply-tone");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n" + "name: reply-tone\n" + "description: House style for customer replies.\n" + "---\n\n"
                        + "Always thank the customer first, then answer directly. Never apologize more than once.\n");

        E10_Skills agent = new E10_Skills(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());
        agent.skills.load(TextSkill.load(skillDir));

        System.out.println("--- active skills ---");
        for (org.jopenagent.skills.Skill skill : agent.skills.all()) {
            System.out.println(skill.getId() + ": " + skill.getDescription());
        }

        TextSkill toneSkill = (TextSkill) agent.skills.get("reply-tone");
        System.out.println("--- full instructions (pulled on demand) ---");
        System.out.println(toneSkill.getInstructions());

        System.out.println("--- generated reply ---");
        System.out.println(agent.reply("My order arrived a week late."));
    }
}
