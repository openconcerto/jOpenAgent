package org.jopenagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jopenagent.trace.Span;
import org.jopenagent.trace.Traced;

/**
 * Runs an {@link EvalSuite} and scores each case. Each case is wrapped in its own {@code eval_case}
 * trace span *before* {@link EvalTask#run()} is invoked, so any spans the task itself produces
 * (the {@code generation} span from a {@code @Generative} call, its nested {@code llm_call}/
 * {@code tool_execution}/{@code code_execution} children, see {@link org.jopenagent.trace.Tracer}'s
 * per-thread span stack) become children of it. That single wrapping span carries the
 * {@code experiment} and {@code eval.*} attributes, so a normal {@link org.jopenagent.trace.TraceExporter}
 * (e.g. {@code TraceViewerServer}) captures everything an eval harness needs with no separate
 * results store: {@code TraceViewerServer}'s {@code /api/eval/*} routes rebuild experiment
 * summaries purely by scanning captured traces for {@code eval_case}-kind spans.
 */
public final class EvalRunner {

    private EvalRunner() {
    }

    /** Runs every case in {@code suite} and returns one {@link EvalResult} per case, in order. */
    public static <T> List<EvalResult<T>> run(EvalSuite<T> suite, Scorer<T> scorer, String model) {
        List<EvalResult<T>> results = new ArrayList<EvalResult<T>>();
        for (EvalCase<T> evalCase : suite.getCases()) {
            results.add(runOne(suite.getExperimentName(), evalCase, scorer, model));
        }
        return results;
    }

    private static <T> EvalResult<T> runOne(String experiment, EvalCase<T> evalCase, Scorer<T> scorer, String model) {
        Span span = Traced.begin("eval_case", evalCase.getDisplayName());
        if (span != null) {
            span.setAttribute("experiment", experiment);
            span.setAttribute("eval.test_id", evalCase.getId());
            span.setAttribute("eval.tier", evalCase.getTier().name().toLowerCase(Locale.ROOT));
            span.setAttribute("eval.model", model);
            for (Map.Entry<String, String> entry : evalCase.getMetadata().entrySet()) {
                span.setAttribute("eval." + entry.getKey(), entry.getValue());
            }
        }

        long startMillis = System.currentTimeMillis();
        T actual = null;
        String error = null;
        try {
            actual = evalCase.getTask().run();
        } catch (RuntimeException e) {
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        long durationMillis = System.currentTimeMillis() - startMillis;

        ScoreDetail scoreDetail = error != null ? ScoreDetail.fail(error) : scorer.score(evalCase.getExpected(), actual);

        if (span != null) {
            span.setAttribute("eval.passed", scoreDetail.isPassed());
            span.setAttribute("eval.score", scoreDetail.getScore());
            span.setAttribute("eval.duration_ms", durationMillis);
            if (error != null) {
                span.setError(error);
            }
            Traced.finish(span);
        }

        return new EvalResult<T>(evalCase.getId(), evalCase.getDisplayName(), evalCase.getTier(), scoreDetail.isPassed(), scoreDetail.getScore(),
                scoreDetail.getReasoning(), evalCase.getExpected(), actual, error, durationMillis, span != null ? span.getId() : null, model);
    }
}
