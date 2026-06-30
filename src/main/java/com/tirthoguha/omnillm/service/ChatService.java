package com.tirthoguha.omnillm.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
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
 *
 * <p>It exposes both the app's native shapes ({@link #chat}, {@link #stream}) and lower-level
 * primitives ({@link #resolve}, {@link #complete}, {@link #runStream}) that the OpenAI-compatible
 * gateway uses to format responses its own way while reusing the same resolution, registry, and
 * shared stream executor.
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

    /** The backend and model a request actually runs against, after applying defaults. */
    public record Resolved(String backend, String model) {
    }

    private String resolveBackend(String requested) {
        return StringUtils.hasText(requested) ? requested : registry.defaultBackend();
    }

    private String resolveModel(String requested, String backend) {
        return StringUtils.hasText(requested) ? requested : props.backend(backend).model();
    }

    /**
     * Resolve backend + model from optional overrides, validating that the backend exists. Throws
     * {@link IllegalArgumentException} (→ 400) for an unknown backend, before any work is scheduled.
     */
    public Resolved resolve(String requestedBackend, String requestedModel) {
        String backend = resolveBackend(requestedBackend);
        String model = resolveModel(requestedModel, backend);   // throws for unknown backend
        return new Resolved(backend, model);
    }

    /** The model ids a backend offers (its own catalog), for the gateway's {@code /v1/models}. */
    public List<String> availableModels(String backend) {
        return registry.get(backend).availableModels();
    }

    /** Blocking, single-shot chat completion against the chosen (or default) backend. */
    public ChatResult chat(String userMessage, String requestedModel, String requestedBackend) {
        return complete(List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, userMessage)),
                requestedBackend, requestedModel);
    }

    /** Blocking completion for a full conversation; backend/model fall back to defaults when blank. */
    public ChatResult complete(List<ChatPrompt.Message> messages, String requestedBackend, String requestedModel) {
        Resolved r = resolve(requestedBackend, requestedModel);
        return registry.get(r.backend()).chat(new ChatPrompt(messages, r.model()));
    }

    /**
     * Run a streaming completion on the shared executor. The backend/model are assumed already
     * resolved (see {@link #resolve}); each token is pushed to {@code onToken}, then {@code onComplete}
     * runs on success or {@code onError} on failure. Callers format the transport (SSE, …) themselves.
     */
    public void runStream(String backend, String model, List<ChatPrompt.Message> messages,
                          Consumer<String> onToken, Runnable onComplete, Consumer<Throwable> onError) {
        ChatProvider provider = registry.get(backend);
        ChatPrompt prompt = new ChatPrompt(messages, model);
        streamExecutor.execute(() -> {
            try {
                provider.streamTokens(prompt, onToken);
                onComplete.run();
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    /**
     * Streaming chat completion in the app's native SSE shape: one JSON object per token
     * ({@code {"t":...}}), a named {@code done} event carrying the resolved backend/model, and a
     * named {@code error} event on failure. See the web client's {@code EventSource} handling.
     */
    public SseEmitter stream(String userMessage, String requestedModel, String requestedBackend) {
        Resolved r = resolve(requestedBackend, requestedModel);   // 400 synchronously if unknown
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        List<ChatPrompt.Message> messages = List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, userMessage));

        runStream(r.backend(), r.model(), messages,
                // One JSON object per token: the text lives inside a JSON string, so it survives the
                // SSE "strip one leading space" rule (raw tokens would lose whitespace).
                token -> send(emitter, null, Map.of("t", token)),
                () -> {
                    // Named terminal event so the client closes instead of EventSource auto-reconnecting.
                    send(emitter, "done", Map.of("backend", r.backend(), "model", r.model()));
                    emitter.complete();
                },
                e -> {
                    log.warn("Streaming chat failed for backend '{}' model '{}'", r.backend(), r.model(), e);
                    try {
                        send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                        emitter.complete();
                    } catch (RuntimeException sendFailure) {
                        emitter.completeWithError(e);
                    }
                });

        return emitter;
    }

    /** Send a JSON SSE event (optionally named), wrapping the checked IOException. */
    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().data(payload, MediaType.APPLICATION_JSON);
            if (eventName != null) {
                event = event.name(eventName);
            }
            emitter.send(event);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
