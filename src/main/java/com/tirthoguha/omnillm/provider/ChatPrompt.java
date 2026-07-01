package com.tirthoguha.omnillm.provider;

import java.util.List;

/**
 * Provider-agnostic chat request. Carries the full conversation ({@link Message}s in order), the
 * already-resolved {@code model} (the caller has applied any default fallback), and any function
 * tools the model may call, so providers can use it directly without knowing about app config.
 *
 * <p>The list-of-messages shape lets multi-turn callers (e.g. the OpenAI-compatible gateway, which
 * receives a whole conversation including a system prompt and history) pass everything through. The
 * convenience {@link #ChatPrompt(String, String)} constructor keeps the simple single-user-message
 * path (the {@code POST /chat} endpoint) terse.
 *
 * @param messages the conversation so far, in order; never empty
 * @param model    the resolved model id to run against
 * @param tools    function-tool declarations the model may call; empty when no tools are needed
 */
public record ChatPrompt(List<Message> messages, String model, List<ToolSpec> tools) {

    public ChatPrompt {
        messages = List.copyOf(messages);   // defensive + immutable
        tools    = tools == null ? List.of() : List.copyOf(tools);
    }

    /** Full conversation without tools — tools default to empty. */
    public ChatPrompt(List<Message> messages, String model) {
        this(messages, model, List.of());
    }

    /** Convenience for a single user-message prompt (no tools). */
    public ChatPrompt(String userMessage, String model) {
        this(List.of(new Message(Role.USER, userMessage)), model, List.of());
    }

    /** Who authored a message — mirrors the OpenAI chat roles we support. */
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    /**
     * One turn in the conversation.
     *
     * <p>For normal turns only {@code role} and {@code content} are set. For an assistant turn that
     * requested tool calls, {@code toolCalls} carries the calls the model made. For a
     * {@link Role#TOOL} turn that returns a function result, {@code toolCallId} ties the result back
     * to the originating call and {@code content} carries the function output.
     *
     * @param role       who authored it
     * @param content    the message text (or function-result text for TOOL messages)
     * @param toolCalls  model-requested function calls; non-empty only on ASSISTANT messages that
     *                   have finish_reason {@code tool_calls}
     * @param toolCallId the tool-call id this message is a response to; set only on TOOL messages
     */
    public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        /** Convenience constructor for non-tool messages (no tool call data). */
        public Message(Role role, String content) {
            this(role, content, List.of(), null);
        }
    }
}
