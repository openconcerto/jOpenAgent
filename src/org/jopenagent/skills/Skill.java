package org.jopenagent.skills;

import org.jopenagent.core.Agent;

/**
 * A packaged capability/prompt bundle attached to an agent: jOpenAgent's port of NOOA's genuine
 * runtime {@code Skill} API (nooa/src/nooa/skill.py; not to be confused with the top-level
 * {@code nooa/skills/*.md} files, which are pure documentation for AI coding assistants and have
 * no runtime counterpart, see the design notes).
 *
 * <p>A skill is attached via {@link org.jopenagent.skills.SkillRegistry#load(Skill)}, which calls
 * {@link #attach(Agent)}; it becomes visible to the LLM through
 * {@link org.jopenagent.skills.SkillRegistry#render()} (name + description, for progressive
 * disclosure) and, for {@code CODE_ACT}/{@code TOOL_CALLING} agents, is reachable from generated
 * code as {@code self.skills.get("id")}.
 */
public abstract class Skill {

    /** The agent this skill is currently attached to, or null before {@link #attach} / after {@link #detach}. */
    private Agent owner;

    /** Wires this skill to {@code agent} and invokes the {@link #onAttach(Agent)} hook. Called by {@link SkillRegistry#load(Skill)}, not typically directly. */
    public final void attach(Agent agent) {
        this.owner = agent;
        onAttach(agent);
    }

    /** Invokes the {@link #onDetach()} hook and clears the owning agent. Called by {@link SkillRegistry#unload(String)}. */
    public final void detach() {
        onDetach();
        this.owner = null;
    }

    /** Override to run setup logic once attached (e.g. registering a context block manually). */
    protected void onAttach(Agent agent) {
    }

    /** Override to run cleanup logic once detached (mirrors {@link #onAttach(Agent)}); a no-op by default. */
    protected void onDetach() {
    }

    protected final Agent getOwner() {
        return owner;
    }

    /** Unique key this skill is registered/looked up under in a {@link SkillRegistry} (e.g. {@code self.skills.get(id)} from generated code). */
    public abstract String getId();

    /** Short, LLM-facing summary shown for progressive disclosure by {@link SkillRegistry#render()}; full detail (e.g. {@link TextSkill#getInstructions()}) is pulled in only on demand. */
    public abstract String getDescription();

    /**
     * Optional {@code {contextKey, self-expression}} pair auto-registered as a dynamic context
     * block (via {@link org.jopenagent.core.context.ContextApi#setDynamic}) when this skill is loaded 
     * mirrors NOOA's {@code Skill.context_block}. Return null (the default) if this skill doesn't
     * need one.
     */
    public String[] contextBlock() {
        return null;
    }
}
