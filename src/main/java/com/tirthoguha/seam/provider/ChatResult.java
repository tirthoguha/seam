package com.tirthoguha.seam.provider;

import java.util.List;

/**
 * Provider-agnostic chat result. Carries the backend that served the request, the model it ran,
 * the assistant text reply, any function calls the model wants to make, the finish reason, and
 * optional token-usage data — so the web layer can echo all of it back without re-deriving them.
 *
 * @param backend      the backend that handled the request, e.g. {@code openai} / {@code docker}
 * @param model        the model that produced the reply
 * @param reply        the assistant's text response; empty string (not null) when the model only
 *                     issued tool calls
 * @param toolCalls    function calls the model wants to make; empty when finish reason is
 *                     {@code stop}
 * @param finishReason why the model stopped, e.g. {@code "stop"} or {@code "tool_calls"}
 * @param usage        token-usage summary, or {@code null} when the backend did not report it
 */
public record ChatResult(
        String backend,
        String model,
        String reply,
        List<ToolCall> toolCalls,
        String finishReason,
        Usage usage) {

    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * Convenience factory for a plain text response (no tool calls). Sets
     * {@code finishReason="stop"} and {@code usage=null}. Use for simple text-only paths where the
     * caller does not have usage data.
     */
    public static ChatResult text(String backend, String model, String reply) {
        return new ChatResult(backend, model, reply, List.of(), "stop", null);
    }
}
