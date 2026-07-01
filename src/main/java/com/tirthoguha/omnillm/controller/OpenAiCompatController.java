package com.tirthoguha.omnillm.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tirthoguha.omnillm.config.LlmProperties;
import com.tirthoguha.omnillm.dto.openai.OpenAiChatCompletionChunk;
import com.tirthoguha.omnillm.dto.openai.OpenAiChatCompletionRequest;
import com.tirthoguha.omnillm.dto.openai.OpenAiChatCompletionResponse;
import com.tirthoguha.omnillm.dto.openai.OpenAiEmbeddingsRequest;
import com.tirthoguha.omnillm.dto.openai.OpenAiEmbeddingsResponse;
import com.tirthoguha.omnillm.dto.openai.OpenAiModelList;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.provider.ChatStreamEvent;
import com.tirthoguha.omnillm.provider.EmbeddingResult;
import com.tirthoguha.omnillm.provider.ToolCall;
import com.tirthoguha.omnillm.provider.ToolSpec;
import com.tirthoguha.omnillm.service.ChatService;
import com.tirthoguha.omnillm.service.EmbeddingService;

/**
 * OpenAI-compatible gateway. Exposes {@code GET /v1/models} and {@code POST /v1/chat/completions} so
 * any OpenAI-API client (e.g. Open WebUI) can drive OmniLLM as if it were OpenAI — while requests
 * still flow through the same {@link ChatService} → registry → provider seam, so per-backend routing
 * is preserved.
 *
 * <p>The OpenAI {@code model} field doubles as the backend selector: it's read as
 * {@code <backend>:<model>} (e.g. {@code docker:ai/gemma3}, {@code openai:gpt-4o-mini}). A bare
 * backend name uses that backend's default model; a bare model name uses the default backend; an
 * empty/unknown-shaped value falls back to the configured defaults.
 */
@RestController
public class OpenAiCompatController {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatController.class);

    private final ChatService chatService;
    private final EmbeddingService embeddingService;
    private final LlmProperties props;

    public OpenAiCompatController(ChatService chatService, EmbeddingService embeddingService, LlmProperties props) {
        this.chatService = chatService;
        this.embeddingService = embeddingService;
        this.props = props;
    }

    /**
     * Advertise <em>every</em> model each backend offers, as {@code <backend>:<id>}, by aggregating
     * each backend's own {@code /v1/models}. If a backend can't be listed (unreachable, bad key, …)
     * it falls back to that backend's configured chat + embedding defaults, so the endpoint never
     * fully fails and the defaults are always selectable. Backend-reported ids are passed through
     * verbatim (backends accept their own ids for routing).
     */
    @GetMapping("/v1/models")
    public OpenAiModelList models() {
        long created = Instant.now().getEpochSecond();
        List<OpenAiModelList.Model> data = new java.util.ArrayList<>();
        props.backends().forEach((name, backend) -> {
            List<String> ids;
            try {
                ids = chatService.availableModels(name);
            } catch (RuntimeException e) {
                log.warn("Listing models for backend '{}' failed; using configured defaults", name, e);
                ids = List.of();
            }
            if (ids.isEmpty()) {
                ids = new java.util.ArrayList<>();
                ids.add(backend.model());
                if (backend.hasEmbedding()) {
                    ids.add(backend.embeddingModel());
                }
            }
            ids.forEach(id -> data.add(OpenAiModelList.Model.of(name + ":" + id, created, name)));
        });
        return OpenAiModelList.of(data);
    }

    /** OpenAI-compatible embeddings — routes to the chosen backend via the same {@code <backend>:<model>} selector. */
    @PostMapping("/v1/embeddings")
    public OpenAiEmbeddingsResponse embeddings(@RequestBody OpenAiEmbeddingsRequest request) {
        List<String> input = mapInput(request.input());
        if (input.isEmpty()) {
            throw new IllegalArgumentException("input must not be empty");
        }
        ParsedModel parsed = parseModel(request.model());
        EmbeddingResult result = embeddingService.embed(input, parsed.backend(), parsed.model());
        return OpenAiEmbeddingsResponse.of(
                result.model(), result.vectors(), result.promptTokens(), result.totalTokens());
    }

    /** Blocking JSON ({@code chat.completion}) or streaming SSE ({@code chat.completion.chunk}). */
    @PostMapping("/v1/chat/completions")
    public Object completions(@RequestBody OpenAiChatCompletionRequest request) {
        List<ChatPrompt.Message> messages = mapMessages(request);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        ParsedModel parsed = parseModel(request.model());

        // Map request tools → provider-agnostic ToolSpec list (shared by both paths).
        List<ToolSpec> tools = mapTools(request.tools());

        if (request.streaming()) {
            return streamCompletion(messages, parsed, tools);
        }

        ChatResult result = chatService.complete(messages, parsed.backend(), parsed.model(), tools);
        return OpenAiChatCompletionResponse.of(
                newId(), Instant.now().getEpochSecond(), result.model(), result);
    }

    // --- streaming ----------------------------------------------------------

    private SseEmitter streamCompletion(List<ChatPrompt.Message> messages, ParsedModel parsed,
                                        List<ToolSpec> tools) {
        // Resolve + validate synchronously so an unknown backend is a 400 JSON error, not a broken
        // stream after headers are already committed.
        ChatService.Resolved r = chatService.resolve(parsed.backend(), parsed.model());
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        String id = newId();
        long created = Instant.now().getEpochSecond();
        String model = r.model();

        sendChunk(emitter, OpenAiChatCompletionChunk.role(id, created, model));   // opening role frame
        chatService.runStreamEvents(r.backend(), r.model(), messages, tools,
                event -> {
                    // Fan the provider-agnostic events out onto OpenAI chunk frames.
                    if (event instanceof ChatStreamEvent.TextDelta text) {
                        sendChunk(emitter, OpenAiChatCompletionChunk.token(id, created, model, text.text()));
                    } else if (event instanceof ChatStreamEvent.ToolCallDelta tc) {
                        sendChunk(emitter, OpenAiChatCompletionChunk.toolCallDelta(id, created, model,
                                tc.index(), tc.id(), tc.name(), tc.argumentsFragment()));
                    } else if (event instanceof ChatStreamEvent.Completed done) {
                        // Final frame carries the finish reason ("stop" or "tool_calls").
                        String finishReason = done.finishReason() != null ? done.finishReason() : "stop";
                        sendChunk(emitter, OpenAiChatCompletionChunk.finish(id, created, model, finishReason));
                        sendDone(emitter);
                        emitter.complete();
                    }
                },
                () -> { /* completion is emitted by the Completed event above */ },
                e -> {
                    log.warn("OpenAI-compatible stream failed for model '{}'", model, e);
                    try {
                        sendError(emitter, e);
                        sendDone(emitter);
                        emitter.complete();
                    } catch (RuntimeException sendFailure) {
                        emitter.completeWithError(e);
                    }
                });
        return emitter;
    }

    private void sendChunk(SseEmitter emitter, OpenAiChatCompletionChunk chunk) {
        try {
            emitter.send(SseEmitter.event().data(chunk, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void sendError(SseEmitter emitter, Throwable e) {
        try {
            emitter.send(SseEmitter.event().data(
                    Map.of("error", Map.of("message", "The upstream model backend could not complete the request.")),
                    MediaType.APPLICATION_JSON));
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }

    /** OpenAI's stream terminator: a literal {@code data: [DONE]} frame (not JSON). */
    private void sendDone(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- mapping helpers ----------------------------------------------------

    private List<ChatPrompt.Message> mapMessages(OpenAiChatCompletionRequest request) {
        if (request.messages() == null) {
            return List.of();
        }
        return request.messages().stream()
                .map(this::mapMessage)
                .toList();
    }

    private ChatPrompt.Message mapMessage(OpenAiChatCompletionRequest.Message m) {
        ChatPrompt.Role role = roleOf(m.role());

        // TOOL result turn: bind the tool-call-id so the provider can echo it back.
        if (role == ChatPrompt.Role.TOOL) {
            return new ChatPrompt.Message(role, textOf(m.content()), List.of(), m.tool_call_id());
        }

        // ASSISTANT turn that carried tool calls: bind them onto the message.
        if (role == ChatPrompt.Role.ASSISTANT
                && m.tool_calls() != null && !m.tool_calls().isEmpty()) {
            List<ToolCall> toolCalls = m.tool_calls().stream()
                    .map(tc -> new ToolCall(
                            tc.id(),
                            tc.function() != null ? tc.function().name() : "",
                            tc.function() != null ? tc.function().arguments() : "{}"))
                    .toList();
            return new ChatPrompt.Message(role, textOf(m.content()), toolCalls, null);
        }

        return new ChatPrompt.Message(role, textOf(m.content()));
    }

    private static ChatPrompt.Role roleOf(String role) {
        if (role == null) {
            return ChatPrompt.Role.USER;
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "system", "developer" -> ChatPrompt.Role.SYSTEM;
            case "assistant" -> ChatPrompt.Role.ASSISTANT;
            case "tool" -> ChatPrompt.Role.TOOL;
            default -> ChatPrompt.Role.USER;
        };
    }

    /** Flatten OpenAI content, which is either a plain string or an array of {type,text} parts. */
    private static String textOf(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && map.get("text") instanceof String text) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /** Map the request's {@code tools} list onto provider-agnostic {@link ToolSpec}s. */
    private static List<ToolSpec> mapTools(List<OpenAiChatCompletionRequest.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .filter(t -> t.function() != null)
                .map(t -> new ToolSpec(
                        t.function().name(),
                        t.function().description(),
                        t.function().parameters()))
                .toList();
    }

    /** Normalise the embeddings {@code input} (a string or array of strings) to a list of texts. */
    private static List<String> mapInput(Object input) {
        if (input == null) {
            return List.of();
        }
        if (input instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s);
        }
        if (input instanceof List<?> items) {
            return items.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .toList();
        }
        return List.of(input.toString());
    }

    /** Reads the OpenAI {@code model} field as {@code <backend>:<model>} (either side optional). */
    private ParsedModel parseModel(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return new ParsedModel(null, null);
        }
        int colon = modelId.indexOf(':');
        if (colon >= 0) {
            return new ParsedModel(blankToNull(modelId.substring(0, colon)),
                    blankToNull(modelId.substring(colon + 1)));
        }
        // No colon: a known backend name routes to its default model; otherwise treat it as a model id.
        return props.backends().containsKey(modelId)
                ? new ParsedModel(modelId, null)
                : new ParsedModel(null, modelId);
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private static String newId() {
        return "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record ParsedModel(String backend, String model) {
    }
}
