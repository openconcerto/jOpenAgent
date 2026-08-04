package org.jopenagent.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScorerTest {

    @Test
    void exactMatchScorerComparesWithEquals() {
        ExactMatchScorer<String> scorer = new ExactMatchScorer<>();
        assertTrue(scorer.score("refund", "refund").isPassed());
        assertFalse(scorer.score("refund", "REFUND").isPassed());
    }

    @Test
    void containsScorerIsCaseInsensitive() {
        ContainsScorer scorer = new ContainsScorer();
        assertTrue(scorer.score("refund", "Customer asked about a REFUND today").isPassed());
        assertFalse(scorer.score("refund", "unrelated ticket").isPassed());
        assertFalse(scorer.score(null, "anything").isPassed());
    }

    @Test
    void numericToleranceScorerPassesWithinToleranceAndPartialScoresOutsideIt() {
        NumericToleranceScorer scorer = new NumericToleranceScorer(0.5);
        assertTrue(scorer.score(10.0, 10.4).isPassed());
        assertFalse(scorer.score(10.0, 12.0).isPassed());
        assertTrue(scorer.score(10.0, 12.0).getScore() < 1.0);
        assertTrue(scorer.score(10.0, 12.0).getScore() > 0.0);
    }
}
