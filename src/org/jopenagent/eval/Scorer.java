package org.jopenagent.eval;

/**
 * Compares an eval case's actual output against its expected value and produces a
 * {@link ScoreDetail}. See {@link ExactMatchScorer} and {@link ContainsScorer} for built-in
 * implementations; write your own (as a named class or anonymous inner class, no lambdas in this
 * codebase) for anything more specific, e.g. an LLM-as-judge scorer that calls another agent.
 */
public interface Scorer<T> {
    ScoreDetail score(T expected, T actual);
}
