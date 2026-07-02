package com.tirthoguha.seam.provider;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding-side counterpart of {@link ChatProviderRegistry}. Unlike chat, <em>not</em> every
 * declared backend belongs here — only those with an embedding model configured
 * ({@code app.llm.backends.<name>.embedding-model}); of those, only the ones with an API key are
 * currently provisioned. {@link #get(String)} therefore distinguishes three failure modes:
 * embedding-incapable (or undeclared) → {@link IllegalArgumentException} (400); embedding-capable
 * but keyless → {@link BackendNotConfiguredException} (503). The shared {@code defaultBackend} name
 * is carried for resolution but is intentionally <em>not</em> required to be embedding-capable at
 * startup: a request falling back to such a default gets a clear error at call time rather than
 * blocking app boot.
 */
public class EmbeddingProviderRegistry {

    private final Map<String, EmbeddingProvider> providers = new ConcurrentHashMap<>();
    private final Set<String> embeddingCapable;
    private final String defaultBackend;

    public EmbeddingProviderRegistry(Set<String> embeddingCapableBackends, String defaultBackend) {
        this.embeddingCapable = Set.copyOf(embeddingCapableBackends);
        this.defaultBackend = defaultBackend;
    }

    /** Name of the backend used when a request doesn't specify one (may lack embedding support). */
    public String defaultBackend() {
        return defaultBackend;
    }

    /** Backend names declared embedding-capable, e.g. {@code [openai, docker]} — provisioned or not. */
    public Set<String> names() {
        return embeddingCapable;
    }

    /** Install (or replace, on key rotation) the embedding provider serving a backend. */
    public void register(String backend, EmbeddingProvider provider) {
        providers.put(backend, provider);
    }

    /** Remove a backend's embedding provider (key cleared). */
    public void deregister(String backend) {
        providers.remove(backend);
    }

    /**
     * Resolve the embedding provider for a backend name.
     *
     * @throws IllegalArgumentException      if the backend isn't declared or has no embedding model
     * @throws BackendNotConfiguredException if it's embedding-capable but has no key right now
     */
    public EmbeddingProvider get(String backend) {
        EmbeddingProvider provider = providers.get(backend);
        if (provider == null) {
            if (embeddingCapable.contains(backend)) {
                throw new BackendNotConfiguredException(backend);
            }
            throw new IllegalArgumentException("Backend '" + backend
                    + "' has no embedding model configured. Embedding-capable backends: " + embeddingCapable);
        }
        return provider;
    }
}
