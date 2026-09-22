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
}
