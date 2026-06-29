package com.tirthoguha.omnillm.provider;

import java.util.function.Consumer;

/**
 * The single seam between the app and any LLM backend (Strategy + Adapter).
 * Implementations translate between
 * these provider-agnostic types ({@link ChatPrompt} / {@link ChatResult}) and whatever the
 * underlying SDK or HTTP API expects.
 *
 * <p>Note the interface is transport-agnostic: streaming pushes plain text tokens to a
 * {@link Consumer}, so providers never depend on Spring MVC's SSE types. Adapting tokens to
 * a particular transport (SSE, WebSocket, …) is the caller's job.
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
     * Streaming chat completion. Invokes {@code onToken} once per text token as it arrives and
     * returns when the stream is exhausted. Exceptions propagate to the caller.
     */
    void streamTokens(ChatPrompt prompt, Consumer<String> onToken);
}
