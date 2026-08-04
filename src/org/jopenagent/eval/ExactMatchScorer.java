package org.jopenagent.eval;

import java.util.Objects;

/** Passes when {@code actual.equals(expected)} (or both are null); the simplest possible scorer. */
public final class ExactMatchScorer<T> implements Scorer<T> {

    @Override
    public ScoreDetail score(T expected, T actual) {
        boolean matches = Objects.equals(expected, actual);
        return new ScoreDetail(matches ? 1.0 : 0.0, matches, "expected=" + expected + ", actual=" + actual);
    }
}
