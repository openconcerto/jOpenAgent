package org.jopenagent.eval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One test case in an {@link EvalSuite}, jOpenAgent's equivalent of one NOOA {@code EvalTestResult}'s
 * input side (see {@code eval_pipeline.eval_types.EvalTestResult}): an id, the actual work to run
 * ({@link EvalTask}), the value it should produce, a {@link Tier}, and free-form metadata that
 * becomes {@code eval.*} span attributes (mirroring NOOA's {@code eval_metadata} dict, which the
 * viewer turns into dynamic table columns).
 */
public final class EvalCase<T> {

    private final String id;
    private final String displayName;
    private final EvalTask<T> task;
    private final T expected;
    private final Tier tier;
    private final Map<String, String> metadata;

    private EvalCase(Builder<T> builder) {
        this.id = builder.id;
        this.displayName = builder.displayName != null ? builder.displayName : builder.id;
        this.task = builder.task;
        this.expected = builder.expected;
        this.tier = builder.tier;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.metadata));
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public EvalTask<T> getTask() {
        return task;
    }

    public T getExpected() {
        return expected;
    }

    public Tier getTier() {
        return tier;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public static <T> Builder<T> builder(String id, EvalTask<T> task) {
        return new Builder<T>(id, task);
    }

    /** Fluent builder for {@link EvalCase}, obtained via {@link EvalCase#builder}; defaults to {@link Tier#STABLE} unless {@link #tier} is called. */
    public static final class Builder<T> {
        private final String id;
        private final EvalTask<T> task;
        private String displayName;
        private T expected;
        private Tier tier = Tier.STABLE;
        private final Map<String, String> metadata = new LinkedHashMap<String, String>();

        private Builder(String id, EvalTask<T> task) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("EvalCase id must not be empty");
            }
            if (task == null) {
                throw new IllegalArgumentException("EvalCase task must not be null");
            }
            this.id = id;
            this.task = task;
        }

        public Builder<T> displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder<T> expected(T expected) {
            this.expected = expected;
            return this;
        }

        public Builder<T> tier(Tier tier) {
            this.tier = tier;
            return this;
        }

        public Builder<T> metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public EvalCase<T> build() {
            return new EvalCase<T>(this);
        }
    }
}
