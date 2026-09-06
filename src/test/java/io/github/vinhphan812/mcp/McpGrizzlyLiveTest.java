package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.transport.GrizzlyStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

class McpGrizzlyLiveTest {
    @Test
    void initializeAndNotificationWorkOverHttp() throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        GrizzlyStreamableServerTransportProvider transport = new GrizzlyStreamableServerTransportProvider(handler)
                .port(0).endpoint("/mcp");
        try {
            transport.start();
            assertTrue(transport.isRunning());
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            HttpResult initialized = post(transport.getUrl(), body, null, "http://localhost");
            assertEquals(200, initialized.status);
            assertNotNull(initialized.sessionId);
            assertTrue(initialized.body.contains("\"protocolVersion\":\"2025-11-25\""));

            HttpResult notification = post(transport.getUrl(),
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    initialized.sessionId, null);
            assertEquals(202, notification.status);
            assertEquals("", notification.body);
        } finally {
            transport.stop();
        }
        assertFalse(transport.isRunning());
    }

    private static HttpResult post(String endpoint, String body, String session, String origin)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if (session != null) connection.setRequestProperty("Mcp-Session-Id", session);
        if (origin != null) connection.setRequestProperty("Origin", origin);
        connection.getOutputStream().write(body.getBytes("UTF-8"));
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        return new HttpResult(status, output.toString("UTF-8"),
                connection.getHeaderField("Mcp-Session-Id"));
    }

    private static final class HttpResult {
        final int status;
        final String body;
        final String sessionId;
        HttpResult(int status, String body, String sessionId) {
            this.status = status;
            this.body = body;
            this.sessionId = sessionId;
        }
    }
}
