package org.jopenagent.examples;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Generative;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.eval.ContainsScorer;
import org.jopenagent.eval.EvalCase;
import org.jopenagent.eval.EvalResult;
import org.jopenagent.eval.EvalRunner;
import org.jopenagent.eval.EvalSuite;
import org.jopenagent.eval.EvalTask;
import org.jopenagent.eval.Tier;
import org.jopenagent.trace.Tracer;
import org.jopenagent.trace.viewer.TraceViewerServer;

/**
 * jOpenAgent's equivalent of NOOA's eval harness (see {@code nooa/util/eval_pipeline} and
 * {@code nooa/src/nooa/viewer/eval_routes.py}): a batch of {@link EvalCase}s grouped into an
 * {@link EvalSuite} ("experiment"), run and scored by {@link EvalRunner}. No separate results
 * store is needed: each case becomes its own {@code eval_case} trace span carrying
 * {@code experiment}/{@code eval.*} attributes, so registering a {@link TraceViewerServer} as a
 * trace exporter (exactly like {@link E16_TraceViewer}) is all it takes to browse pass/fail
 * results, per-model/per-tier breakdowns, and each case's full underlying trace (LLM calls, tool
 * calls, etc.) from its "Experiments" tab.
 */
@SystemPrompt("You are a support ticket triage agent.")
public class E19_EvalHarness extends Agent {

    public E19_EvalHarness(AgentConfig config) {
        super(config);
    }

    /**
     * The capability under evaluation: each {@link EvalCase} built in {@link #main} wraps a call
     * to this method and compares its result against an expected category via
     * {@link ContainsScorer}.
     */
    @Generative("Classify the support ticket into exactly one category: billing, technical, or account. Respond with just the category word.")
    public String classify(String ticketText) {
        return generate(ticketText);
    }

    public static void main(String[] args) throws IOException {
        TraceViewerServer viewer = new TraceViewerServer();
        Tracer.addExporter(viewer);
        viewer.start(5001);

        final E19_EvalHarness agent = new E19_EvalHarness(AgentConfig.builder().llmClient(ExampleSetup.createLlmClient()).build());

        EvalSuite<String> suite = EvalSuite.<String>builder("ticket-triage-v1")
                .addCase(EvalCase.builder("billing-1", new EvalTask<String>() {
                    @Override
                    public String run() {
                        return agent.classify("I was charged twice for my subscription this month.");
                    }
                }).displayName("Duplicate charge").expected("billing").tier(Tier.STABLE).metadata("difficulty", "easy").build())
                .addCase(EvalCase.builder("technical-1", new EvalTask<String>() {
                    @Override
                    public String run() {
                        return agent.classify("The app crashes every time I try to upload a photo.");
                    }
                }).displayName("App crash on upload").expected("technical").tier(Tier.STABLE).metadata("difficulty", "easy").build())
                .addCase(EvalCase.builder("account-1", new EvalTask<String>() {
                    @Override
                    public String run() {
                        return agent.classify("I can't remember my password and the reset email never arrives.");
                    }
                }).displayName("Password reset never arrives").expected("account").tier(Tier.STABLE).metadata("difficulty", "easy").build())
                .addCase(EvalCase.builder("ambiguous-1", new EvalTask<String>() {
                    @Override
                    public String run() {
                        return agent.classify("My invoice shows a technical-looking error code instead of the amount due.");
                    }
                }).displayName("Invoice shows an error code").expected("billing").tier(Tier.FRONTIER).metadata("difficulty", "hard").build())
                .build();

        List<EvalResult<String>> results = EvalRunner.run(suite, new ContainsScorer(), agent.getLlmClient().modelName());

        int passed = 0;
        for (EvalResult<String> result : results) {
            System.out.println((result.isPassed() ? "PASS" : "FAIL") + "  " + result.getCaseId() + "  expected=" + result.getExpected() + "  actual="
                    + result.getActual());
            if (result.isPassed()) {
                passed++;
            }
        }
        System.out.println(passed + "/" + results.size() + " passed");

        System.out.println();
        System.out.println("Trace viewer running at http://localhost:" + viewer.port() + " -- open it, click the \"Experiments\" tab, then press Enter here to stop.");
        new BufferedReader(new InputStreamReader(System.in)).readLine();
        viewer.stop();
    }
}
