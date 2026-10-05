package io.github.vinhphan812.mcp.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Represents an in-flight server-initiated JSON-RPC request tracked by
 * {@link McpProtocolHandler}.
 *
 * <p>Each instance carries a server-generated UUID token ({@code uuid:<UUID>}) used as
 * both the JSON-RPC {@code id} and the {@code _meta.progressToken}. When a response
 * arrives, {@link #responseFuture} is completed with the response body.
 *
 * @see McpProtocolHandler#sendServerRequest(String, String, Map, long)
 * @since 2026-07-28
 */
public final class ServerInitiatedRequest {

    /** Server-generated correlation token. Always prefixed {@code uuid:}. */
    public final String requestId;

    /** JSON-RPC method name, e.g. {@code "elicitation/create"}. */
    public final String method;

    /** Request params map. Never null. */
    public final Map<String, Object> params;

    /** Target MCP session ID. */
    public final String sessionId;

    /** Epoch-ms when this request was enqueued. */
    public final long enqueuedAt;

    /** Epoch-ms when this request expires (enqueuedAt + timeoutMs). */
    public final long expiresAt;

    /**
     * CompletableFuture completed by {@link McpProtocolHandler#handleServerInitiatedResponse}
     * when a response arrives, or with an error on timeout/cancellation/shutdown.
     */
    public final CompletableFuture<Map<String, Object>> responseFuture;

    /**
     * Creates a new server-initiated request record.
     */
    public ServerInitiatedRequest(String requestId, String method, Map<String, Object> params,
                                  String sessionId, long enqueuedAt, long expiresAt,
                                  CompletableFuture<Map<String, Object>> responseFuture) {
        this.requestId = requestId;
        this.method = method;
        this.params = params;
        this.sessionId = sessionId;
        this.enqueuedAt = enqueuedAt;
        this.expiresAt = expiresAt;
        this.responseFuture = responseFuture;
    }

    /**
     * Returns {@code true} if this request has expired.
     *
     * @param now current epoch-ms
     */
    public boolean isExpired(long now) {
        return now > expiresAt;
    }
}
