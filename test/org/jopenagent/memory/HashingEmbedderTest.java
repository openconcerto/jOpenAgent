package org.jopenagent.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashingEmbedderTest {

    @Test
    void sameTextProducesSameVector() {
        HashingEmbedder embedder = new HashingEmbedder();
        float[] a = embedder.embed("the invoice was charged twice");
        float[] b = embedder.embed("the invoice was charged twice");
        assertEquals(1.0, VectorMath.cosineSimilarity(a, b), 0.0001);
    }

    @Test
    void similarTextsScoreHigherThanUnrelatedText() {
        HashingEmbedder embedder = new HashingEmbedder();
        float[] billingA = embedder.embed("invoice billing charge refund payment");
        float[] billingB = embedder.embed("payment invoice charge billing dispute");
        float[] unrelated = embedder.embed("the weather today is sunny and warm");

        double similarBillingScore = VectorMath.cosineSimilarity(billingA, billingB);
        double crossTopicScore = VectorMath.cosineSimilarity(billingA, unrelated);

        assertTrue(similarBillingScore > crossTopicScore, "shared-vocabulary texts should score higher than unrelated text");
    }

    @Test
    void emptyTextProducesZeroVector() {
        HashingEmbedder embedder = new HashingEmbedder(16);
        float[] vector = embedder.embed("");
        for (float value : vector) {
            assertEquals(0.0f, value);
        }
    }
}
