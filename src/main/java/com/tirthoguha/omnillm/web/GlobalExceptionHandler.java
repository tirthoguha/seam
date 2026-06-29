package com.tirthoguha.omnillm.web;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.tirthoguha.omnillm.provider.ChatProviderException;

import jakarta.validation.ConstraintViolationException;

/**
 * Centralised error handling: turns exceptions into RFC 7807 {@link ProblemDetail} responses
 * (the Spring 6 / Boot 3 standard) instead of leaking stack traces or whitelabel pages.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions — wrong HTTP
 * method (405), unsupported media type (415), unreadable body (400), etc. — keep their correct
 * status codes instead of being swallowed by the catch-all below and reported as 500.
 *
 * <ul>
 *   <li>Bean Validation failures → {@code 400 Bad Request} with field details.</li>
 *   <li>Upstream/provider failures → {@code 502 Bad Gateway}; full cause is logged, not returned.</li>
 *   <li>Anything else → {@code 500 Internal Server Error} with a generic message.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Invalid request body (e.g. blank {@code message}). Overrides the framework handler to
     * produce a field-level detail message while keeping the standard 400 + ProblemDetail wiring.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Validation failed");
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Invalid request parameter (e.g. blank {@code message} query param), service-layer constraints. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleParamValidation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Validation failed");
        return pd;
    }

    /** Caller named an unknown backend (or otherwise passed an illegal argument). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad request");
        return pd;
    }

    /** The model backend could not fulfil the request. */
    @ExceptionHandler(ChatProviderException.class)
    public ProblemDetail handleProviderError(ChatProviderException ex) {
        log.error("Provider '{}' failed", ex.provider(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "The upstream model backend could not complete the request.");
        pd.setTitle("LLM backend error");
        return pd;
    }

    /** Last-resort handler so unexpected errors still return a clean response. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        pd.setTitle("Internal error");
        return pd;
    }
}
