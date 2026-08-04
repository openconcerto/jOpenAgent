package org.jopenagent.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named group of {@link EvalCase}s: jOpenAgent's "experiment" concept, matching NOOA's grouping of
 * eval sessions under one {@code experiment} resource attribute (see
 * {@code nooa/src/nooa/viewer/eval_routes.py}: "Experiments are groups of sessions sharing the
 * same experiment resource attribute"). Running the same suite against different models produces
 * separate rows within the same experiment (see {@link EvalRunner#run}), so the trace viewer can
 * show a pass-rate matrix by model, exactly like NOOA's.
 */
public final class EvalSuite<T> {

    private final String experimentName;
    private final List<EvalCase<T>> cases;

    private EvalSuite(String experimentName, List<EvalCase<T>> cases) {
        this.experimentName = experimentName;
        this.cases = Collections.unmodifiableList(new ArrayList<EvalCase<T>>(cases));
    }

    public String getExperimentName() {
        return experimentName;
    }

    public List<EvalCase<T>> getCases() {
        return cases;
    }

    public static <T> Builder<T> builder(String experimentName) {
        return new Builder<T>(experimentName);
    }

    /** Fluent builder for {@link EvalSuite}, obtained via {@link EvalSuite#builder}; {@link #build()} rejects an empty suite. */
    public static final class Builder<T> {
        private final String experimentName;
        private final List<EvalCase<T>> cases = new ArrayList<EvalCase<T>>();

        private Builder(String experimentName) {
            if (experimentName == null || experimentName.isEmpty()) {
                throw new IllegalArgumentException("experimentName must not be empty");
            }
            this.experimentName = experimentName;
        }

        public Builder<T> addCase(EvalCase<T> evalCase) {
            this.cases.add(evalCase);
            return this;
        }

        public EvalSuite<T> build() {
            if (cases.isEmpty()) {
                throw new IllegalStateException("EvalSuite '" + experimentName + "' has no cases");
            }
            return new EvalSuite<T>(experimentName, cases);
        }
    }
}
