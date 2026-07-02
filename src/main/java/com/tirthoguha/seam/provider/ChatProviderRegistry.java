package com.tirthoguha.seam.provider;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the {@link ChatProvider} for every <em>provisioned</em> backend, keyed by backend name, so a
 * request can pick which backend serves it. This is what makes "all backends available at once"
 * real: {@link #get(String)} selects per call.
 *
 * <p>The registry knows two sets of names: the <em>declared</em> backends (everything under
 * {@code app.llm.backends}, fixed at boot) and the <em>provisioned</em> ones (those currently
 * holding a provider). A backend without an API key is declared but not provisioned — the app boots
 * without it, and {@code BackendProvisioner} {@link #register registers}/{@link #deregister
 * deregisters} its provider when a key is supplied or removed at runtime. {@link #get(String)}
 * distinguishes the two failure modes: undeclared → {@link IllegalArgumentException} (caller error,
 * 400); declared but unprovisioned → {@link BackendNotConfiguredException} (server state, 503).
 */
public class ChatProviderRegistry {

    private final Map<String, ChatProvider> providers = new ConcurrentHashMap<>();
    private final Set<String> declared;
    private final String defaultBackend;

    public ChatProviderRegistry(Set<String> declaredBackends, String defaultBackend) {
        this.declared = Set.copyOf(declaredBackends);
        this.defaultBackend = defaultBackend;
        if (!this.declared.contains(defaultBackend)) {
            throw new IllegalStateException("Default backend '" + defaultBackend
                    + "' is not declared. Declared: " + this.declared);
        }
    }

    /** Name of the backend used when a request doesn't specify one (may be unprovisioned). */
    public String defaultBackend() {
        return defaultBackend;
    }

    /** All declared backend names, e.g. {@code [openai, docker]} — provisioned or not. */
    public Set<String> names() {
        return declared;
    }

    /** True when the backend currently holds a provider (has a key and can serve requests). */
    public boolean isProvisioned(String backend) {
        return providers.containsKey(backend);
    }

    /** Install (or replace, on key rotation) the provider serving a backend. */
    public void register(String backend, ChatProvider provider) {
        requireDeclared(backend);
        providers.put(backend, provider);
    }

    /** Remove a backend's provider (key cleared) — it stays declared, requests get a clear 503. */
    public void deregister(String backend) {
        providers.remove(backend);
    }

    /**
     * Resolve the provider for a backend name.
     *
     * @throws IllegalArgumentException      if {@code backend} isn't declared at all
     * @throws BackendNotConfiguredException if it's declared but has no key/provider right now
     */
    public ChatProvider get(String backend) {
        ChatProvider provider = providers.get(backend);
        if (provider == null) {
            requireDeclared(backend);
            throw new BackendNotConfiguredException(backend);
        }
        return provider;
    }

    private void requireDeclared(String backend) {
        if (!declared.contains(backend)) {
            throw new IllegalArgumentException(
                    "Unknown backend '" + backend + "'. Available: " + declared);
        }
    }
}
