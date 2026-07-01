package com.tirthoguha.seam.provider;

import java.util.Map;
import java.util.Set;

/**
 * Holds every configured {@link ChatProvider}, keyed by backend name, so a request can pick which
 * backend serves it. This is what makes "all backends available at once" real: one provider per
 * configured backend lives here for the app's lifetime, and {@link #get(String)} selects per call.
 */
public class ChatProviderRegistry {

    private final Map<String, ChatProvider> providers;
    private final String defaultBackend;

    public ChatProviderRegistry(Map<String, ChatProvider> providers, String defaultBackend) {
        this.providers = Map.copyOf(providers);
        this.defaultBackend = defaultBackend;
        if (!this.providers.containsKey(defaultBackend)) {
            throw new IllegalStateException("Default backend '" + defaultBackend
                    + "' is not configured. Available: " + this.providers.keySet());
        }
    }

    /** Name of the backend used when a request doesn't specify one. */
    public String defaultBackend() {
        return defaultBackend;
    }

    /** All configured backend names, e.g. {@code [openai, docker]}. */
    public Set<String> names() {
        return providers.keySet();
    }

    /**
     * Resolve the provider for a backend name.
     *
     * @throws IllegalArgumentException if no backend is registered under {@code backend}
     */
    public ChatProvider get(String backend) {
        ChatProvider provider = providers.get(backend);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown backend '" + backend + "'. Available: " + providers.keySet());
        }
        return provider;
    }
}
