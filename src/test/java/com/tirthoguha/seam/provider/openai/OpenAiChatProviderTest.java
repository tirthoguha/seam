package com.tirthoguha.seam.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.tirthoguha.seam.provider.ChatPrompt;
import com.tirthoguha.seam.provider.ChatProviderException;
import com.tirthoguha.seam.provider.ChatResult;
import com.tirthoguha.seam.provider.ChatStreamEvent;
import com.tirthoguha.seam.provider.ToolSpec;

/**
 * Unit tests for {@link OpenAiChatProvider} with the official OpenAI SDK fully mocked — no network,
 * no running Docker Model Runner. We stub the {@code client.chat().completions()} chain and feed
 * back SDK response objects, exactly as a live Docker Model Runner ({@code ai/gemma3}) would.
 */
class OpenAiChatProviderTest {

    private OpenAIClient client;
    private ChatCompletionService completions;
    private OpenAiChatProvider provider;

    @BeforeEach
    void setUp() {
        client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        completions = mock(ChatCompletionService.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);

        provider = new OpenAiChatProvider("docker", client);
    }

    @Test
    void name_isTheBackendName() {
        assertThat(provider.name()).isEqualTo("docker");
    }

    @Test
    void chat_buildsParamsFromPrompt_andReturnsFirstChoiceContent() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionReturning("Hello from Docker Model Runner"));

        ChatResult result = provider.chat(new ChatPrompt("Say hi", "ai/gemma3"));

        // The provider echoes back its backend name, the resolved model, and the reply text.
        assertThat(result.backend()).isEqualTo("docker");
        assertThat(result.model()).isEqualTo("ai/gemma3");
        assertThat(result.reply()).isEqualTo("Hello from Docker Model Runner");

        // And the params it sent carry that model and the single user message.
        ArgumentCaptor<ChatCompletionCreateParams> captor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completions).create(captor.capture());
        ChatCompletionCreateParams sent = captor.getValue();
        assertThat(sent.model().asString()).isEqualTo("ai/gemma3");
        assertThat(sent.messages()).hasSize(1);
    }

    @Test
    void chat_whenNoChoices_returnsPlaceholder() {
        ChatCompletion empty = ChatCompletion.builder()
                .id("cmpl-empty")
                .created(0L)
                .model("ai/gemma3")
                .choices(List.of())
                .build();
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(empty);

        ChatResult result = provider.chat(new ChatPrompt("anything", "ai/gemma3"));

        assertThat(result.reply()).isEqualTo("(no content returned)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamTokens_emitsEachDeltaContentInOrder() {
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(
                chunkWithContent("Hel"),
                chunkWithContent("lo"),
                chunkWithContent(null),   // a delta with no content (e.g. the final role/stop chunk)
                chunkWithContent("!")));
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);

        StringBuilder seen = new StringBuilder();
        provider.streamTokens(new ChatPrompt("Tell me a joke", "ai/gemma3"), seen::append);

        assertThat(seen.toString()).isEqualTo("Hello!");
        verify(streamResponse).close();   // try-with-resources must close the stream
    }

    @Test
    @SuppressWarnings("unchecked")
    void stream_surfacesToolCallFragmentsAsToolCallDeltaEvents_andCompleted() {
        // A tool call streams as: an opening fragment (index+id+name), then argument fragments,
        // then a chunk with finish_reason=tool_calls. The provider must map these to ToolCallDelta
        // events (id/name only on the first) and a terminal Completed("tool_calls").
        StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
        when(streamResponse.stream()).thenReturn(Stream.of(
                chunkWithToolCall(0, "call-1", "get_weather", "{\"ci"),
                chunkWithToolCall(0, null, null, "ty\":\"Sydney\"}"),
                chunkWithFinish("tool_calls")));
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);

        List<ChatStreamEvent> events = new java.util.ArrayList<>();
        provider.stream(new ChatPrompt("What's the weather?", "ai/gemma3"), events::add);

        // Two tool-call fragments + one Completed.
        List<ChatStreamEvent.ToolCallDelta> toolDeltas = events.stream()
                .filter(e -> e instanceof ChatStreamEvent.ToolCallDelta)
                .map(e -> (ChatStreamEvent.ToolCallDelta) e)
                .toList();
        assertThat(toolDeltas).hasSize(2);
        assertThat(toolDeltas.get(0).index()).isEqualTo(0);
        assertThat(toolDeltas.get(0).id()).isEqualTo("call-1");
        assertThat(toolDeltas.get(0).name()).isEqualTo("get_weather");
        assertThat(toolDeltas.get(0).argumentsFragment()).isEqualTo("{\"ci");
        // Continuation fragment: id/name null, only the arguments fragment.
        assertThat(toolDeltas.get(1).id()).isNull();
        assertThat(toolDeltas.get(1).name()).isNull();
        assertThat(toolDeltas.get(1).argumentsFragment()).isEqualTo("ty\":\"Sydney\"}");

        ChatStreamEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(ChatStreamEvent.Completed.class);
        assertThat(((ChatStreamEvent.Completed) last).finishReason()).isEqualTo("tool_calls");
        verify(streamResponse).close();
    }

    @Test
    void chat_wrapsSdkErrorAsChatProviderException() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenThrow(new OpenAIInvalidDataException("backend unavailable"));

        assertThatThrownBy(() -> provider.chat(new ChatPrompt("hi", "ai/gemma3")))
                .isInstanceOf(ChatProviderException.class)
                .hasMessageContaining("OpenAI chat completion failed")
                .extracting(e -> ((ChatProviderException) e).provider()).isEqualTo("docker");
    }

    @Test
    void streamTokens_wrapsSdkErrorAsChatProviderException() {
        when(completions.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenThrow(new OpenAIInvalidDataException("backend unavailable"));

        assertThatThrownBy(() -> provider.streamTokens(new ChatPrompt("hi", "ai/gemma3"), t -> { }))
                .isInstanceOf(ChatProviderException.class)
                .hasMessageContaining("OpenAI streaming completion failed");
    }

    @Test
    void chat_withTools_includesToolsInSdkParams() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionReturning("sure"));

        List<ToolSpec> tools = List.of(
                new ToolSpec("get_weather", "Get weather for a city",
                        java.util.Map.of("type", "object",
                                "properties", java.util.Map.of(
                                        "city", java.util.Map.of("type", "string")))));
        ChatPrompt prompt = new ChatPrompt(
                List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, "What's the weather?")),
                "ai/gemma3", tools);

        provider.chat(prompt);

        ArgumentCaptor<ChatCompletionCreateParams> captor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completions).create(captor.capture());

        // The SDK params must carry exactly the one tool we passed in.
        ChatCompletionCreateParams sent = captor.getValue();
        assertThat(sent.tools()).isPresent();
        assertThat(sent.tools().get()).hasSize(1);
        assertThat(sent.tools().get().get(0).isFunction()).isTrue();
        assertThat(sent.tools().get().get(0).asFunction().function().name())
                .isEqualTo("get_weather");
    }

    @Test
    void chat_withToolCallResponse_extractsToolCallsAndFinishReason() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionWithToolCall("call-1", "get_weather", "{\"city\":\"Sydney\"}"));

        ChatResult result = provider.chat(new ChatPrompt(
                "What's the weather?", "ai/gemma3"));

        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(result.toolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(result.toolCalls().get(0).argumentsJson()).isEqualTo("{\"city\":\"Sydney\"}");
        // No text content when model only made tool calls.
        assertThat(result.reply()).isEmpty();
    }

    @Test
    void chat_forwardsRequiredToolChoice() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionReturning("ok"));

        provider.chat(new ChatPrompt(
                List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, "weather?")),
                "ai/gemma3", weatherTool(), com.tirthoguha.seam.provider.ToolChoice.required(),
                com.tirthoguha.seam.provider.SamplingParams.NONE));

        ChatCompletionCreateParams sent = capture();
        assertThat(sent.toolChoice()).isPresent();
        assertThat(sent.toolChoice().get().isAuto()).isTrue();
        assertThat(sent.toolChoice().get().asAuto())
                .isEqualTo(com.openai.models.chat.completions.ChatCompletionToolChoiceOption.Auto.REQUIRED);
    }

    @Test
    void chat_forwardsForcedFunctionToolChoice() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionReturning("ok"));

        provider.chat(new ChatPrompt(
                List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, "weather?")),
                "ai/gemma3", weatherTool(),
                com.tirthoguha.seam.provider.ToolChoice.function("get_weather"),
                com.tirthoguha.seam.provider.SamplingParams.NONE));

        ChatCompletionCreateParams sent = capture();
        assertThat(sent.toolChoice()).isPresent();
        assertThat(sent.toolChoice().get().isNamedToolChoice()).isTrue();
        assertThat(sent.toolChoice().get().asNamedToolChoice().function().name())
                .isEqualTo("get_weather");
    }

    @Test
    void chat_forwardsSamplingParams() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionReturning("ok"));

        var sampling = new com.tirthoguha.seam.provider.SamplingParams(
                0.2, 0.9, 128, List.of("STOP"), 42L, null);
        provider.chat(new ChatPrompt(
                List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, "hi")),
                "ai/gemma3", List.of(), null, sampling));

        ChatCompletionCreateParams sent = capture();
        assertThat(sent.temperature()).hasValue(0.2);
        assertThat(sent.topP()).hasValue(0.9);
        assertThat(sent.maxCompletionTokens()).hasValue(128L);
        assertThat(sent.seed()).hasValue(42L);
        assertThat(sent.stop()).isPresent();
        assertThat(sent.stop().get().asStrings()).containsExactly("STOP");
    }

    @Test
    void chat_forwardsMultimodalUserMessageAsContentPartArray() {
        when(completions.create(any(ChatCompletionCreateParams.class)))
                .thenReturn(completionReturning("a cat"));

        ChatPrompt.Message multimodal = new ChatPrompt.Message(
                ChatPrompt.Role.USER, "what is this?", List.of(), null,
                List.of(ChatPrompt.ContentPart.text("what is this?"),
                        ChatPrompt.ContentPart.imageUrl("data:image/png;base64,AAAA")));
        provider.chat(new ChatPrompt(List.of(multimodal), "ai/gemma3"));

        var userContent = capture().messages().get(0).asUser().content();
        assertThat(userContent.isArrayOfContentParts()).isTrue();
        var parts = userContent.asArrayOfContentParts();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).isText()).isTrue();
        assertThat(parts.get(1).isImageUrl()).isTrue();
        assertThat(parts.get(1).asImageUrl().imageUrl().url())
                .isEqualTo("data:image/png;base64,AAAA");
    }

    /** Capture the single params object passed to the mocked completions.create(...). */
    private ChatCompletionCreateParams capture() {
        ArgumentCaptor<ChatCompletionCreateParams> captor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completions).create(captor.capture());
        return captor.getValue();
    }

    private static List<ToolSpec> weatherTool() {
        return List.of(new ToolSpec("get_weather", "Get weather for a city",
                java.util.Map.of("type", "object",
                        "properties", java.util.Map.of("city", java.util.Map.of("type", "string")))));
    }

    // --- helpers that build the SDK response objects a real backend would return ---

    private static ChatCompletion completionReturning(String content) {
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .role(JsonValue.from("assistant"))
                .content(content)
                .refusal(Optional.empty())
                .build();

        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0L)
                .message(message)
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .logprobs(Optional.empty())
                .build();

        return ChatCompletion.builder()
                .id("cmpl-1")
                .created(0L)
                .model("ai/gemma3")
                .choices(List.of(choice))
                .build();
    }

    /** Build a completion whose first choice has a function tool call and finish_reason=TOOL_CALLS. */
    private static ChatCompletion completionWithToolCall(String callId, String fnName, String args) {
        ChatCompletionMessageFunctionToolCall fnToolCall =
                ChatCompletionMessageFunctionToolCall.builder()
                        .id(callId)
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name(fnName)
                                .arguments(args)
                                .build())
                        .build();

        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .role(JsonValue.from("assistant"))
                .content(Optional.empty())   // no text content when only tool calls
                .refusal(Optional.empty())
                .addToolCall(fnToolCall)
                .build();

        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0L)
                .message(message)
                .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
                .logprobs(Optional.empty())
                .build();

        return ChatCompletion.builder()
                .id("cmpl-tool")
                .created(0L)
                .model("ai/gemma3")
                .choices(List.of(choice))
                .build();
    }

    private static ChatCompletionChunk chunkWithContent(String content) {
        ChatCompletionChunk.Choice.Delta.Builder delta = ChatCompletionChunk.Choice.Delta.builder();
        if (content != null) {
            delta.content(content);
        }

        ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
                .index(0L)
                .delta(delta.build())
                .finishReason(Optional.empty())
                .build();

        return ChatCompletionChunk.builder()
                .id("chunk-1")
                .created(0L)
                .model("ai/gemma3")
                .choices(List.of(choice))
                .build();
    }

    /**
     * A chunk carrying one streamed tool-call fragment. On the first fragment for an index, id/name
     * are set (announcing the call); continuation fragments pass them null and only carry arguments.
     */
    private static ChatCompletionChunk chunkWithToolCall(int index, String id, String name, String argsFragment) {
        ChatCompletionChunk.Choice.Delta.ToolCall.Function.Builder fn =
                ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                        .arguments(argsFragment);
        if (name != null) {
            fn.name(name);
        }
        ChatCompletionChunk.Choice.Delta.ToolCall.Builder toolCall =
                ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                        .index((long) index)
                        .function(fn.build());
        if (id != null) {
            toolCall.id(id);
        }

        ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
                .addToolCall(toolCall.build())
                .build();

        ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
                .index(0L)
                .delta(delta)
                .finishReason(Optional.empty())
                .build();

        return ChatCompletionChunk.builder()
                .id("chunk-1")
                .created(0L)
                .model("ai/gemma3")
                .choices(List.of(choice))
                .build();
    }

    /** A terminal chunk: empty delta with a finish reason. */
    private static ChatCompletionChunk chunkWithFinish(String finishReason) {
        ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
                .index(0L)
                .delta(ChatCompletionChunk.Choice.Delta.builder().build())
                .finishReason(ChatCompletionChunk.Choice.FinishReason.of(finishReason))
                .build();

        return ChatCompletionChunk.builder()
                .id("chunk-1")
                .created(0L)
                .model("ai/gemma3")
                .choices(List.of(choice))
                .build();
    }
}
