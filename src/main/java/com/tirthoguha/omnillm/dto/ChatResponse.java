package com.tirthoguha.omnillm.dto;

/**
 * @param backend the backend that served the reply, e.g. {@code openai} / {@code docker}
 * @param model   the model that produced the reply
 * @param reply   the assistant's text response
 */
public record ChatResponse(String backend, String model, String reply) {
}
