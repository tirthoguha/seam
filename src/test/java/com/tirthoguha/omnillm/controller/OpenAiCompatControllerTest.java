package com.tirthoguha.omnillm.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tirthoguha.omnillm.config.LlmProperties;
import com.tirthoguha.omnillm.provider.ChatResult;
import com.tirthoguha.omnillm.provider.ChatStreamEvent;
import com.tirthoguha.omnillm.provider.EmbeddingResult;
import com.tirthoguha.omnillm.provider.SamplingParams;
import com.tirthoguha.omnillm.provider.ToolCall;
import com.tirthoguha.omnillm.provider.ToolChoice;
import com.tirthoguha.omnillm.provider.Usage;
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
        when(chatService.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatResult.text("docker", "ai/gemma3", "hello!"));

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
        when(chatService.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatResult.text("openai", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o","messages":[{"role":"user","content":"hi"}]}"""))
                .andExpect(status().isOk());

        verify(chatService).complete(any(), eq("openai"), eq("gpt-4o"), any(), any(), any());
    }

    @Test
    void completions_flattensSystemAndArrayContent() throws Exception {
        when(chatService.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatResult.text("docker", "ai/gemma3", "ok"));

        // system message + a user message whose content is an array of parts (multimodal-style text)
        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"docker","messages":[
                          {"role":"system","content":"be terse"},
                          {"role":"user","content":[{"type":"text","text":"hi there"}]}
                        ]}"""))
                .andExpect(status().isOk());
    }

    @Test
    void completions_carriesImageUrlPartsThroughToTheProvider() throws Exception {
        when(chatService.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatResult.text("openai", "gpt-4o-mini", "a cat"));

        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o-mini","messages":[
                          {"role":"user","content":[
                            {"type":"text","text":"what is this?"},
                            {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}
                          ]}
                        ]}"""))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.tirthoguha.omnillm.provider.ChatPrompt.Message>> msgs =
                ArgumentCaptor.forClass(List.class);
        verify(chatService).complete(msgs.capture(), any(), any(), any(), any(), any());

        var userMsg = msgs.getValue().get(0);
        assertThat(userMsg.hasImageParts()).isTrue();
        assertThat(userMsg.parts()).hasSize(2);
        assertThat(userMsg.parts().get(1).imageUrl()).isEqualTo("data:image/png;base64,AAAA");
        // flattened text is still populated as a fallback
        assertThat(userMsg.content()).isEqualTo("what is this?");
    }

    @Test
    void completions_rejectsEmptyMessagesWith400() throws Exception {
        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"docker","messages":[]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completions_toolCallResponse_serializesToolCallsFinishReasonAndUsage() throws Exception {
        // The provider returned a tool-call result: finish_reason="tool_calls", no text content,
        // one function call, and usage data. The gateway must wire all of this to the response.
        ChatResult toolCallResult = new ChatResult(
                "openai", "gpt-4o-mini", "",
                List.of(new ToolCall("call-abc123", "get_weather", "{\"city\":\"Sydney\"}")),
                "tool_calls",
                new Usage(10L, 5L, 15L));
        when(chatService.complete(any(), any(), any(), any(), any(), any())).thenReturn(toolCallResult);

        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o-mini",
                         "messages":[{"role":"user","content":"What is the weather?"}],
                         "tools":[{"type":"function","function":{
                           "name":"get_weather",
                           "description":"Get weather",
                           "parameters":{"type":"object","properties":{"city":{"type":"string"}}}
                         }}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].id").value("call-abc123"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].type").value("function"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("get_weather"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.arguments")
                        .value("{\"city\":\"Sydney\"}"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(10))
                .andExpect(jsonPath("$.usage.completion_tokens").value(5))
                .andExpect(jsonPath("$.usage.total_tokens").value(15));
    }

    @Test
    void completions_forwardsForcedToolChoiceAndSamplingParams() throws Exception {
        when(chatService.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatResult.text("openai", "gpt-4o-mini", "ok"));

        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o-mini",
                         "messages":[{"role":"user","content":"weather?"}],
                         "tools":[{"type":"function","function":{"name":"get_weather"}}],
                         "tool_choice":{"type":"function","function":{"name":"get_weather"}},
                         "temperature":0.3,"top_p":0.8,"max_tokens":256,"seed":7,"stop":["END"]}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<ToolChoice> tc = ArgumentCaptor.forClass(ToolChoice.class);
        ArgumentCaptor<SamplingParams> sp = ArgumentCaptor.forClass(SamplingParams.class);
        verify(chatService).complete(any(), any(), any(), any(), tc.capture(), sp.capture());

        assertThat(tc.getValue().mode()).isEqualTo(ToolChoice.Mode.FUNCTION);
        assertThat(tc.getValue().functionName()).isEqualTo("get_weather");
        assertThat(sp.getValue().temperature()).isEqualTo(0.3);
        assertThat(sp.getValue().topP()).isEqualTo(0.8);
        assertThat(sp.getValue().maxTokens()).isEqualTo(256);
        assertThat(sp.getValue().seed()).isEqualTo(7L);
        assertThat(sp.getValue().stop()).containsExactly("END");
    }

    @Test
    void completions_parsesStringToolChoiceAndPrefersMaxCompletionTokens() throws Exception {
        when(chatService.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(ChatResult.text("openai", "gpt-4o-mini", "ok"));

        // tool_choice as a bare string; both max_tokens and max_completion_tokens present.
        mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o-mini",
                         "messages":[{"role":"user","content":"hi"}],
                         "tool_choice":"none","max_tokens":100,"max_completion_tokens":50}"""))
                .andExpect(status().isOk());

        ArgumentCaptor<ToolChoice> tc = ArgumentCaptor.forClass(ToolChoice.class);
        ArgumentCaptor<SamplingParams> sp = ArgumentCaptor.forClass(SamplingParams.class);
        verify(chatService).complete(any(), any(), any(), any(), tc.capture(), sp.capture());

        assertThat(tc.getValue().mode()).isEqualTo(ToolChoice.Mode.NONE);
        assertThat(sp.getValue().maxTokens()).isEqualTo(50);   // max_completion_tokens wins
    }

    @Test
    void completions_streaming_emitsToolCallDeltaChunksAndToolCallsFinishReason() throws Exception {
        // resolve() is called synchronously before the stream starts.
        when(chatService.resolve(any(), any()))
                .thenReturn(new ChatService.Resolved("openai", "gpt-4o-mini"));

        // Drive the event consumer as a provider would for a streamed tool call: an opening
        // fragment (index+id+name), an argument continuation fragment, then Completed("tool_calls").
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatStreamEvent> onEvent = invocation.getArgument(6);
            Runnable onComplete = invocation.getArgument(7);
            onEvent.accept(new ChatStreamEvent.ToolCallDelta(0, "call-1", "get_weather", "{\"ci"));
            onEvent.accept(new ChatStreamEvent.ToolCallDelta(0, null, null, "ty\":\"Sydney\"}"));
            onEvent.accept(new ChatStreamEvent.Completed("tool_calls", new Usage(10L, 5L, 15L)));
            onComplete.run();
            return null;
        }).when(chatService).runStreamEvents(any(), any(), any(), any(), any(), any(), any(), any(), any());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions").contentType(APPLICATION_JSON).content("""
                        {"model":"openai:gpt-4o-mini","stream":true,
                         "messages":[{"role":"user","content":"What is the weather?"}],
                         "tools":[{"type":"function","function":{
                           "name":"get_weather",
                           "parameters":{"type":"object","properties":{"city":{"type":"string"}}}
                         }}]}"""))
                .andExpect(status().isOk())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Opening role frame, the tool-call delta chunks (id+name on the first, arguments on both),
        // a finish_reason:"tool_calls" final chunk, and the [DONE] sentinel.
        assertThat(body).contains("\"role\":\"assistant\"");
        assertThat(body).contains("\"tool_calls\"");
        assertThat(body).contains("\"id\":\"call-1\"");
        assertThat(body).contains("\"name\":\"get_weather\"");
        assertThat(body).contains("\"type\":\"function\"");
        assertThat(body).contains("Sydney");
        assertThat(body).contains("\"finish_reason\":\"tool_calls\"");
        assertThat(body).contains("[DONE]");
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
