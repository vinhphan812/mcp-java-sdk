package io.github.vinhphan812.mcp.api.dto;

import java.util.Map;

/**
 * Result returned when a tool, prompt, or resource-read operation requires client input.
 * <p>
 * This result is returned in-band within a {@code tools/call}, {@code prompts/get}, or
 * {@code resources/read} response when the operation cannot proceed without further
 * user elicitation.
 *
 * @see <a href="https://github.com/modelcontextprotocol/spec">MCP Specification (SEP-2322)</a>
 */
public final class InputRequiredResult {

    private final String requestState;
    private final String prompt;
    private final Map<String, Object> requestedSchema;
    private final Long expiresAt;
    private final Integer maxRounds;

    /**
     * @param requestState    opaque token for managing the elicitation round
     * @param prompt          human-readable instruction for the user
     * @param requestedSchema JSON Schema defining the structure of expected input
     * @param expiresAt       optional epoch timestamp (ms) when this request state expires
     * @param maxRounds       optional maximum number of elicitation rounds allowed
     */
    public InputRequiredResult(String requestState, String prompt,
                               Map<String, Object> requestedSchema,
                               Long expiresAt, Integer maxRounds) {
        if (requestState == null || requestState.trim().isEmpty())
            throw new IllegalArgumentException("requestState cannot be null or blank");
        this.requestState = requestState;
        this.prompt = prompt;
        this.requestedSchema = requestedSchema;
        this.expiresAt = expiresAt;
        this.maxRounds = maxRounds;
    }

    /** Returns the opaque token for this elicitation round. */
    public String getRequestState() {
        return requestState;
    }

    /** Returns the human-readable prompt. */
    public String getPrompt() {
        return prompt;
    }

    /** Returns the JSON Schema for the expected input. */
    public Map<String, Object> getRequestedSchema() {
        return requestedSchema;
    }

    /** Returns the expiry epoch timestamp (ms), or null if not set. */
    public Long getExpiresAt() {
        return expiresAt;
    }

    /** Returns the max rounds limit, or null if not set. */
    public Integer getMaxRounds() {
        return maxRounds;
    }
}
