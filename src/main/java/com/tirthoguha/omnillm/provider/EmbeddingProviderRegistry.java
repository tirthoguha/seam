package com.tirthoguha.omnillm.provider;

import java.util.Map;
import java.util.Set;

/**
 * Holds every embedding-capable {@link EmbeddingProvider}, keyed by backend name. Unlike
 * {@link ChatProviderRegistry}, <em>not</em> every configured backend appears here — only those with
 * an embedding model configured ({@code app.llm.backends.<name>.embedding-model}). The shared
 * {@code defaultBackend} name is carried for resolution, but is intentionally <em>not</em> required
 * to be embedding-capable at startup: a request that falls back to a default lacking embeddings gets
 * a clear {@code 400} at call time rather than blocking app boot.
 */
public class EmbeddingProviderRegistry {

    private final Map<String, EmbeddingProvider> providers;
    private final String defaultBackend;

    public EmbeddingProviderRegistry(Map<String, EmbeddingProvider> providers, String defaultBackend) {
        this.providers = Map.copyOf(providers);
        this.defaultBackend = defaultBackend;
    }

    /** Name of the backend used when a request doesn't specify one (may lack embedding support). */
    public String defaultBackend() {
        return defaultBackend;
    }

    /** Backend names that can serve embeddings, e.g. {@code [openai, docker]}. */
    public Set<String> names() {
        return providers.keySet();
    }

    /**
     * Resolve the embedding provider for a backend name.
     *
     * @throws IllegalArgumentException if the backend isn't configured or has no embedding model
     */
    public EmbeddingProvider get(String backend) {
        EmbeddingProvider provider = providers.get(backend);
        if (provider == null) {
            throw new IllegalArgumentException("Backend '" + backend
                    + "' has no embedding model configured. Embedding-capable backends: " + providers.keySet());
        }
        return provider;
    }
}
