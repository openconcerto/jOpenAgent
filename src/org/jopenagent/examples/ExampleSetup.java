package org.jopenagent.examples;

import org.jopenagent.llm.LlmClient;
import org.jopenagent.llm.anthropic.AnthropicClient;
import org.jopenagent.llm.openai.OpenAiClient;
import org.jopenagent.memory.Embedder;
import org.jopenagent.memory.HashingEmbedder;
import org.jopenagent.memory.OpenAiEmbedder;

/**
 * Shared LLM setup for the example programs in this package, configured entirely from
 * environment variables so the examples can be run directly from Eclipse (Run As > Java
 * Application) without editing code:
 *
 * <ul>
 * <li>{@code ANTHROPIC_API_KEY} + {@code JOPENAGENT_MODEL}, uses {@link AnthropicClient}.</li>
 * <li>{@code OPENAI_API_KEY} + {@code JOPENAGENT_MODEL}, uses {@link OpenAiClient} against the real OpenAI API.</li>
 * <li>{@code JOPENAGENT_LMSTUDIO_MODEL} (and optionally {@code JOPENAGENT_LMSTUDIO_BASE_URL}, default
 * {@code http://localhost:1234}): uses {@link OpenAiClient#forLmStudio} against a local LM Studio server.</li>
 * <li>{@code JOPENAGENT_OLLAMA_MODEL} (and optionally {@code JOPENAGENT_OLLAMA_BASE_URL}, default
 * {@code http://localhost:11434}): uses {@link OpenAiClient#forOllama} against a local Ollama server.</li>
 * </ul>
 *
 * If none are set, {@link #createLlmClient()} throws with instructions rather than silently
 * guessing a model name.
 */
public final class ExampleSetup {

    private ExampleSetup() {
    }

    /**
     * Picks and constructs an {@link LlmClient} by checking the supported environment variables in
     * order (Anthropic, then OpenAI, then LM Studio, then Ollama, see the class-level list), so
     * every example can call this one method without knowing which provider is actually configured.
     */
    public static LlmClient createLlmClient() {
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String openAiKey = System.getenv("OPENAI_API_KEY");
        String lmStudioModel = System.getenv("JOPENAGENT_LMSTUDIO_MODEL");
        String ollamaModel = System.getenv("JOPENAGENT_OLLAMA_MODEL");
        String model = System.getenv("JOPENAGENT_MODEL");

        if (anthropicKey != null && !anthropicKey.isEmpty()) {
            requireModel(model, "ANTHROPIC_API_KEY");
            return new AnthropicClient(anthropicKey, model);
        }
        if (openAiKey != null && !openAiKey.isEmpty()) {
            requireModel(model, "OPENAI_API_KEY");
            return new OpenAiClient(openAiKey, model);
        }
        if (lmStudioModel != null && !lmStudioModel.isEmpty()) {
            String baseUrl = System.getenv("JOPENAGENT_LMSTUDIO_BASE_URL");
            return OpenAiClient.forLmStudio(lmStudioModel, baseUrl);
        }
        if (ollamaModel != null && !ollamaModel.isEmpty()) {
            String baseUrl = System.getenv("JOPENAGENT_OLLAMA_BASE_URL");
            return OpenAiClient.forOllama(ollamaModel, baseUrl != null ? baseUrl : "http://localhost:11434");
        }
        throw new IllegalStateException("No LLM configured. Set one of:\n" + "  ANTHROPIC_API_KEY + JOPENAGENT_MODEL (e.g. claude-sonnet-4-5)\n"
                + "  OPENAI_API_KEY + JOPENAGENT_MODEL (e.g. gpt-4o-mini)\n" + "  JOPENAGENT_LMSTUDIO_MODEL (e.g. qwen/qwen3-coder-30b), with LM Studio's local server running\n"
                + "  JOPENAGENT_OLLAMA_MODEL (e.g. llama3.1), with Ollama running locally");
    }

    private static void requireModel(String model, String withEnvVar) {
        if (model == null || model.isEmpty()) {
            throw new IllegalStateException("Set JOPENAGENT_MODEL alongside " + withEnvVar + " to pick which model to call.");
        }
    }

    /**
     * Builds an {@link Embedder} for {@code AgentConfig.builder().embedder(...)}. Defaults to the
     * offline, zero-dependency {@link HashingEmbedder} unless {@code JOPENAGENT_EMBEDDING_MODEL} is set,
     * in which case it picks a real {@link OpenAiEmbedder} using whichever provider's credentials
     * ({@code OPENAI_API_KEY}) or local-server settings ({@code JOPENAGENT_LMSTUDIO_MODEL}/
     * {@code JOPENAGENT_OLLAMA_MODEL}, reused from {@link #createLlmClient()} just to pick the server 
     * the embedding model name is independent of the chat model name) are present.
     */
    public static Embedder createEmbedder() {
        String embeddingModel = System.getenv("JOPENAGENT_EMBEDDING_MODEL");
        if (embeddingModel == null || embeddingModel.isEmpty()) {
            return new HashingEmbedder();
        }
        String openAiKey = System.getenv("OPENAI_API_KEY");
        if (openAiKey != null && !openAiKey.isEmpty()) {
            return new OpenAiEmbedder(openAiKey, embeddingModel);
        }
        String lmStudioModel = System.getenv("JOPENAGENT_LMSTUDIO_MODEL");
        if (lmStudioModel != null && !lmStudioModel.isEmpty()) {
            return OpenAiEmbedder.forLmStudio(embeddingModel, System.getenv("JOPENAGENT_LMSTUDIO_BASE_URL"));
        }
        String ollamaModel = System.getenv("JOPENAGENT_OLLAMA_MODEL");
        if (ollamaModel != null && !ollamaModel.isEmpty()) {
            String baseUrl = System.getenv("JOPENAGENT_OLLAMA_BASE_URL");
            return OpenAiEmbedder.forOllama(embeddingModel, baseUrl != null ? baseUrl : "http://localhost:11434");
        }
        throw new IllegalStateException(
                "JOPENAGENT_EMBEDDING_MODEL is set but no matching provider is configured. Set OPENAI_API_KEY, JOPENAGENT_LMSTUDIO_MODEL, or JOPENAGENT_OLLAMA_MODEL alongside it.");
    }
}
