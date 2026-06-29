package com.tirthoguha.omnillm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tirthoguha.omnillm.config.LlmProperties;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderRegistry;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;

/**
 * Unit tests for {@link ChatService}. Providers are mocked behind a {@link ChatProviderRegistry},
 * so these tests cover the service's own job — resolving the effective backend and model, and
 * delegating — without any SDK or network.
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatProvider docker;
    @Mock
    private ChatProvider openai;

    private ChatService service;

    @BeforeEach
    void setUp() {
        // Two backends configured; "docker" is the default. Each carries its own default model.
        LlmProperties props = new LlmProperties("docker", Map.of(
                "docker", new LlmProperties.Backend("http://localhost:12434/engines/v1", "docker", "ai/gemma3"),
                "openai", new LlmProperties.Backend("https://api.openai.com/v1", "k", "gpt-4o-mini")));
        ChatProviderRegistry registry = new ChatProviderRegistry(
                Map.of("docker", docker, "openai", openai), "docker");
        // Run streaming inline so the test is deterministic (no real thread pool needed).
        Executor directExecutor = Runnable::run;
        service = new ChatService(registry, props, directExecutor);
    }

    @Test
    void chat_usesDefaultBackendAndItsModel_whenNeitherRequested() {
        when(docker.chat(any(ChatPrompt.class)))
                .thenReturn(new ChatResult("docker", "ai/gemma3", "hi"));

        service.chat("hello", null, null);

        ArgumentCaptor<ChatPrompt> captor = ArgumentCaptor.forClass(ChatPrompt.class);
        verify(docker).chat(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("ai/gemma3");   // docker's default model
        assertThat(captor.getValue().message()).isEqualTo("hello");
    }

    @Test
    void chat_routesToRequestedBackend_andUsesThatBackendsDefaultModel() {
        when(openai.chat(any(ChatPrompt.class)))
                .thenReturn(new ChatResult("openai", "gpt-4o-mini", "hi"));

        service.chat("hello", null, "openai");

        ArgumentCaptor<ChatPrompt> captor = ArgumentCaptor.forClass(ChatPrompt.class);
        verify(openai).chat(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-4o-mini");   // openai's default model
    }

    @Test
    void chat_usesRequestedModel_whenProvided() {
        when(openai.chat(any(ChatPrompt.class)))
                .thenReturn(new ChatResult("openai", "gpt-4o", "hi"));

        service.chat("hello", "gpt-4o", "openai");

        ArgumentCaptor<ChatPrompt> captor = ArgumentCaptor.forClass(ChatPrompt.class);
        verify(openai).chat(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-4o");   // explicit override wins
    }

    @Test
    void chat_unknownBackend_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.chat("hello", null, "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void stream_resolvesBackendAndModel_andForwardsProviderTokens() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(1);
            onToken.accept("Hel");
            onToken.accept("lo");
            done.countDown();
            return null;
        }).when(docker).streamTokens(any(ChatPrompt.class), any());

        SseEmitter emitter = service.stream("hi", null, null);   // default backend = docker

        assertThat(emitter).isNotNull();
        assertThat(done.await(2, TimeUnit.SECONDS))
                .as("provider.streamTokens should run on the service's executor")
                .isTrue();

        ArgumentCaptor<ChatPrompt> captor = ArgumentCaptor.forClass(ChatPrompt.class);
        verify(docker).streamTokens(captor.capture(), any());
        assertThat(captor.getValue().model()).isEqualTo("ai/gemma3");   // docker default applied
        assertThat(captor.getValue().message()).isEqualTo("hi");
    }
}
