package com.tirthoguha.omnillm.dto.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.provider.ToolCall;
import com.tirthoguha.omnillm.provider.Usage;

/**
 * OpenAI {@code chat.completion} response (non-streaming). Field names match the OpenAI schema so
 * standard clients parse it unchanged. {@code usage} is omitted from JSON when {@code null}
 * (backends that do not report tokens).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        UsageDto usage) {

    public record Choice(int index, Message message, String finish_reason) {
    }

    /**
     * An assistant message in the response. {@code tool_calls} is omitted from JSON when null so
     * plain text responses stay compact.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String role, String content, List<ToolCallDto> tool_calls) {
    }

    /** One function call the model is requesting. */
    public record ToolCallDto(String id, String type, FunctionDto function) {

        public record FunctionDto(String name, String arguments) {
        }
    }

    /** Token-usage summary; omitted from JSON when the backend did not report it. */
    public record UsageDto(long prompt_tokens, long completion_tokens, long total_tokens) {
    }

    /**
     * Build a single-choice response from a full {@link ChatResult}. The {@code finish_reason}
     * and {@code tool_calls} come from the result; usage is included when present.
     */
    public static OpenAiChatCompletionResponse of(String id, long created, String model,
                                                   ChatResult result) {
        List<ToolCallDto> toolCallDtos = result.toolCalls().isEmpty() ? null
                : result.toolCalls().stream()
                        .map(OpenAiChatCompletionResponse::toToolCallDto)
                        .toList();

        // When the model only made tool calls, content is an empty string per the OpenAI spec;
        // pass it through from the result rather than substituting null.
        Message message = new Message("assistant",
                result.reply().isEmpty() && toolCallDtos != null ? null : result.reply(),
                toolCallDtos);

        UsageDto usageDto = toUsageDto(result.usage());

        return new OpenAiChatCompletionResponse(id, "chat.completion", created, model,
                List.of(new Choice(0, message, result.finishReason())), usageDto);
    }

    /**
     * Legacy factory kept for callers that only have a plain text reply. Delegates to the richer
     * factory via a synthetic {@link ChatResult}.
     */
    public static OpenAiChatCompletionResponse of(String id, long created, String model,
                                                   String content) {
        return of(id, created, model, ChatResult.text(null, model, content));
    }

    private static ToolCallDto toToolCallDto(ToolCall tc) {
        return new ToolCallDto(tc.id(), "function",
                new ToolCallDto.FunctionDto(tc.name(), tc.argumentsJson()));
    }

    private static UsageDto toUsageDto(Usage usage) {
        if (usage == null) {
            return null;
        }
        return new UsageDto(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }
}
