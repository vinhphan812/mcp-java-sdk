package io.github.vinhphan812.mcp.api.utils;

/**
 * Canonical JSON-RPC and MCP protocol version constants.
 *
 * <p>Protocol version strings come from the MCP specification.  Both
 * {@code 2025-03-26} and {@code 2025-06-18} are accepted by the server
 * (the handler validates the negotiated protocol version).
 */
public final class McpJsonRpc {

    private McpJsonRpc() {}

    // ── JSON-RPC 2.0 ──────────────────────────────────────────────────────────

    /**
     * JSON-RPC 2.0 version string, used as the {@code jsonrpc} field
     * in every request and response object.
     */
    public static final String VERSION = "2.0";

    // ── MCP protocol versions ─────────────────────────────────────────────────

    /** The MCP 2026 stateless protocol version. */
    public static final String PROTOCOL_VERSION_STATELESS = "2026-07-28";

    /** The MCP 2025 protocol version used by the default session-oriented mode. */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /** Legacy MCP protocol version accepted for backward compatibility. */
    public static final String PROTOCOL_VERSION_LEGACY = "2025-06-18";

    // ── Server info ──────────────────────────────────────────────────────────

    /**
     * Default server implementation version, used as the {@code info.version}
     * field in {@code initialize} responses.
     */
    public static final String SERVER_VERSION = "1.0.0";
}
