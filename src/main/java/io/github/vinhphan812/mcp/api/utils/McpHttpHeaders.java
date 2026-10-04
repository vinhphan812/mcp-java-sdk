package io.github.vinhphan812.mcp.api.utils;

/**
 * Canonical HTTP header name constants used by the MCP transport handler.
 * Having these in one place eliminates duplicated string literals.
 */
public final class McpHttpHeaders {

    /** {@code Mcp-Session-Id} — carries the MCP session identifier. */
    public static final String SESSION = "Mcp-Session-Id";

    /** {@code Authorization} — Bearer token for optional API-key authentication. */
    public static final String AUTH = "Authorization";

    /** {@code Mcp-Protocol-Version} — advertises the MCP protocol version. */
    public static final String PROTOCOL = "Mcp-Protocol-Version";

    /** {@code Last-Event-ID} — client-supplied SSE event cursor for replay. */
    public static final String LAST_EVENT_ID = "Last-Event-ID";

    /** {@code Mcp-Method} — method mirrored from the JSON-RPC request body. */
    public static final String METHOD = "Mcp-Method";

    /** {@code Mcp-Name} — tool, resource, or prompt name mirrored from params. */
    public static final String NAME = "Mcp-Name";

    /** {@code X-Forwarded-For} — original client IP when running behind a proxy. */
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    /** {@code X-RateLimit-Limit} — HTTP response header for rate-limit ceiling. */
    public static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";

    /** {@code X-RateLimit-Remaining} — HTTP response header for remaining requests. */
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";

    /** {@code X-RateLimit-Reset} — HTTP response header for rate-limit reset timestamp (Unix seconds). */
    public static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";

    private McpHttpHeaders() {
        // utility class — prevent instantiation
    }
}
