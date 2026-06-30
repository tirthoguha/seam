package com.tirthoguha.omnillm.provider;

import java.util.List;

/**
 * Provider-agnostic chat request. Carries the full conversation ({@link Message}s in order) plus the
 * already-resolved {@code model} (the caller has applied any default fallback), so providers can use
 * it directly without knowing about app config.
 *
 * <p>The list-of-messages shape lets multi-turn callers (e.g. the OpenAI-compatible gateway, which
 * receives a whole conversation including a system prompt and history) pass everything through. The
 * convenience {@link #ChatPrompt(String, String)} constructor keeps the simple single-user-message
 * path (the {@code POST /chat} endpoint) terse.
 *
 * @param messages the conversation so far, in order; never empty
 * @param model    the resolved model id to run against
 */
public record ChatPrompt(List<Message> messages, String model) {

    public ChatPrompt {
        messages = List.copyOf(messages);   // defensive + immutable
    }

    /** Convenience for a single user-message prompt. */
    public ChatPrompt(String userMessage, String model) {
        this(List.of(new Message(Role.USER, userMessage)), model);
    }

    /** Who authored a message — mirrors the OpenAI chat roles we support. */
    public enum Role { SYSTEM, USER, ASSISTANT }

    /**
     * One turn in the conversation.
     *
     * @param role    who authored it
     * @param content the message text
     */
    public record Message(Role role, String content) {
    }
}
