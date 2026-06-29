package com.tirthoguha.omnillm.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tirthoguha.omnillm.provider.ChatProviderException;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.service.ChatService;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @Test
    void returnsReplyForValidRequest() throws Exception {
        when(chatService.chat(eq("hi"), any(), any()))
                .thenReturn(new ChatResult("openai", "gpt-4o-mini", "hello!"));

        mockMvc.perform(post("/chat").contentType(APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backend").value("openai"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.reply").value("hello!"));
    }

    @Test
    void rejectsBlankMessageWith400() throws Exception {
        mockMvc.perform(post("/chat").contentType(APPLICATION_JSON).content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void mapsProviderFailureTo502() throws Exception {
        when(chatService.chat(any(), any(), any()))
                .thenThrow(new ChatProviderException("openai", "boom", new RuntimeException()));

        mockMvc.perform(post("/chat").contentType(APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("LLM backend error"));
    }

    @Test
    void wrongHttpMethodReturns405NotServerError() throws Exception {
        // /chat is POST-only; a GET must surface as 405, not be swallowed into a 500
        // by the catch-all handler.
        mockMvc.perform(get("/chat"))
                .andExpect(status().isMethodNotAllowed());
    }
}
