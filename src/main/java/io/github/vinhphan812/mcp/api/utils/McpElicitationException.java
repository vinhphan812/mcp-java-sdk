package io.github.vinhphan812.mcp.api.utils;

/**
 * Unchecked exception thrown when an elicitation or other server-initiated request
 * fails due to timeout, cancellation, client rejection, or transport error.
 *
 * <p>Extends {@link McpException} to carry a JSON-RPC-compatible error code
 * from {@link McpErrorCodes}. The code follows ADR-0022 error code assignments.
 *
 * @since 2026-07-28
 * @see McpErrorCodes#SERVER_REQUEST_TIMEOUT
 * @see McpErrorCodes#ELICITATION_REJECTED
 */
public final class McpElicitationException extends RuntimeException {

    private final int code;

    /**
     * Creates a new elicitation exception.
     *
     * @param code    a {@link McpErrorCodes} value
     * @param message human-readable description
     */
    public McpElicitationException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Returns the JSON-RPC-compatible error code.
     */
    public int getCode() {
        return code;
    }
}
