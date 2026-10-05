/*
 * Minimal diagnostic test — will be deleted after diagnosis.
 */
package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.transport.HttpTransportProvider;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class Mcp2026WireContractDiagTest {
    static HttpTransportProvider transport;
    static String url;

    @BeforeAll
    static void start() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        transport = new HttpTransportProvider(handler).port(0);
        transport.start();
        url = transport.getUrl();
    }

    @AfterAll
    static void stop() { transport.stop(); }

    static Result httpPost(String body, String sessionId, String protocolVersion) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null) conn.setRequestProperty("Mcp-Session-Id", sessionId);
        if (protocolVersion != null) {
            conn.setRequestProperty("Mcp-Protocol-Version", protocolVersion);
            int start = body.indexOf("\"method\":\"") + 10;
            int end = body.indexOf("\"", start);
            if (start > 9 && end > start) {
                conn.setRequestProperty("Mcp-Method", body.substring(start, end));
            }
        }
        conn.connect();
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int status = conn.getResponseCode();
        String session = conn.getHeaderField("Mcp-Session-Id");
        String respBody = readBody(conn);
        conn.disconnect();
        return new Result(status, respBody, session);
    }

    static String readBody(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString(StandardCharsets.UTF_8);
    }

    static class Result {
        final int status;
        final String body;
        final String session;
        Result(int status, String body, String session) {
            this.status = status;
            this.body = body;
            this.session = session;
        }
    }

    @Test
    void diag() throws Exception {
        System.out.println("=== 2026 ping ===");
        Result r1 = httpPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}", null, "2026-07-28");
        System.out.println("status=" + r1.status + " session=" + r1.session + " body=" + r1.body);
        assertEquals(200, r1.status, "2026 ping: " + r1.body);

        System.out.println("\n=== 2025 initialize ===");
        Result r2 = httpPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null, "2025-11-25");
        System.out.println("status=" + r2.status + " session=" + r2.session + " body=" + r2.body);
        assertNotNull(r2.session, "2025 initialize: " + r2.body);

        System.out.println("\n=== 2025 ping no session ===");
        Result r3 = httpPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}", null, "2025-11-25");
        System.out.println("status=" + r3.status + " session=" + r3.session + " body=" + r3.body);
        assertEquals(200, r3.status, "2025 ping: " + r3.body);

        System.out.println("\n=== 2025 tools/list no session ===");
        Result r4 = httpPost("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", null, "2025-11-25");
        System.out.println("status=" + r4.status + " session=" + r4.session + " body=" + r4.body);
        assertEquals(200, r4.status, "2025 tools/list: " + r4.body);
        assertTrue(r4.body.contains("Missing or invalid MCP session"), "session required: " + r4.body);

        System.out.println("\n=== 2025 tools/list WITH session ===");
        Result r5 = httpPost("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", r2.session, "2025-11-25");
        System.out.println("status=" + r5.status + " session=" + r5.session + " body=" + r5.body);
        assertEquals(200, r5.status, "2025 tools/list with session: " + r5.body);
        assertTrue(r5.body.contains("\"result\""), "success: " + r5.body);

        System.out.println("\nAll diagnostics passed.");
    }
}
