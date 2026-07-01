package com.tirthoguha.omnillm.dto.openai;

import java.util.List;
import java.util.stream.IntStream;

/**
 * OpenAI {@code POST /v1/embeddings} response. Field names match the OpenAI schema so standard
 * clients (e.g. Open WebUI's RAG/document pipeline) parse it unchanged.
 */
public record OpenAiEmbeddingsResponse(
        String object,
        List<Data> data,
        String model,
        Usage usage) {

    public record Data(String object, List<Float> embedding, int index) {
    }

    public record Usage(int prompt_tokens, int total_tokens) {
    }

    /** Build a {@code list} response, one {@code embedding} entry per input vector, in order. */
    public static OpenAiEmbeddingsResponse of(String model, List<List<Float>> vectors,
                                              long promptTokens, long totalTokens) {
        List<Data> data = IntStream.range(0, vectors.size())
                .mapToObj(i -> new Data("embedding", vectors.get(i), i))
                .toList();
        return new OpenAiEmbeddingsResponse("list", data, model,
                new Usage((int) promptTokens, (int) totalTokens));
    }
}
