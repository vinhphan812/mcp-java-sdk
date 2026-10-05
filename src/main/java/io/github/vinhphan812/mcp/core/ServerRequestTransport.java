package io.github.vinhphan812.mcp.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable transport adapter for server-initiated JSON-RPC requests
 * (MRTR — Method Request/Transaction Transport).
 *
 * <p>Used by {@link McpProtocolHandler} to send server-initiated requests (e.g.
 * {@code elicitation/create}) to the client and receive responses. The SSE transport
 * implementation is provided out-of-the-box; alternative transports (e.g. WebSocket)
 * can be plugged in via {@code McpServerConfig.Builder.serverRequestTransport(...)}.
 *
 * <p>The {@link CompletableFuture} is pre-created by the protocol handler and passed
 * to {@link #sendRequest(String, String, long, CompletableFuture)} so the transport
 * can enqueue the request and return the same future that will be completed when
 * {@link McpProtocolHandler#handleServerInitiatedResponse(String, Map, boolean)} is called.
 *
 * @since 2026-07-28
 */
public interface ServerRequestTransport {

    /**
     * Sends a server-initiated JSON-RPC request to the client over the given session.
     *
     * <p>The session ID is used to route the request to the correct client connection.
     * The timeout applies to the entire round-trip.
     *
     * <p>The {@code responseFuture} is pre-created by the protocol handler and is
     * completed asynchronously when
     * {@link McpProtocolHandler#handleServerInitiatedResponse(String, Map, boolean)} is
     * called. This method must return the same future unchanged — it exists so the
     * caller can return it directly from {@code sendServerRequest}.
     *
     * @param sessionId      target MCP session
     * @param request       JSON-RPC request body (already serialised as a string)
     * @param timeoutMs     per-request timeout in milliseconds
     * @param responseFuture the protocol handler's pre-created future; must be returned unchanged
     * @return the same {@code responseFuture} passed in
     */
    CompletableFuture<Map<String, Object>> sendRequest(
            String sessionId, String request, long timeoutMs,
            CompletableFuture<Map<String, Object>> responseFuture);

    /**
     * Returns {@code true} when this transport is capable of sending server-initiated requests.
     *
     * <p>When {@code false}, {@link McpProtocolHandler} will return error
     * {@code -32601} for elicitation calls and log a warning.
     */
    boolean supportsServerRequests();
}
