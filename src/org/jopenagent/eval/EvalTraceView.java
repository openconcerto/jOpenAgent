package org.jopenagent.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

/**
 * Pure functions turning a flat collection of captured trace JSON (in {@link org.jopenagent.trace.JsonTraceExporter}'s
 * shape) into eval-harness views: experiment summaries, one experiment's per-case results, and one
 * experiment's aggregate pass-rate/matrix stats. Shared by every eval-harness UI so the "how do we
 * find and group eval_case spans" logic lives in exactly one place. Both
 * {@code org.jopenagent.trace.viewer.TraceViewerServer}'s {@code /api/eval/*} routes and
 * {@code org.jopenagent.trace.viewer.swing.TraceViewerSwingApp}'s live-mode Experiments tab compute the
 * same thing this way rather than each re-scanning trace JSON independently. See
 * {@link EvalRunner} for how a trace ends up looking like this in the first place.
 */
public final class EvalTraceView {

    private static final Set<String> FIXED_EVAL_ATTRIBUTES = Set.of("eval.test_id", "eval.tier", "eval.model", "eval.passed", "eval.score", "eval.duration_ms");

    private EvalTraceView() {
    }

    /** Filters {@code allTraces} down to just the {@code eval_case}-kind spans {@link EvalRunner} produces. */
    public static List<JSONObject> evalCaseTraces(Collection<JSONObject> allTraces) {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject trace : allTraces) {
            if ("eval_case".equals(trace.optString("kind", ""))) {
                result.add(trace);
            }
        }
        return result;
    }

    /** The {@code attributes} object of a trace span, or an empty object if the span has none. */
    public static JSONObject attributesOf(JSONObject trace) {
        JSONObject attrs = trace.optJSONObject("attributes");
        return attrs != null ? attrs : new JSONObject();
    }

    /** The {@code experiment} attribute {@link EvalRunner} stamped on an eval_case trace, or {@code null} if it has none. */
    public static String experimentOf(JSONObject trace) {
        String experiment = attributesOf(trace).optString("experiment", "");
        return experiment.isEmpty() ? null : experiment;
    }

    /** One eval_case trace rendered as a result row: testId/displayName/tier/model/passed/score/durationMillis/status/spanId/metadata. */
    public static JSONObject evalResultJson(JSONObject trace) {
        JSONObject attrs = attributesOf(trace);
        JSONObject result = new JSONObject();
        result.put("testId", attrs.optString("eval.test_id", trace.optString("id", "")));
        result.put("displayName", trace.optString("name", ""));
        result.put("tier", attrs.optString("eval.tier", ""));
        result.put("model", attrs.optString("eval.model", ""));
        result.put("passed", attrs.optBoolean("eval.passed", false));
        result.put("score", attrs.optDouble("eval.score", 0.0));
        result.put("durationMillis", trace.optDouble("durationMillis", 0.0));
        result.put("status", trace.optString("status", "OK"));
        result.put("spanId", trace.optString("id", ""));
        String error = trace.optString("error", "");
        if (!error.isEmpty()) {
            result.put("error", error);
        }
        JSONObject metadata = new JSONObject();
        Iterator<String> keys = attrs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("eval.") && !FIXED_EVAL_ATTRIBUTES.contains(key)) {
                metadata.put(key.substring("eval.".length()), attrs.opt(key));
            }
        }
        result.put("metadata", metadata);
        return result;
    }

    /** {id, testCount, passedCount, models[]} for every experiment found among {@code allTraces}. */
    public static JSONArray experimentSummaries(Collection<JSONObject> allTraces) {
        Map<String, List<JSONObject>> byExperiment = groupByExperiment(allTraces);
        JSONArray experiments = new JSONArray();
        for (Map.Entry<String, List<JSONObject>> entry : byExperiment.entrySet()) {
            List<JSONObject> traces = entry.getValue();
            Set<String> models = new LinkedHashSet<>();
            int passedCount = 0;
            for (JSONObject trace : traces) {
                JSONObject attrs = attributesOf(trace);
                String model = attrs.optString("eval.model", "");
                if (!model.isEmpty()) {
                    models.add(model);
                }
                if (attrs.optBoolean("eval.passed", false)) {
                    passedCount++;
                }
            }
            JSONObject summary = new JSONObject();
            summary.put("id", entry.getKey());
            summary.put("testCount", traces.size());
            summary.put("passedCount", passedCount);
            JSONArray modelsJson = new JSONArray();
            for (String model : models) {
                modelsJson.add(model);
            }
            summary.put("models", modelsJson);
            experiments.add(summary);
        }
        return experiments;
    }

    /** {experimentId, results[], metadataKeys[]} for one experiment, or {@code null} if no case belongs to it. */
    public static JSONObject experimentDetail(Collection<JSONObject> allTraces, String experimentId) {
        List<JSONObject> results = new ArrayList<>();
        Set<String> metadataKeys = new LinkedHashSet<>();
        for (JSONObject trace : evalCaseTraces(allTraces)) {
            if (!experimentId.equals(experimentOf(trace))) {
                continue;
            }
            JSONObject resultJson = evalResultJson(trace);
            JSONObject metadata = resultJson.optJSONObject("metadata");
            if (metadata != null) {
                Iterator<String> keys = metadata.keys();
                while (keys.hasNext()) {
                    metadataKeys.add(keys.next());
                }
            }
            results.add(resultJson);
        }
        if (results.isEmpty()) {
            return null;
        }
        JSONObject response = new JSONObject();
        response.put("experimentId", experimentId);
        JSONArray resultsJson = new JSONArray();
        for (JSONObject result : results) {
            resultsJson.add(result);
        }
        response.put("results", resultsJson);
        JSONArray metadataKeysJson = new JSONArray();
        for (String key : metadataKeys) {
            metadataKeysJson.add(key);
        }
        response.put("metadataKeys", metadataKeysJson);
        return response;
    }

    /** {total, passed, successRate, byModel, byTier, matrix} for one experiment, or {@code null} if no case belongs to it. */
    public static JSONObject experimentSummaryStats(Collection<JSONObject> allTraces, String experimentId) {
        List<JSONObject> traces = new ArrayList<>();
        for (JSONObject trace : evalCaseTraces(allTraces)) {
            if (experimentId.equals(experimentOf(trace))) {
                traces.add(trace);
            }
        }
        if (traces.isEmpty()) {
            return null;
        }

        int total = traces.size();
        int passed = 0;
        Map<String, int[]> byModel = new LinkedHashMap<>();
        Map<String, int[]> byTier = new LinkedHashMap<>();
        Map<String, Map<String, int[]>> matrix = new LinkedHashMap<>();

        for (JSONObject trace : traces) {
            JSONObject attrs = attributesOf(trace);
            String testId = attrs.optString("eval.test_id", trace.optString("id", ""));
            String model = attrs.optString("eval.model", "unknown");
            String tier = attrs.optString("eval.tier", "");
            boolean casePassed = attrs.optBoolean("eval.passed", false);
            if (casePassed) {
                passed++;
            }
            tally(byModel, model, casePassed);
            if (!tier.isEmpty()) {
                tally(byTier, tier, casePassed);
            }
            Map<String, int[]> row = matrix.get(testId);
            if (row == null) {
                row = new LinkedHashMap<>();
                matrix.put(testId, row);
            }
            tally(row, model, casePassed);
        }

        JSONObject response = new JSONObject();
        response.put("total", total);
        response.put("passed", passed);
        response.put("successRate", total > 0 ? (double) passed / total : 0.0);
        response.put("byModel", tallyToJson(byModel));
        response.put("byTier", tallyToJson(byTier));
        JSONObject matrixJson = new JSONObject();
        for (Map.Entry<String, Map<String, int[]>> entry : matrix.entrySet()) {
            matrixJson.put(entry.getKey(), tallyToJson(entry.getValue()));
        }
        response.put("matrix", matrixJson);
        return response;
    }

    private static Map<String, List<JSONObject>> groupByExperiment(Collection<JSONObject> allTraces) {
        Map<String, List<JSONObject>> byExperiment = new LinkedHashMap<>();
        for (JSONObject trace : evalCaseTraces(allTraces)) {
            String experiment = experimentOf(trace);
            if (experiment == null) {
                continue;
            }
            List<JSONObject> traces = byExperiment.get(experiment);
            if (traces == null) {
                traces = new ArrayList<>();
                byExperiment.put(experiment, traces);
            }
            traces.add(trace);
        }
        return byExperiment;
    }

    private static void tally(Map<String, int[]> counts, String key, boolean passed) {
        int[] slot = counts.get(key);
        if (slot == null) {
            slot = new int[2];
            counts.put(key, slot);
        }
        slot[0]++;
        if (passed) {
            slot[1]++;
        }
    }

    private static JSONObject tallyToJson(Map<String, int[]> counts) {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            JSONObject cell = new JSONObject();
            cell.put("total", entry.getValue()[0]);
            cell.put("passed", entry.getValue()[1]);
            json.put(entry.getKey(), cell);
        }
        return json;
    }
}
