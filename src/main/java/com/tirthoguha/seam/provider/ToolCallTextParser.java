package com.tirthoguha.seam.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable fallback for backend runtimes that fail to parse a model-emitted tool call and instead
 * leak it into the assistant {@code content} as raw text. Modeled on LiteLLM's
 * {@code _parse_content_for_reasoning}-style regex recovery: rather than trusting the runtime's
 * structured {@code tool_calls} field, we additionally scan the text reply for the runtime's known
 * leak grammar and, if found, synthesize the {@link ToolCall}(s) the runtime should have returned.
 *
 * <p>The one grammar currently handled is empirically confirmed on Docker Model Runner's
 * {@code ai/gemma4:E2B}:
 * <pre>{@code
 * <|tool_call>call:book_flight{destination:<|"|>Denver<|"|>,origin:<|"|>Boston<|"|>,passengers:2}<tool_call|>
 * }</pre>
 * i.e. {@code <|tool_call>call:NAME{key:value,key:value,...}<tool_call|>}, where string values are
 * wrapped in {@code <|"|>} sentinels and numeric values are left bare. Multiple occurrences in one
 * reply are all recovered. This class is provider-agnostic (no {@code com.openai.*} imports) so it
 * can be applied uniformly from any {@link ChatProvider} implementation.
 */
public final class ToolCallTextParser {

    /** Matches one leaked tool-call wrapper: group(1) = function name, group(2) = body. */
    private static final Pattern LEAK_PATTERN =
            Pattern.compile("<\\|tool_call>call:(\\w+)\\{(.*?)\\}<tool_call\\|>", Pattern.DOTALL);

    /** Sentinel wrapping string values inside the leaked body, e.g. {@code <|"|>Denver<|"|>}. */
    private static final String STRING_SENTINEL = "<|\"|>";

    /** A bare (unquoted) numeric value, e.g. {@code 2} or {@code 3.5}. */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(\\.\\d+)?");

    private ToolCallTextParser() {
    }

    /**
     * Scan {@code content} for every occurrence of the leak grammar and return the recovered
     * {@link ToolCall}s in encounter order. Returns an empty list when {@code content} is
     * null/blank or carries no recognizable leak.
     */
    public static List<ToolCall> extract(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<ToolCall> calls = new ArrayList<>();
        Matcher matcher = LEAK_PATTERN.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1);
            String argumentsJson = parseBodyToJson(matcher.group(2));
            String id = "call_" + UUID.randomUUID().toString().replace("-", "");
            calls.add(new ToolCall(id, name, argumentsJson));
        }
        return calls;
    }

    /**
     * Remove every occurrence of the leak wrapper from {@code content} and return the trimmed
     * remainder (never null when {@code content} is non-null; may be empty).
     */
    public static String stripLeaks(String content) {
        if (content == null) {
            return null;
        }
        return LEAK_PATTERN.matcher(content).replaceAll("").trim();
    }

    /**
     * Apply the fallback to a provider's {@link ChatResult}: if the runtime already returned
     * structured {@code toolCalls}, or the prompt offered no tools, or no leak is present, the
     * result is returned unchanged. Otherwise the leak is recovered — honoring
     * {@link ChatPrompt#toolChoice()} ({@code NONE} strips the leak without synthesizing a call;
     * any other mode, including unspecified/{@code null}, recovers it as a real tool call).
     */
    public static ChatResult applyFallback(ChatResult result, ChatPrompt prompt) {
        if (result == null) {
            return result;
        }
        if (!result.toolCalls().isEmpty()) {
            return result;   // native parse already worked
        }
        if (prompt.tools().isEmpty()) {
            return result;   // no tools were offered; nothing to recover
        }

        List<ToolCall> extracted = extract(result.reply());
        if (extracted.isEmpty()) {
            return result;   // no leak present
        }

        ToolChoice toolChoice = prompt.toolChoice();
        ToolChoice.Mode mode = toolChoice == null ? ToolChoice.Mode.AUTO : toolChoice.mode();

        if (mode == ToolChoice.Mode.NONE) {
            // Tool use was forbidden: strip the garbage but do not synthesize a call.
            return new ChatResult(result.backend(), result.model(), stripLeaks(result.reply()),
                    List.of(), result.finishReason(), result.usage());
        }

        // Recover the call the runtime failed to parse.
        return new ChatResult(result.backend(), result.model(), "", extracted,
                "tool_calls", result.usage());
    }

    /**
     * Parse the leaked body (top-level {@code key:value} pairs, comma-separated) into a hand-built
     * JSON object string, preserving encounter order.
     */
    private static String parseBodyToJson(String body) {
        if (body == null || body.isBlank()) {
            return "{}";
        }

        StringBuilder json = new StringBuilder("{");
        String[] pairs = body.split(",");
        boolean first = true;
        for (String pair : pairs) {
            int colonIndex = pair.indexOf(':');
            if (colonIndex < 0) {
                continue;   // malformed pair; skip rather than fail the whole parse
            }
            String key = pair.substring(0, colonIndex).trim();
            String rawValue = pair.substring(colonIndex + 1).trim();

            if (!first) {
                json.append(',');
            }
            first = false;

            json.append('"').append(escapeJson(key)).append("\":");
            json.append(valueToJson(rawValue));
        }
        json.append('}');
        return json.toString();
    }

    /** Render one raw value as JSON: a quoted string (sentinel-wrapped or not) or a bare number. */
    private static String valueToJson(String rawValue) {
        if (rawValue.startsWith(STRING_SENTINEL) && rawValue.endsWith(STRING_SENTINEL)
                && rawValue.length() >= 2 * STRING_SENTINEL.length()) {
            String inner = rawValue.substring(STRING_SENTINEL.length(),
                    rawValue.length() - STRING_SENTINEL.length());
            return '"' + escapeJson(inner) + '"';
        }
        if (NUMBER_PATTERN.matcher(rawValue).matches()) {
            return rawValue;
        }
        return '"' + escapeJson(rawValue) + '"';
    }

    /** Escape a string for embedding in hand-built JSON (backslash and double-quote only). */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
