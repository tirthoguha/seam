package com.tirthoguha.seam.provider;

/**
 * Builds concrete providers for one backend from its connection coordinates. The only
 * implementation lives inside {@code config/OpenAiConfig} (the composition root), so SDK imports
 * stay confined there — this interface is what lets {@code BackendProvisioner} (re)build providers
 * when a key is supplied or rotated at runtime without touching the SDK itself.
 */
public interface ProviderFactory {

    /**
     * Build the chat provider for a backend.
     *
     * @param responsesApi true to speak the OpenAI Responses API instead of Chat Completions
     */
    ChatProvider chat(String backend, String baseUrl, String apiKey, boolean responsesApi);

    /** Build the embedding provider for a backend (callers ensure it has an embedding model). */
    EmbeddingProvider embedding(String backend, String baseUrl, String apiKey);
}
