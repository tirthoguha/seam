package com.tirthoguha.omnillm.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @param message the user prompt; must not be blank
 * @param model   optional model override; falls back to the chosen backend's default when null/blank
 * @param backend optional backend name (e.g. {@code openai}, {@code docker});
 *                falls back to the configured default backend when null/blank
 */
public record ChatRequest(
        @NotBlank(message = "message must not be blank") String message,
        String model,
        String backend) {
}
