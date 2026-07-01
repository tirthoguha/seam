package com.tirthoguha.omnillm.provider;

import java.util.Map;

/**
 * Provider-agnostic function-tool declaration. Mirrors the OpenAI JSON Schema function shape so it
 * can be forwarded to any backend without exposing SDK types past the provider boundary.
 *
 * @param name        the function name the model may call
 * @param description human-readable description shown to the model
 * @param parameters  the raw JSON Schema object that describes the function's arguments
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
