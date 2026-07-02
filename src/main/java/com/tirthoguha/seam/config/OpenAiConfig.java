package com.tirthoguha.seam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.tirthoguha.seam.provider.ChatProvider;
import com.tirthoguha.seam.provider.ChatProviderRegistry;
import com.tirthoguha.seam.provider.EmbeddingProvider;
import com.tirthoguha.seam.provider.EmbeddingProviderRegistry;
import com.tirthoguha.seam.provider.ProviderFactory;
import com.tirthoguha.seam.provider.openai.OpenAiChatProvider;
import com.tirthoguha.seam.provider.openai.OpenAiEmbeddingProvider;
import com.tirthoguha.seam.provider.openai.OpenAiResponsesProvider;

/**
 * Composition root for the provider seam. Exposes the {@link ProviderFactory} that turns one
 * backend's coordinates into an {@link OpenAIClient}-backed provider (the SDK lets us override the
 * base URL per client — the whole trick behind "one adapter, many backends"), plus the two
 * registries the factory's output is registered into:
 *
 * <ul>
 *   <li>a {@link ChatProviderRegistry} declaring every backend — either an
 *       {@link OpenAiChatProvider} (Chat Completions) or an {@link OpenAiResponsesProvider}
 *       (Responses API), chosen by the backend's {@code api} flavour;</li>
 *   <li>an {@link EmbeddingProviderRegistry} declaring the <em>embedding-capable</em> backends
 *       (those with an {@code embedding-model} configured).</li>
 * </ul>
 *
 * <p>Registries start empty: {@code BackendProvisioner} populates them from each backend's current
 * API key — at startup from env/yml, and again whenever a key is set or cleared at runtime. That is
 * why construction lives behind {@link ProviderFactory} rather than in eager bean loops: a keyless
 * backend must be able to boot now and provision later without a restart.
 *
 * <p>Together with the classes in {@code provider/openai}, this is one of the only places allowed
 * to import {@code com.openai.*}.
 */
@Configuration
public class OpenAiConfig {

    /** Builds one SDK client per (backend, key) on demand; each provider wraps its own client. */
    @Bean
    public ProviderFactory providerFactory() {
        return new ProviderFactory() {
            @Override
            public ChatProvider chat(String backend, String baseUrl, String apiKey, boolean responsesApi) {
                OpenAIClient client = client(baseUrl, apiKey);
                return responsesApi
                        ? new OpenAiResponsesProvider(backend, client)
                        : new OpenAiChatProvider(backend, client);
            }

            @Override
            public EmbeddingProvider embedding(String backend, String baseUrl, String apiKey) {
                return new OpenAiEmbeddingProvider(backend, client(baseUrl, apiKey));
            }

            private OpenAIClient client(String baseUrl, String apiKey) {
                return OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .build();
            }
        };
    }

    @Bean
    public ChatProviderRegistry chatProviderRegistry(LlmProperties props) {
        return new ChatProviderRegistry(props.backends().keySet(), props.defaultBackend());
    }

    @Bean
    public EmbeddingProviderRegistry embeddingProviderRegistry(LlmProperties props) {
        java.util.Set<String> embeddingCapable = props.backends().entrySet().stream()
                .filter(e -> e.getValue().hasEmbedding())
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        return new EmbeddingProviderRegistry(embeddingCapable, props.defaultBackend());
    }
}
