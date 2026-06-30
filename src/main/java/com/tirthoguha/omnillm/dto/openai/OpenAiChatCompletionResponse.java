package com.tirthoguha.omnillm.dto.openai;

import java.util.List;

/**
 * OpenAI {@code chat.completion} response (non-streaming). Field names match the OpenAI schema so
 * standard clients parse it unchanged.
 */
public record OpenAiChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices) {

    public record Choice(int index, Message message, String finish_reason) {
    }

    public record Message(String role, String content) {
    }

    /** Build a single-choice {@code stop} response. */
    public static OpenAiChatCompletionResponse of(String id, long created, String model, String content) {
        return new OpenAiChatCompletionResponse(id, "chat.completion", created, model,
                List.of(new Choice(0, new Message("assistant", content), "stop")));
    }
}
