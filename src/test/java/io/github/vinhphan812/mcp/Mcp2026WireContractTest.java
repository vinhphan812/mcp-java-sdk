/*
 * Copyright 2025 Phan Thanh Vinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vinhphan812.mcp;

import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.Mcp2026RequestContext;
import io.github.vinhphan812.mcp.api.utils.McpJsonRpc;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.transport.HttpTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the MCP 2026-07-28 wire contract enforcement.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>initialize/initialized rejection in 2026 mode</li>
 *   <li>server/discover in 2026 mode (no session required)</li>
 *   <li>ping in 2026 mode (no session required)</li>
 *   <li>tools/resources/prompts/call/read/get in 2026 mode (no session required)</li>
 *   <li>Mcp-Session-Id header never set on 2026 responses</li>
 *   <li>Malformed protocolVersion handling</li>
 *   <li>Version-gating: 2025 methods rejected in 2026 mode</li>
 *   <li>Complete 2025-11-25 backward compatibility</li>
 *   <li>Mcp2026RequestContext parsing</li>
 *   <li>McpJsonRpc version helper methods</li>
 * </ul>
 */
class Mcp2026WireContractTest {

    private HttpTransportProvider transport;
    private String url;

    // ── Setup ────────────────────────────────────────────────────────────────

    @BeforeEach
    void startServer() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());
        transport = new HttpTransportProvider(handler).port(0);
        transport.start();
        url = transport.getUrl();
    }

    @AfterEach
    void stopServer() {
        transport.stop();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Extracts the JSON-RPC method name from a request body string.
     * Handles top-level, params-level, and protocolVersion-in-value edge cases.
     */
    private static String extractJsonRpcMethod(String body) {
        if (body == null || body.isEmpty()) return null;
        int methodPos = body.indexOf("\"method\":\"");
        if (methodPos < 0) return null;
        // Skip past "method" to the opening quote of the value
        int valueStart = methodPos + "\"method\":\"".length();
        // Check we're not inside a "protocolVersion" value that happens to start with "method"
        // by verifying the preceding context: only valid if the closest preceding " is at the method key
        int prevQuote = body.lastIndexOf("\"", methodPos - 1);
        if (prevQuote >= 0) {
            String preceding = body.substring(Math.max(0, prevQuote - 20), methodPos).trim();
            // If the preceding text contains "protocolVersion", this "method" is inside a value
            if (preceding.contains("protocolVersion")) return null;
        }
        int valueEnd = body.indexOf("\"", valueStart);
        if (valueEnd < 0 || valueEnd <= valueStart) return null;
        return body.substring(valueStart, valueEnd);
    }

    private Result httpPost(String body, String sessionId, String protocolVersion) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null) conn.setRequestProperty("Mcp-Session-Id", sessionId);
        if (protocolVersion != null) conn.setRequestProperty("Mcp-Protocol-Version", protocolVersion);
        // Always set Mcp-Method to match the JSON-RPC method in the body.
        // This is required for the 2026 routing layer (Mcp-Protocol-Version: 2026-07-28
        // triggers requiresModernRoutingHeaders). For 2025 requests, the header is validated
        // and must match the JSON-RPC method.
        if (body != null) {
            String rpcMethod = extractJsonRpcMethod(body);
            if (rpcMethod != null) {
                conn.setRequestProperty("Mcp-Method", rpcMethod);
            }
        }
        conn.connect();
        if (body != null) {
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        String respSession = conn.getHeaderField("Mcp-Session-Id");
        String respBody = readBody(conn);
        conn.disconnect();
        return new Result(status, respBody, respSession);
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static class Result {
        final int status;
        final String body;
        final String session;

        Result(int status, String body, String session) {
            this.status = status;
            this.body = body;
            this.session = session;
        }
    }

    // ── Mcp2026RequestContext unit tests ────────────────────────────────────

    @Nested
    class Mcp2026RequestContextTests {

        @Test
        void fromParamsReturnsEmptyWhenParamsNull() {
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(null);
            assertFalse(ctx.hasProgressToken());
            assertTrue(ctx.extras.isEmpty());
        }

        @Test
        void fromParamsReturnsEmptyWhenMetaAbsent() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", "tool");
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(params);
            assertFalse(ctx.hasProgressToken());
            assertTrue(ctx.extras.isEmpty());
        }

        @Test
        void fromParamsReturnsEmptyWhenMetaNotMap() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("_meta", "not-a-map");
            params.put("name", "tool");
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(params);
            assertFalse(ctx.hasProgressToken());
            assertTrue(ctx.extras.isEmpty());
        }

        @Test
        void fromParamsReturnsEmptyWhenProgressTokenAbsent() {
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("otherKey", "value");
            params.put("_meta", meta);
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(params);
            assertFalse(ctx.hasProgressToken());
            assertTrue(ctx.extras.isEmpty());
        }

        @Test
        void fromParamsExtractsStringProgressToken() {
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("progressToken", "abc-123");
            params.put("_meta", meta);
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(params);
            assertTrue(ctx.hasProgressToken());
            assertEquals("abc-123", ctx.progressTokenAsString());
            assertEquals("abc-123", ctx.progressToken);
            assertNull(ctx.progressTokenAsNumber());
            assertTrue(ctx.extras.isEmpty());
        }

        @Test
        void fromParamsExtractsNumericProgressToken() {
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("progressToken", 42L);
            params.put("_meta", meta);
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(params);
            assertTrue(ctx.hasProgressToken());
            assertEquals(42L, ctx.progressTokenAsNumber().longValue());
            assertNull(ctx.progressTokenAsString());
        }

        @Test
        void fromParamsCollectsExtras() {
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("progressToken", "tok-1");
            meta.put("requestId", "req-1");
            meta.put("extraField", 123);
            params.put("_meta", meta);
            Mcp2026RequestContext ctx = Mcp2026RequestContext.fromParams(params);
            assertEquals(2, ctx.extras.size());
            assertEquals("req-1", ctx.extras.get("requestId"));
            assertEquals(123, ctx.extras.get("extraField"));
            assertFalse(ctx.extras.containsKey("progressToken"));
        }

        @Test
        void emptyContextHasCorrectEqualityAndHashCode() {
            assertEquals(Mcp2026RequestContext.EMPTY, Mcp2026RequestContext.EMPTY);
            assertEquals(Mcp2026RequestContext.EMPTY.hashCode(), Mcp2026RequestContext.EMPTY.hashCode());
            assertEquals(Mcp2026RequestContext.EMPTY.toString(), Mcp2026RequestContext.EMPTY.toString());
        }
    }

    // ── McpJsonRpc helper tests ─────────────────────────────────────────────

    @Nested
    class McpJsonRpcHelperTests {

        @Test
        void isStatelessVersionReturnsTrueFor2026() {
            assertTrue(McpJsonRpc.isStatelessVersion("2026-07-28"));
            assertFalse(McpJsonRpc.isStatelessVersion("2025-11-25"));
            assertFalse(McpJsonRpc.isStatelessVersion("2025-06-18"));
            assertFalse(McpJsonRpc.isStatelessVersion(null));
            assertFalse(McpJsonRpc.isStatelessVersion(""));
            assertFalse(McpJsonRpc.isStatelessVersion("unknown"));
        }

        @Test
        void isSessionedVersionReturnsTrueFor2025Eras() {
            assertTrue(McpJsonRpc.isSessionedVersion("2025-11-25"));
            assertTrue(McpJsonRpc.isSessionedVersion("2025-06-18"));
            assertFalse(McpJsonRpc.isSessionedVersion("2026-07-28"));
            assertFalse(McpJsonRpc.isSessionedVersion(null));
        }

        @Test
        void isCurrentVersionReturnsTrueForDefaultVersion() {
            assertTrue(McpJsonRpc.isCurrentVersion("2025-11-25"));
            assertFalse(McpJsonRpc.isCurrentVersion("2025-06-18"));
            assertFalse(McpJsonRpc.isCurrentVersion("2026-07-28"));
            assertFalse(McpJsonRpc.isCurrentVersion(null));
        }
    }

    // ── 2026 wire contract: initialize and initialized ────────────────────────

    @Nested
    class InitializeRejectionTests {

        @Test
        void initializeRejectedWithProtocolVersionHeader() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertNotNull(r.body);
            assertTrue(r.body.contains("-32601"),
                    "Expected methodNotFound error, got: " + r.body);
            assertTrue(r.body.contains("2026-07-28"), "Error message should mention 2026: " + r.body);
            assertTrue(r.body.contains("server/discover"),
                    "Error should suggest server/discover: " + r.body);
            // No session created
            assertNull(r.session, "No Mcp-Session-Id should be set in 2026 mode");
        }

        @Test
        void initializeRejectedWithBodyProtocolVersion() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("-32601"), "Expected methodNotFound error, got: " + r.body);
            assertNull(r.session, "No Mcp-Session-Id should be set in 2026 mode");
        }

        @Test
        void initializeRejectedWithTopLevelProtocolVersion() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"protocolVersion\":\"2026-07-28\",\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("-32601"), "Expected methodNotFound error, got: " + r.body);
            assertNull(r.session);
        }

        @Test
        void initializedNotificationSilentlyAccepted() throws Exception {
            // notifications/initialized: silent 202 in 2026 mode (no session, no side-effects)
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(202, r.status, "Notification returns 202: " + r.body);
            assertTrue(r.body.isEmpty(), "Notification has no body: " + r.body);
            assertNull(r.session);
        }

        @Test
        void initializedNotificationSilentlyAcceptedWithBodyVersion() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"protocolVersion\":\"2026-07-28\",\"params\":{}}",
                    null, null);
            assertEquals(202, r.status);
            assertTrue(r.body.isEmpty());
            assertNull(r.session);
        }

        // ── 2025 backward compatibility ─────────────────────────────────────

        @Test
        void initializeAcceptedIn2025Mode() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success response: " + r.body);
            assertNotNull(r.session, "2025 mode should create a session");
        }

        @Test
        void initializedNotificationAcceptedSilentlyIn2025Mode() throws Exception {
            // First initialize to get a session
            Result init = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertNotNull(init.session);
            // Then send initialized notification with the session
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                    init.session, null);
            assertEquals(202, r.status, "Notification returns 202: " + r.body);
            assertTrue(r.body.isEmpty());
        }
    }

    // ── 2026 wire contract: server/discover (sessionless) ─────────────────────

    @Nested
    class ServerDiscoverTests {

        @Test
        void serverDiscoverRequiresNoSessionIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertTrue(r.body.contains("\"resultType\":\"complete\""), "Should be complete: " + r.body);
            assertTrue(r.body.contains("\"2026-07-28\""), "Should advertise 2026: " + r.body);
            assertNull(r.session, "server/discover should not set Mcp-Session-Id");
        }

        @Test
        void serverDiscoverRequiresNoSessionIn2025() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertNull(r.session, "server/discover should not set Mcp-Session-Id in 2025 either");
        }

        @Test
        void serverDiscoverIn2026Advertises2026OnlyCapabilities() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            // Should NOT advertise subscriptions in 2026 mode (no SSE sessions)
            assertFalse(r.body.contains("\"subscribe\""),
                    "2026 discover should not advertise subscribe: " + r.body);
        }
    }

    // ── 2026 wire contract: ping (sessionless) ───────────────────────────────

    @Nested
    class PingTests {

        @Test
        void pingRequiresNoSessionIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertNull(r.session, "ping should not set Mcp-Session-Id");
        }

        @Test
        void pingRequiresNoSessionIn2025() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
                    null, "2025-11-25");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertNull(r.session, "ping should not set Mcp-Session-Id");
        }
    }

    // ── 2026 wire contract: tools/resources/prompts (sessionless) ────────────

    @Nested
    class ServiceCallsNoSessionTests {

        @Test
        void toolsListRequiresNoSessionIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status, "Expected HTTP 200, got: " + r.body);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertTrue(r.body.contains("\"tools\""), "Should contain tools: " + r.body);
            assertNull(r.session, "tools/list should not set Mcp-Session-Id in 2026");
        }

        @Test
        void resourcesListRequiresNoSessionIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertTrue(r.body.contains("\"resources\""), "Should contain resources: " + r.body);
            assertNull(r.session, "resources/list should not set Mcp-Session-Id in 2026");
        }

        @Test
        void promptsListRequiresNoSessionIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
            assertTrue(r.body.contains("\"prompts\""), "Should contain prompts: " + r.body);
            assertNull(r.session, "prompts/list should not set Mcp-Session-Id in 2026");
        }

        @Test
        void toolsListRequiresSessionIn2025() throws Exception {
            // Without a session, tools/list should fail in 2025 mode
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("Missing or invalid MCP session"),
                    "2025 mode should require session: " + r.body);
        }

        @Test
        void toolsListWithSessionWorksIn2025() throws Exception {
            // First initialize to get a session
            Result init = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertNotNull(init.session);
            // Then call tools/list with session
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                    init.session, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("\"result\""), "Expected success: " + r.body);
        }
    }

    // ── 2026 wire contract: Mcp-Session-Id never set ─────────────────────────

    @Nested
    class NoSessionIdIn2026Tests {

        @Test
        void noSessionIdOnDiscoverResponseIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertNull(r.session, "Mcp-Session-Id should be null in 2026 mode");
        }

        @Test
        void noSessionIdOnPingResponseIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertNull(r.session, "Mcp-Session-Id should be null in 2026 mode");
        }

        @Test
        void noSessionIdOnToolsListResponseIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertNull(r.session, "Mcp-Session-Id should be null in 2026 mode");
        }

        @Test
        void noSessionIdOnToolsCallResponseIn2026() throws Exception {
            // In 2026 mode, tools/call passes the HTTP session check.
            // The protocol handler then validates the tool name itself.
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"missing-tool\"}}",
                    null, "2026-07-28");
            assertEquals(200, r.status, "tools/call should reach protocol handler in 2026: " + r.body);
            // Should get an invalid params error for unknown tool, not session error
            assertTrue(r.body.contains("-32602") || r.body.contains("Unknown tool"),
                    "Should get tool error, not session error: " + r.body);
            assertNull(r.session, "No Mcp-Session-Id in 2026 response");
        }

        @Test
        void sessionIdSetOnInitializeIn2025() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertNotNull(r.session, "Mcp-Session-Id should be set in 2025 mode");
        }

        @Test
        void sessionIdPreservedOnToolsListWithSessionIn2025() throws Exception {
            // First initialize to get a session
            Result init = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertNotNull(init.session, "initialize should create session");
            // Then call tools/list with session
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                    init.session, null);
            assertEquals(200, r.status, "tools/list with session should succeed: " + r.body);
            assertTrue(r.body.contains("\"result\""), "tools/list should return result: " + r.body);
            assertNull(r.session, "Subsequent 2025 requests should not change session id");
        }
    }

    // ── Version gating: 2025-only behaviour cannot leak into 2026 ─────────────

    @Nested
    class VersionGatingTests {

        @Test
        void notificationsCancelledAcceptedSilentlyIn2026() throws Exception {
            // notifications/cancelled is a 2025 feature
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}",
                    null, "2026-07-28");
            // notifications/cancelled is accepted silently (no side-effects in stateless)
            assertEquals(202, r.status);
            assertTrue(r.body.isEmpty());
        }

        @Test
        void resourcesSubscribeRejectedIn2026() throws Exception {
            // In 2026 mode, the HTTP session guard is bypassed but the protocol handler
            // still requires a session for resources/subscribe → Missing session ID error.
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"test://a\"}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("Missing session ID"),
                    "resources/subscribe should require session in 2026: " + r.body);
        }

        @Test
        void resourcesUnsubscribeRejectedIn2026() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/unsubscribe\",\"params\":{\"uri\":\"test://a\"}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            assertTrue(r.body.contains("Missing session ID"),
                    "resources/unsubscribe should require session in 2026: " + r.body);
        }
    }

    // ── Malformed metadata/version tests ─────────────────────────────────────

    @Nested
    class MalformedMetadataTests {

        @Test
        void nonStringProtocolVersionRejected() throws Exception {
            // Top-level non-string protocolVersion
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"protocolVersion\":123,\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("-32602") || r.body.contains("must be a string"),
                    "Non-string protocolVersion should be rejected: " + r.body);
        }

        @Test
        void nonStringParamsProtocolVersionRejected() throws Exception {
            // params.protocolVersion as non-string
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"protocolVersion\":true}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("-32602") || r.body.contains("must be a string"),
                    "Non-string params.protocolVersion should be rejected: " + r.body);
        }

        @Test
        void unsupportedProtocolVersionRejected() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"protocolVersion\":\"2099-01-01\"}}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("Unsupported protocol version"),
                    "Unsupported version should be rejected: " + r.body);
        }

        @Test
        void malformedJsonRejected() throws Exception {
            Result r = httpPost(
                    "not valid json",
                    null, "2025-11-25");
            assertEquals(400, r.status);
        }
    }

    // ── Regression: tools/resources/prompts/server/discover ───────────────────

    @Nested
    class RegressionTests {

        @Test
        void toolsListReturnsEmptyList() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            assertTrue(obj.has("result"));
            assertTrue(obj.getAsJsonObject("result").has("tools"));
        }

        @Test
        void resourcesListReturnsEmptyList() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            assertTrue(obj.has("result"));
            assertTrue(obj.getAsJsonObject("result").has("resources"));
        }

        @Test
        void promptsListReturnsEmptyList() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            assertTrue(obj.has("result"));
            assertTrue(obj.getAsJsonObject("result").has("prompts"));
        }

        @Test
        void serverDiscoverReturnsCompleteResult() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            JsonObject result = obj.getAsJsonObject("result");
            assertEquals("complete", result.get("resultType").getAsString());
            assertTrue(result.has("supportedVersions"));
            assertTrue(result.has("capabilities"));
            assertTrue(result.has("_meta"));
        }

        @Test
        void pingReturnsEmptyResult() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}",
                    null, "2026-07-28");
            assertEquals(200, r.status);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            assertTrue(obj.has("result"));
        }
    }

    // ── Backward compatibility: full 2025-11-25 preserve ─────────────────────

    @Nested
    class BackwardCompatibilityTests {

        @Test
        void initializeCreatesSessionAndNegotiatesVersion() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                    null, null);
            assertEquals(200, r.status);
            assertNotNull(r.session, "2025 initialize must create a session");
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            JsonObject result = obj.getAsJsonObject("result");
            assertEquals("2025-11-25", result.get("protocolVersion").getAsString());
            assertTrue(result.has("capabilities"));
            assertTrue(result.has("serverInfo"));
        }

        @Test
        void initializeDefaultsTo2025WhenNoVersionRequested() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertEquals(200, r.status);
            assertNotNull(r.session);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            JsonObject result = obj.getAsJsonObject("result");
            assertEquals("2025-11-25", result.get("protocolVersion").getAsString());
        }

        @Test
        void initializeSupportsLegacy2025Version() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
                    null, null);
            assertEquals(200, r.status);
            assertNotNull(r.session);
            JsonObject obj = new com.google.gson.Gson().fromJson(r.body, JsonObject.class);
            JsonObject result = obj.getAsJsonObject("result");
            assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        }

        @Test
        void sessionRequiredForProtectedMethodsIn2025() throws Exception {
            // Any method other than initialize/server-discover/ping requires session in 2025.
            // Do NOT send Mcp-Protocol-Version header for these — it's not required in 2025.
            String[] methods = {"tools/list", "tools/call", "resources/list",
                    "resources/read", "prompts/list", "prompts/get",
                    "logging/setLevel"};
            for (String method : methods) {
                Result r = httpPost(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":{}}",
                        null, null);
                assertEquals(200, r.status, method + " should return 200");
                assertTrue(r.body.contains("Missing or invalid MCP session"),
                        method + " should return session error: " + r.body);
            }
        }

        @Test
        void sessionSurvivesMultipleRequestsIn2025() throws Exception {
            Result init = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertNotNull(init.session);
            String session = init.session;

            // Make several requests with the same session (no protocol header)
            for (int i = 2; i <= 5; i++) {
                Result r = httpPost(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + i + ",\"method\":\"tools/list\",\"params\":{}}",
                        session, null);
                assertEquals(200, r.status, "Request " + i + " should succeed");
                assertTrue(r.body.contains("\"result\""), "Request " + i + " should return result");
            }
        }

        @Test
        void invalidJsonRpcEnvelopeRejected() throws Exception {
            Result r = httpPost(
                    "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
                    null, null);
            assertEquals(200, r.status);
            assertTrue(r.body.contains("-32600"), "Invalid JSON-RPC version should be rejected: " + r.body);
        }

        @Test
        void unknownMethodRejected() throws Exception {
            // First initialize to get a session so the unknown method reaches method validation.
            Result init = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                    null, null);
            assertNotNull(init.session);
            Result r = httpPost(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown/method\",\"params\":{}}",
                    init.session, null);
            assertEquals(200, r.status, "Should return 200: " + r.body);
            assertTrue(r.body.contains("-32601"), "Unknown method should be rejected: " + r.body);
        }
    }
}
