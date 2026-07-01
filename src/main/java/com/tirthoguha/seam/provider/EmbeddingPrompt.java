package com.tirthoguha.seam.provider;

import java.util.List;

/**
 * Provider-agnostic embedding request: the texts to embed and the model to embed them with.
 * Mirrors {@link ChatPrompt} on the embedding side of the seam, so the OpenAI SDK never leaks past
 * an {@link EmbeddingProvider}.
 *
 * @param input one or more texts to embed (order is preserved in the result vectors)
 * @param model the embedding model id, e.g. {@code text-embedding-3-small} or {@code ai/mxbai-embed-large}
 */
public record EmbeddingPrompt(List<String> input, String model) {
}
