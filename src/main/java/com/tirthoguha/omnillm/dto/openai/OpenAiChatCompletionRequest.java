package com.tirthoguha.omnillm.dto.openai;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of the OpenAI {@code POST /v1/chat/completions} request we honour. Unknown fields are
 * ignored so clients like Open WebUI can send their full payload without 400s. {@code content} is
 * typed as {@code Object} because OpenAI allows either a plain string or an array of content parts;
 * the gateway flattens it to text.
 *
 * @param model    target model id; the gateway reads it as {@code <backend>:<model>} (see controller)
 * @param messages the conversation (system / user / assistant / tool turns), in order
 * @param stream   whether to stream the response as SSE; defaults to false when absent
 * @param tools    optional list of function-tool declarations the model may call
 * @param toolChoice optional override for tool selection ({@code "auto"}/{@code "none"}/{@code "required"}
 *                   or a {@code {"type":"function","function":{"name":…}}} object); forwarded to the backend
 * @param temperature          optional sampling temperature
 * @param topP                 optional nucleus-sampling mass ({@code top_p})
 * @param maxTokens            optional generation cap ({@code max_tokens}, legacy)
 * @param maxCompletionTokens  optional generation cap ({@code max_completion_tokens}, preferred when present)
 * @param stop                 optional stop sequence(s): a string or array of strings
 * @param seed                 optional deterministic-sampling seed
 * @param reasoningEffort      optional reasoning effort ({@code reasoning_effort}); Responses API models only
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiChatCompletionRequest(
        String model,
        List<Message> messages,
        Boolean stream,
        List<Tool> tools,
        @JsonProperty("tool_choice") Object toolChoice,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        Object stop,
        Long seed,
        @JsonProperty("reasoning_effort") String reasoningEffort) {

    /** True only when the client explicitly asked to stream. */
    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }

    /**
     * One turn in the conversation. {@code tool_calls} is non-null on assistant turns that
     * requested function calls; {@code tool_call_id} is set on {@code role:"tool"} result turns.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            Object content,
            @JsonProperty("tool_calls") List<ToolCallParam> tool_calls,
            @JsonProperty("tool_call_id") String tool_call_id,
            String name) {
    }

    /** A tool call carried inside an assistant message ({@code tool_calls[]}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallParam(
            String id,
            String type,
            FunctionCallParam function) {
    }

    /** The function reference inside a {@link ToolCallParam}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCallParam(String name, String arguments) {
    }

    /**
     * A function-tool declaration in the top-level {@code tools[]} array. {@code parameters} is the
     * raw JSON Schema object; we leave it as {@code Map<String,Object>} to avoid a custom
     * deserializer while still giving the controller a typed handle.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tool(String type, Function function) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Function(
                String name,
                String description,
                Map<String, Object> parameters) {
        }
    }
}
