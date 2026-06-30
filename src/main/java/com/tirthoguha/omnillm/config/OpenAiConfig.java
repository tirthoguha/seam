package com.tirthoguha.omnillm.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderRegistry;
import com.tirthoguha.omnillm.provider.EmbeddingProvider;
import com.tirthoguha.omnillm.provider.EmbeddingProviderRegistry;
import com.tirthoguha.omnillm.provider.openai.OpenAiChatProvider;
import com.tirthoguha.omnillm.provider.openai.OpenAiEmbeddingProvider;
import com.tirthoguha.omnillm.provider.openai.OpenAiResponsesProvider;

/**
 * Composition root for the provider seam. Builds one {@link OpenAIClient} per configured backend
 * (the SDK lets us override the base URL per client — the whole trick behind "one adapter, many
 * backends") and wires up two registries from those shared clients:
 *
 * <ul>
 *   <li>a {@link ChatProviderRegistry} with one chat provider per backend — either an
 *       {@link OpenAiChatProvider} (Chat Completions) or an {@link OpenAiResponsesProvider}
 *       (Responses API), chosen by the backend's {@code api} flavour;</li>
 *   <li>an {@link EmbeddingProviderRegistry} with one {@link OpenAiEmbeddingProvider} per
 *       <em>embedding-capable</em> backend (those with an {@code embedding-model} configured).</li>
 * </ul>
 *
 * <p>Together with {@link OpenAiChatProvider} and the other classes in {@code provider/openai},
 * this is one of the only places allowed to import {@code com.openai.*}.
 */
@Configuration
public class OpenAiConfig {

    /** One SDK client per backend, shared by that backend's chat and embedding providers. */
    @Bean
    public Map<String, OpenAIClient> openAiClients(LlmProperties props) {
        Map<String, OpenAIClient> clients = new LinkedHashMap<>();
        props.backends().forEach((name, backend) -> clients.put(name,
                OpenAIOkHttpClient.builder()
                        .apiKey(backend.apiKey())
                        .baseUrl(backend.baseUrl())
                        .build()));
        return clients;
    }

    @Bean
    public ChatProviderRegistry chatProviderRegistry(LlmProperties props, Map<String, OpenAIClient> clients) {
        Map<String, ChatProvider> providers = new LinkedHashMap<>();
        props.backends().forEach((name, backend) -> {
            OpenAIClient client = clients.get(name);
            ChatProvider provider = backend.usesResponsesApi()
                    ? new OpenAiResponsesProvider(name, client)
                    : new OpenAiChatProvider(name, client);
            providers.put(name, provider);
        });
        return new ChatProviderRegistry(providers, props.defaultBackend());
    }

    @Bean
    public EmbeddingProviderRegistry embeddingProviderRegistry(LlmProperties props, Map<String, OpenAIClient> clients) {
        Map<String, EmbeddingProvider> providers = new LinkedHashMap<>();
        props.backends().forEach((name, backend) -> {
            if (backend.hasEmbedding()) {
                providers.put(name, new OpenAiEmbeddingProvider(name, clients.get(name)));
            }
        });
        return new EmbeddingProviderRegistry(providers, props.defaultBackend());
    }
}
