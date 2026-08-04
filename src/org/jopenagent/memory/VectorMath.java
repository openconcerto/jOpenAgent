package org.jopenagent.memory;

/** Small vector helpers shared by every {@link Embedder} and {@link MemoryManager}. */
public final class VectorMath {

    private VectorMath() {
    }

    /** Cosine similarity, assuming both vectors are already L2-normalized (as every {@link Embedder} here produces). */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /** L2-normalizes {@code vector} into a new array (unit length), or returns it unchanged if it's the zero vector (avoids a division by zero). */
    public static float[] normalize(float[] vector) {
        double sumSquares = 0;
        for (float v : vector) {
            sumSquares += v * v;
        }
        if (sumSquares == 0) {
            return vector;
        }
        float norm = (float) Math.sqrt(sumSquares);
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = vector[i] / norm;
        }
        return result;
    }
}
