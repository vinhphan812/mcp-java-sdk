package io.github.vinhphan812.mcp.api.utils;

/**
 * A unchecked exception carrying a JSON-RPC error code and message.
 * <p>
 * Used by {@link io.github.vinhphan812.mcp.core.McpProtocolHandler} to signal
 * protocol-level errors from within handler methods.  The caller converts it to
 * a JSON-RPC error response via {@code errorResponse(id, e.getCode(), e.getMessage())}.
 * <p>
 * This class replaces the private {@code McpErrorException} nested class that
 * previously lived inside {@code McpProtocolHandler}.
 */
public final class McpException extends RuntimeException {

    private final int code;

    /**
     * Constructs a new MCP exception.
     *
     * @param code    a {@link McpErrorCodes} value
     * @param message human-readable description
     */
    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** Returns the JSON-RPC error code. */
    public int getCode() {
        return code;
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#INVALID_REQUEST}.
     */
    public static McpException invalidRequest(String message) {
        throw new McpException(McpErrorCodes.INVALID_REQUEST, message);
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#INVALID_PARAMS}.
     */
    public static McpException invalidParams(String detail) {
        throw new McpException(McpErrorCodes.INVALID_PARAMS, "Invalid params: " + detail);
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#RATE_LIMIT_EXCEEDED}.
     */
    public static McpException rateLimitExceeded(String message) {
        throw new McpException(McpErrorCodes.RATE_LIMIT_EXCEEDED, message);
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#RESULT_NOT_COMPLETE}.
     */
    public static McpException taskNotComplete(String taskId) {
        throw new McpException(McpErrorCodes.RESULT_NOT_COMPLETE, "Task is not complete: " + taskId);
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#RESULT_ALREADY_TERMINAL}.
     */
    public static McpException taskAlreadyTerminal(String status) {
        throw new McpException(McpErrorCodes.RESULT_ALREADY_TERMINAL,
                "Task is already terminal: " + status.toLowerCase());
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#INTERNAL_ERROR}.
     */
    public static McpException internalError(String message) {
        throw new McpException(McpErrorCodes.INTERNAL_ERROR, message);
    }

    /**
     * Convenience factory: builds and throws a new {@code McpException} with
     * {@link McpErrorCodes#METHOD_NOT_FOUND}.
     */
    public static McpException methodNotFound(String message) {
        throw new McpException(McpErrorCodes.METHOD_NOT_FOUND, message);
    }
}
