package com.tirthoguha.seam.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.tirthoguha.seam.config.LlmProperties;
import com.tirthoguha.seam.provider.ChatProviderRegistry;
import com.tirthoguha.seam.provider.EmbeddingProviderRegistry;
import com.tirthoguha.seam.provider.ProviderFactory;

/**
 * Owns the <em>runtime</em> API keys and the single provisioning path from "backend + current key"
 * to registered providers. Keys are seeded from {@link LlmProperties} (env/yml) at startup and can
 * be supplied, rotated, or removed per backend afterwards via {@code /admin/backends} — so the app
 * boots with zero cloud keys and picks them up without a restart (BYOK). Keys live in memory only:
 * they are never persisted, returned, or logged; a restart falls back to the env/yml seed.
 *
 * <p>Provider construction is delegated to the {@link ProviderFactory} implemented inside
 * {@code config/OpenAiConfig}, keeping SDK imports confined to the composition root. Registries are
 * mutated through their register/deregister seam; everything downstream (services, controllers)
 * just sees providers appear and disappear.
 *
 * <p>Tenancy note (deliberate non-feature): keys are currently keyed by backend name only. When
 * orgs/teams arrive, this becomes a {@code (tenant, backend)} lookup and the registries become a
 * client cache — the seam stays the same.
 */
@Service
public class BackendProvisioner {

    private static final Logger log = LoggerFactory.getLogger(BackendProvisioner.class);

    private final LlmProperties props;
    private final ProviderFactory factory;
    private final ChatProviderRegistry chatRegistry;
    private final EmbeddingProviderRegistry embeddingRegistry;
    private final Map<String, String> keys = new ConcurrentHashMap<>();

    public BackendProvisioner(LlmProperties props, ProviderFactory factory,
                              ChatProviderRegistry chatRegistry,
                              EmbeddingProviderRegistry embeddingRegistry) {
        this.props = props;
        this.factory = factory;
        this.chatRegistry = chatRegistry;
        this.embeddingRegistry = embeddingRegistry;
        props.backends().forEach((name, backend) -> {
            if (backend.hasKey()) {
                keys.put(name, backend.apiKey());
            }
            provision(name);
        });
    }

    /** Set (or rotate) a backend's API key and provision its providers immediately. */
    public BackendStatus setKey(String backend, String apiKey) {
        props.backend(backend);   // throws (→400) for an undeclared backend
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("apiKey must not be blank; use DELETE to remove a key.");
        }
        keys.put(backend, apiKey);
        provision(backend);
        return status(backend);
    }

    /** Remove a backend's key: it stays declared but stops serving until a new key arrives. */
    public BackendStatus clearKey(String backend) {
        props.backend(backend);   // throws (→400) for an undeclared backend
        keys.remove(backend);
        provision(backend);
        return status(backend);
    }

    /** Whether a backend currently holds a key (and therefore live providers). */
    public boolean isConfigured(String backend) {
        return keys.containsKey(backend);
    }

    /** Per-backend status for the admin API — never includes the key itself. */
    public List<BackendStatus> statuses() {
        return props.backends().keySet().stream().map(this::status).toList();
    }

    private BackendStatus status(String name) {
        LlmProperties.Backend backend = props.backend(name);
        return new BackendStatus(name, isConfigured(name), backend.baseUrl(), backend.model(),
                backend.hasEmbedding() ? backend.embeddingModel() : null, backend.api());
    }

    /** (Re)build and register providers for a backend from its current key, or tear them down. */
    private void provision(String name) {
        LlmProperties.Backend backend = props.backend(name);
        String key = keys.get(name);
        if (key == null) {
            chatRegistry.deregister(name);
            embeddingRegistry.deregister(name);
            log.info("Backend '{}' declared without an API key — boots unconfigured; "
                    + "supply one via PUT /admin/backends/{}/key", name, name);
            return;
        }
        chatRegistry.register(name,
                factory.chat(name, backend.baseUrl(), key, backend.usesResponsesApi()));
        if (backend.hasEmbedding()) {
            embeddingRegistry.register(name, factory.embedding(name, backend.baseUrl(), key));
        }
        log.info("Backend '{}' provisioned ({} API{})", name, backend.api(),
                backend.hasEmbedding() ? ", embeddings" : "");
    }

    /** What the admin API reveals about a backend — everything except the key. */
    public record BackendStatus(String name, boolean configured, String baseUrl,
                                String model, String embeddingModel, String api) {
    }
}
