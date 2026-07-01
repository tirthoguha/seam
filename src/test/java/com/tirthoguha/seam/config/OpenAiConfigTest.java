package com.tirthoguha.seam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openai.client.OpenAIClient;
import com.tirthoguha.seam.provider.ChatProviderRegistry;
import com.tirthoguha.seam.provider.EmbeddingProviderRegistry;
import com.tirthoguha.seam.provider.openai.OpenAiChatProvider;
import com.tirthoguha.seam.provider.openai.OpenAiResponsesProvider;

/**
 * Verifies the composition root's per-backend wiring without any network I/O: building an SDK client
 * makes no calls, so we can assert which provider type each backend resolves to and which backends
 * are embedding-capable.
 */
class OpenAiConfigTest {

    private final OpenAiConfig config = new OpenAiConfig();
    private LlmProperties props;

    @BeforeEach
    void setUp() {
        Map<String, LlmProperties.Backend> backends = new LinkedHashMap<>();
        // "plain": Chat Completions, no embedding model.
        backends.put("plain", new LlmProperties.Backend("https://api.openai.com/v1", "k", "gpt-4o-mini", null, "chat"));
        // "nextgen": Responses API (e.g. gpt-5.5) + an embedding model.
        backends.put("nextgen", new LlmProperties.Backend("https://api.openai.com/v1", "k", "gpt-5.5", "text-embedding-3-small", "responses"));
        props = new LlmProperties("plain", backends);
    }

    @Test
    void chatRegistry_picksProviderByApiFlavour() {
        Map<String, OpenAIClient> clients = config.openAiClients(props);
        ChatProviderRegistry registry = config.chatProviderRegistry(props, clients);

        assertThat(registry.get("plain")).isInstanceOf(OpenAiChatProvider.class);
        assertThat(registry.get("nextgen")).isInstanceOf(OpenAiResponsesProvider.class);
    }

    @Test
    void embeddingRegistry_containsOnlyEmbeddingCapableBackends() {
        Map<String, OpenAIClient> clients = config.openAiClients(props);
        EmbeddingProviderRegistry registry = config.embeddingProviderRegistry(props, clients);

        assertThat(registry.names()).containsExactly("nextgen");
        assertThatThrownBy(() -> registry.get("plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no embedding model");
    }
}
