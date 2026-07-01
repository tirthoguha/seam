package com.tirthoguha.seam.provider;

/**
 * Provider-agnostic representation of a function call the model wants to make. Returned inside
 * {@link ChatResult} when the model's finish reason is {@code tool_calls}.
 *
 * @param id            the opaque tool-call id that must be echoed back in the next
 *                      {@link ChatPrompt.Role#TOOL} message
 * @param name          the function name the model is calling
 * @param argumentsJson the raw JSON string of arguments, exactly as the model produced it
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
