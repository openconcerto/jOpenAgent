package org.jopenagent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jopenkit.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Tracer;

/** Unit tests for the pure trace-scanning functions shared by the web and Swing eval-harness UIs. */
class EvalTraceViewTest {

    @AfterEach
    void cleanup() {
        Tracer.clear();
        Tracer.removeAllExporters();
    }

    private List<JSONObject> runSampleSuite() {
        JsonTraceExporter exporter = new JsonTraceExporter();
        Tracer.addExporter(exporter);
        EvalSuite<Integer> suite = EvalSuite.<Integer>builder("sample-experiment")
                .addCase(EvalCase.builder("case-pass", constant(1)).expected(1).tier(Tier.STABLE).metadata("difficulty", "easy").build())
                .addCase(EvalCase.builder("case-fail", constant(1)).expected(2).tier(Tier.FRONTIER).build()).build();
        EvalRunner.run(suite, new ExactMatchScorer<Integer>(), "test-model");
        return new ArrayList<>(exporter.getCaptured());
    }

    private static EvalTask<Integer> constant(int value) {
        return new EvalTask<Integer>() {
            @Override
            public Integer run() {
                return value;
            }
        };
    }

    @Test
    void experimentSummariesGroupsByExperimentAttribute() {
        List<JSONObject> traces = runSampleSuite();

        org.jopenkit.json.JSONArray summaries = EvalTraceView.experimentSummaries(traces);

        assertEquals(1, summaries.size());
        assertEquals("sample-experiment", summaries.getJSONObject(0).getString("id"));
        assertEquals(2, summaries.getJSONObject(0).getInt("testCount"));
        assertEquals(1, summaries.getJSONObject(0).getInt("passedCount"));
    }

    @Test
    void experimentDetailIncludesCaseMetadataKeys() {
        List<JSONObject> traces = runSampleSuite();

        JSONObject detail = EvalTraceView.experimentDetail(traces, "sample-experiment");

        assertEquals(2, detail.getJSONArray("results").size());
        assertEquals(1, detail.getJSONArray("metadataKeys").size());
        assertEquals("difficulty", detail.getJSONArray("metadataKeys").getString(0));
    }

    @Test
    void experimentDetailReturnsNullForAnUnknownExperiment() {
        List<JSONObject> traces = runSampleSuite();

        assertNull(EvalTraceView.experimentDetail(traces, "does-not-exist"));
    }

    @Test
    void experimentSummaryStatsComputesSuccessRateAndMatrix() {
        List<JSONObject> traces = runSampleSuite();

        JSONObject stats = EvalTraceView.experimentSummaryStats(traces, "sample-experiment");

        assertEquals(2, stats.getInt("total"));
        assertEquals(1, stats.getInt("passed"));
        assertEquals(0.5, stats.getDouble("successRate"));
        assertTrue(stats.getJSONObject("byModel").has("test-model"));
        assertTrue(stats.getJSONObject("matrix").has("case-pass"));
        assertTrue(stats.getJSONObject("matrix").has("case-fail"));
    }

    @Test
    void nonEvalCaseTracesAreIgnored() {
        org.jopenkit.json.JSONObject notAnEvalCase = new org.jopenkit.json.JSONObject();
        notAnEvalCase.put("id", "some-id");
        notAnEvalCase.put("kind", "generation");
        notAnEvalCase.put("name", "SomeAgent.method");
        notAnEvalCase.put("attributes", new org.jopenkit.json.JSONObject());

        assertEquals(0, EvalTraceView.experimentSummaries(List.of(notAnEvalCase)).size());
    }
}
