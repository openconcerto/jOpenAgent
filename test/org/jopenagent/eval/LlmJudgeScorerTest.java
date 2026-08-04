package org.jopenagent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.jopenagent.testsupport.MockLlmClient;

class LlmJudgeScorerTest {

    @Test
    void parsesAPassingVerdictFromTheJudge() {
        MockLlmClient judge = new MockLlmClient();
        judge.enqueueText("{\"score\": 0.9, \"passed\": true, \"reasoning\": \"conveys the same key points\"}");

        ScoreDetail result = new LlmJudgeScorer<String>(judge).score("the sky is blue", "the sky appears blue");

        assertTrue(result.isPassed());
        assertEquals(0.9, result.getScore());
        assertEquals("conveys the same key points", result.getReasoning());
        assertTrue(judge.lastRequest().getMessages().get(judge.lastRequest().getMessages().size() - 1).getContent().contains("the sky is blue"));
    }

    @Test
    void parsesAFailingVerdictFromTheJudge() {
        MockLlmClient judge = new MockLlmClient();
        judge.enqueueText("{\"score\": 0.1, \"passed\": false, \"reasoning\": \"missing the key conclusion\"}");

        ScoreDetail result = new LlmJudgeScorer<String>(judge).score("expected", "unrelated actual");

        assertFalse(result.isPassed());
        assertEquals(0.1, result.getScore());
    }

    @Test
    void stripsMarkdownFencesAroundTheJudgesJsonResponse() {
        MockLlmClient judge = new MockLlmClient();
        judge.enqueueText("```json\n{\"score\": 1.0, \"passed\": true, \"reasoning\": \"exact match\"}\n```");

        ScoreDetail result = new LlmJudgeScorer<String>(judge).score("x", "x");

        assertTrue(result.isPassed());
        assertEquals(1.0, result.getScore());
    }

    @Test
    void clampsAnOutOfRangeScoreFromAMisbehavingJudge() {
        MockLlmClient judge = new MockLlmClient();
        judge.enqueueText("{\"score\": 1.7, \"passed\": true, \"reasoning\": \"overconfident judge\"}");

        ScoreDetail result = new LlmJudgeScorer<String>(judge).score("x", "y");

        assertEquals(1.0, result.getScore());
    }

    @Test
    void aMalformedJudgeResponseIsReportedAsAFailingScoreRatherThanThrowing() {
        MockLlmClient judge = new MockLlmClient();
        judge.enqueueText("not json at all");

        ScoreDetail result = new LlmJudgeScorer<String>(judge).score("x", "y");

        assertFalse(result.isPassed());
        assertEquals(0.0, result.getScore());
        assertTrue(result.getReasoning().contains("LLM judge failed"));
    }

    @Test
    void customRubricIsIncludedInThePromptSentToTheJudge() {
        MockLlmClient judge = new MockLlmClient();
        judge.enqueueText("{\"score\": 1.0, \"passed\": true, \"reasoning\": \"ok\"}");

        new LlmJudgeScorer<String>(judge, "Only check that the tone is polite.").score("x", "y");

        String prompt = judge.lastRequest().getMessages().get(judge.lastRequest().getMessages().size() - 1).getContent();
        assertTrue(prompt.contains("Only check that the tone is polite."));
    }
}
