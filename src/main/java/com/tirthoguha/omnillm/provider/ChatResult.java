package com.tirthoguha.omnillm.provider;

/**
 * Provider-agnostic chat result. Carries the backend that served the request and the model it ran,
 * so the web layer can echo both back to the caller without re-deriving them.
 *
 * @param backend the backend that handled the request, e.g. {@code openai} / {@code docker}
 * @param model   the model that produced the reply
 * @param reply   the assistant's text response
 */
public record ChatResult(String backend, String model, String reply) {
}
