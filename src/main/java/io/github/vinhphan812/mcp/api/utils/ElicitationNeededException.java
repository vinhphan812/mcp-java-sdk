package io.github.vinhphan812.mcp.api.utils;

import io.github.vinhphan812.mcp.api.dto.InputRequiredResult;

/**
 * Exception thrown by protocol handlers to signal that in-band user elicitation is required.
 * <p>
 * This exception is caught by {@code McpProtocolHandler} to construct the appropriate wire response
 * containing the {@link InputRequiredResult}.
 */
public final class ElicitationNeededException extends RuntimeException {

    private final InputRequiredResult result;

    /**
     * Constructs a new elicitation exception.
     *
     * @param result the details of the required in-band elicitation
     */
    public ElicitationNeededException(InputRequiredResult result) {
        super("In-band elicitation required: " + result.getPrompt());
        this.result = result;
    }

    /** Returns the elicitation result details. */
    public InputRequiredResult getResult() {
        return result;
    }
}
