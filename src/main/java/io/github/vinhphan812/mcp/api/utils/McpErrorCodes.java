package io.github.vinhphan812.mcp.api.utils;

/**
 * Canonical JSON-RPC error code constants used by the MCP protocol layer.
 * Named constants replace magic numbers scattered through {@link io.github.vinhphan812.mcp.core.McpProtocolHandler}.
 *
 * <p>Error code ranges follow the JSON-RPC 2.0 specification and MCP extension conventions:
 * <ul>
 *   <li>{@code -32600 .. -32609} — JSON-RPC reserved codes (predefined by the spec)</li>
 *   <li>{@code -32000 .. -32099} — MCP server-defined errors</li>
 * </ul>
 */
public final class McpErrorCodes {

    // ── JSON-RPC reserved codes (JSON-RPC 2.0 spec) ─────────────────────────

    /** {@code -32600} — The JSON sent is not a valid Request object. */
    public static final int INVALID_REQUEST = -32600;

    /** {@code -32601} — The method does not exist or is not available. */
    public static final int METHOD_NOT_FOUND = -32601;

    /** {@code -32602} — The method exists but the params are invalid. */
    public static final int INVALID_PARAMS = -32602;

    /** {@code -32603} — Internal JSON-RPC error (catch-all for unhandled server errors). */
    public static final int INTERNAL_ERROR = -32603;

    // ── MCP server-defined codes (extension range -32000 .. -32099) ──────────

    /**
     * {@code -32000} — General internal server error.
     * Use when no more specific code applies.
     */
    public static final int INTERNAL = -32000;

    /**
     * {@code -32001} — The requested result is not yet available (task not complete).
     */
    public static final int RESULT_NOT_COMPLETE = -32001;

    /**
     * {@code -32002} — The requested result is already in a terminal state
     * (task already completed/cancelled/failed).
     */
    public static final int RESULT_ALREADY_TERMINAL = -32002;

    /**
     * {@code -32021} — Client capability required by a server-side task operation
     * was absent from the request. Used by the Tasks extension (io.modelcontextprotocol/tasks)
     * when the server requires the client to declare the tasks extension in its
     * per-request capabilities before the server will create a task for that request.
     */
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;

    /**
     * {@code -32029} — Request rejected due to rate limiting
     * (too many requests per IP, per session, per category, or per concurrent slot).
     */
    public static final int RATE_LIMIT_EXCEEDED = -32029;

    /**
     * {@code -32003} — Server-initiated request (e.g. elicitation) timed out.
     * @since 2026-07-28
     */
    public static final int SERVER_REQUEST_TIMEOUT = -32003;

    /**
     * {@code -32004} — Client rejected or declined an elicitation request.
     * @since 2026-07-28
     */
    public static final int ELICITATION_REJECTED = -32004;

    private McpErrorCodes() {
        // utility class — prevent instantiation
    }
}
