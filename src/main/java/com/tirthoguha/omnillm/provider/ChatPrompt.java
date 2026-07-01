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
 * @param messages   the conversation so far, in order; never empty
 * @param model      the resolved model id to run against
 * @param tools      function-tool declarations the model may call; empty when no tools are needed
 * @param toolChoice how the model should select among {@code tools}; {@code null} leaves selection
 *                   entirely to the backend (the behaviour before tool_choice forwarding)
 * @param sampling   generation knobs (temperature, top_p, …); never {@code null} (defaults to
 *                   {@link SamplingParams#NONE})
 */
public record ChatPrompt(List<Message> messages, String model, List<ToolSpec> tools,
                         ToolChoice toolChoice, SamplingParams sampling) {

    public ChatPrompt {
        messages = List.copyOf(messages);   // defensive + immutable
        tools    = tools == null ? List.of() : List.copyOf(tools);
        sampling = sampling == null ? SamplingParams.NONE : sampling;
        // toolChoice may stay null → provider forwards no tool_choice
    }

    /** Full conversation with tools but no tool_choice / sampling overrides. */
    public ChatPrompt(List<Message> messages, String model, List<ToolSpec> tools) {
        this(messages, model, tools, null, SamplingParams.NONE);
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

    /** The kind of a multimodal content part. */
    public enum ContentType { TEXT, IMAGE_URL }

    /**
     * One part of a multimodal message. Exactly one of {@code text}/{@code imageUrl} is set,
     * per {@link #type()}. {@code imageUrl} may be an {@code http(s)} URL or a {@code data:} URI
     * (base64 inline image, as Open WebUI sends on upload).
     */
    public record ContentPart(ContentType type, String text, String imageUrl) {
        public static ContentPart text(String text) {
            return new ContentPart(ContentType.TEXT, text, null);
        }

        public static ContentPart imageUrl(String url) {
            return new ContentPart(ContentType.IMAGE_URL, null, url);
        }
    }

    /**
     * One turn in the conversation.
     *
     * <p>For normal turns only {@code role} and {@code content} are set. For an assistant turn that
     * requested tool calls, {@code toolCalls} carries the calls the model made. For a
     * {@link Role#TOOL} turn that returns a function result, {@code toolCallId} ties the result back
     * to the originating call and {@code content} carries the function output. For a multimodal user
     * turn, {@code parts} carries the ordered text + image parts; {@code content} then holds the
     * flattened text (a fallback for backends/callers that ignore parts).
     *
     * @param role       who authored it
     * @param content    the message text (or function-result text for TOOL messages); for multimodal
     *                   turns, the flattened text of {@code parts}
     * @param toolCalls  model-requested function calls; non-empty only on ASSISTANT messages that
     *                   have finish_reason {@code tool_calls}
     * @param toolCallId the tool-call id this message is a response to; set only on TOOL messages
     * @param parts      multimodal content parts (text + image), in order; empty for plain-text turns
     */
    public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId,
                          List<ContentPart> parts) {

        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            parts     = parts == null ? List.of() : List.copyOf(parts);
        }

        /** Convenience constructor for non-tool, non-multimodal messages. */
        public Message(Role role, String content) {
            this(role, content, List.of(), null, List.of());
        }

        /** Convenience constructor for tool round-trip messages (no multimodal parts). */
        public Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this(role, content, toolCalls, toolCallId, List.of());
        }

        /** True when this message carries at least one image part (i.e. is multimodal). */
        public boolean hasImageParts() {
            return parts.stream().anyMatch(p -> p.type() == ContentType.IMAGE_URL);
        }
    }
}
