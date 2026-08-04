package org.jopenagent.eval;

/**
 * The outcome of running one {@link EvalCase}, jOpenAgent's trimmed equivalent of NOOA's
 * {@code EvalTestResult} row. The full record (including the {@code eval.*} attributes) is also
 * attached to the {@code eval_case} trace span with id {@link #getSpanId()}, so
 * {@code TraceViewerServer}'s {@code /api/eval/*} routes can rebuild this same information (and
 * the trace detail view) purely from captured traces, without a separate results store.
 */
public final class EvalResult<T> {

    private final String caseId;
    private final String displayName;
    private final Tier tier;
    private final boolean passed;
    private final double score;
    private final String reasoning;
    private final T expected;
    private final T actual;
    private final String error;
    private final long durationMillis;
    private final String spanId;
    private final String model;

    public EvalResult(String caseId, String displayName, Tier tier, boolean passed, double score, String reasoning, T expected, T actual, String error,
            long durationMillis, String spanId, String model) {
        this.caseId = caseId;
        this.displayName = displayName;
        this.tier = tier;
        this.passed = passed;
        this.score = score;
        this.reasoning = reasoning;
        this.expected = expected;
        this.actual = actual;
        this.error = error;
        this.durationMillis = durationMillis;
        this.spanId = spanId;
        this.model = model;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Tier getTier() {
        return tier;
    }

    public boolean isPassed() {
        return passed;
    }

    public double getScore() {
        return score;
    }

    public String getReasoning() {
        return reasoning;
    }

    public T getExpected() {
        return expected;
    }

    public T getActual() {
        return actual;
    }

    public String getError() {
        return error;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getModel() {
        return model;
    }
}
