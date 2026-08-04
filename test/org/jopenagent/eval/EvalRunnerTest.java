package org.jopenagent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Tracer;

/** Exercises EvalRunner against the real Tracer (not mocked), since the whole point of the design is that eval results are just spans. */
class EvalRunnerTest {

    @AfterEach
    void cleanup() {
        Tracer.clear();
        Tracer.removeAllExporters();
    }

    @Test
    void passingCaseProducesAPassingResultAndAMatchingEvalCaseSpan() {
        JsonTraceExporter exporter = new JsonTraceExporter();
        Tracer.addExporter(exporter);

        EvalCase<Integer> testCase = EvalCase.builder("case-1", new EvalTask<Integer>() {
            @Override
            public Integer run() {
                return 42;
            }
        }).displayName("computes the answer").expected(42).tier(Tier.STABLE).metadata("difficulty", "easy").build();
        EvalSuite<Integer> suite = EvalSuite.<Integer>builder("my-experiment").addCase(testCase).build();

        List<EvalResult<Integer>> results = EvalRunner.run(suite, new ExactMatchScorer<Integer>(), "test-model");

        assertEquals(1, results.size());
        EvalResult<Integer> result = results.get(0);
        assertTrue(result.isPassed());
        assertEquals(1.0, result.getScore());
        assertEquals(42, result.getActual());
        assertNotNull(result.getSpanId());

        assertEquals(1, exporter.getCaptured().size());
        org.jopenkit.json.JSONObject trace = exporter.getCaptured().get(0);
        assertEquals("eval_case", trace.getString("kind"));
        assertEquals("OK", trace.getString("status"));
        org.jopenkit.json.JSONObject attrs = trace.getJSONObject("attributes");
        assertEquals("my-experiment", attrs.getString("experiment"));
        assertEquals("case-1", attrs.getString("eval.test_id"));
        assertEquals("stable", attrs.getString("eval.tier"));
        assertEquals("test-model", attrs.getString("eval.model"));
        assertTrue(attrs.getBoolean("eval.passed"));
        assertEquals("easy", attrs.getString("eval.difficulty"));
    }

    @Test
    void failingCaseIsMarkedAsErrorOnTheSpan() {
        JsonTraceExporter exporter = new JsonTraceExporter();
        Tracer.addExporter(exporter);

        EvalCase<String> testCase = EvalCase.builder("case-2", new EvalTask<String>() {
            @Override
            public String run() {
                throw new IllegalStateException("agent blew up");
            }
        }).expected("anything").build();
        EvalSuite<String> suite = EvalSuite.<String>builder("crash-experiment").addCase(testCase).build();

        List<EvalResult<String>> results = EvalRunner.run(suite, new ExactMatchScorer<String>(), "test-model");

        EvalResult<String> result = results.get(0);
        assertFalse(result.isPassed());
        assertEquals("agent blew up", result.getError());
        assertNotNull(result.getActual() == null ? "null-actual-ok" : result.getActual());

        org.jopenkit.json.JSONObject trace = exporter.getCaptured().get(0);
        assertEquals("ERROR", trace.getString("status"));
        assertEquals("agent blew up", trace.getString("error"));
    }

    @Test
    void multipleCasesEachProduceTheirOwnRootSpanUnderTheSameExperiment() {
        JsonTraceExporter exporter = new JsonTraceExporter();
        Tracer.addExporter(exporter);

        EvalSuite<Integer> suite = EvalSuite.<Integer>builder("multi").addCase(EvalCase.builder("a", constant(1)).expected(1).build())
                .addCase(EvalCase.builder("b", constant(2)).expected(99).build()).build();

        List<EvalResult<Integer>> results = EvalRunner.run(suite, new ExactMatchScorer<Integer>(), "m");

        assertEquals(2, results.size());
        assertTrue(results.get(0).isPassed());
        assertFalse(results.get(1).isPassed());
        assertEquals(2, exporter.getCaptured().size());
    }

    private static EvalTask<Integer> constant(int value) {
        return new EvalTask<Integer>() {
            @Override
            public Integer run() {
                return value;
            }
        };
    }
}
