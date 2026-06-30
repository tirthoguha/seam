package com.tirthoguha.omnillm.provider;

/**
 * The embedding-side counterpart of {@link ChatProvider}: the single seam between the app and any
 * embedding backend. Implementations translate between the provider-agnostic {@link EmbeddingPrompt}
 * / {@link EmbeddingResult} types and whatever the underlying SDK or HTTP API expects, and wrap
 * failures in {@link ChatProviderException} so callers never see vendor exception types.
 *
 * <p>Only embedding-capable backends have a provider; see {@link EmbeddingProviderRegistry}.
 */
public interface EmbeddingProvider {

    /** Stable identifier for this provider's backend, e.g. {@code "openai"}. */
    String name();

    /** Embed every text in the prompt, returning one vector per input in input order. */
    EmbeddingResult embed(EmbeddingPrompt prompt);
}
