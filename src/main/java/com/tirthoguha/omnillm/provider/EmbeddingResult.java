package com.tirthoguha.omnillm.provider;

import java.util.List;

/**
 * Provider-agnostic embedding result. Carries the backend that served the request, the model that
 * ran, one vector per input (same order as {@link EmbeddingPrompt#input()}), and token usage so the
 * gateway can echo an OpenAI-shaped {@code usage} block.
 *
 * @param backend      the backend that handled the request, e.g. {@code openai} / {@code docker}
 * @param model        the embedding model that produced the vectors
 * @param vectors      one embedding vector per input text, in input order
 * @param promptTokens tokens counted for the input
 * @param totalTokens  total tokens billed
 */
public record EmbeddingResult(
        String backend,
        String model,
        List<List<Float>> vectors,
        long promptTokens,
        long totalTokens) {
}
