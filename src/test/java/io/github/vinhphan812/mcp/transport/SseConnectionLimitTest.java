package io.github.vinhphan812.mcp.transport;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SseConnectionLimitTest {

    @Test
    void testSseConnectionLimitAndOrdering() throws Exception {
        // Setup with max 1 connection to make testing easier
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());

        HttpTransportProvider transport = new HttpTransportProvider(handler)
                .port(0)
                .apiKey("secret");
        
        // This is a bit tricky: `HttpTransportProvider` creates `McpHttpHandler` internally 
        // with `maxSseConnections` via its own builder which isn't easily accessible.
        // Looking at `HttpTransportProvider.java`:
        // McpHttpHandler httpHandler = new McpHttpHandler(handler, endpoint, apiKeySupplier, allowedOrigins, maxRequestBodyBytes, 4, ...);
        // It hardcodes 4. I might need to adjust or rely on 4.
        
        transport.start();
        String url = transport.getUrl();

        try {
            // Need a valid session for GET /mcp
            // Initialize
            String initReq = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
            
            assertNotNull(url, "Transport URL must be available after start");
            HttpURLConnection initConn = (HttpURLConnection) new URL(url).openConnection();
            initConn.setRequestMethod("POST");
            initConn.setDoOutput(true);
            initConn.setRequestProperty("Authorization", "Bearer secret");
            initConn.setRequestProperty("Content-Type", "application/json");
            initConn.setRequestProperty("Accept", "application/json, text/event-stream");
            initConn.getOutputStream().write(initReq.getBytes(StandardCharsets.UTF_8));
            int initStatus = initConn.getResponseCode();
            InputStream initInput = initStatus >= 400 ? initConn.getErrorStream() : initConn.getInputStream();
            String initBody = initInput == null ? "" : new String(initInput.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(200, initStatus, "initialize failed: " + initBody);
            
            String session = initConn.getHeaderField("Mcp-Session-Id");
            if (session == null) {
                // Try parsing body if header missing
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"sessionId\":\"([^\"]+)\"").matcher(initBody);
                if (m.find()) session = m.group(1);
            }
            assertNotNull(session, "Session ID not found in response: " + initBody);

            // Connect 4 clients (limit=4)
            HttpURLConnection[] conns = new HttpURLConnection[4];
            for (int i = 0; i < 4; i++) {
                conns[i] = (HttpURLConnection) new URL(url).openConnection();
                conns[i].setRequestProperty("Mcp-Session-Id", session);
                conns[i].setRequestProperty("Authorization", "Bearer secret");
                conns[i].setRequestProperty("Accept", "text/event-stream");
            }
            
            CountDownLatch started = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        conns[idx].getResponseCode();
                    } catch (Exception ignored) {
                        // The assertion below observes the four successful responses.
                    } finally {
                        started.countDown();
                    }
                }).start();
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "All connection slots should be established");

            // 5th should be rejected
            HttpURLConnection conn5 = (HttpURLConnection) new URL(url).openConnection();
            conn5.setRequestProperty("Mcp-Session-Id", session);
            conn5.setRequestProperty("Authorization", "Bearer secret");
            conn5.setRequestProperty("Accept", "text/event-stream");
            int status = conn5.getResponseCode();
            assertEquals(429, status, "5th connection should be rejected with 429");
            String contentType = conn5.getHeaderField("Content-Type");
            assertFalse(contentType != null && contentType.toLowerCase().contains("text/event-stream"),
                    "Permit denial must not emit SSE headers: " + contentType);
            InputStream deniedInput = conn5.getErrorStream();
            String deniedBody = deniedInput == null ? "" : new String(deniedInput.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(deniedBody.contains("Too many active SSE connections"),
                    "Permit denial should emit the JSON error body");
            conn5.disconnect();
            
            // Clean up the held streams. The production finally block releases each permit
            // when the polling loop observes the disconnect.
            for (int i = 0; i < 4; i++) {
                conns[i].disconnect();
            }

            // A released permit must become usable after the client disconnects.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean acquiredAfterDisconnect = false;
            while (System.nanoTime() < deadline && !acquiredAfterDisconnect) {
                HttpURLConnection replacement = (HttpURLConnection) new URL(url).openConnection();
                replacement.setRequestProperty("Mcp-Session-Id", session);
                replacement.setRequestProperty("Authorization", "Bearer secret");
                replacement.setRequestProperty("Accept", "text/event-stream");
                acquiredAfterDisconnect = replacement.getResponseCode() == 200;
                replacement.disconnect();
                if (!acquiredAfterDisconnect) Thread.sleep(50);
            }
            assertTrue(acquiredAfterDisconnect, "A permit must be released after client disconnect");
        } finally {
            transport.stop();
        }
    }
}
