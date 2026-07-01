package com.tirthoguha.seam.provider;

/**
 * Thrown when a {@link ChatProvider} fails to fulfil a request because of an upstream/backend
 * problem (network error, model backend returning an error, etc.). Providers wrap their
 * SDK-specific exceptions in this type so callers — and the global exception handler — can react
 * to a stable, provider-agnostic exception instead of leaking vendor classes across the seam.
 */
public class ChatProviderException extends RuntimeException {

    private final String provider;

    public ChatProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    /** Name of the provider that failed, e.g. {@code "openai"}. */
    public String provider() {
        return provider;
    }
}
