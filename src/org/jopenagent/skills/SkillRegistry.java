package org.jopenagent.skills;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jopenagent.core.Agent;

/**
 * Per-agent skill lifecycle: load/unload/lookup, plus discovery of a directory of
 * {@code SKILL.md} bundles: jOpenAgent's port of NOOA's {@code SkillRegistry}
 * (nooa/src/nooa/skill_registry.py), scoped down from its full entry-point/hot-reload machinery
 * to the load→attach→render lifecycle that's actually observable from an agent's behavior.
 */
public final class SkillRegistry {

    private final Agent owner;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(Agent owner) {
        this.owner = owner;
    }

    /**
     * Attaches {@code skill} to this registry's owning agent (calling {@link Skill#attach(Agent)}),
     * registers it under {@link Skill#getId()}, and, if the skill declares one, wires up its
     * {@link Skill#contextBlock()} as a dynamic {@link org.jopenagent.core.context.ContextApi} block.
     */
    public void load(Skill skill) {
        skill.attach(owner);
        skills.put(skill.getId(), skill);
        String[] block = skill.contextBlock();
        if (block != null && block.length == 2) {
            owner.context.setDynamic(block[0], block[1]);
        }
    }

    /** Loads every subdirectory of {@code rootDir} containing a SKILL.md/skill.md as a {@link TextSkill}. */
    public void discoverAndLoadAll(Path rootDir) {
        if (!Files.isDirectory(rootDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
            for (Path candidate : stream) {
                if (Files.isDirectory(candidate) && (Files.isRegularFile(candidate.resolve("SKILL.md")) || Files.isRegularFile(candidate.resolve("skill.md")))) {
                    load(TextSkill.load(candidate));
                }
            }
        } catch (IOException e) {
            throw new SkillException("Failed scanning skills directory " + rootDir, e);
        }
    }

    /** Removes and {@link Skill#detach() detaches} the skill registered under {@code id}; a no-op if none is loaded. */
    public void unload(String id) {
        Skill skill = skills.remove(id);
        if (skill != null) {
            skill.detach();
        }
    }

    /** Looks up a loaded skill by id (as used from generated code via {@code self.skills.get("id")}); throws {@link SkillException} if none is loaded under that id. */
    public Skill get(String id) {
        Skill skill = skills.get(id);
        if (skill == null) {
            throw new SkillException("No skill loaded with id '" + id + "'. Loaded skills: " + skills.keySet());
        }
        return skill;
    }

    public boolean has(String id) {
        return skills.containsKey(id);
    }

    public Collection<Skill> all() {
        return skills.values();
    }

    /** Renders active skills (name + description only, for progressive disclosure) into the system prompt. */
    public String render() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## Active skills\n");
        for (Skill skill : skills.values()) {
            sb.append("- **").append(skill.getId()).append("**: ").append(skill.getDescription()).append("\n");
        }
        return sb.toString();
    }
}
