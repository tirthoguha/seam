package com.tirthoguha.omnillm.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tirthoguha.omnillm.config.LlmProperties;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.provider.EmbeddingResult;
import com.tirthoguha.omnillm.service.ChatService;
import com.tirthoguha.omnillm.service.EmbeddingService;

@WebMvcTest(OpenAiCompatController.class)
class OpenAiCompatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private LlmProperties props;

    @BeforeEach
    void setUp() {
        Map<String, LlmProperties.Backend> backends = new LinkedHashMap<>();
        // docker: chat + embedding model. openai: chat + embedding model.
        backends.put("docker", new LlmProperties.Backend(
                "http://localhost:12434/engines/v1", "docker", "ai/gemma3", "ai/mxbai-embed-large", null));
        backends.put("openai", new LlmProperties.Backend(
                "https://api.openai.com/v1", "k", "gpt-4o-mini", "text-embedding-3-small", "chat"));
        when(props.backends()).thenReturn(backends);
    }

    @Test
    void models_aggregatesEveryModelFromEachBackend_asBackendColonId() throws Exception {
        // Each backend reports its own full catalog; the gateway prefixes every id with <backend>:.
        when(chatService.availableModels("docker"))
                .thenReturn(List.of("ai/gemma3", "ai/smollm2", "ai/mxbai-embed-large"));
        when(chatService.availableModels("openai"))
                .thenReturn(List.of("gpt-4o-mini", "gpt-5.5", "text-embedding-3-small"));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].id").value("docker:ai/gemma3"))
                .andExpect(jsonPath("$.data[1].id").value("docker:ai/smollm2"))
                .andExpect(jsonPath("$.data[2].id").value("docker:ai/mxbai-embed-large"))
                .andExpect(jsonPath("$.data[3].id").value("openai:gpt-4o-mini"))
                .andExpect(jsonPath("$.data[4].id").value("openai:gpt-5.5"))
                .andExpect(jsonPath("$.data[5].id").value("openai:text-embedding-3-small"));
    }

    @Test
    void models_fallsBackToConfiguredDefaults_whenBackendListingFails() throws Exception {
        // docker listing errors; openai returns nothing — both fall back to configured defaults
        // (chat model + embedding model), so the endpoint still lists 4 selectable entries.
        when(chatService.availableModels("docker")).thenThrow(new RuntimeException("backend down"));
        when(chatService.availableModels("openai")).thenReturn(List.of());

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].id").value("docker:ai/gemma3"))
                .andExpect(jsonPath("$.data[1].id").value("docker:ai/mxbai-embed-large"))
                .andExpect(jsonPath("$.data[2].id").value("openai:gpt-4o-mini"))
                .andExpect(jsonPath("$.data[3].id").value("openai:text-embedding-3-small"));
    }

    @Test
    void completions_blocking_returnsOpenAiChatCompletionShape() throws Exception {
        when(chatService.complete(any(), any(), any()))
                .thenReturn(new ChatResult("docker", "ai/gemma3", "hello!"));

        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"docker:ai/gemma3","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value("ai/gemma3"))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("hello!"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"));
    }

    @Test
    void completions_parsesModelIntoBackendAndModel() throws Exception {
        when(chatService.complete(any(), any(), any()))
                .thenReturn(new ChatResult("openai", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isOk());

        verify(chatService).complete(any(), eq("openai"), eq("gpt-4o"));
    }

    @Test
    void completions_flattensSystemAndArrayContent() throws Exception {
        when(chatService.complete(any(), any(), any()))
                .thenReturn(new ChatResult("docker", "ai/gemma3", "ok"));

        // system message + a user message whose content is an array of parts (multimodal-style text)
        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"docker","messages":[
                          {"role":"system","content":"be terse"},
                          {"role":"user","content":[{"type":"text","text":"hi there"}]}
                        ]}"""))
                .andExpect(status().isOk());
    }

    @Test
    void completions_rejectsEmptyMessagesWith400() throws Exception {
        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"docker","messages":[]}"""))
                .andExpect(status().isBadRequest());
    }

    // --- embeddings ---------------------------------------------------------

    @Test
    void embeddings_returnsOpenAiEmbeddingsShape() throws Exception {
        when(embeddingService.embed(any(), any(), any())).thenReturn(new EmbeddingResult(
                "openai", "text-embedding-3-small", List.of(List.of(0.1f, 0.2f, 0.3f)), 4, 4));

        mockMvc.perform(post("/v1/embeddings").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:text-embedding-3-small","input":"hello world"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.data[0].object").value("embedding"))
                .andExpect(jsonPath("$.data[0].index").value(0))
                .andExpect(jsonPath("$.data[0].embedding.length()").value(3))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(4))
                .andExpect(jsonPath("$.usage.total_tokens").value(4));
    }

    @Test
    void embeddings_parsesModelAndRoutesToBackend_withArrayInput() throws Exception {
        when(embeddingService.embed(any(), any(), any())).thenReturn(new EmbeddingResult(
                "docker", "ai/mxbai-embed-large", List.of(List.of(0.5f), List.of(0.6f)), 6, 6));

        mockMvc.perform(post("/v1/embeddings").contentType(APPLICATION_JSON).content("""
                        {"model":"docker:ai/mxbai-embed-large","input":["a","b"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].index").value(1));

        verify(embeddingService).embed(eq(List.of("a", "b")), eq("docker"), eq("ai/mxbai-embed-large"));
    }

    @Test
    void embeddings_rejectsEmptyInputWith400() throws Exception {
        mockMvc.perform(post("/v1/embeddings").contentType(APPLICATION_JSON).content("""
                        {"model":"openai","input":""}"""))
                .andExpect(status().isBadRequest());
    }
}
