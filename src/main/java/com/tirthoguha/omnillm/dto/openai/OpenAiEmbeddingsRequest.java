package com.tirthoguha.omnillm.dto.openai;

/**
 * OpenAI {@code POST /v1/embeddings} request. {@code input} is polymorphic in the OpenAI schema — a
 * single string or an array of strings (token-array forms are not supported here) — so it's typed as
 * {@link Object} and flattened to a list by the controller. {@code model} doubles as the
 * {@code <backend>:<model>} selector, exactly like the chat-completions path.
 *
 * @param model           {@code <backend>:<embedding-model>} selector (either side optional)
 * @param input           a string or array of strings to embed
 * @param encoding_format ignored (float vectors are always returned); accepted for client compatibility
 * @param dimensions      ignored for now; accepted for client compatibility
 * @param user            ignored; accepted for client compatibility
 */
public record OpenAiEmbeddingsRequest(
        String model,
        Object input,
        String encoding_format,
        Integer dimensions,
        String user) {
}
