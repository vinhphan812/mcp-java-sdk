package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.ElicitRequest;
import io.github.vinhphan812.mcp.api.dto.ElicitationMessage;
import io.github.vinhphan812.mcp.api.handler.ElicitationCallback;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MRTR/elicitation feature (ADR-0022).
 * Covers:
 * - Capability advertisement in initialize response
 * - Server-initiated elicitation request via sendElicitRequest()
 * - Client response handling via elicitation/response
 * - Timeout callback invocation
 * - Cancellation
 */
class McpElicitationTest {

    private static final Gson GSON = new Gson();

    // ── Test 1: initialize advertises elicitation capability ─────────────────────

    @Test
    void initializeAdvertisesElicitationCapability() {
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .elicitationTimeoutMs(30_000L)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, null);

        String responseBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        JsonObject result = GSON.fromJson(responseBody, JsonObject.class)
                .getAsJsonObject("result");
        JsonObject caps = result.getAsJsonObject("capabilities");

        assertTrue(caps.has("elicitation"), "elicitation capability should be advertised");
        JsonObject elicitationCap = caps.getAsJsonObject("elicitation");
        assertTrue(elicitationCap.has("requestTimeoutMs"));
        assertEquals(30_000L, elicitationCap.get("requestTimeoutMs").getAsLong());
    }

    @Test
    void initializeDoesNotAdvertiseElicitationWhenDisabled() {
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(false)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, null);

        String responseBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        JsonObject result = GSON.fromJson(responseBody, JsonObject.class)
                .getAsJsonObject("result");
        JsonObject caps = result.getAsJsonObject("capabilities");

        assertFalse(caps.has("elicitation"),
                "elicitation should NOT be advertised when disabled");
    }

    // ── Test 2: sendElicitRequest enqueues SSE event ─────────────────────────

    @Test
    void sendElicitRequestEnqueuesSseEvent() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .elicitationTimeoutMs(60_000L)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        // Initialize session
        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        // Send elicitation request
        ElicitRequest request = ElicitRequest.builder()
                .message("Please confirm: continue operation?")
                .timeoutMs(60_000L)
                .build();
        handler.sendElicitRequest(sessionId, request);

        // Verify elicitation was registered
        assertEquals(1, handler.getPendingElicitationCount(sessionId),
                "Session should have 1 pending elicitation after sendElicitRequest");

        // Verify request ID was generated
        assertNotNull(request.getRequestId());
    }

    // ── Test 3: elicitation/response dispatches to callback ────────────────────

    @Test
    void elicitationResponseDispatchesToCallback() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .elicitationTimeoutMs(60_000L)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        // Initialize session
        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        // Send elicitation request
        ElicitRequest request = ElicitRequest.builder()
                .message("Please confirm")
                .timeoutMs(60_000L)
                .build();
        handler.sendElicitRequest(sessionId, request);
        String requestId = request.getRequestId();

        // Client sends response
        String responseBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"elicitation/response\","
                        + "\"params\":{\"requestId\":\"" + requestId + "\","
                        + "\"content\":\"yes, proceed\"}}",
                sessionId);

        // Verify callback was invoked with correct values
        assertEquals(1, callback.responseCount);
        assertNull(callback.lastError);
        assertEquals(requestId, callback.lastResponse.getRequestId());
        assertEquals("yes, proceed", callback.lastResponse.getContent());
        assertFalse(callback.lastResponse.isCancelled());

        // Verify response is valid JSON-RPC
        JsonObject rpcResponse = GSON.fromJson(responseBody, JsonObject.class);
        assertEquals("2.0", rpcResponse.get("jsonrpc").getAsString());
        assertEquals(2, rpcResponse.get("id").getAsInt());
        assertTrue(rpcResponse.has("result"));
    }

    @Test
    void elicitationResponseWithCancellationDispatchesToCallback() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        ElicitRequest request = ElicitRequest.builder()
                .message("Confirm?")
                .timeoutMs(60_000L)
                .build();
        handler.sendElicitRequest(sessionId, request);

        // Client cancels
        String responseBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"elicitation/response\","
                        + "\"params\":{\"requestId\":\"" + request.getRequestId() + "\","
                        + "\"cancelled\":true,\"reason\":\"User declined\"}}",
                sessionId);

        assertEquals(1, callback.responseCount);
        assertTrue(callback.lastResponse.isCancelled());
        assertEquals("User declined", callback.lastResponse.getReason());
        assertNull(callback.lastError);
    }

    // ── Test 4: unknown requestId triggers onUnknownRequestId ───────────────────

    @Test
    void elicitationResponseUnknownRequestIdCallsOnUnknownRequestId() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        // No elicitation sent, but client responds
        handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"elicitation/response\","
                        + "\"params\":{\"requestId\":\"unknown-id-123\",\"content\":\"oops\"}}",
                sessionId);

        assertEquals(0, callback.responseCount);
        assertEquals("unknown-id-123", callback.unknownRequestId);
    }

    // ── Test 5: elicitation/response returns error when disabled ───────────────

    @Test
    void elicitationResponseReturnsMethodNotFoundWhenDisabled() {
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(false)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, null);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        String responseBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"elicitation/response\","
                        + "\"params\":{\"requestId\":\"abc\",\"content\":\"test\"}}",
                sessionId);

        JsonObject rpc = GSON.fromJson(responseBody, JsonObject.class);
        assertTrue(rpc.has("error"), "Response should be an error");
        assertEquals(-32601, rpc.getAsJsonObject("error").get("code").getAsInt());
    }

    // ── Test 6: cancelElicitRequest invokes callback ───────────────────────────

    @Test
    void cancelElicitRequestInvokesCallback() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        ElicitRequest request = ElicitRequest.builder()
                .message("Please wait")
                .timeoutMs(60_000L)
                .build();
        handler.sendElicitRequest(sessionId, request);

        // Cancel
        handler.cancelElicitRequest(request.getRequestId(), "Server logic changed");

        assertEquals(0, callback.responseCount);
        assertEquals(request.getRequestId(), callback.lastCancelledId);
        assertEquals("Server logic changed", callback.lastCancelledReason);
    }

    // ── Test 7: sendElicitRequest rejects null sessionId ───────────────────────

    @Test
    void sendElicitRequestRejectsNullSessionId() {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        ElicitRequest request = ElicitRequest.builder()
                .message("test")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> handler.sendElicitRequest(null, request));
        assertTrue(ex.getMessage().contains("sessionId"));
    }

    @Test
    void sendElicitRequestRejectsNullRequest() {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> handler.sendElicitRequest(sessionId, null));
        assertTrue(ex.getMessage().contains("request"));
    }

    @Test
    void sendElicitRequestRejectsUnknownSession() {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        ElicitRequest request = ElicitRequest.builder().message("test").build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> handler.sendElicitRequest("no-such-session", request));
        assertTrue(ex.getMessage().contains("Unknown session"));
    }

    @Test
    void sendElicitRequestThrowsWhenNotEnabled() {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(false)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        ElicitRequest request = ElicitRequest.builder().message("test").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> handler.sendElicitRequest(sessionId, request));
        assertTrue(ex.getMessage().contains("not enabled"));
    }

    @Test
    void sendElicitRequestThrowsWhenNoCallback() {
        // No callback provided
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, null);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        ElicitRequest request = ElicitRequest.builder().message("test").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> handler.sendElicitRequest(sessionId, request));
        assertTrue(ex.getMessage().contains("callback"));
    }

    @Test
    void isElicitationEnabledReturnsCorrectValue() {
        McpServerConfig enabledConfig = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler enabledHandler = new McpProtocolHandler(
                new McpRegistry(), enabledConfig, null, null, null);
        assertTrue(enabledHandler.isElicitationEnabled());

        McpServerConfig disabledConfig = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(false)
                .build();
        McpProtocolHandler disabledHandler = new McpProtocolHandler(
                new McpRegistry(), disabledConfig, null, null, null);
        assertFalse(disabledHandler.isElicitationEnabled());
    }

    // ── Test: timeout callback is invoked by scheduled executor ─────────────────

    @Test
    void timeoutCallbackInvokedWhenClientNeverResponds() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        // Use a very short timeout (50ms) so the test runs quickly
        ElicitRequest request = ElicitRequest.builder()
                .message("Respond quickly or I will timeout")
                .timeoutMs(50L)
                .build();
        handler.sendElicitRequest(sessionId, request);
        String requestId = request.getRequestId();

        // Wait for the timeout to fire (give some buffer)
        Thread.sleep(200);

        assertEquals(0, callback.responseCount);
        assertEquals(1, callback.timeoutCount);
        assertEquals(requestId, callback.timedOutId.get());
        assertEquals(0, handler.getPendingElicitationCount(sessionId),
                "Pending elicitation should be removed after timeout");
    }

    @Test
    void elicitationRequestWithMetadataPreservedInSseEvent() throws Exception {
        CountingCallback callback = new CountingCallback();
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .elicitation(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpRegistry(), config, null, null, callback);

        String initBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = GSON.fromJson(initBody, JsonObject.class)
                .getAsJsonObject("result").get("sessionId").getAsString();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operationId", "op-123");
        metadata.put("severity", "warning");

        ElicitRequest request = ElicitRequest.builder()
                .message("Confirm deletion")
                .metadata(metadata)
                .timeoutMs(60_000L)
                .build();
        handler.sendElicitRequest(sessionId, request);

        // Verify pending notification exists (SSE event enqueued)
        assertEquals(1, handler.getPendingElicitationCount(sessionId));
    }

    @AfterEach
    void cleanup() {
        // Handler.shutdown() is called implicitly by test framework cleanup
    }

    // ── Test callback helper ───────────────────────────────────────────────────

    static class CountingCallback implements ElicitationCallback {
        int responseCount = 0;
        int timeoutCount = 0;
        int cancelledCount = 0;
        ElicitationMessage lastResponse;
        String lastError;
        String unknownRequestId;
        String lastCancelledId;
        String lastCancelledReason;
        final AtomicReference<String> timedOutId = new AtomicReference<>();

        @Override
        public void onResponse(String requestId, ElicitationMessage response) {
            responseCount++;
            this.lastResponse = response;
        }

        @Override
        public void onTimeout(String requestId, long timeoutMs) {
            timeoutCount++;
            this.timedOutId.set(requestId);
        }

        @Override
        public void onCancelled(String requestId, String reason) {
            cancelledCount++;
            this.lastCancelledId = requestId;
            this.lastCancelledReason = reason;
        }

        @Override
        public void onUnknownRequestId(String requestId) {
            this.unknownRequestId = requestId;
        }
    }
}
