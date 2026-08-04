package org.jopenagent.eval;

import java.util.Locale;

/** Passes when {@code actual} (case-insensitively) contains {@code expected} as a substring, useful for free-text generative output. */
public final class ContainsScorer implements Scorer<String> {

    @Override
    public ScoreDetail score(String expected, String actual) {
        if (expected == null || actual == null) {
            return new ScoreDetail(0.0, false, "expected or actual was null (expected=" + expected + ", actual=" + actual + ")");
        }
        boolean matches = actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        return new ScoreDetail(matches ? 1.0 : 0.0, matches, "looked for \"" + expected + "\" in \"" + actual + "\"");
    }
}
