package io.github.vinhphan812.mcp.transport;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CORS origin checking and preflight handling in the MCP HTTP transport.
 *
 * <p>These tests verify status-code-based security assertions using HttpURLConnection.
 * HttpURLConnection may filter CORS headers from responses on same-origin requests,
 * so header-level assertions are covered by {@link CorsOriginPolicyTest}.
 *
 * <p>Test coverage:
 * <ol>
 *   <li>Absent Origin + no auth → 200 (non-browser access preserved)
 *   <li>Loopback origins (localhost, 127.0.0.1, [::1]) with any port → 200
 *   <li>Private/public non-loopback origins → 403
 *   <li>OPTIONS preflight with allowed origin → 200 + CORS headers
 *   <li>OPTIONS preflight with rejected origin → 403
 *   <li>OPTIONS with no Origin → 200 (no CORS headers)
 *   <li>Auth independence: valid origin + invalid bearer → 401
 *   <li>Auth independence: rejected origin + valid bearer → 403
 *   <li>Explicit non-loopback allowlist → accepted when listed
 *   <li>Empty allowlist → only loopback accepted
 *   <li>202 response on notification (no body)
 * </ol>
 */
class McpCorsIntegrationTest {

    /** Default transport: no auth — used for most CORS tests. */
    private HttpTransportProvider transport;
    /** Authenticated transport — created on demand for auth-independence tests. */
    private HttpTransportProvider authTransport;
    private String url;

    // ── Setup ────────────────────────────────────────────────────────────────

    @BeforeEach
    void startServer() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());

        // Default transport: no authentication (most CORS tests).
        transport = new HttpTransportProvider(handler).port(0);
        transport.start();
        url = transport.getUrl();
    }

    @AfterEach
    void stopServer() {
        if (authTransport != null) authTransport.stop();
        transport.stop();
    }

    /**
     * Starts a new authenticated transport for auth-independence tests.
     * Returns its URL.
     */
    private String startAuthTransport() {
        if (authTransport != null) authTransport.stop();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());
        authTransport = new HttpTransportProvider(handler).port(0).apiKey("secret");
        authTransport.start();
        return authTransport.getUrl();
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    /**
     * Sends a raw HTTP POST request and returns the status code.
     * Uses a raw socket to bypass HttpURLConnection's Origin-header filtering
     * (HttpURLConnection strips the Origin header on same-origin requests).
     */
    private int postStatus(String body, String origin) throws Exception {
        URL parsedUrl = new URL(url);
        int port = parsedUrl.getPort() != -1 ? parsedUrl.getPort() : 80;
        String host = parsedUrl.getHost();
        byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        StringBuilder req = new StringBuilder();
        req.append("POST ").append(parsedUrl.getPath()).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(":").append(port).append("\r\n");
        if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
        req.append("Mcp-Protocol-Version: 2025-11-25\r\n");
        req.append("Accept: application/json, text/event-stream\r\n");
        req.append("Content-Type: application/json\r\n");
        req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 5000);
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) out.write(bodyBytes);
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String firstLine = in.readLine();
            if (firstLine == null) return -1;
            return parseStatusCode(firstLine);
        }
    }

    /**
     * Sends a raw HTTP OPTIONS request and returns the status code.
     */
    private int optionsStatus(String origin) throws Exception {
        URL parsedUrl = new URL(url);
        int port = parsedUrl.getPort() != -1 ? parsedUrl.getPort() : 80;
        String host = parsedUrl.getHost();
        StringBuilder req = new StringBuilder();
        req.append("OPTIONS ").append(parsedUrl.getPath()).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(":").append(port).append("\r\n");
        if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
        req.append("Access-Control-Request-Method: POST\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 5000);
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String firstLine = in.readLine();
            if (firstLine == null) return -1;
            return parseStatusCode(firstLine);
        }
    }

    /**
     * Sends a raw HTTP POST request with Bearer auth and Origin header.
     * Used for auth-independence tests.
     */
    private int authPostStatus(String targetUrl, String body, String bearer, String origin) throws Exception {
        URL parsedUrl = new URL(targetUrl);
        int port = parsedUrl.getPort() != -1 ? parsedUrl.getPort() : 80;
        String host = parsedUrl.getHost();
        byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        StringBuilder req = new StringBuilder();
        req.append("POST ").append(parsedUrl.getPath()).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(":").append(port).append("\r\n");
        if (bearer != null) req.append("Authorization: Bearer ").append(bearer).append("\r\n");
        if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
        req.append("Mcp-Protocol-Version: 2025-11-25\r\n");
        req.append("Accept: application/json, text/event-stream\r\n");
        req.append("Content-Type: application/json\r\n");
        req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 5000);
            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) out.write(bodyBytes);
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String firstLine = in.readLine();
            if (firstLine == null) return -1;
            return parseStatusCode(firstLine);
        }
    }

    private static int parseStatusCode(String statusLine) {
        if (statusLine == null) return -1;
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); }
            catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    private static String init() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"cors-test\",\"version\":\"1\"}}}";
    }

    // ── Origin-absent tests ───────────────────────────────────────────────

    @Test
    void noOrigin_noAuth_returns200() throws Exception {
        // Absent Origin = non-browser client → accepted without auth.
        assertEquals(200, postStatus(init(), null));
    }

    // ── Loopback origin tests ──────────────────────────────────────────────

    @Test
    void localhost_noAuth_returns200() throws Exception {
        assertEquals(200, postStatus(init(), "http://localhost"));
    }

    @Test
    void localhostWithPort_noAuth_returns200() throws Exception {
        assertEquals(200, postStatus(init(), "http://localhost:3000"));
        assertEquals(200, postStatus(init(), "http://localhost:5173"));
    }

    @Test
    void localhostIpv4_noAuth_returns200() throws Exception {
        assertEquals(200, postStatus(init(), "http://127.0.0.1"));
        assertEquals(200, postStatus(init(), "http://127.0.0.1:5173"));
        assertEquals(200, postStatus(init(), "http://127.0.0.1:3000"));
    }

    @Test
    void ipv6Loopback_noAuth_returns200() throws Exception {
        assertEquals(200, postStatus(init(), "http://[::1]"));
        assertEquals(200, postStatus(init(), "http://[::1]:8080"));
    }

    @Test
    void httpsLocalhost_noAuth_returns200() throws Exception {
        assertEquals(200, postStatus(init(), "https://localhost"));
        assertEquals(200, postStatus(init(), "https://localhost:8443"));
        assertEquals(200, postStatus(init(), "https://127.0.0.1"));
    }

    // ── Rejected origin tests ──────────────────────────────────────────────

    @Test
    void privateIpOrigin_rejectedWith403() throws Exception {
        assertEquals(403, postStatus(init(), "http://192.168.1.1:8080"));
        assertEquals(403, postStatus(init(), "http://10.0.0.1"));
        assertEquals(403, postStatus(init(), "http://172.16.0.1"));
    }

    @Test
    void publicOrigin_rejectedWith403() throws Exception {
        assertEquals(403, postStatus(init(), "http://example.com"));
        assertEquals(403, postStatus(init(), "https://example.com"));
    }

    @Test
    void zeroZeroZeroZero_rejectedWith403() throws Exception {
        // 0.0.0.0 is not loopback and should be rejected.
        assertEquals(403, postStatus(init(), "http://0.0.0.0"));
        assertEquals(403, postStatus(init(), "http://0.0.0.0:8080"));
    }

    // ── OPTIONS preflight tests ───────────────────────────────────────────

    @Test
    void optionsLocalhost_accepted() throws Exception {
        assertEquals(200, optionsStatus("http://localhost"));
        assertEquals(200, optionsStatus("http://localhost:3000"));
        assertEquals(200, optionsStatus("http://127.0.0.1"));
        assertEquals(200, optionsStatus("http://[::1]:8080"));
    }

    @Test
    void optionsNonLoopback_rejectedWith403() throws Exception {
        assertEquals(403, optionsStatus("http://example.com"));
        assertEquals(403, optionsStatus("http://192.168.1.1"));
    }

    @Test
    void optionsNoOrigin_returns200() throws Exception {
        // No Origin = non-browser preflight → 200, no CORS headers.
        assertEquals(200, optionsStatus(null));
    }

    // ── Auth independence tests ─────────────────────────────────────────────

    @Test
    void allowedOrigin_withInvalidBearer_returns401() throws Exception {
        String authUrl = startAuthTransport();
        assertEquals(401, authPostStatus(authUrl, init(), "wrong-secret", "http://localhost"),
                "Valid origin + wrong bearer → 401");
    }

    @Test
    void allowedOrigin_withValidBearer_returns200() throws Exception {
        String authUrl = startAuthTransport();
        assertEquals(200, authPostStatus(authUrl, init(), "secret", "http://localhost"),
                "Valid origin + correct bearer → 200");
    }

    @Test
    void rejectedOrigin_withValidBearer_returns403() throws Exception {
        // Origin check runs BEFORE auth check.
        String authUrl = startAuthTransport();
        assertEquals(403, authPostStatus(authUrl, init(), "secret", "http://example.com"),
                "Rejected origin → 403 even with valid bearer");
    }

    // ── Explicit allowlist tests ──────────────────────────────────────────

    @Test
    void explicitNonLoopbackOrigin_acceptedWhenInAllowlist() throws Exception {
        // Second transport with explicit allowlist for https://app.example.com
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());

        try (HttpTransportProvider t2 = new HttpTransportProvider(handler).port(0)
                .allowedOrigins(Collections.singleton("https://app.example.com"))
                .apiKey("secret")) {
            t2.start();
            String t2Url = t2.getUrl();

            // Non-loopback IN allowlist → accepted
            assertEquals(200, authPostStatus(t2Url, init(), "secret", "https://app.example.com"),
                    "Explicitly allowed non-loopback origin → 200");

            // Loopback IS STILL accepted alongside explicit allowlist
            assertEquals(200, authPostStatus(t2Url, init(), "secret", "http://localhost"),
                    "Loopback accepted alongside explicit allowlist → 200");

            // Non-loopback NOT in allowlist → rejected
            assertEquals(403, authPostStatus(t2Url, init(), "secret", "http://unknown.com"),
                    "Non-loopback not in allowlist → 403");
        }
    }

    @Test
    void emptyExplicitAllowlist_acceptsOnlyLoopback() throws Exception {
        // Explicit empty set: only loopback accepted
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());

        try (HttpTransportProvider t2 = new HttpTransportProvider(handler).port(0)
                .allowedOrigins(Collections.emptySet())) {
            t2.start();
            String t2Url = t2.getUrl();

            // Loopback with non-standard port → accepted
            assertEquals(200, postStatus(init(), "http://localhost:9999"),
                    "Loopback with non-standard port → 200");

            // Non-loopback → rejected
            assertEquals(403, postStatus(init(), "https://example.com"),
                    "Non-loopback rejected with empty explicit allowlist → 403");
        }
    }

    // ── 202 response on notification ───────────────────────────────────────

    @Test
    void notificationOnlyResult_returns202() throws Exception {
        // notifications/initialized has no response body → 202
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}";
        assertEquals(202, postStatus(body, "http://localhost"),
                "Notification with no body → 202");
    }

    // ── Case-insensitivity ─────────────────────────────────────────────────

    @Test
    void originCaseInsensitive() throws Exception {
        // Origins are compared case-insensitively
        assertEquals(200, postStatus(init(), "HTTP://LOCALHOST"));
        assertEquals(200, postStatus(init(), "Http://Localhost:3000"));
        assertEquals(403, postStatus(init(), "HTTP://EXAMPLE.COM"));
    }
}
