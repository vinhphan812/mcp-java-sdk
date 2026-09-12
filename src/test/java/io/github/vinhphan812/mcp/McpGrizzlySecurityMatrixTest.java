package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.transport.GrizzlyStreamableServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class McpGrizzlySecurityMatrixTest {
    static {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private GrizzlyStreamableServerTransportProvider transport;
    private String url;

    @BeforeEach
    void startServer() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().protocolVersion("2025-11-25").build());
        transport = new GrizzlyStreamableServerTransportProvider(handler).port(0).apiKey("secret");
        transport.start();
        url = transport.getUrl();
    }

    @AfterEach
    void stopServer() {
        transport.stop();
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        assertStatus(request("POST", "{", null, "secret", "application/json", "application/json, text/event-stream", null), 400);
    }

    @Test
    void rejectsUnsupportedMethod() throws Exception {
        assertStatus(request("PUT", "", null, "secret", null, null, null), 405);
    }

    @Test
    void rejectsMissingAndInvalidSession() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        assertStatus(request("POST", body, null, "secret", "application/json", "application/json, text/event-stream", null), 400);
        assertStatus(request("POST", body, "not-a-session", "secret", "application/json", "application/json, text/event-stream", null), 400);
    }

    @Test
    void rejectsWrongOrigin() throws Exception {
        assertStatus(request("POST", initialize(), null, "secret", "application/json", "application/json, text/event-stream", "http://evil.example"), 403);
    }

    @Test
    void rejectsUnsupportedProtocolVersion() throws Exception {
        assertStatus(request("POST", initialize(), null, "secret", "application/json", "application/json, text/event-stream", null, "2099-01-01"), 400);
    }

    @Test
    void rejectsWrongContentType() throws Exception {
        assertStatus(request("POST", initialize(), null, "secret", "text/plain", "application/json, text/event-stream", null), 415);
    }

    @Test
    void rejectsUnacceptableAccept() throws Exception {
        assertStatus(request("POST", initialize(), null, "secret", "application/json", "application/json", null), 406);
    }

    @Test
    void rejectsBodyOverConfiguredLimit() throws Exception {
        transport.stop();
        McpProtocolHandler handler = transport.getProtocolHandler();
        transport = new GrizzlyStreamableServerTransportProvider(handler).port(0).maxRequestBodyBytes(8).apiKey("secret");
        transport.start();
        url = transport.getUrl();
        assertStatus(request("POST", "123456789", null, "secret", "application/json", "application/json, text/event-stream", null), 413);
    }

    @Test
    void deletesSessionAndRejectsItAfterward() throws Exception {
        Result initialized = request("POST", initialize(), null, "secret", "application/json", "application/json, text/event-stream", null);
        assertEquals(200, initialized.status);
        assertNotNull(initialized.session);
        assertEquals(204, request("DELETE", "", initialized.session, "secret", null, null, null).status);
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        assertEquals(400, request("POST", body, initialized.session, "secret", "application/json", "application/json, text/event-stream", null).status);
    }

    @Test
    void acceptsValidBearerAuthentication() throws Exception {
        assertEquals(200, request("POST", initialize(), null, "secret", "application/json", "application/json, text/event-stream", null).status);
        assertEquals(401, request("POST", initialize(), null, "wrong", "application/json", "application/json, text/event-stream", null).status);
    }

    //noinspection SameReturnValue
    private String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    }

    private Result request(String method, String body, String session, String token,
                           String contentType, String accept, String origin) throws Exception {
        return request(method, body, session, token, contentType, accept, origin, null);
    }

    private Result request(String method, String body, String session, String token,
                           String contentType, String accept, String origin, String protocol) throws Exception {
        if (origin != null) {
            return rawRequestWithOrigin(method, body, session, token, contentType, accept, origin, protocol);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setDoOutput(true);
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
        if (accept != null) connection.setRequestProperty("Accept", accept);
        if (session != null) connection.setRequestProperty("Mcp-Session-Id", session);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (protocol != null) connection.setRequestProperty("Mcp-Protocol-Version", protocol);
        if (body != null && !body.isEmpty()) connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
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

    private Result rawRequestWithOrigin(String method, String body, String session, String token,
                                        String contentType, String accept, String origin, String protocol) throws Exception {
        URL requestUrl = new URL(url);
        try (Socket socket = new Socket(requestUrl.getHost(), requestUrl.getPort())) {
            String requestBody = body == null ? "" : body;
            StringBuilder request = new StringBuilder()
                    .append(method).append(" ").append(requestUrl.getPath()).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(requestUrl.getHost()).append(":").append(requestUrl.getPort()).append("\r\n")
                    .append("Connection: close\r\n");
            if (contentType != null) request.append("Content-Type: ").append(contentType).append("\r\n");
            if (accept != null) request.append("Accept: ").append(accept).append("\r\n");
            if (session != null) request.append("Mcp-Session-Id: ").append(session).append("\r\n");
            if (token != null) request.append("Authorization: Bearer ").append(token).append("\r\n");
            request.append("Origin: ").append(origin).append("\r\n");
            if (protocol != null) request.append("Mcp-Protocol-Version: ").append(protocol).append("\r\n");
            request.append("Content-Length: ").append(requestBody.getBytes(StandardCharsets.UTF_8).length).append("\r\n\r\n")
                    .append(requestBody);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String response = output.toString("UTF-8");
            int status = Integer.parseInt(response.substring(9, 12));
            return new Result(status, response, null);
        }
    }

    private void assertStatus(Result result, int expected) {
        assertEquals(expected, result.status, result.body);
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
