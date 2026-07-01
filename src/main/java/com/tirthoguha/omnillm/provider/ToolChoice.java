package com.tirthoguha.omnillm.provider;

/**
 * Provider-agnostic tool-selection directive — the seam analogue of OpenAI's {@code tool_choice}.
 * {@link Mode} covers the string forms ({@code auto}/{@code none}/{@code required}); {@code AUTO} lets
 * the model decide, {@code NONE} forbids tool use, {@code REQUIRED} forces some tool. {@code FUNCTION}
 * forces one specific function, named by {@link #functionName()}.
 *
 * <p>A {@code null} {@link ToolChoice} on a {@link ChatPrompt} means "not specified" — the provider
 * leaves selection entirely to the backend (the historical behaviour before tool_choice forwarding).
 *
 * @param mode         which selection behaviour to request
 * @param functionName the forced function name; set only when {@code mode == FUNCTION}
 */
public record ToolChoice(Mode mode, String functionName) {

    /** The tool-selection modes OpenAI's {@code tool_choice} supports. */
    public enum Mode { AUTO, NONE, REQUIRED, FUNCTION }

    public static ToolChoice auto()     { return new ToolChoice(Mode.AUTO, null); }
    public static ToolChoice none()     { return new ToolChoice(Mode.NONE, null); }
    public static ToolChoice required() { return new ToolChoice(Mode.REQUIRED, null); }

    /** Force the model to call one specific function by name. */
    public static ToolChoice function(String name) {
        return new ToolChoice(Mode.FUNCTION, name);
    }
}
