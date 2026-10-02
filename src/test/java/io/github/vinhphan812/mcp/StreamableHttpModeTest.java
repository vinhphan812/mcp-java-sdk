package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.transport.HttpTransportProvider;
import io.github.vinhphan812.mcp.transport.TransportMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Streamable HTTP mode specific behavior.
 * These tests focus on the modern Streamable HTTP transport mode
 * and verify POST with streaming response and GET replay-only behavior.
 */
class StreamableHttpModeTest {

    @Test
    void testPostJsonResponse() throws Exception {
        // Test normal JSON response in StreamableHttp mode
        // Note: Currently still requires both Accept values; Phase 2 will allow JSON-only
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            assertTrue(transport.isRunning());

            // POST request should return normal JSON response
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            Result result = post(transport.getUrl(), body, null, "application/json, text/event-stream");
            assertEquals(200, result.status);
            assertNotNull(result.session);
            // Check response contains protocolVersion
            assertTrue(result.body.contains("protocolVersion"), "Response should contain protocolVersion");
        }
    }

    @Test
    void testGetReplayOnly() throws Exception {
        // Test GET returns immediately with replay events in StreamableHttp mode
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            assertTrue(transport.isRunning());

            // First initialize to get a session
            String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            Result initResult = post(transport.getUrl(), initBody, null, "application/json, text/event-stream");
            assertEquals(200, initResult.status);
            String session = initResult.session;
            assertNotNull(session);

            // GET should return immediately (replay-only mode)
            Result getResult = get(transport.getUrl(), session, null);
            assertEquals(200, getResult.status);
            // Should contain connected event
            assertTrue(getResult.body.contains("event: connected"));
            assertTrue(getResult.body.contains("\"sessionId\""));
        }
    }

    @Test
    void testGetReplayWithLastEventId() throws Exception {
        // Test GET with Last-Event-ID for replay
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            assertTrue(transport.isRunning());

            // First initialize to get a session
            String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            Result initResult = post(transport.getUrl(), initBody, null, "application/json, text/event-stream");
            assertEquals(200, initResult.status);
            String session = initResult.session;
            assertNotNull(session);

            // GET with Last-Event-ID should work (even if no missed events)
            Result getResult = get(transport.getUrl(), session, "5");
            assertEquals(200, getResult.status);
            // Should contain connected event
            assertTrue(getResult.body.contains("event: connected"));
        }
    }

    @Test
    void testModeDetectionAutoMode() throws Exception {
        // Test AUTO mode detection - protocol version header should indicate modern client
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.AUTO)) {
            transport.start();
            assertTrue(transport.isRunning());

            // With MCP-Protocol-Version header, should work in auto mode
            String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            Result result = postWithProtocolVersion(transport.getUrl(), initBody, null, "2025-11-25",
                    "application/json, text/event-stream");
            assertEquals(200, result.status);
            assertNotNull(result.session);
        }
    }

    @Test
    void testSessionManagementInStreamableHttpMode() throws Exception {
        // Test session creation and reuse in StreamableHttp mode
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            assertTrue(transport.isRunning());

            // First request - create session
            String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            Result initResult = post(transport.getUrl(), initBody, null, "application/json, text/event-stream");
            assertEquals(200, initResult.status);
            String session = initResult.session;
            assertNotNull(session);

            // Second request - reuse session
            String pingBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
            Result pingResult = post(transport.getUrl(), pingBody, session, "application/json, text/event-stream");
            assertEquals(200, pingResult.status);
            // Session should be maintained
            assertNotNull(pingResult.session);
        }
    }

    @Test
    void testPostWithNotification() throws Exception {
        // Test POST response with pending notifications
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            assertTrue(transport.isRunning());

            // Initialize
            String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            Result initResult = post(transport.getUrl(), initBody, null, "application/json, text/event-stream");
            assertEquals(200, initResult.status);
            String session = initResult.session;
            assertNotNull(session);

            // Send notification (202 response expected)
            String notificationBody = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
            Result notifResult = post(transport.getUrl(), notificationBody, session, "application/json, text/event-stream");
            assertEquals(202, notifResult.status);
        }
    }

    @Test
    void testStatelessToolsCallWithoutInitialize() throws Exception {
        // tools/call via HTTP stateless 2026 routing — no prior initialize, no session required.
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        // Register a tool so tools/call has something to invoke.
        handler.registerTool("test_echo", "Echoes the message argument",
                new java.util.LinkedHashMap<>(), null,
                (java.util.Map<String, Object> args) -> {
                    java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("echo", args.getOrDefault("message", ""));
                    return result;
                });
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"test_echo\",\"arguments\":{\"message\":\"hello stateless\"}}}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            connection.setRequestProperty("Mcp-Method", "tools/call");
            connection.setRequestProperty("Mcp-Name", "test_echo");
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(200, status, "tools/call stateless should return 200: " + respBody);
            assertTrue(respBody.contains("\"result\""), "Should be a JSON-RPC success: " + respBody);
            assertTrue(respBody.contains("hello stateless"), "Should return the echoed value: " + respBody);
            assertNull(connection.getHeaderField("Mcp-Session-Id"),
                    "Response should not contain Mcp-Session-Id: " + connection.getHeaderField("Mcp-Session-Id"));
        }
    }

    @Test
    void testHeaderSelectedStatelessRequestWithoutInitializeOrSession() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            // Statless 2026 mode requires Mcp-Method header to be present.
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            Result result = postStateless(transport.getUrl(), body, "2026-07-28", "tools/list",
                    "application/json, text/event-stream");
            assertEquals(200, result.status);
            assertTrue(result.body.contains("\"result\""), result.body);
            assertNull(result.session);
        }
    }

    @Test
    void testMalformedJsonAndInvalidMethodAreRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            Result malformed = postWithProtocolVersion(transport.getUrl(), "{not-json", null, "2025-11-25",
                    "application/json, text/event-stream");
            assertEquals(400, malformed.status);
            assertTrue(malformed.body.contains("Malformed JSON"), malformed.body);

            Result missingMethod = postWithProtocolVersion(transport.getUrl(), "{\"jsonrpc\":\"2.0\",\"id\":1}",
                    null, "2025-11-25", "application/json, text/event-stream");
            assertEquals(400, missingMethod.status);
            assertTrue(missingMethod.body.contains("Invalid JSON-RPC"), missingMethod.body);
        }
    }

    @Test
    void testUnsupportedProtocolHeaderIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            Result result = postWithProtocolVersion(transport.getUrl(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
                    null, "2099-01-01", "application/json, text/event-stream");
            assertEquals(400, result.status);
            assertTrue(result.body.contains("Unsupported MCP protocol version"), result.body);
        }
    }

    private static Result post(String url, String body, String session, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", accept);
        if (session != null) connection.setRequestProperty("Mcp-Session-Id", session);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        connection.getOutputStream().flush();
        int status = connection.getResponseCode();
        
        // Read response body - try input stream first, then error stream
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream input = connection.getInputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            input.close();
        }
        
        // If empty body, try error stream
        if (output.size() == 0) {
            InputStream errorInput = connection.getErrorStream();
            if (errorInput != null) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = errorInput.read(buffer)) != -1) output.write(buffer, 0, count);
                errorInput.close();
            }
        }
        
        return new Result(status, output.toString("UTF-8"), connection.getHeaderField("Mcp-Session-Id"));
    }

    private static Result postWithProtocolVersion(String url, String body, String session,
                                                  String protocolVersion, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", accept);
        if (session != null) connection.setRequestProperty("Mcp-Session-Id", session);
        if (protocolVersion != null) connection.setRequestProperty("Mcp-Protocol-Version", protocolVersion);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return new Result(status, output.toString("UTF-8"), connection.getHeaderField("Mcp-Session-Id"));
    }

    /** POST with stateless 2026 routing headers (Mcp-Protocol-Version + Mcp-Method). */
    private static Result postStateless(String url, String body, String protocolVersion,
                                        String mcpMethod, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Mcp-Protocol-Version", protocolVersion);
        connection.setRequestProperty("Mcp-Method", mcpMethod);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return new Result(status, output.toString("UTF-8"), connection.getHeaderField("Mcp-Session-Id"));
    }

    private static Result get(String url, String session, String lastEventId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/event-stream");
        if (session != null) connection.setRequestProperty("Mcp-Session-Id", session);
        if (lastEventId != null) connection.setRequestProperty("Last-Event-ID", lastEventId);
        connection.setReadTimeout(5000);
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return new Result(status, output.toString("UTF-8"), connection.getHeaderField("Mcp-Session-Id"));
    }

    private static final class Result {
        final int status;
        final String body;
        final String session;

        Result(int status, String body, String session) {
            this.status = status;
            this.body = body;
            this.session = session;
        }
    }

    // ==================== MCP 2026 Routing Headers Tests ====================

    @Test
    void testMissingMcpMethodHeaderIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            // Send 2026-07-28 request WITHOUT Mcp-Method header
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(400, status, "Missing Mcp-Method should return 400: " + respBody);
            assertTrue(respBody.contains("Mcp-Method"), "Body should mention Mcp-Method: " + respBody);
        }
    }

    @Test
    void testDuplicateMcpMethodHeaderIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            connection.addRequestProperty("Mcp-Method", "ping");
            connection.addRequestProperty("Mcp-Method", "ping"); // duplicate
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(400, status, "Duplicate Mcp-Method should return 400: " + respBody);
            assertTrue(respBody.contains("Mcp-Method"), "Body should mention Mcp-Method: " + respBody);
        }
    }

    @Test
    void testMcpMethodHeaderMismatchIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            // Body says ping, header says tools/list
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            Result result = postStateless(transport.getUrl(), body, "2026-07-28", "tools/list",
                    "application/json, text/event-stream");
            assertEquals(400, result.status, "Mismatched Mcp-Method should return 400: " + result.body);
            assertTrue(result.body.contains("does not match"), "Body should mention mismatch: " + result.body);
        }
    }

    @Test
    void testMissingMcpNameHeaderOnToolsCallIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"test\"}}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            connection.setRequestProperty("Mcp-Method", "tools/call");
            // Note: no Mcp-Name header
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(400, status, "Missing Mcp-Name on tools/call should return 400: " + respBody);
            assertTrue(respBody.contains("Mcp-Name"), "Body should mention Mcp-Name: " + respBody);
        }
    }

    @Test
    void testMcpNameHeaderMismatchOnToolsCallIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"actual_tool\"}}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            connection.setRequestProperty("Mcp-Method", "tools/call");
            connection.setRequestProperty("Mcp-Name", "wrong_tool"); // mismatched
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(400, status, "Mismatched Mcp-Name should return 400: " + respBody);
            assertTrue(respBody.contains("does not match"), "Body should mention mismatch: " + respBody);
        }
    }

    @Test
    void testMcpNameHeaderOnNonNameMethodIsRejected() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            connection.setRequestProperty("Mcp-Method", "ping");
            connection.setRequestProperty("Mcp-Name", "some_tool"); // not valid for ping
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(400, status, "Mcp-Name on non-name method should return 400: " + respBody);
            assertTrue(respBody.contains("not valid for"), "Body should mention not valid: " + respBody);
        }
    }

    @Test
    void testServerDiscoverReturnsServerInfoWithoutSession() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .serverName("test-server").serverVersion("1.0.1").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}";
            Result result = postStateless(transport.getUrl(), body, "2026-07-28", "server/discover",
                    "application/json, text/event-stream");
            assertEquals(200, result.status, "server/discover should return 200: " + result.body);
            assertTrue(result.body.contains("\"result\""), "Should contain result: " + result.body);
            assertTrue(result.body.contains("supportedVersions"), "Should contain supportedVersions: " + result.body);
            assertTrue(result.body.contains("2026-07-28"), "Should advertise 2026-07-28: " + result.body);
            assertTrue(result.body.contains("capabilities"), "Should contain capabilities: " + result.body);
            assertTrue(result.body.contains("test-server"), "Should contain serverName: " + result.body);
            assertNull(result.session, "server/discover should not return session: " + result.session);
        }
    }

    @Test
    void testServerDiscoverRequiresMcpMethodHeader() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolMode(McpServerConfig.ProtocolMode.STATELESS).build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}";
            HttpURLConnection connection = (HttpURLConnection) new URL(transport.getUrl()).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Mcp-Protocol-Version", "2026-07-28");
            // No Mcp-Method header
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            String respBody = readBody(connection);
            assertEquals(400, status, "Missing Mcp-Method on server/discover should return 400: " + respBody);
        }
    }

    @Test
    void testLegacyRequestWithoutMcpMethodHeaderIsAccepted() throws Exception {
        // Non-2026-07-28 requests should NOT require Mcp-Method header
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        try (HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0).endpoint("/mcp").transportMode(TransportMode.STREAMABLE_HTTP)) {
            transport.start();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            Result result = postWithProtocolVersion(transport.getUrl(), body, null, "2025-11-25",
                    "application/json, text/event-stream");
            assertEquals(200, result.status, "Legacy ping should succeed without Mcp-Method: " + result.body);
        }
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        InputStream input = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }
}
