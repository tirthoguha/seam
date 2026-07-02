package com.tirthoguha.seam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tirthoguha.seam.provider.BackendNotConfiguredException;
import com.tirthoguha.seam.provider.ChatProviderRegistry;
import com.tirthoguha.seam.provider.EmbeddingProviderRegistry;
import com.tirthoguha.seam.provider.openai.OpenAiChatProvider;
import com.tirthoguha.seam.provider.openai.OpenAiResponsesProvider;
import com.tirthoguha.seam.service.BackendProvisioner;

/**
 * Verifies the composition root + provisioner wiring without any network I/O: building an SDK
 * client makes no calls, so we can assert which provider type each backend resolves to, which
 * backends are embedding-capable, and the full BYOK lifecycle (boot keyless → set key → provisioned
 * → clear key → unconfigured again).
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
        // "keyless": declared without a key — must boot unconfigured, provisionable at runtime.
        backends.put("keyless", new LlmProperties.Backend("https://api.example.com/v1", null, "some-model", "some-embed", "chat"));
        props = new LlmProperties("plain", backends);
    }

    private record Wired(ChatProviderRegistry chat, EmbeddingProviderRegistry embedding,
                         BackendProvisioner provisioner) {
    }

    private Wired wire() {
        ChatProviderRegistry chat = config.chatProviderRegistry(props);
        EmbeddingProviderRegistry embedding = config.embeddingProviderRegistry(props);
        BackendProvisioner provisioner = new BackendProvisioner(props, config.providerFactory(), chat, embedding);
        return new Wired(chat, embedding, provisioner);
    }

    @Test
    void chatRegistry_picksProviderByApiFlavour() {
        Wired w = wire();

        assertThat(w.chat().get("plain")).isInstanceOf(OpenAiChatProvider.class);
        assertThat(w.chat().get("nextgen")).isInstanceOf(OpenAiResponsesProvider.class);
    }

    @Test
    void embeddingRegistry_containsOnlyEmbeddingCapableBackends() {
        Wired w = wire();

        assertThat(w.embedding().names()).containsExactlyInAnyOrder("nextgen", "keyless");
        assertThat(w.embedding().get("nextgen")).isNotNull();
        assertThatThrownBy(() -> w.embedding().get("plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no embedding model");
    }

    @Test
    void keylessBackend_bootsDeclaredButUnconfigured() {
        Wired w = wire();

        assertThat(w.chat().names()).contains("keyless");           // declared → visible
        assertThat(w.chat().isProvisioned("keyless")).isFalse();    // but not serving
        assertThatThrownBy(() -> w.chat().get("keyless"))
                .isInstanceOf(BackendNotConfiguredException.class)
                .hasMessageContaining("keyless");
        assertThatThrownBy(() -> w.embedding().get("keyless"))
                .isInstanceOf(BackendNotConfiguredException.class);
    }

    @Test
    void setKey_provisionsAtRuntime_andClearKey_deprovisions() {
        Wired w = wire();

        // BYOK: supplying a key makes the backend serve chat + embeddings without a restart.
        BackendProvisioner.BackendStatus configured = w.provisioner().setKey("keyless", "sk-test");
        assertThat(configured.configured()).isTrue();
        assertThat(w.chat().get("keyless")).isInstanceOf(OpenAiChatProvider.class);
        assertThat(w.embedding().get("keyless")).isNotNull();

        // Removing the key reverts to declared-but-unconfigured, with the clear 503 error.
        BackendProvisioner.BackendStatus cleared = w.provisioner().clearKey("keyless");
        assertThat(cleared.configured()).isFalse();
        assertThatThrownBy(() -> w.chat().get("keyless"))
                .isInstanceOf(BackendNotConfiguredException.class);
    }

    @Test
    void setKey_rejectsUnknownBackendAndBlankKey() {
        Wired w = wire();

        assertThatThrownBy(() -> w.provisioner().setKey("nope", "sk-test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        assertThatThrownBy(() -> w.provisioner().setKey("keyless", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void statuses_reportEveryDeclaredBackend_withoutEchoingKeys() {
        Wired w = wire();

        assertThat(w.provisioner().statuses())
                .extracting(BackendProvisioner.BackendStatus::name,
                        BackendProvisioner.BackendStatus::configured)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("plain", true),
                        org.assertj.core.groups.Tuple.tuple("nextgen", true),
                        org.assertj.core.groups.Tuple.tuple("keyless", false));
        // The status record has no key field at all — nothing to leak.
        assertThat(BackendProvisioner.BackendStatus.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("apiKey", "key");
    }
}
