package com.tirthoguha.omnillm.provider.openai;

import java.util.List;
import java.util.function.Consumer;

import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderException;
import com.tirthoguha.omnillm.provider.ChatPrompt;
import com.tirthoguha.omnillm.provider.ChatResult;

/**
 * {@link ChatProvider} backed by the official OpenAI Java SDK. Because every OpenAI-compatible
 * backend (OpenAI cloud, Docker Model Runner) speaks the same {@code /v1} API, this one
 * adapter covers all of them — one instance is created per configured backend (each with its own
 * base-url/key {@link OpenAIClient}) and registered by {@code name} in the
 * {@link com.tirthoguha.omnillm.provider.ChatProviderRegistry}.
 *
 * <p>This is the only class in the app that imports {@code com.openai.*}; that coupling stops at the
 * provider boundary. SDK failures are wrapped in {@link ChatProviderException} so callers never see
 * vendor exception types.
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

            String reply = completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElse("(no content returned)");

            return new ChatResult(name, prompt.model(), reply);
        } catch (OpenAIException e) {
            throw new ChatProviderException(name, "OpenAI chat completion failed", e);
        }
    }

    @Override
    public List<String> availableModels() {
        return OpenAiModels.list(name, client);
    }

    @Override
    public void streamTokens(ChatPrompt prompt, Consumer<String> onToken) {
        ChatCompletionCreateParams params = buildParams(prompt);

        try (var streaming = client.chat().completions().createStreaming(params)) {
            streaming.stream().forEach(chunk ->
                    chunk.choices().forEach(choice ->
                            choice.delta().content().ifPresent(onToken)));
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
                case ASSISTANT -> builder.addAssistantMessage(message.content());
                case USER -> builder.addUserMessage(message.content());
            }
        }
        return builder.build();
    }
}
