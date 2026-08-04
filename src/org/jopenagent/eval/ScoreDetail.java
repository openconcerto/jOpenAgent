package org.jopenagent.eval;

/**
 * The outcome of scoring one eval case: jOpenAgent's equivalent of NOOA's {@code ScoreDetail}
 * (same file as {@link Tier}): a normalized score, a pass/fail verdict, and an optional
 * human-readable explanation.
 */
public final class ScoreDetail {

    private final double score;
    private final boolean passed;
    private final String reasoning;

    public ScoreDetail(double score, boolean passed, String reasoning) {
        this.score = score;
        this.passed = passed;
        this.reasoning = reasoning;
    }

    /** A perfect, passing score, shorthand for {@code new ScoreDetail(1.0, true, reasoning)}. */
    public static ScoreDetail pass(String reasoning) {
        return new ScoreDetail(1.0, true, reasoning);
    }

    /** A zero, failing score, shorthand for {@code new ScoreDetail(0.0, false, reasoning)}, used e.g. by {@link EvalRunner} when a case throws instead of producing a value. */
    public static ScoreDetail fail(String reasoning) {
        return new ScoreDetail(0.0, false, reasoning);
    }

    public double getScore() {
        return score;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getReasoning() {
        return reasoning;
    }
}
