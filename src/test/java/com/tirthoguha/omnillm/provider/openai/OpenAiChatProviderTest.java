package com.tirthoguha.omnillm.provider.openai;

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
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatProviderException;
import com.tirthoguha.omnillm.provider.ChatResult;

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
}
