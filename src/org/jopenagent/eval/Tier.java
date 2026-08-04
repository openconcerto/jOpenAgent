package org.jopenagent.eval;

/**
 * How much an eval case is expected to matter for release readiness, jOpenAgent's equivalent of
 * NOOA's {@code Tier} (see {@code nooa/util/eval_pipeline/src/eval_pipeline/eval_types.py}).
 */
public enum Tier {
    /** Must pass. */
    STABLE,
    /** At the edge of current capability. */
    FRONTIER,
    /** Aspirational, not expected to pass yet. */
    HORIZON
}
