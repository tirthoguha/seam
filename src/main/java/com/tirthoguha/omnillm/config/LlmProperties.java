package com.tirthoguha.omnillm.config;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code app.llm.*} properties. Instead of a single backend chosen at boot, the app now
 * configures <em>all</em> OpenAI-compatible backends at once ({@code app.llm.backends.<name>}) and
 * picks one per request — falling back to {@code app.llm.default-backend} when the caller doesn't
 * name one. The Spring `docker` profile just flips the default; every backend stays callable.
 *
 * <p>Immutable record with constructor binding, {@code @Validated} for fail-fast startup: a missing
 * default, an empty backend map, or a blank field in any backend stops the app from starting rather
 * than erroring on the first request. Defaults come from {@code application.yml} via
 * {@code ${ENV:default}} placeholders.
 *
 * @param defaultBackend name of the backend used when a request doesn't specify one
 * @param backends       all configured backends, keyed by name (e.g. {@code openai}, {@code docker})
 */
@Validated
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        @NotBlank String defaultBackend,
        @NotEmpty Map<String, @Valid Backend> backends) {

    /**
     * Look up a backend by name, failing clearly if it isn't configured.
     *
     * @throws IllegalArgumentException if no backend is registered under {@code name}
     */
    public Backend backend(String name) {
        Backend backend = backends.get(name);
        if (backend == null) {
            throw new IllegalArgumentException(
                    "Unknown backend '" + name + "'. Configured backends: " + backends.keySet());
        }
        return backend;
    }

    /**
     * One OpenAI-compatible backend (OpenAI cloud, Docker Model Runner, …).
     *
     * @param baseUrl OpenAI-compatible base URL, e.g. https://api.openai.com/v1
     * @param apiKey  API key; real key for OpenAI, any non-blank placeholder for local runtimes
     * @param model   default model id for this backend, e.g. gpt-4o-mini, gemma3, ai/gemma3
     */
    public record Backend(
            @NotBlank String baseUrl,
            @NotBlank String apiKey,
            @NotBlank String model) {
    }
}
