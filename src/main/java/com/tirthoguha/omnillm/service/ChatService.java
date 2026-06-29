package com.tirthoguha.omnillm.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tirthoguha.omnillm.config.AsyncConfig;
import com.tirthoguha.omnillm.config.LlmProperties;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderRegistry;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;

/**
 * Orchestrates chat requests: resolves the effective backend (request override, else the configured
 * default) and the effective model (request override, else that backend's default), then delegates
 * to the matching {@link ChatProvider} from the {@link ChatProviderRegistry}. It owns no
 * provider-specific code — every backend lives behind the registry seam.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatProviderRegistry registry;
    private final LlmProperties props;
    private final Executor streamExecutor;

    public ChatService(ChatProviderRegistry registry,
                       LlmProperties props,
                       @Qualifier(AsyncConfig.STREAM_EXECUTOR) Executor streamExecutor) {
        this.registry = registry;
        this.props = props;
        this.streamExecutor = streamExecutor;
    }

    private String resolveBackend(String requested) {
        return StringUtils.hasText(requested) ? requested : registry.defaultBackend();
    }

    private String resolveModel(String requested, String backend) {
        return StringUtils.hasText(requested) ? requested : props.backend(backend).model();
    }

    /** Blocking, single-shot chat completion against the chosen (or default) backend. */
    public ChatResult chat(String userMessage, String requestedModel, String requestedBackend) {
        String backend = resolveBackend(requestedBackend);
        ChatProvider provider = registry.get(backend);
        return provider.chat(new ChatPrompt(userMessage, resolveModel(requestedModel, backend)));
    }

    /** Streaming chat completion, adapting the provider's token stream onto Server-Sent Events. */
    public SseEmitter stream(String userMessage, String requestedModel, String requestedBackend) {
        String backend = resolveBackend(requestedBackend);
        ChatProvider provider = registry.get(backend);
        ChatPrompt prompt = new ChatPrompt(userMessage, resolveModel(requestedModel, backend));
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        streamExecutor.execute(() -> {
            try {
                provider.streamTokens(prompt, token -> {
                    try {
                        emitter.send(token);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                log.warn("Streaming chat failed for backend '{}' model '{}'", backend, prompt.model(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
