package com.tirthoguha.seam.controller;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tirthoguha.seam.dto.ChatRequest;
import com.tirthoguha.seam.dto.ChatResponse;
import com.tirthoguha.seam.provider.ChatResult;
import com.tirthoguha.seam.service.ChatService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * REST endpoints for chat. {@code @Validated} on the class enables validation of method
 * parameters (e.g. the {@code message} query param); {@code @Valid} validates request bodies.
 * Validation failures and provider errors are translated to RFC 7807 responses by
 * {@link com.tirthoguha.seam.web.GlobalExceptionHandler}.
 */
@RestController
@Validated
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** POST /chat  {"message":"hi","model":"gemma3","backend":"docker"} */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        ChatResult result = chatService.chat(request.message(), request.model(), request.backend());
        return new ChatResponse(result.backend(), result.model(), result.reply());
    }

    /** GET /chat/stream?message=hi&model=gemma3&backend=docker  -> Server-Sent Events */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam @NotBlank String message,
                             @RequestParam(required = false) String model,
                             @RequestParam(required = false) String backend) {
        return chatService.stream(message, model, backend);
    }
}
