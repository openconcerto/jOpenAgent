package org.jopenagent.core.strategy;

/**
 * Selects which {@link Strategy} handles a {@code @Generative} method.
 * Mirrors NOOA's PredictStrategy/CodeActStrategy choice (see nooa/AGENTS.md "Strategy Selection").
 */
public enum StrategyKind {

    /** Not set on this method, fall back to the agent's configured default. */
    INHERIT,

    /** Single-shot structured output validated against the return type, with retry. jOpenAgent's default. */
    PREDICT,

    /** The LLM writes Java-like code executed in a sandbox with access to {@code self}, iterating until it calls returnResult(). */
    CODE_ACT,

    /** Classic function-calling loop: the LLM calls the agent's ordinary methods as JSON-schema tools until it produces a final answer. */
    TOOL_CALLING,

    /** Generate (via PREDICT) -> self-critique -> retry-with-feedback loop, up to 3 attempts. See {@link ReflexionStrategy}. */
    REFLEXION
}
