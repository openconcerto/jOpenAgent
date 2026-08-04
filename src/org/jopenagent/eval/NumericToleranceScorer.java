package org.jopenagent.eval;

/** Passes when {@code |actual - expected| <= tolerance}, for numeric outputs where an exact match is too strict. */
public final class NumericToleranceScorer implements Scorer<Number> {

    private final double tolerance;

    public NumericToleranceScorer(double tolerance) {
        this.tolerance = tolerance;
    }

    @Override
    public ScoreDetail score(Number expected, Number actual) {
        if (expected == null || actual == null) {
            return new ScoreDetail(0.0, false, "expected or actual was null (expected=" + expected + ", actual=" + actual + ")");
        }
        double diff = Math.abs(actual.doubleValue() - expected.doubleValue());
        boolean matches = diff <= tolerance;
        return new ScoreDetail(matches ? 1.0 : Math.max(0.0, 1.0 - diff / Math.max(1.0, Math.abs(expected.doubleValue()))), matches,
                "expected=" + expected + ", actual=" + actual + ", diff=" + diff + ", tolerance=" + tolerance);
    }
}
