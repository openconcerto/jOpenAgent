package org.jopenagent.eval;

import org.jopenagent.json.ObjectBinder;
import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.LlmMessage;
import org.jopenagent.llm.LlmRequest;
import org.jopenagent.llm.LlmResponse;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Scores a case by asking an LLM to judge whether {@code actual} satisfies {@code expected}
 * against a rubric, instead of an exact/substring/numeric comparison, for free-text output where
 * there's no single correct string (e.g. "does this summary correctly convey the key points").
 * The judge call itself becomes a normal {@code llm_call} trace span, nested under the
 * {@code eval_case} span exactly like any other LLM call {@link EvalRunner} wraps (see its
 * javadoc), so a judged case's trace shows both the task's own LLM call(s) and the judge's.
 *
 * <p>If the judge call fails outright (network error, malformed judge response), that failure is
 * reported as a failing {@link ScoreDetail} rather than thrown: consistent with
 * {@link EvalRunner} treating an eval case's own failures as data rather than propagating
 * exceptions out of a batch run.
 */
public final class LlmJudgeScorer<T> implements Scorer<T> {

    private static final String DEFAULT_RUBRIC = "The actual output should adequately convey the same meaning/substance as the expected output. "
            + "Minor differences in wording, formatting, or ordering are acceptable; differences in facts, conclusions, or key content are not.";

    private static final String JUDGE_SYSTEM_PROMPT = "You are a strict, impartial evaluator scoring one test case. "
            + "Respond with ONLY a JSON object of the exact shape {\"score\": <number 0.0-1.0>, \"passed\": <true|false>, \"reasoning\": \"<one concise sentence>\"} "
            + "and nothing else: no markdown fences, no extra text.";

    private final LlmClient judge;
    private final String rubric;

    /** Uses {@link #DEFAULT_RUBRIC} as the judging rubric. */
    public LlmJudgeScorer(LlmClient judge) {
        this(judge, DEFAULT_RUBRIC);
    }

    /**
     * @param judge the LLM asked to render a verdict; may be a different (e.g. smaller/cheaper) model than the one under test
     * @param rubric replaces {@link #DEFAULT_RUBRIC}. The criteria the judge is told to apply when deciding whether {@code actual} satisfies {@code expected}
     */
    public LlmJudgeScorer(LlmClient judge, String rubric) {
        this.judge = judge;
        this.rubric = rubric;
    }

    @Override
    public ScoreDetail score(T expected, T actual) {
        Span span = Traced.begin("llm_call", "eval judge");
        try {
            String prompt = "Rubric: " + rubric + "\n\nExpected output:\n" + expected + "\n\nActual output:\n" + actual;
            LlmRequest request = LlmRequest.builder().systemPrompt(JUDGE_SYSTEM_PROMPT).addMessage(LlmMessage.user(prompt)).build();
            LlmResponse response = judge.generate(request);
            JudgeVerdict verdict = ObjectBinder.bindResponse(response.getContent(), JudgeVerdict.class);
            Traced.finish(span);
            return new ScoreDetail(clamp(verdict.score()), verdict.passed(), verdict.reasoning());
        } catch (RuntimeException e) {
            Traced.fail(span, e);
            return ScoreDetail.fail("LLM judge failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private static double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    /** Raw JSON shape of the judge's response, before being clamped and folded into a {@link ScoreDetail}. */
    private record JudgeVerdict(double score, boolean passed, String reasoning) {
    }
}
