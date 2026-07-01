package com.tirthoguha.omnillm.provider.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceFunction;
import com.openai.models.responses.ToolChoiceOptions;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderException;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.provider.ChatStreamEvent;
import com.tirthoguha.omnillm.provider.SamplingParams;
import com.tirthoguha.omnillm.provider.ToolCall;
import com.tirthoguha.omnillm.provider.ToolCallTextParser;
import com.tirthoguha.omnillm.provider.ToolChoice;
import com.tirthoguha.omnillm.provider.ToolSpec;
import com.tirthoguha.omnillm.provider.Usage;

/**
 * {@link ChatProvider} backed by the OpenAI <strong>Responses API</strong>
 * ({@code POST /v1/responses}) instead of Chat Completions. This is the path for newer OpenAI models
 * (e.g. {@code gpt-5.5}) that are steered toward the Responses API. A backend opts in with
 * {@code app.llm.backends.<name>.api: responses}; {@link com.tirthoguha.omnillm.config.OpenAiConfig}
 * then registers this provider for that backend in place of {@link OpenAiChatProvider}.
 *
 * <p>It implements the same {@link ChatProvider} seam, so {@code ChatService}, the controllers, and
 * the OpenAI-compatible gateway are unaware of which wire protocol a backend uses — they keep
 * speaking {@link ChatPrompt} / {@link ChatResult}. Chat messages are mapped onto the Responses API
 * shape: {@code system} turns become {@code instructions}; {@code user}/{@code assistant} turns are
 * added as structured input items.
 *
 * <p><strong>Tool calling (blocking path):</strong> function tools are forwarded to the Responses
 * API and function-call output items are extracted from the response. {@link ChatPrompt.Role#TOOL}
 * result messages are replayed as {@code function_call_output} input items.
 */
public class OpenAiResponsesProvider implements ChatProvider {

    private final String name;
    private final OpenAIClient client;

    public OpenAiResponsesProvider(String name, OpenAIClient client) {
        this.name = name;
        this.client = client;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatResult chat(ChatPrompt prompt) {
        ResponseCreateParams params = buildParams(prompt);

        try {
            Response response = client.responses().create(params);

            // Extract function-call output items first so we know whether this is a tool-call turn.
            List<ToolCall> toolCalls = response.output().stream()
                    .filter(ResponseOutputItem::isFunctionCall)
                    .map(ResponseOutputItem::asFunctionCall)
                    .map(fc -> new ToolCall(fc.callId(), fc.name(), fc.arguments()))
                    .collect(Collectors.toList());

            String finishReason = toolCalls.isEmpty() ? "stop" : "tool_calls";
            String reply = toolCalls.isEmpty() ? extractText(response) : "";

            Usage usage = response.usage()
                    .map(u -> new Usage(u.inputTokens(), u.outputTokens(), u.totalTokens()))
                    .orElse(null);

            ChatResult result = new ChatResult(name, prompt.model(), reply, toolCalls, finishReason, usage);
            return ToolCallTextParser.applyFallback(result, prompt);
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI Responses API request failed", e);
        }
    }

    @Override
    public List<String> availableModels() {
        return OpenAiModels.list(name, client);
    }

    @Override
    public void stream(ChatPrompt prompt, Consumer<ChatStreamEvent> onEvent) {
        ResponseCreateParams params = buildParams(prompt);

        // Responses streams function calls across events: an output-item-added event announces a
        // call (call_id + name, keyed by its output index), then argument-delta events carry the
        // JSON fragments for that item id. We map the item id → its output index so continuation
        // fragments land on the same ToolCallDelta index the announcement used.
        java.util.Map<String, Integer> indexByItemId = new java.util.HashMap<>();
        boolean sawFunctionCall = false;
        Usage usage = null;

        try (var streaming = client.responses().createStreaming(params)) {
            var iterator = streaming.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();

                // Output text fragments → TextDelta.
                if (event.outputTextDelta().isPresent()) {
                    onEvent.accept(new ChatStreamEvent.TextDelta(event.outputTextDelta().get().delta()));
                    continue;
                }

                // A new output item was announced. If it's a function call, record its index and
                // emit the opening ToolCallDelta carrying call_id + name.
                if (event.outputItemAdded().isPresent()) {
                    var added = event.outputItemAdded().get();
                    ResponseOutputItem item = added.item();
                    if (item.isFunctionCall()) {
                        ResponseFunctionToolCall fc = item.asFunctionCall();
                        int index = (int) added.outputIndex();
                        fc.id().ifPresent(itemId -> indexByItemId.put(itemId, index));
                        sawFunctionCall = true;
                        onEvent.accept(new ChatStreamEvent.ToolCallDelta(
                                index, fc.callId(), fc.name(), ""));
                    }
                    continue;
                }

                // Argument fragments for a previously-announced function call.
                if (event.functionCallArgumentsDelta().isPresent()) {
                    var argsDelta = event.functionCallArgumentsDelta().get();
                    int index = indexByItemId.getOrDefault(argsDelta.itemId(), (int) argsDelta.outputIndex());
                    sawFunctionCall = true;
                    onEvent.accept(new ChatStreamEvent.ToolCallDelta(
                            index, null, null, argsDelta.delta()));
                    continue;
                }

                // Terminal event: capture usage if the SDK exposes it.
                if (event.completed().isPresent()) {
                    usage = event.completed().get().response().usage()
                            .map(u -> new Usage(u.inputTokens(), u.outputTokens(), u.totalTokens()))
                            .orElse(null);
                }
            }

            String finishReason = sawFunctionCall ? "tool_calls" : "stop";
            onEvent.accept(new ChatStreamEvent.Completed(finishReason, usage));
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI Responses API streaming failed", e);
        }
    }

    /**
     * Map provider-agnostic messages onto the Responses API. System turns become
     * {@code instructions}; user/assistant turns are added as structured input items (text parts);
     * TOOL result turns are replayed as {@code function_call_output} input items; ASSISTANT turns
     * that carried tool calls are replayed as {@code function_call} input items.
     */
    private ResponseCreateParams buildParams(ChatPrompt prompt) {
        StringBuilder instructions = new StringBuilder();
        List<ResponseInputItem> inputItems = new ArrayList<>();

        for (ChatPrompt.Message message : prompt.messages()) {
            switch (message.role()) {
                case SYSTEM -> append(instructions, message.content());
                case USER -> inputItems.add(
                        ResponseInputItem.ofEasyInputMessage(
                                com.openai.models.responses.EasyInputMessage.builder()
                                        .role(com.openai.models.responses.EasyInputMessage.Role.USER)
                                        .content(userContent(message))
                                        .build()));
                case ASSISTANT -> {
                    if (!message.toolCalls().isEmpty()) {
                        // Replay each tool call the assistant made in a prior turn.
                        for (ToolCall tc : message.toolCalls()) {
                            inputItems.add(ResponseInputItem.ofFunctionCall(
                                    ResponseFunctionToolCall.builder()
                                            .callId(tc.id())
                                            .name(tc.name())
                                            .arguments(tc.argumentsJson())
                                            .build()));
                        }
                    } else {
                        inputItems.add(
                                ResponseInputItem.ofEasyInputMessage(
                                        com.openai.models.responses.EasyInputMessage.builder()
                                                .role(com.openai.models.responses.EasyInputMessage.Role.ASSISTANT)
                                                .content(message.content() != null ? message.content() : "")
                                                .build()));
                    }
                }
                case TOOL -> {
                    // Tool-result: echo the function output back keyed by callId.
                    inputItems.add(ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                    .callId(message.toolCallId() != null ? message.toolCallId() : "")
                                    .output(message.content() != null ? message.content() : "")
                                    .build()));
                }
            }
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(prompt.model());

        if (!inputItems.isEmpty()) {
            builder.inputOfResponse(inputItems);
        } else {
            builder.input("");
        }

        if (!instructions.isEmpty()) {
            builder.instructions(instructions.toString().strip());
        }

        // Attach function tools when the prompt carries any. tool_choice only makes sense
        // alongside tools, so it is forwarded only in that case.
        if (!prompt.tools().isEmpty()) {
            List<Tool> sdkTools = prompt.tools().stream()
                    .map(OpenAiResponsesProvider::toSdkTool)
                    .collect(Collectors.toList());
            builder.tools(sdkTools);
            applyToolChoice(builder, prompt.toolChoice());
        }

        applySampling(builder, prompt.sampling());

        return builder.build();
    }

    /** Forward a {@link ToolChoice} onto the Responses request; no-op when unset. */
    private static void applyToolChoice(ResponseCreateParams.Builder builder, ToolChoice tc) {
        if (tc == null) {
            return;
        }
        switch (tc.mode()) {
            case AUTO -> builder.toolChoice(ToolChoiceOptions.AUTO);
            case NONE -> builder.toolChoice(ToolChoiceOptions.NONE);
            case REQUIRED -> builder.toolChoice(ToolChoiceOptions.REQUIRED);
            case FUNCTION -> builder.toolChoice(ToolChoiceFunction.builder()
                    .name(tc.functionName())
                    .build());
        }
    }

    /**
     * Apply the sampling knobs the Responses API supports. {@code max_tokens} maps to
     * {@code max_output_tokens}; {@code reasoning_effort} maps to the reasoning config.
     * {@code stop}/{@code seed} have no Responses-API equivalent and are ignored here.
     */
    private static void applySampling(ResponseCreateParams.Builder builder, SamplingParams s) {
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
            builder.maxOutputTokens(s.maxTokens().longValue());
        }
        if (s.reasoningEffort() != null && !s.reasoningEffort().isBlank()) {
            builder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(s.reasoningEffort()))
                    .build());
        }
    }

    /**
     * Build the Responses API content for a user turn: a multimodal content list (ordered text +
     * images) when the message carries image parts, otherwise a plain text string.
     */
    private static EasyInputMessage.Content userContent(ChatPrompt.Message message) {
        if (message.hasImageParts()) {
            List<ResponseInputContent> content = message.parts().stream()
                    .map(p -> switch (p.type()) {
                        case TEXT -> ResponseInputContent.ofInputText(
                                ResponseInputText.builder()
                                        .text(p.text() != null ? p.text() : "")
                                        .build());
                        case IMAGE_URL -> ResponseInputContent.ofInputImage(
                                ResponseInputImage.builder()
                                        .imageUrl(p.imageUrl())
                                        .detail(ResponseInputImage.Detail.AUTO)
                                        .build());
                    })
                    .collect(Collectors.toList());
            return EasyInputMessage.Content.ofResponseInputMessageContentList(content);
        }
        return EasyInputMessage.Content.ofTextInput(message.content() != null ? message.content() : "");
    }

    private static void append(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(text);
    }

    /** Concatenate the text of every output-message content part. */
    private static String extractText(Response response) {
        StringBuilder reply = new StringBuilder();
        response.output().stream()
                .filter(item -> item.isMessage())
                .map(item -> item.asMessage())
                .map(ResponseOutputMessage::content)
                .flatMap(java.util.List::stream)
                .filter(content -> content.isOutputText())
                .forEach(content -> reply.append(content.asOutputText().text()));
        return reply.isEmpty() ? "(no content returned)" : reply.toString();
    }

    /** Map a {@link ToolSpec} onto the Responses API's {@link Tool} (function type). */
    private static Tool toSdkTool(ToolSpec spec) {
        FunctionTool.Builder fnBuilder = FunctionTool.builder()
                .name(spec.name());
        if (spec.description() != null) {
            fnBuilder.description(spec.description());
        }
        if (spec.parameters() != null && !spec.parameters().isEmpty()) {
            // FunctionTool.Parameters uses the same additionalProperties bag as FunctionParameters.
            FunctionTool.Parameters.Builder paramsBuilder = FunctionTool.Parameters.builder();
            spec.parameters().forEach((k, v) ->
                    paramsBuilder.putAdditionalProperty(k, JsonValue.from(v)));
            fnBuilder.parameters(paramsBuilder.build());
        }
        return Tool.ofFunction(fnBuilder.build());
    }
}
