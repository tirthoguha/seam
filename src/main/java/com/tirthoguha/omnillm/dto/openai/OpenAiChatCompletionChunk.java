package com.tirthoguha.omnillm.dto.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One OpenAI {@code chat.completion.chunk} SSE frame (streaming). A stream is: an optional opening
 * chunk carrying {@code delta.role}, then one chunk per token with {@code delta.content}, then a
 * final chunk with an empty delta and {@code finish_reason = "stop"} — followed by the literal
 * {@code data: [DONE]} sentinel. Null delta fields are omitted to match OpenAI's wire shape.
 */
public record OpenAiChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices) {

    public record Choice(int index, Delta delta, String finish_reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }

    private static OpenAiChatCompletionChunk of(String id, long created, String model, Delta delta, String finish) {
        return new OpenAiChatCompletionChunk(id, "chat.completion.chunk", created, model,
                List.of(new Choice(0, delta, finish)));
    }

    /** Opening frame announcing the assistant role. */
    public static OpenAiChatCompletionChunk role(String id, long created, String model) {
        return of(id, created, model, new Delta("assistant", null), null);
    }

    /** A content token frame. */
    public static OpenAiChatCompletionChunk token(String id, long created, String model, String token) {
        return of(id, created, model, new Delta(null, token), null);
    }

    /** Final frame: empty delta, finish_reason = stop. */
    public static OpenAiChatCompletionChunk stop(String id, long created, String model) {
        return of(id, created, model, new Delta(null, null), "stop");
    }
}
