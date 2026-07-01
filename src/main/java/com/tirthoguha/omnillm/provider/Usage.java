package com.tirthoguha.omnillm.provider;

/**
 * Provider-agnostic token-usage summary. Carried by {@link ChatResult} when the backend reports it;
 * may be {@code null} when a backend does not return usage information.
 *
 * @param promptTokens     tokens consumed by the input (prompt + context)
 * @param completionTokens tokens produced by the model
 * @param totalTokens      sum of prompt and completion tokens
 */
public record Usage(long promptTokens, long completionTokens, long totalTokens) {
}
