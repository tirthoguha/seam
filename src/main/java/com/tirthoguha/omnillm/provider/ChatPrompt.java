package com.tirthoguha.omnillm.provider;

/**
 * Provider-agnostic chat request. The {@code model} is already resolved (caller has applied
 * any default fallback), so providers can use it directly without knowing about app config.
 *
 * @param message the user prompt
 * @param model   the resolved model id to run against
 */
public record ChatPrompt(String message, String model) {
}
