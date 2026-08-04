package org.jopenagent.trace.otlp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

/**
 * Pre-configured {@link OtlpHttpExporter} for Langfuse's OTLP ingestion endpoint, matching
 * NOOA's {@code exporters.langfuse()} (nooa/src/nooa/tracing/exporters.py), which is documented
 * there as "pre-configured OTLP for Langfuse". Langfuse authenticates OTLP/HTTP with HTTP Basic
 * auth using the public/secret key pair.
 */
public final class LangfuseExporter extends OtlpHttpExporter {

    private static final String DEFAULT_BASE_URL = "https://cloud.langfuse.com";

    /** Targets Langfuse Cloud ({@value #DEFAULT_BASE_URL}); see {@link #LangfuseExporter(String, String, String)} to target a self-hosted instance instead. */
    public LangfuseExporter(String publicKey, String secretKey) {
        this(publicKey, secretKey, DEFAULT_BASE_URL);
    }

    /** Targets a Langfuse instance at {@code baseUrl} (its OTLP ingestion path is appended automatically), authenticating with HTTP Basic auth built from {@code publicKey}/{@code secretKey}. */
    public LangfuseExporter(String publicKey, String secretKey, String baseUrl) {
        super(baseUrl + "/api/public/otel/v1/traces", "jooa",
                Collections.singletonMap("Authorization", "Basic " + Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8))));
    }
}
