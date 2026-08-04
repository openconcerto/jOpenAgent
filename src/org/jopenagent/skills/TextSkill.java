package org.jopenagent.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads a {@code SKILL.md}/{@code skill.md} directory: jOpenAgent's port of NOOA's {@code TextSkill}
 * (nooa/src/nooa/skill.py). Parses the YAML-ish frontmatter block for the required {@code name}
 * and {@code description} fields (Claude-Code-compatible format: {@code ---} delimited, simple
 * {@code key: value} lines: full YAML is not implemented, matching the actual complexity found
 * in real SKILL.md files), keeps the remaining markdown body as {@link #getInstructions()}, and
 * exposes a sandboxed {@link #readFile(String)} that cannot escape the skill's own directory.
 */
public final class TextSkill extends Skill {

    private final Path sourceDir;
    private final String id;
    private final String description;
    private final String instructions;

    private TextSkill(Path sourceDir, String id, String description, String instructions) {
        this.sourceDir = sourceDir;
        this.id = id;
        this.description = description;
        this.instructions = instructions;
    }

    /**
     * Reads {@code skillDir}'s {@code SKILL.md}/{@code skill.md}, parses its frontmatter for the
     * required {@code name}/{@code description} fields, and keeps the remaining markdown as the
     * instructions body. Throws {@link SkillException} if the file is missing, unreadable, or its
     * frontmatter omits either required field.
     */
    public static TextSkill load(Path skillDir) {
        Path skillFile = resolveSkillFile(skillDir);
        String raw;
        try {
            raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillException("Cannot read skill file: " + skillFile, e);
        }
        Map<String, String> frontmatter = new LinkedHashMap<>();
        String body = parseFrontmatter(raw, frontmatter);
        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        if (name == null || description == null) {
            throw new SkillException("SKILL.md at " + skillFile + " must declare both 'name' and 'description' in its frontmatter.");
        }
        return new TextSkill(skillDir, name, description, body.trim());
    }

    private static Path resolveSkillFile(Path skillDir) {
        Path upper = skillDir.resolve("SKILL.md");
        if (Files.isRegularFile(upper)) {
            return upper;
        }
        Path lower = skillDir.resolve("skill.md");
        if (Files.isRegularFile(lower)) {
            return lower;
        }
        throw new SkillException("No SKILL.md/skill.md found in " + skillDir);
    }

    private static String parseFrontmatter(String raw, Map<String, String> out) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return text;
        }
        int firstDelim = text.indexOf("---");
        int secondDelim = text.indexOf("\n---", firstDelim + 3);
        if (secondDelim < 0) {
            return text;
        }
        String frontmatterBlock = text.substring(firstDelim + 3, secondDelim).trim();
        String body = text.substring(secondDelim + 4);
        for (String line : frontmatterBlock.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.length() >= 2 && isQuoted(value)) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return body;
    }

    private static boolean isQuoted(String value) {
        return (value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDescription() {
        return description;
    }

    /** The skill's full body markdown (after frontmatter), pull this into context/prompt when its full detail is actually needed. */
    public String getInstructions() {
        return instructions;
    }

    public Path getSourceDir() {
        return sourceDir;
    }

    /** Reads a file relative to this skill's own directory; rejects any path that would escape it. */
    public String readFile(String relativePath) {
        Path resolved = sourceDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(sourceDir.normalize())) {
            throw new SkillException("Path escapes skill directory: " + relativePath);
        }
        try {
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillException("Cannot read file '" + relativePath + "' from skill '" + id + "'", e);
        }
    }

    private static final long DEFAULT_SCRIPT_TIMEOUT_MILLIS = 30_000L;

    /**
     * Runs a script from this skill's {@code scripts/} directory as a real subprocess, jOpenAgent's
     * port of NOOA's {@code TextSkill.run_script(name, *args, interpreter=..., timeout=...)}
     * (nooa/src/nooa/skill.py). The interpreter is auto-detected from the script's extension
     * ({@code .sh}→bash, {@code .py}→python3, {@code .js}→node, {@code .ps1}→powershell; anything
     * else is executed directly, e.g. a compiled binary or a Unix script with its own shebang)
     * unless explicitly overridden. Uses a 30-second default timeout that, unlike
     * {@link org.jopenagent.sandbox.CodeSandbox}'s in-JVM timeout, which can't preempt a tight infinite
     * loop: is a genuine hard kill ({@link Process#destroyForcibly()}), since this runs in its
     * own OS process.
     */
    public ScriptResult runScript(String scriptName, String... args) {
        return runScript(scriptName, null, DEFAULT_SCRIPT_TIMEOUT_MILLIS, args);
    }

    /**
     * Full-control overload of {@link #runScript(String, String...)}: resolves {@code scriptName}
     * against this skill's {@code scripts/} directory (rejecting any path that would escape it),
     * builds the command line via an explicit {@code interpreter} (or extension-based
     * auto-detection when null, see {@link #runScript(String, String...)}), and runs it through
     * {@link ScriptRunner#run(List, Path, long)} with the given {@code timeoutMillis}.
     */
    public ScriptResult runScript(String scriptName, String interpreter, long timeoutMillis, String... args) {
        Path scriptsDir = sourceDir.resolve("scripts").normalize();
        Path scriptPath = scriptsDir.resolve(scriptName).normalize();
        if (!scriptPath.startsWith(scriptsDir)) {
            throw new SkillException("Script path escapes the skill's scripts directory: " + scriptName);
        }
        if (!Files.isRegularFile(scriptPath)) {
            throw new SkillException("No such script '" + scriptName + "' in skill '" + id + "' (expected under " + scriptsDir + ")");
        }
        List<String> command = buildCommand(scriptPath, interpreter, args);
        return ScriptRunner.run(command, scriptsDir, timeoutMillis);
    }

    private List<String> buildCommand(Path scriptPath, String interpreter, String[] args) {
        List<String> command = new ArrayList<>();
        if (interpreter != null) {
            command.add(interpreter);
            command.add(scriptPath.toString());
        } else {
            String name = scriptPath.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".sh")) {
                command.add("bash");
                command.add(scriptPath.toString());
            } else if (name.endsWith(".py")) {
                command.add("python3");
                command.add(scriptPath.toString());
            } else if (name.endsWith(".js")) {
                command.add("node");
                command.add(scriptPath.toString());
            } else if (name.endsWith(".ps1")) {
                command.add("powershell");
                // Bypass applies only to this one invocation (a command-line flag, not a system
                // setting), so a machine's default execution policy (often "Restricted") doesn't
                // block a skill's own bundled script.
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
                command.add(scriptPath.toString());
            } else {
                command.add(scriptPath.toString());
            }
        }
        command.addAll(Arrays.asList(args));
        return command;
    }
}
