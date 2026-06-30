package com.tirthoguha.omnillm.provider.openai;

import java.util.List;
import java.util.function.Consumer;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputMessage;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderException;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;

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
 * flattened into the {@code input} text.
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
            return new ChatResult(name, prompt.model(), extractText(response));
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI Responses API request failed", e);
        }
    }

    @Override
    public List<String> availableModels() {
        return OpenAiModels.list(name, client);
    }

    @Override
    public void streamTokens(ChatPrompt prompt, Consumer<String> onToken) {
        ResponseCreateParams params = buildParams(prompt);

        try (var streaming = client.responses().createStreaming(params)) {
            streaming.stream().forEach(event ->
                    event.outputTextDelta().ifPresent(delta -> onToken.accept(delta.delta())));
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI Responses API streaming failed", e);
        }
    }

    /** Map provider-agnostic messages onto the Responses API: system → instructions, rest → input. */
    private ResponseCreateParams buildParams(ChatPrompt prompt) {
        StringBuilder instructions = new StringBuilder();
        StringBuilder input = new StringBuilder();
        for (ChatPrompt.Message message : prompt.messages()) {
            switch (message.role()) {
                case SYSTEM -> append(instructions, message.content());
                // Label assistant turns so multi-turn history stays legible in a single input string.
                case ASSISTANT -> append(input, "Assistant: " + message.content());
                case USER -> append(input, message.content());
            }
        }

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(prompt.model())
                .input(input.toString().strip());
        if (!instructions.isEmpty()) {
            builder.instructions(instructions.toString().strip());
        }
        return builder.build();
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
}
