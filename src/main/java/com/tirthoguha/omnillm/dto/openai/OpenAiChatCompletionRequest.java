package com.tirthoguha.omnillm.dto.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Subset of the OpenAI {@code POST /v1/chat/completions} request we honour. Unknown fields
 * (temperature, top_p, tools, …) are ignored so clients like Open WebUI can send their full payload
 * without 400s. {@code content} is typed as {@code Object} because OpenAI allows either a plain
 * string or an array of content parts; the gateway flattens it to text.
 *
 * @param model    target model id; the gateway reads it as {@code <backend>:<model>} (see controller)
 * @param messages the conversation (system / user / assistant turns), in order
 * @param stream   whether to stream the response as SSE; defaults to false when absent
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatCompletionRequest(String model, List<Message> messages, Boolean stream) {

    /** True only when the client explicitly asked to stream. */
    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, Object content) {
    }
}
