package com.tirthoguha.seam.provider.openai;

import java.util.Comparator;
import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.tirthoguha.seam.provider.ChatProviderException;
import com.tirthoguha.seam.provider.EmbeddingPrompt;
import com.tirthoguha.seam.provider.EmbeddingProvider;
import com.tirthoguha.seam.provider.EmbeddingResult;

/**
 * {@link EmbeddingProvider} backed by the official OpenAI Java SDK. Because every OpenAI-compatible
 * backend (OpenAI cloud, Docker Model Runner, …) exposes the same {@code /v1/embeddings} endpoint,
 * this one adapter covers all of them — one instance per embedding-capable backend, each wrapping
 * that backend's own {@link OpenAIClient}.
 *
 * <p>Like {@link OpenAiChatProvider}, this is one of the few classes allowed to import
 * {@code com.openai.*}; the coupling stops at the provider boundary and SDK failures are wrapped in
 * {@link ChatProviderException}.
 */
public class OpenAiEmbeddingProvider implements EmbeddingProvider {

    private final String name;
    private final OpenAIClient client;

    public OpenAiEmbeddingProvider(String name, OpenAIClient client) {
        this.name = name;
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public EmbeddingResult embed(EmbeddingPrompt prompt) {
        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(prompt.model())
                .inputOfArrayOfStrings(prompt.input())
                .build();

        try {
            CreateEmbeddingResponse response = client.embeddings().create(params);

            // The API may return vectors out of order; sort by index so result order matches input.
            List<List<Float>> vectors = response.data().stream()
                    .sorted(Comparator.comparingLong(Embedding::index))
                    .map(Embedding::embedding)
                    .toList();

            CreateEmbeddingResponse.Usage usage = response.usage();
            return new EmbeddingResult(name, prompt.model(), vectors,
                    usage.promptTokens(), usage.totalTokens());
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI embeddings request failed", e);
        }
    }
}
