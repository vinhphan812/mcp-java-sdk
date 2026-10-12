package io.github.vinhphan812.mcp.transport;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.security.McpSecurityContext;
import io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthProtectedResourceIntegrationTest {
    private HttpTransportProvider transport;
    private String url;

    @BeforeEach
    void startServer() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), McpServerConfig.builder().build());
        ProtectedResourceMetadata metadata = new ProtectedResourceMetadata(
                "https://resource.example.test/mcp", Collections.singletonList("https://issuer.example.test"),
                Arrays.asList("mcp:read", "mcp:write"));
        transport = new HttpTransportProvider(handler).port(0)
                .protectedResourceMetadata(metadata)
                .requiredBearerScopes(Collections.singleton("mcp:read"))
                .tokenVerifier((token, context) -> {
                    if (!"valid".equals(token) || !"https://resource.example.test/mcp".equals(context.getResource())) {
                        return Optional.empty();
                    }
                    return Optional.of(new McpSecurityContext("local-user",
                            new LinkedHashSet<String>(Arrays.asList("mcp:read", "mcp:write")), new LinkedHashMap<String, Object>()));
                });
        transport.start();
        url = transport.getUrl();
    }

    @AfterEach
    void stopServer() { if (transport != null) transport.stop(); }

    @Test
    void metadataIsPublishedAtRfc9728Path() throws Exception {
        RawResponse response = request("GET", "/.well-known/oauth-protected-resource/mcp", null);
        assertEquals(200, response.status);
        assertTrue(response.body.contains("\"resource\":\"https://resource.example.test/mcp\""));
        assertTrue(response.body.contains("\"authorization_servers\":[\"https://issuer.example.test\"]"));
    }

    @Test
    void missingTokenReturnsRfc9728BearerChallenge() throws Exception {
        RawResponse response = request("POST", "/mcp", null);
        assertEquals(401, response.status);
        assertTrue(response.headers.get("www-authenticate").contains("Bearer error=\"invalid_token\""));
        assertTrue(response.headers.get("www-authenticate").contains("resource_metadata=\"/.well-known/oauth-protected-resource/mcp\""));
    }

    @Test
    void invalidTokenReturnsUnauthorizedChallenge() throws Exception {
        RawResponse response = request("POST", "/mcp", "wrong");
        assertEquals(401, response.status);
        assertTrue(response.headers.get("www-authenticate").contains("error=\"invalid_token\""));
    }

    @Test
    void validTokenIsAcceptedByVerifier() throws Exception {
        RawResponse response = request("POST", "/mcp", "valid");
        assertEquals(200, response.status);
    }

    @Test
    void validTokenWithoutRequiredScopeGetsInsufficientScopeChallenge() throws Exception {
        transport.stop();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), McpServerConfig.builder().build());
        transport = new HttpTransportProvider(handler).port(0)
                .protectedResourceMetadata(new ProtectedResourceMetadata("https://resource.example.test/mcp",
                        Collections.singletonList("https://issuer.example.test"), Collections.singletonList("mcp:read")))
                .requiredBearerScopes(Collections.singleton("mcp:read"))
                .tokenVerifier((token, context) -> Optional.of(new McpSecurityContext("local-user",
                        Collections.<String>emptySet(), new LinkedHashMap<String, Object>())))
                ;
        transport.start();
        url = transport.getUrl();
        RawResponse response = request("POST", "/mcp", "valid");
        assertEquals(403, response.status);
        assertTrue(response.headers.get("www-authenticate").contains("error=\"insufficient_scope\""));
    }

    private RawResponse request(String method, String path, String bearer) throws Exception {
        URL parsed = new URL(url);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"oauth-test\",\"version\":\"1\"}}}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(parsed.getHost(), parsed.getPort()), 5000);
            StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ")
                    .append(parsed.getHost()).append(':').append(parsed.getPort()).append("\r\nConnection: close\r\n");
            if (bearer != null) request.append("Authorization: Bearer ").append(bearer).append("\r\n");
            if ("POST".equals(method)) request.append("Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\nMcp-Protocol-Version: 2025-11-25\r\nContent-Length: ").append(payload.length).append("\r\n");
            request.append("\r\n");
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if ("POST".equals(method)) output.write(payload);
            output.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = input.readLine();
            RawResponse response = new RawResponse(Integer.parseInt(statusLine.split(" ")[1]));
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) response.headers.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 1).trim());
            }
            StringBuilder responseBody = new StringBuilder();
            while ((line = input.readLine()) != null) responseBody.append(line);
            response.body = responseBody.toString();
            return response;
        }
    }

    private static final class RawResponse {
        private final int status;
        private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        private String body = "";
        private RawResponse(int status) { this.status = status; }
    }
}
