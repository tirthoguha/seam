package com.tirthoguha.omnillm.provider.openai;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderException;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.provider.ChatStreamEvent;
import com.tirthoguha.omnillm.provider.SamplingParams;
import com.tirthoguha.omnillm.provider.ToolCall;
import com.tirthoguha.omnillm.provider.ToolChoice;
import com.tirthoguha.omnillm.provider.ToolSpec;
import com.tirthoguha.omnillm.provider.Usage;

/**
 * {@link ChatProvider} backed by the official OpenAI Java SDK. Because every OpenAI-compatible
 * backend (OpenAI cloud, Docker Model Runner) speaks the same {@code /v1} API, this one
 * adapter covers all of them — one instance is created per configured backend (each with its own
 * base-url/key {@link OpenAIClient}) and registered by {@code name} in the
 * {@link com.tirthoguha.omnillm.provider.ChatProviderRegistry}.
 *
 * <p>This is the only class in the app (besides {@link OpenAiResponsesProvider} and
 * {@code OpenAiConfig}) that imports {@code com.openai.*}; that coupling stops at the provider
 * boundary. SDK failures are wrapped in {@link ChatProviderException} so callers never see vendor
 * exception types.
 */
public class OpenAiChatProvider implements ChatProvider {

    private final String name;
    private final OpenAIClient client;

    public OpenAiChatProvider(String name, OpenAIClient client) {
        this.name = name;
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResult chat(ChatPrompt prompt) {
        ChatCompletionCreateParams params = buildParams(prompt);

        try {
            ChatCompletion completion = client.chat().completions().create(params);

            // Extract the first choice — use empty string (not a placeholder) when there is no
            // content, since the model may have responded with tool calls instead.
            ChatCompletion.Choice firstChoice = completion.choices().stream()
                    .findFirst()
                    .orElse(null);

            if (firstChoice == null) {
                return new ChatResult(name, prompt.model(), "(no content returned)",
                        List.of(), "stop", extractUsage(completion));
            }

            String reply = firstChoice.message().content().orElse("");
            String finishReason = firstChoice.finishReason().asString();

            // Map SDK tool calls → our provider-agnostic ToolCall records.
            List<ToolCall> toolCalls = firstChoice.message().toolCalls()
                    .map(calls -> calls.stream()
                            .filter(ChatCompletionMessageToolCall::isFunction)
                            .map(tc -> {
                                ChatCompletionMessageFunctionToolCall fn = tc.asFunction();
                                return new ToolCall(
                                        fn.id(),
                                        fn.function().name(),
                                        fn.function().arguments());
                            })
                            .collect(Collectors.toList()))
                    .orElse(List.of());

            return new ChatResult(name, prompt.model(), reply, toolCalls,
                    finishReason, extractUsage(completion));
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI chat completion failed", e);
        }
    }

    @Override
    public List<String> availableModels() {
        return OpenAiModels.list(name, client);
    }

    @Override
    public void stream(ChatPrompt prompt, Consumer<ChatStreamEvent> onEvent) {
        // Request usage on the stream so the terminal chunk carries token counts we can surface on
        // Completed. Backends that don't support stream options simply omit usage (Optional.empty).
        ChatCompletionCreateParams params = buildParams(prompt).toBuilder()
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
                .build();

        String finishReason = null;
        Usage usage = null;

        try (var streaming = client.chat().completions().createStreaming(params)) {
            var iterator = streaming.stream().iterator();
            while (iterator.hasNext()) {
                ChatCompletionChunk chunk = iterator.next();

                // The terminal usage chunk (from includeUsage) has no choices — capture and skip.
                if (usage == null) {
                    usage = chunk.usage()
                            .map(u -> new Usage(u.promptTokens(), u.completionTokens(), u.totalTokens()))
                            .orElse(null);
                }

                ChatCompletionChunk.Choice choice = chunk.choices().stream().findFirst().orElse(null);
                if (choice == null) {
                    continue;
                }

                ChatCompletionChunk.Choice.Delta delta = choice.delta();
                delta.content().ifPresent(text -> onEvent.accept(new ChatStreamEvent.TextDelta(text)));

                delta.toolCalls().ifPresent(calls -> calls.forEach(tc ->
                        onEvent.accept(new ChatStreamEvent.ToolCallDelta(
                                (int) tc.index(),
                                tc.id().orElse(null),
                                tc.function().flatMap(f -> f.name()).orElse(null),
                                tc.function().flatMap(f -> f.arguments()).orElse("")))));

                if (choice.finishReason().isPresent()) {
                    finishReason = choice.finishReason().get().asString();
                }
            }
            // One terminal event once the stream is exhausted.
            onEvent.accept(new ChatStreamEvent.Completed(finishReason, usage));
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI streaming completion failed", e);
        }
    }

    private ChatCompletionCreateParams buildParams(ChatPrompt prompt) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(prompt.model());

        for (ChatPrompt.Message message : prompt.messages()) {
            switch (message.role()) {
                case SYSTEM -> builder.addSystemMessage(message.content());
                case USER -> {
                    if (message.hasImageParts()) {
                        // Multimodal turn: forward ordered text + image parts as a content array.
                        builder.addUserMessageOfArrayOfContentParts(toContentParts(message.parts()));
                    } else {
                        builder.addUserMessage(message.content());
                    }
                }
                case ASSISTANT -> {
                    if (!message.toolCalls().isEmpty()) {
                        // Assistant turn that requested function calls: replay the tool calls so
                        // the model understands its own prior actions in multi-turn history.
                        ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                                ChatCompletionAssistantMessageParam.builder();
                        if (message.content() != null && !message.content().isEmpty()) {
                            assistantBuilder.content(message.content());
                        }
                        for (ToolCall tc : message.toolCalls()) {
                            assistantBuilder.addToolCall(
                                    ChatCompletionMessageFunctionToolCall.builder()
                                            .id(tc.id())
                                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                                    .name(tc.name())
                                                    .arguments(tc.argumentsJson())
                                                    .build())
                                            .build());
                        }
                        builder.addMessage(assistantBuilder.build());
                    } else {
                        builder.addAssistantMessage(message.content());
                    }
                }
                case TOOL -> {
                    // Tool-result message: echo the function output back to the model.
                    builder.addMessage(ChatCompletionToolMessageParam.builder()
                            .content(message.content() != null ? message.content() : "")
                            .toolCallId(message.toolCallId() != null ? message.toolCallId() : "")
                            .build());
                }
            }
        }

        // Attach tool definitions when the prompt carries any. tool_choice only makes sense
        // alongside tools, so it is forwarded only in that case.
        if (!prompt.tools().isEmpty()) {
            List<ChatCompletionTool> sdkTools = prompt.tools().stream()
                    .map(OpenAiChatProvider::toSdkTool)
                    .collect(Collectors.toList());
            builder.tools(sdkTools);
            applyToolChoice(builder, prompt.toolChoice());
        }

        applySampling(builder, prompt.sampling());

        return builder.build();
    }

    /** Forward a {@link ToolChoice} onto the Chat Completions request; no-op when unset. */
    private static void applyToolChoice(ChatCompletionCreateParams.Builder builder, ToolChoice tc) {
        if (tc == null) {
            return;
        }
        switch (tc.mode()) {
            case AUTO -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO);
            case NONE -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
            case REQUIRED -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED);
            case FUNCTION -> builder.toolChoice(ChatCompletionNamedToolChoice.builder()
                    .function(ChatCompletionNamedToolChoice.Function.builder()
                            .name(tc.functionName())
                            .build())
                    .build());
        }
    }

    /**
     * Apply the sampling knobs this protocol supports. {@code max_tokens} maps to the modern
     * {@code max_completion_tokens}; {@code reasoning_effort} is Responses-API-only and ignored here.
     */
    private static void applySampling(ChatCompletionCreateParams.Builder builder, SamplingParams s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if (s.temperature() != null) {
            builder.temperature(s.temperature());
        }
        if (s.topP() != null) {
            builder.topP(s.topP());
        }
        if (s.maxTokens() != null) {
            builder.maxCompletionTokens(s.maxTokens().longValue());
        }
        if (s.seed() != null) {
            builder.seed(s.seed());
        }
        if (s.stop() != null && !s.stop().isEmpty()) {
            builder.stop(ChatCompletionCreateParams.Stop.ofStrings(s.stop()));
        }
    }

    /** Map provider-agnostic content parts onto the SDK's multimodal content-part list. */
    private static List<ChatCompletionContentPart> toContentParts(List<ChatPrompt.ContentPart> parts) {
        return parts.stream()
                .map(p -> switch (p.type()) {
                    case TEXT -> ChatCompletionContentPart.ofText(
                            ChatCompletionContentPartText.builder()
                                    .text(p.text() != null ? p.text() : "")
                                    .build());
                    case IMAGE_URL -> ChatCompletionContentPart.ofImageUrl(
                            ChatCompletionContentPartImage.builder()
                                    .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                            .url(p.imageUrl())
                                            .build())
                                    .build());
                })
                .collect(Collectors.toList());
    }

    /** Map a {@link ToolSpec} onto the SDK's {@link ChatCompletionTool} (function type). */
    private static ChatCompletionTool toSdkTool(ToolSpec spec) {
        FunctionDefinition.Builder fnBuilder = FunctionDefinition.builder()
                .name(spec.name());
        if (spec.description() != null) {
            fnBuilder.description(spec.description());
        }
        if (spec.parameters() != null) {
            // FunctionParameters stores its schema as arbitrary additional properties
            // (JsonValue map), exactly how the OpenAI SDK encodes a raw JSON Schema object.
            FunctionParameters.Builder paramsBuilder = FunctionParameters.builder();
            spec.parameters().forEach((k, v) ->
                    paramsBuilder.putAdditionalProperty(k, JsonValue.from(v)));
            fnBuilder.parameters(paramsBuilder.build());
        }
        return ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder()
                        .function(fnBuilder.build())
                        .build());
    }

    /** Convert SDK usage (present only when the backend reported it) to our record. */
    private static Usage extractUsage(ChatCompletion completion) {
        return completion.usage()
                .map(u -> new Usage(u.promptTokens(), u.completionTokens(), u.totalTokens()))
                .orElse(null);
    }
}
