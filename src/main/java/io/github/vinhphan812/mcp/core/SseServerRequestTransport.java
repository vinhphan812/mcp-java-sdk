package io.github.vinhphan812.mcp.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * SSE-based implementation of {@link ServerRequestTransport} for server-initiated
 * JSON-RPC requests.
 *
 * <p>The request is written into the session's SSE event queue via
 * {@link McpProtocolHandler#enqueueServerEvent(String, String)}.
 * The response is delivered when
 * {@link McpProtocolHandler#handleServerInitiatedResponse(String, Map, boolean)} is called
 * by the HTTP handler on the incoming response POST.
 *
 * <p>Timeout enforcement and future completion are handled by
 * {@link McpProtocolHandler#sendServerRequest(String, String, Map, long)}.
 * This class is responsible only for wire delivery.
 *
 * @since 2026-07-28
 */
public final class SseServerRequestTransport implements ServerRequestTransport {

    private static final Logger LOGGER = Logger.getLogger(SseServerRequestTransport.class.getName());

    private final McpProtocolHandler handler;

    SseServerRequestTransport(McpProtocolHandler handler) {
        this.handler = handler;
    }

    @Override
    public CompletableFuture<Map<String, Object>> sendRequest(
            String sessionId, String request, long timeoutMs,
            CompletableFuture<Map<String, Object>> responseFuture) {
        // Enqueue the server-initiated request into the session's SSE event queue.
        // The client will receive it on its SSE stream.
        if (handler.hasSession(sessionId)) {
            handler.enqueueServerEvent(sessionId, request);
            LOGGER.fine("Enqueued server-initiated request for session: " + sessionId);
        } else {
            LOGGER.warning("No session found for server-initiated request: " + sessionId);
        }
        // Return the protocol handler's pre-created future unchanged.
        return responseFuture;
    }

    @Override
    public boolean supportsServerRequests() {
        return true;
    }
}
