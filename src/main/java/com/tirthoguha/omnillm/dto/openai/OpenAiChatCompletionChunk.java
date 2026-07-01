package com.tirthoguha.omnillm.dto.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One OpenAI {@code chat.completion.chunk} SSE frame (streaming). A stream is: an optional opening
 * chunk carrying {@code delta.role}, then one chunk per token with {@code delta.content} (or, for a
 * function call, chunks carrying {@code delta.tool_calls} fragments), then a final chunk with an
 * empty delta and {@code finish_reason} ({@code "stop"} or {@code "tool_calls"}) — followed by the
 * literal {@code data: [DONE]} sentinel. Null fields are omitted to match OpenAI's wire shape.
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
    public record Delta(String role, String content, List<ToolCall> tool_calls) {
    }

    /** One streamed tool-call fragment. On the first fragment for an {@code index}, {@code id}, */
    /** {@code type} and {@code function.name} are set; continuation fragments carry only the index */
    /** plus incremental {@code function.arguments}. Field names match OpenAI's wire schema. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(int index, String id, String type, Function function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Function(String name, String arguments) {
    }

    private static OpenAiChatCompletionChunk of(String id, long created, String model, Delta delta, String finish) {
        return new OpenAiChatCompletionChunk(id, "chat.completion.chunk", created, model,
                List.of(new Choice(0, delta, finish)));
    }

    /** Opening frame announcing the assistant role. */
    public static OpenAiChatCompletionChunk role(String id, long created, String model) {
        return of(id, created, model, new Delta("assistant", null, null), null);
    }

    /** A content token frame. */
    public static OpenAiChatCompletionChunk token(String id, long created, String model, String token) {
        return of(id, created, model, new Delta(null, token, null), null);
    }

    /**
     * A tool-call delta frame carrying one fragment for {@code index}. Pass a non-null
     * {@code callId} / {@code name} only on the <em>first</em> fragment for that index (announcing
     * the call); on continuation fragments pass them as {@code null} so only {@code index} +
     * {@code function.arguments} are emitted, matching OpenAI's streaming shape.
     */
    public static OpenAiChatCompletionChunk toolCallDelta(String id, long created, String model,
                                                          int index, String callId, String name,
                                                          String argumentsFragment) {
        boolean first = callId != null || name != null;
        Function function = new Function(name, argumentsFragment);
        ToolCall toolCall = new ToolCall(index, callId, first ? "function" : null, function);
        return of(id, created, model, new Delta(null, null, List.of(toolCall)), null);
    }

    /** Final frame: empty delta, finish_reason = stop. */
    public static OpenAiChatCompletionChunk stop(String id, long created, String model) {
        return finish(id, created, model, "stop");
    }

    /** Final frame carrying an explicit finish reason (e.g. {@code "stop"} or {@code "tool_calls"}). */
    public static OpenAiChatCompletionChunk finish(String id, long created, String model, String finishReason) {
        return of(id, created, model, new Delta(null, null, null), finishReason);
    }
}
