package com.tirthoguha.seam.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.tirthoguha.seam.config.LlmProperties;
import com.tirthoguha.seam.provider.EmbeddingPrompt;
import com.tirthoguha.seam.provider.EmbeddingProviderRegistry;
import com.tirthoguha.seam.provider.EmbeddingResult;

/**
 * Embedding-side counterpart of {@link ChatService}: resolves the effective backend (request
 * override, else the configured default) and the effective embedding model (request override, else
 * that backend's {@code embedding-model}), then delegates to the matching provider from the
 * {@link EmbeddingProviderRegistry}. Owns no provider-specific code — the SDK lives behind the seam.
 *
 * <p>Unlike chat, a backend may have <em>no</em> embedding model; resolving against such a backend
 * (or an unknown one) throws {@link IllegalArgumentException} (→ 400) with a clear message.
 */
@Service
public class EmbeddingService {

    private final EmbeddingProviderRegistry registry;
    private final LlmProperties props;

    public EmbeddingService(EmbeddingProviderRegistry registry, LlmProperties props) {
        this.registry = registry;
        this.props = props;
    }

    /** Embed the given texts against the chosen (or default) backend and model. */
    public EmbeddingResult embed(List<String> input, String requestedBackend, String requestedModel) {
        String backend = StringUtils.hasText(requestedBackend) ? requestedBackend : registry.defaultBackend();
        LlmProperties.Backend config = props.backend(backend);   // throws (→400) for unknown backend

        String model = StringUtils.hasText(requestedModel) ? requestedModel : config.embeddingModel();
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("Backend '" + backend
                    + "' has no embedding model configured; specify one as '<backend>:<model>'.");
        }

        return registry.get(backend).embed(new EmbeddingPrompt(input, model));
    }
}
