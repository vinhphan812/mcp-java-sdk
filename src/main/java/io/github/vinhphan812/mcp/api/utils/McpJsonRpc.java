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

    /**
     * The MCP 2026 stateless protocol version.
     * In this mode: no Mcp-Session-Id, no initialize/initialized handshake,
     * per-request HTTP cancellation via stream close.
     */
    public static final String PROTOCOL_VERSION_STATELESS = "2026-07-28";

    /** The MCP 2025 protocol version used by the default session-oriented mode. */
    public static final String PROTOCOL_VERSION = "2025-11-25";

    /** Legacy MCP protocol version accepted for backward compatibility. */
    public static final String PROTOCOL_VERSION_LEGACY = "2025-06-18";

    // ── Version checks ───────────────────────────────────────────────────────

    /**
     * Returns true when the given version string represents MCP 2026-07-28
     * (the stateless mode).
     *
     * @param version protocol version string, possibly null
     * @return true when version is 2026-07-28
     */
    public static boolean isStatelessVersion(String version) {
        return PROTOCOL_VERSION_STATELESS.equals(version);
    }

    /**
     * Returns true when the given version string represents a 2025-era protocol
     * (2025-06-18 or 2025-11-25) that uses the session-based wire contract.
     *
     * @param version protocol version string, possibly null
     * @return true when version is a 2025-era version
     */
    public static boolean isSessionedVersion(String version) {
        return PROTOCOL_VERSION.equals(version) || PROTOCOL_VERSION_LEGACY.equals(version);
    }

    /**
     * Returns true when the given version string represents the default
     * session-oriented protocol version (2025-11-25).
     *
     * @param version protocol version string, possibly null
     * @return true when version is the current default
     */
    public static boolean isCurrentVersion(String version) {
        return PROTOCOL_VERSION.equals(version);
    }

    // ── Server info ──────────────────────────────────────────────────────────

    /**
     * Default server implementation version, used as the {@code info.version}
     * field in {@code initialize} responses.
     */
    public static final String SERVER_VERSION = "1.0.0";
}
