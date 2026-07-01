package com.tirthoguha.seam.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolCallTextParser}: the app-side fallback that recovers tool calls a
 * backend runtime leaked into the assistant text instead of returning as structured
 * {@code tool_calls} — reproducing the empirically-confirmed {@code ai/gemma4:E2B} leak grammar.
 */
class ToolCallTextParserTest {

    @Test
    void extract_singleStringArg_returnsOneToolCall() {
        String content = "<|tool_call>call:get_weather{city:<|\"|>Paris<|\"|>}<tool_call|>";

        List<ToolCall> calls = ToolCallTextParser.extract(content);

        assertThat(calls).hasSize(1);
        ToolCall call = calls.get(0);
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.argumentsJson()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(call.id()).isNotBlank();
    }

    @Test
    void extract_multiArgMixedStringAndNumber_buildsOrderedJson() {
        String content = "<|tool_call>call:book_flight{destination:<|\"|>Denver<|\"|>,"
                + "origin:<|\"|>Boston<|\"|>,passengers:2}<tool_call|>";

        List<ToolCall> calls = ToolCallTextParser.extract(content);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).name()).isEqualTo("book_flight");
        assertThat(calls.get(0).argumentsJson())
                .isEqualTo("{\"destination\":\"Denver\",\"origin\":\"Boston\",\"passengers\":2}");
    }

    @Test
    void extract_plainText_returnsEmptyList() {
        assertThat(ToolCallTextParser.extract("just a normal reply, no leak here")).isEmpty();
    }

    @Test
    void extract_nullOrBlank_returnsEmptyList() {
        assertThat(ToolCallTextParser.extract(null)).isEmpty();
        assertThat(ToolCallTextParser.extract("")).isEmpty();
        assertThat(ToolCallTextParser.extract("   ")).isEmpty();
    }

    @Test
    void stripLeaks_removesWrapper_leavesSurroundingTextTrimmed() {
        String content = "  Sure, booking now. "
                + "<|tool_call>call:book_flight{passengers:2}<tool_call|>  ";

        String stripped = ToolCallTextParser.stripLeaks(content);

        assertThat(stripped).isEqualTo("Sure, booking now.");
    }

    @Test
    void applyFallback_recoversToolCall_whenToolsOfferedAndAutoOrNullChoice() {
        String leak = "<|tool_call>call:book_flight{destination:<|\"|>Denver<|\"|>,"
                + "origin:<|\"|>Boston<|\"|>,passengers:2}<tool_call|>";
        ChatResult result = new ChatResult("docker", "ai/gemma4:E2B", leak, List.of(), "stop", null);
        ChatPrompt prompt = promptWithTool(null);

        ChatResult fallback = ToolCallTextParser.applyFallback(result, prompt);

        assertThat(fallback.toolCalls()).hasSize(1);
        assertThat(fallback.toolCalls().get(0).name()).isEqualTo("book_flight");
        assertThat(fallback.finishReason()).isEqualTo("tool_calls");
        assertThat(fallback.reply()).isEmpty();
    }

    @Test
    void applyFallback_recoversToolCall_whenToolChoiceExplicitlyAuto() {
        String leak = "<|tool_call>call:get_weather{city:<|\"|>Paris<|\"|>}<tool_call|>";
        ChatResult result = new ChatResult("docker", "ai/gemma4:E2B", leak, List.of(), "stop", null);
        ChatPrompt prompt = promptWithTool(ToolChoice.auto());

        ChatResult fallback = ToolCallTextParser.applyFallback(result, prompt);

        assertThat(fallback.toolCalls()).hasSize(1);
        assertThat(fallback.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void applyFallback_stripsLeak_withoutSynthesizingCall_whenToolChoiceNone() {
        String leak = "<|tool_call>call:book_flight{passengers:2}<tool_call|>";
        ChatResult result = new ChatResult("docker", "ai/gemma4:E2B", leak, List.of(), "stop", null);
        ChatPrompt prompt = promptWithTool(ToolChoice.none());

        ChatResult fallback = ToolCallTextParser.applyFallback(result, prompt);

        assertThat(fallback.toolCalls()).isEmpty();
        assertThat(fallback.finishReason()).isEqualTo("stop");
        assertThat(fallback.reply()).doesNotContain("<|tool_call>");
    }

    @Test
    void applyFallback_noOp_whenNativeToolCallsAlreadyPresent() {
        ToolCall nativeCall = new ToolCall("call-1", "get_weather", "{\"city\":\"Paris\"}");
        ChatResult result = new ChatResult("docker", "ai/gemma4:E2B", "", List.of(nativeCall),
                "tool_calls", null);
        ChatPrompt prompt = promptWithTool(null);

        ChatResult fallback = ToolCallTextParser.applyFallback(result, prompt);

        assertThat(fallback).isSameAs(result);
    }

    @Test
    void applyFallback_noOp_whenPromptHasNoTools() {
        String leak = "<|tool_call>call:book_flight{passengers:2}<tool_call|>";
        ChatResult result = new ChatResult("docker", "ai/gemma4:E2B", leak, List.of(), "stop", null);
        ChatPrompt prompt = new ChatPrompt("book a flight", "ai/gemma4:E2B");

        ChatResult fallback = ToolCallTextParser.applyFallback(result, prompt);

        assertThat(fallback).isSameAs(result);
    }

    private static ChatPrompt promptWithTool(ToolChoice toolChoice) {
        ToolSpec tool = new ToolSpec("book_flight", "Book a flight",
                Map.of("type", "object", "properties", Map.of(
                        "destination", Map.of("type", "string"),
                        "origin", Map.of("type", "string"),
                        "passengers", Map.of("type", "integer"))));
        return new ChatPrompt(
                List.of(new ChatPrompt.Message(ChatPrompt.Role.USER, "book a flight")),
                "ai/gemma4:E2B", List.of(tool), toolChoice, SamplingParams.NONE);
    }
}
