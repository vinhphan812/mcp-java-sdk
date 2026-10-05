package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.ElicitAction;
import io.github.vinhphan812.mcp.api.dto.ElicitationResult;
import io.github.vinhphan812.mcp.api.utils.McpElicitationException;
import io.github.vinhphan812.mcp.api.utils.McpErrorCodes;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MRTR/elicitation implementation (ADR-0022).
 * Covers T1–T10 from the ADR test plan.
 */
class McpElicitationTest {

    private static final long TEST_TIMEOUT_MS = 3_000L;

    /**
     * Initialises a session and returns the session ID.
     */
    private String initSession(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(body, null);
        JsonObject result = new Gson().fromJson(resp.getBody(), JsonObject.class).getAsJsonObject("result");
        if (result != null && result.has("serverInfo")) {
            handler.handleRequestResponse(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    resp.getSessionId());
            return resp.getSessionId();
        }
        return null;
    }

    // ==================== DTO Tests ====================

    @Test
    void elicitActionRejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class, () -> new ElicitAction("  ", null));
        assertThrows(IllegalArgumentException.class, () -> new ElicitAction(null, null));
    }

    @Test
    void elicitActionEqualityByLabel() {
        ElicitAction a = new ElicitAction("confirm", "Delete");
        ElicitAction b = new ElicitAction("confirm", "Different desc");
        ElicitAction c = new ElicitAction("cancel", "Keep");
        assertEquals(a, b, "actions with same label are equal");
        assertNotEquals(a, c, "actions with different labels differ");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void elicitationResultIsDeclined() {
        assertTrue(ElicitationResult.declined().isDeclined());
        assertFalse(ElicitationResult.ofAction("confirm").isDeclined());
        assertFalse(ElicitationResult.ofValue("foo").isDeclined());
    }

    @Test
    void elicitationResultFromResponseAction() {
        Map<String, Object> r = Map.of("action", "confirm");
        ElicitationResult er = ElicitationResult.fromResponse(r);
        assertEquals("confirm", er.getAction());
        assertFalse(er.isDeclined());
    }

    @Test
    void elicitationResultFromResponseDecline() {
        for (String cancel : Arrays.asList("cancel", "CANCEL", "decline", "dismiss", "reject")) {
            Map<String, Object> r = Map.of("action", cancel);
            ElicitationResult er = ElicitationResult.fromResponse(r);
            assertTrue(er.isDeclined(), "action '" + cancel + "' should be declined");
        }
    }

    @Test
    void elicitationResultFromResponseValue() {
        Map<String, Object> r = Map.of("value", "user-input");
        ElicitationResult er = ElicitationResult.fromResponse(r);
        assertEquals("user-input", er.getValue());
        assertFalse(er.isDeclined());
    }

    @Test
    void elicitationResultFromResponseNull() {
        assertTrue(ElicitationResult.fromResponse(null).isDeclined());
        // Empty object map is still "unrecognised" — only null is declined
        assertThrows(IllegalArgumentException.class,
                () -> ElicitationResult.fromResponse(Map.of()));
    }

    @Test
    void elicitationResultRejectsUnrecognisedShape() {
        Map<String, Object> bad = Map.of("unknown", "field");
        assertThrows(IllegalArgumentException.class, () -> ElicitationResult.fromResponse(bad));
    }

    // ==================== T1: elicitConfirmation resolves with client-selected action ====================

    @Test
    void elicitConfirmation_resolvesWithSelectedAction() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolMode(McpServerConfig.ProtocolMode.SESSIONED)
                        .elicitation(true)
                        .build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Submit the raw CompletableFuture (no inner get() call).
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<String> cf = handler.elicitConfirmation(sessionId,
                    "Delete record #42?",
                    List.of(new ElicitAction("confirm", "Delete"), new ElicitAction("cancel", "Keep")),
                    200);  // 200ms timeout for fast test

            // The future completes exceptionally with McpElicitationException on timeout.
            // JUnit wraps it as ExecutionException through cf.get().
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> cf.toCompletableFuture().get(2_000, TimeUnit.MILLISECONDS),
                    "Without client response, elicitation should time out");
            assertTrue(ex.getCause() instanceof McpElicitationException,
                    "Root cause should be McpElicitationException");
        } finally {
            executor.shutdownNow();
            handler.shutdown();
        }
    }

    // ==================== T2: elicitConfirmation timeout ====================

    @Test
    void elicitConfirmation_timesOut() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .elicitation(true)
                        .serverRequestTimeoutMs(100)
                        .build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        try {
            CompletableFuture<String> cf = handler.elicitConfirmation(sessionId,
                    "Are you sure?",
                    List.of(new ElicitAction("yes", "Yes")),
                    100);  // 100ms timeout

            // The future completes exceptionally with McpElicitationException on timeout.
            // get() wraps it as ExecutionException; unwrap to check the root cause.
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> cf.toCompletableFuture().get(2_000, TimeUnit.MILLISECONDS));
            Throwable cause = unwrapCause(ex);
            assertTrue(cause instanceof McpElicitationException,
                    "Root cause should be McpElicitationException, got: " + cause.getClass().getName());
            assertEquals(McpErrorCodes.SERVER_REQUEST_TIMEOUT, ((McpElicitationException) cause).getCode());
        } finally {
            handler.shutdown();
        }
    }

    // ==================== T3: elicitConfirmation cancelled by cancelServerRequest API ====================

    @Test
    void elicitConfirmation_cancelledByApi() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Drain init events
        handler.pollPendingNotification(sessionId);

        // Kick off elicitation
        CompletableFuture<String> cf = handler.elicitConfirmation(sessionId,
                "Confirm?",
                List.of(new ElicitAction("ok", "OK")),
                5_000);

        // Drain the enqueued elicitation request from the SSE queue
        String elicitationEvent = handler.pollPendingNotification(sessionId);
        assertNotNull(elicitationEvent, "Elicitation event should be enqueued");
        JsonObject elicitationJson = new Gson().fromJson(elicitationEvent, JsonObject.class);
        String requestId = elicitationJson.get("id").getAsString();
        assertTrue(requestId.startsWith("uuid:"), "requestId should be server-generated uuid");

        // Cancel the in-flight request via the API (ADR-0022 §3 Application-initiated)
        handler.cancelServerRequest(requestId);

        // The future should complete with CancellationException
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> cf.toCompletableFuture().get(2_000, TimeUnit.MILLISECONDS));
        Throwable cause = unwrapCause(ex);
        assertTrue(cause instanceof java.util.concurrent.CancellationException,
                "Root cause should be CancellationException, got: " + cause.getClass().getName());

        handler.shutdown();
    }

    // ==================== T5: Duplicate client response discarded ====================

    @Test
    void handleServerInitiatedResponse_discardsDuplicateResponse() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Enqueue a request
        handler.elicitConfirmation(sessionId, "Are you sure?",
                List.of(new ElicitAction("yes", "Yes")), 5_000);

        String event = handler.pollPendingNotification(sessionId);
        JsonObject json = new Gson().fromJson(event, JsonObject.class);
        String reqId = json.get("id").getAsString();

        // First response — should resolve
        Map<String, Object> response = Map.of("action", "yes");
        handler.handleServerInitiatedResponse(reqId, response, false);

        // Duplicate response — should be discarded without exception
        handler.handleServerInitiatedResponse(reqId, Map.of("action", "cancel"), false);

        assertEquals(0, handler.getPendingServerRequestCount(), "Request should be resolved after first response");
    }

    // ==================== T6: Malformed client response (missing id) discarded ====================

    @Test
    void handleServerInitiatedResponse_discardsMalformedMissingId() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Should not throw — just log warning
        assertDoesNotThrow(() ->
                handler.handleServerInitiatedResponse(null, Map.of("result", "ok"), false),
                "Response with null id should be discarded");
    }

    // ==================== T7: cancelServerRequest while elicitation in flight ====================

    @Test
    void cancelServerRequest_cancelsInFlightRequest() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Start elicitation
        CompletableFuture<String> cf = handler.elicitConfirmation(sessionId,
                "Confirm?",
                List.of(new ElicitAction("ok", "OK")),
                5_000);

        // Drain the request
        String event = handler.pollPendingNotification(sessionId);
        JsonObject json = new Gson().fromJson(event, JsonObject.class);
        String reqId = json.get("id").getAsString();

        assertEquals(1, handler.getPendingServerRequestCount());

        // Cancel via the API
        handler.cancelServerRequest(reqId);

        assertEquals(0, handler.getPendingServerRequestCount());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> cf.toCompletableFuture().get(1_000, TimeUnit.MILLISECONDS));
        Throwable cause = unwrapCause(ex);
        assertTrue(cause instanceof java.util.concurrent.CancellationException
                || cause instanceof McpElicitationException,
                "Root cause should be CancellationException or McpElicitationException, got: " + cause.getClass().getName());

        handler.shutdown();
    }

    // ==================== T8: shutdown() with pending elicitation ====================

    @Test
    void shutdown_completesPendingRequestsWithShutdownError() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Start a long-running elicitation (won't get a response)
        CompletableFuture<String> cf = handler.elicitConfirmation(sessionId,
                "Confirm?",
                List.of(new ElicitAction("ok", "OK")),
                10_000);

        assertEquals(1, handler.getPendingServerRequestCount());

        // Shutdown while request is in flight
        handler.shutdown();

        assertEquals(0, handler.getPendingServerRequestCount());
        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> cf.toCompletableFuture().get(1_000, TimeUnit.MILLISECONDS));
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof McpElicitationException,
                "Should be McpElicitationException, got: " + cause.getClass().getName());
        assertEquals(McpErrorCodes.INTERNAL, ((McpElicitationException) cause).getCode());
        assertTrue(ex.getCause().getMessage().contains("shutting down"));
    }

    // ==================== T9: Elicitation on transport that does not support server requests ====================

    @Test
    void sendServerRequest_rejectsUnsupportedTransport() {
        McpRegistry registry = new McpRegistry();
        // Build a transport that claims no support
        io.github.vinhphan812.mcp.core.ServerRequestTransport unsupported =
                new io.github.vinhphan812.mcp.core.ServerRequestTransport() {
                    @Override
                    public CompletableFuture<Map<String, Object>> sendRequest(
                            String sessionId, String request, long timeoutMs,
                            CompletableFuture<Map<String, Object>> responseFuture) {
                        return responseFuture;
                    }
                    @Override
                    public boolean supportsServerRequests() {
                        return false;
                    }
                };

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        handler.setServerRequestTransport(unsupported);

        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        CompletableFuture<Map<String, Object>> cf = handler.sendServerRequest(sessionId,
                "elicitation/create", Map.of("message", "test"), 1_000);

        CompletionException ex = assertThrows(CompletionException.class, () -> cf.join());
        Throwable root = unwrapCause(new ExecutionException(ex));
        assertTrue(root instanceof McpElicitationException,
                "Root cause should be McpElicitationException, got: " + root.getClass().getName());
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, ((McpElicitationException) root).getCode());

        handler.shutdown();
    }

    // ==================== T10: sampling/createMessage returns stub error ====================

    @Test
    void samplingCreateMessage_returnsMethodNotFound() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build()); // sampling not explicitly enabled
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"sampling/createMessage\","
                + "\"params\":{\"systemPrompt\":\"test\",\"maxTokens\":100}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = new Gson().fromJson(resp.getBody(), JsonObject.class);
        assertTrue(json.has("error"), "Should return error response");
        assertEquals(-32601, json.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(json.getAsJsonObject("error").get("message").getAsString()
                .contains("Sampling not implemented"));

        handler.shutdown();
    }

    // ==================== elicitInput test ====================

    @Test
    void elicitInput_resolvesWithUserValue() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Enqueue elicitation
        CompletableFuture<String> cf = handler.elicitInput(sessionId,
                "Enter your name:", "Anonymous", 5_000);

        String event = handler.pollPendingNotification(sessionId);
        JsonObject json = new Gson().fromJson(event, JsonObject.class);
        String reqId = json.get("id").getAsString();

        // Client responds with a value
        handler.handleServerInitiatedResponse(reqId, Map.of("value", "Alice"), false);

        String result = cf.toCompletableFuture().get(2_000, TimeUnit.MILLISECONDS);
        assertEquals("Alice", result);

        handler.shutdown();
    }

    @Test
    void elicitInput_returnsDefaultOnDecline() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        CompletableFuture<String> cf = handler.elicitInput(sessionId,
                "Enter your name:", "Anonymous", 5_000);

        String event = handler.pollPendingNotification(sessionId);
        JsonObject json = new Gson().fromJson(event, JsonObject.class);
        String reqId = json.get("id").getAsString();

        // Client declines
        handler.handleServerInitiatedResponse(reqId, Map.of("action", "cancel"), false);

        String result = cf.toCompletableFuture().get(2_000, TimeUnit.MILLISECONDS);
        assertEquals("Anonymous", result, "Should return default value on decline");

        handler.shutdown();
    }

    // ==================== Capability gate: elicitation disabled ====================

    @Test
    void elicitationCreate_rejectedWhenCapabilityDisabled() {
        McpRegistry registry = new McpRegistry();
        // elicitation = false (default)
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"elicitation/create\","
                + "\"params\":{\"message\":\"Are you sure?\"}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = new Gson().fromJson(resp.getBody(), JsonObject.class);
        assertTrue(json.has("error"));
        assertEquals(-32601, json.getAsJsonObject("error").get("code").getAsInt());

        handler.shutdown();
    }

    // ==================== elicit() generic method ====================

    @Test
    void elicit_genericReturnsResponseBody() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        io.github.vinhphan812.mcp.api.dto.ElicitRequest req =
                io.github.vinhphan812.mcp.api.dto.ElicitRequest.builder()
                        .message("Delete?")
                        .actions(List.of(new ElicitAction("yes", "Yes"), new ElicitAction("no", "No")))
                        .build();

        CompletableFuture<Map<String, Object>> cf = handler.elicit(sessionId, req, 5_000);

        String event = handler.pollPendingNotification(sessionId);
        JsonObject json = new Gson().fromJson(event, JsonObject.class);
        String reqId = json.get("id").getAsString();

        handler.handleServerInitiatedResponse(reqId, Map.of("action", "yes"), false);

        Map<String, Object> result = cf.toCompletableFuture().get(2_000, TimeUnit.MILLISECONDS);
        assertEquals("yes", result.get("action"));

        handler.shutdown();
    }

    // ==================== isServerRequestCancelled ====================

    @Test
    void isServerRequestCancelled_returnsTrueForCancelledRequest() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().elicitation(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        // Enqueue request
        handler.elicitConfirmation(sessionId, "Confirm?",
                List.of(new ElicitAction("ok", "OK")), 5_000);

        String event = handler.pollPendingNotification(sessionId);
        JsonObject json = new Gson().fromJson(event, JsonObject.class);
        String reqId = json.get("id").getAsString();

        assertFalse(handler.isServerRequestCancelled(reqId));

        handler.cancelServerRequest(reqId);

        assertTrue(handler.isServerRequestCancelled(reqId));

        handler.shutdown();
    }

    private static Throwable unwrapCause(ExecutionException ex) {
        Throwable cause = ex.getCause();
        while (cause instanceof CompletionException || cause instanceof ExecutionException) {
            cause = cause.getCause();
        }
        return cause;
    }
}
