package org.jopenagent.memory;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic, offline, no-network embedder, feature-hashing (bag-of-hashed-tokens) into a
 * fixed-size vector, then L2-normalized. This is jOpenAgent's default embedder and mirrors NOOA's own
 * {@code HashingEmbedder} (nooa/packages/nooa-memory/src/nooa_memory/embeddings.py), used there
 * specifically so quickstarts/tests don't need a real embedding API.
 */
public final class HashingEmbedder implements Embedder {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9]+");

    private final int dimensions;

    /** Uses the same 256-dimensional vector size as NOOA's default {@code HashingEmbedder}. */
    public HashingEmbedder() {
        this(256);
    }

    public HashingEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * Lowercases {@code text}, tokenizes on {@code [a-zA-Z0-9]+} runs, and for each token hashes
     * into a bucket ({@code hashCode() mod dimensions}) with a hash-derived sign (feature hashing's
     * standard trick to make hash collisions cancel out on average rather than always add), then
     * L2-normalizes the accumulated vector via {@link VectorMath#normalize}.
     */
    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isEmpty()) {
            return vector;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            int hash = token.hashCode();
            int bucket = Math.floorMod(hash, dimensions);
            int sign = (Math.floorMod(hash, 2) == 0) ? 1 : -1;
            vector[bucket] += sign;
        }
        return VectorMath.normalize(vector);
    }
}
