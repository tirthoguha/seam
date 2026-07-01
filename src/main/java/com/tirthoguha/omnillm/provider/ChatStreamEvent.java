package com.tirthoguha.omnillm.provider;

/**
 * Provider-agnostic streaming event — the fine-grained analogue of {@link ChatResult} for the
 * streaming path. A provider pushes a sequence of these to a {@code Consumer} as the model produces
 * output: interleaved {@link TextDelta} content fragments and {@link ToolCallDelta} function-call
 * fragments, terminated by exactly one {@link Completed}.
 *
 * <p>Like the rest of the seam, this never exposes SDK types: providers translate whatever their
 * underlying wire protocol emits (OpenAI {@code chat.completion.chunk} deltas, Responses API stream
 * events, …) into these records, so callers ({@code ChatService}, the gateway) format the transport
 * without knowing which backend produced the stream.
 */
public sealed interface ChatStreamEvent
        permits ChatStreamEvent.TextDelta, ChatStreamEvent.ToolCallDelta, ChatStreamEvent.Completed {

    /**
     * An assistant content fragment (a streamed token or token group).
     *
     * @param text the incremental assistant text; never null, may be empty
     */
    record TextDelta(String text) implements ChatStreamEvent {
    }

    /**
     * A fragment of a tool call. Tool-call arguments arrive incrementally across several fragments;
     * {@code index} groups the fragments that belong to the same call. On the first fragment for an
     * index {@code id} and {@code name} are set (the call is being announced); on continuation
     * fragments they may be null and only {@code argumentsFragment} carries new text.
     *
     * @param index             groups fragments belonging to the same tool call
     * @param id                the tool-call id; may be null on continuation fragments
     * @param name              the function name; may be null on continuation fragments
     * @param argumentsFragment the incremental JSON arguments text; never null, may be empty
     */
    record ToolCallDelta(int index, String id, String name, String argumentsFragment)
            implements ChatStreamEvent {
    }

    /**
     * Terminal event, emitted exactly once when the stream is exhausted.
     *
     * @param finishReason why the model stopped, e.g. {@code "stop"} or {@code "tool_calls"}
     * @param usage        token-usage summary, or {@code null} when the backend did not report it
     */
    record Completed(String finishReason, Usage usage) implements ChatStreamEvent {
    }
}
