package com.tirthoguha.omnillm.provider;

import java.util.List;
import java.util.function.Consumer;

/**
 * The single seam between the app and any LLM backend (Strategy + Adapter).
 * Implementations translate between
 * these provider-agnostic types ({@link ChatPrompt} / {@link ChatResult}) and whatever the
 * underlying SDK or HTTP API expects.
 *
 * <p>Note the interface is transport-agnostic: streaming pushes provider-agnostic
 * {@link ChatStreamEvent}s to a {@link Consumer}, so providers never depend on Spring MVC's SSE
 * types. Adapting events to a particular transport (SSE, WebSocket, …) is the caller's job.
 *
 * <p>Add a native (non-OpenAI-compatible) backend by dropping in one more {@code @Component}
 * that implements this interface — nothing else in the app needs to change.
 */
public interface ChatProvider {

    /** Stable identifier for this provider, e.g. {@code "openai"}. */
    String name();

    /** Blocking, single-shot chat completion. */
    ChatResult chat(ChatPrompt prompt);

    /**
     * Streaming chat completion. Invokes {@code onEvent} for each {@link ChatStreamEvent} as it
     * arrives — interleaved {@link ChatStreamEvent.TextDelta} and
     * {@link ChatStreamEvent.ToolCallDelta} fragments, then exactly one
     * {@link ChatStreamEvent.Completed} — and returns when the stream is exhausted. Exceptions
     * propagate to the caller (wrapped as {@link ChatProviderException}).
     */
    void stream(ChatPrompt prompt, Consumer<ChatStreamEvent> onEvent);

    /**
     * Text-only streaming: pushes each assistant content fragment to {@code onToken}, ignoring
     * tool-call and completion events. This is the native {@code /chat/stream} path, which only ever
     * renders text. Implemented on top of {@link #stream} so providers implement one method.
     */
    default void streamTokens(ChatPrompt prompt, Consumer<String> onToken) {
        stream(prompt, event -> {
            if (event instanceof ChatStreamEvent.TextDelta delta) {
                onToken.accept(delta.text());
            }
        });
    }

    /**
     * The model ids this backend actually offers (its own {@code /v1/models}), used to advertise the
     * full catalog at the gateway as {@code <backend>:<id>}. Default is empty: a provider that can't
     * enumerate models simply contributes none, and the gateway falls back to the configured default.
     * May throw {@link ChatProviderException} if the backend is reachable-but-failing; the caller
     * decides how to degrade.
     */
    default List<String> availableModels() {
        return List.of();
    }
}
