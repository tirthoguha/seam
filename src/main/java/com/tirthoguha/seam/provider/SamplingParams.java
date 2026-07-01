package com.tirthoguha.seam.provider;

import java.util.List;

/**
 * Provider-agnostic generation parameters — the sampling knobs a caller may set on a {@link ChatPrompt}.
 * Every field is nullable and {@code null} means "not set: let the backend apply its own default".
 *
 * <p>Not every backend or wire protocol honours every field. The OpenAI <strong>Responses API</strong>
 * has no {@code stop}/{@code seed} and instead exposes {@code reasoning_effort} (reasoning models only);
 * Chat Completions honours {@code stop}/{@code seed} but not {@code reasoning_effort}. Each provider
 * applies the fields its protocol supports and silently ignores the rest — a dropped knob is never an
 * error.
 *
 * @param temperature     sampling temperature (higher = more random)
 * @param topP            nucleus-sampling probability mass
 * @param maxTokens       max tokens to generate (Chat Completions {@code max_completion_tokens};
 *                        Responses {@code max_output_tokens})
 * @param stop            stop sequences (Chat Completions only)
 * @param seed            deterministic-sampling seed (Chat Completions only)
 * @param reasoningEffort reasoning effort for reasoning models (Responses API only), e.g. {@code low}
 */
public record SamplingParams(
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        Long seed,
        String reasoningEffort) {

    /** The "nothing set" sentinel — providers apply no overrides. */
    public static final SamplingParams NONE = new SamplingParams(null, null, null, null, null, null);

    public SamplingParams {
        stop = stop == null ? null : List.copyOf(stop);
    }

    /** True when no field is set (nothing to forward). */
    public boolean isEmpty() {
        return temperature == null && topP == null && maxTokens == null
                && (stop == null || stop.isEmpty()) && seed == null && reasoningEffort == null;
    }
}
