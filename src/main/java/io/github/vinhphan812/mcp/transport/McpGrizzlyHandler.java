package io.github.vinhphan812.mcp.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Grizzly Streamable HTTP adapter for the MCP protocol handler. */
public final class McpGrizzlyHandler extends HttpHandler {
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String AUTH_HEADER = "Authorization";
    private static final String DEFAULT_ENDPOINT = "/mcp";
    private static final String PROTOCOL_HEADER = "Mcp-Protocol-Version";
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    private static final Set<String> DEFAULT_ALLOWED_ORIGINS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("http://localhost", "http://127.0.0.1", "https://localhost")));
    private static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    private static final int MAX_SSE_CONNECTIONS = 4;
    private static final Semaphore SSE_CONNECTIONS = new Semaphore(MAX_SSE_CONNECTIONS);
    private static final Gson GSON = new Gson();
    private static final Pattern CR_LF = Pattern.compile("\r\n|[\r\n]");

    /**
     * Removes carriage-return and line-feed from a header value to prevent
     * HTTP response-splitting / CRLF injection.
     *
     * @param value raw header value
     * @return sanitized value safe for use in a response header
     */
    private static String sanitizeHeaderValue(String value) {
        if (value == null) return null;
        return CR_LF.matcher(value).replaceAll("");
    }

    /** Escapes control characters in SSE data to prevent stream injection.
     * @param data raw data content
     * @return escaped data safe for SSE output
     */
    private static String escapeSseData(String data) {
        if (data == null) return "";
        return CR_LF.matcher(data).replaceAll("");
    }

    private final McpProtocolHandler handler;
    private final String endpoint;
    private final Supplier<String> apiKeySupplier;
    private final Set<String> allowedOrigins;
    private final int maxRequestBodyBytes;

    /** Creates handler with default endpoint and request limits.
     * @param handler protocol handler serving requests */
    public McpGrizzlyHandler(McpProtocolHandler handler) {
        this(handler, DEFAULT_ENDPOINT, null, DEFAULT_ALLOWED_ORIGINS,
                DEFAULT_MAX_REQUEST_BODY_BYTES);
    }

    /** Creates handler with endpoint and optional API key supplier.
     * @param handler protocol handler serving requests
     * @param endpoint HTTP endpoint path
     * @param apiKeySupplier optional bearer key supplier */
    public McpGrizzlyHandler(McpProtocolHandler handler, String endpoint,
                             Supplier<String> apiKeySupplier) {
        this(handler, endpoint, apiKeySupplier, DEFAULT_ALLOWED_ORIGINS,
                DEFAULT_MAX_REQUEST_BODY_BYTES);
    }

    /** Creates handler with explicit transport security and request limits.
     * @param handler protocol handler serving requests
     * @param endpoint HTTP endpoint path
     * @param apiKeySupplier optional bearer key supplier
     * @param allowedOrigins accepted browser origins
     * @param maxRequestBodyBytes maximum POST body size */
    public McpGrizzlyHandler(McpProtocolHandler handler, String endpoint,
                             Supplier<String> apiKeySupplier, Set<String> allowedOrigins,
                             int maxRequestBodyBytes) {
        if (handler == null) throw new IllegalArgumentException("handler cannot be null");
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("endpoint cannot be empty");
        }
        if (allowedOrigins == null) throw new IllegalArgumentException("allowedOrigins cannot be null");
        if (maxRequestBodyBytes <= 0) throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        Set<String> normalizedOrigins = new HashSet<>();
        for (String origin : allowedOrigins) {
            if (origin == null || origin.trim().isEmpty()) {
                throw new IllegalArgumentException("allowedOrigins cannot contain blank values");
            }
            normalizedOrigins.add(origin.trim().toLowerCase(Locale.ROOT));
        }
        String normalized = endpoint.trim();
        this.handler = handler;
        this.endpoint = normalized.startsWith("/") ? normalized : "/" + normalized;
        this.apiKeySupplier = apiKeySupplier;
        this.allowedOrigins = Collections.unmodifiableSet(normalizedOrigins);
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    @Override
    public void service(Request request, Response response) throws Exception {
        String uri = request.getRequestURI();
        if (!endpoint.equals(uri)) {
            writeError(response, 404, "Unknown MCP endpoint");
            return;
        }
        String method = request.getMethod().toString().toUpperCase(Locale.ROOT);
        switch (method) {
            case "POST":
                handlePost(request, response);
                break;
            case "GET":
                handleGet(request, response);
                break;
            case "DELETE":
                handleDelete(request, response);
                break;
            default:
                writeError(response, 405, "Method not allowed");
                break;
        }
    }

    private boolean validContentType(Request request) {
        String value = request.getHeader("Content-Type");
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private boolean acceptsPostResponse(Request request) {
        String value = request.getHeader("Accept");
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") && lower.contains("text/event-stream");
    }

    private boolean validProtocolHeader(Request request) {
        return handler.supportsProtocolVersion(request.getHeader(PROTOCOL_HEADER));
    }

    private boolean requiresSession(String method) {
        return !"initialize".equals(method)
                && !"notifications/initialized".equals(method)
                && !"ping".equals(method);
    }

    private boolean isInvalidOrigin(Request request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.trim().isEmpty()) return false;
        for (String allowed : allowedOrigins) if (allowed.equalsIgnoreCase(origin.trim())) return false;
        return true;
    }

    private void handlePost(Request request, Response response) throws IOException {
        if (isInvalidOrigin(request)) {
            writeError(response, 403, "Forbidden Origin");
            return;
        }
        if (!validProtocolHeader(request)) {
            writeError(response, 400, "Unsupported MCP protocol version");
            return;
        }
        if (!validContentType(request)) {
            writeError(response, 415, "Content-Type must be application/json");
            return;
        }
        if (!acceptsPostResponse(request)) {
            writeError(response, 406, "Accept must include application/json and text/event-stream");
            return;
        }
        String body;
        try {
            body = readBody(request.getInputStream());
        } catch (RequestBodyTooLargeException e) {
            writeError(response, 413, "Request body exceeds configured maximum");
            return;
        }
        if (body.trim().isEmpty()) {
            writeError(response, 400, "Empty request body");
            return;
        }
        if (isUnauthorized(request)) {
            writeError(response, 401, "Unauthorized");
            return;
        }
        JsonObject requestObject;
        try {
            requestObject = GSON.fromJson(body, JsonObject.class);
        } catch (RuntimeException e) {
            writeError(response, 400, "Malformed JSON request body");
            return;
        }
        if (requestObject == null || !requestObject.has("method")
                || !requestObject.get("method").isJsonPrimitive()
                || !requestObject.get("method").getAsJsonPrimitive().isString()) {
            writeError(response, 400, "Invalid JSON-RPC request body");
            return;
        }
        String method = requestObject.get("method").getAsString();
        String sessionId = request.getHeader(SESSION_HEADER);
        if (requiresSession(method) && !handler.hasSession(sessionId)) {
            writeError(response, 400, "Missing or invalid Mcp-Session-Id header");
            return;
        }
        McpProtocolHandler.McpResponse result = handler.handleRequestResponse(body, sessionId);
        if (result.getBody() == null) {
            response.setStatus(202);
            return;
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (result.getSessionId() != null)
            response.setHeader(SESSION_HEADER, sanitizeHeaderValue(result.getSessionId()));
        response.setStatus(200);
        response.getWriter().write(result.getBody());
    }

    static Long parseLastEventId(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        try {
            long id = Long.parseLong(normalized);
            if (id < 0) throw new NumberFormatException("negative event id");
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer", e);
        }
    }

    static String formatSseEvent(long id, String event, String data) {
        return "id: " + id + "\nevent: " + event + "\ndata: " + data + "\n\n";
    }

    private void handleGet(Request request, Response response) throws IOException {
        if (isInvalidOrigin(request)) {
            writeError(response, 403, "Forbidden Origin");
            return;
        }
        if (isUnauthorized(request)) {
            writeError(response, 401, "Unauthorized");
            return;
        }
        String sessionId = request.getHeader(SESSION_HEADER);
        if (sessionId == null || !handler.hasSession(sessionId)) {
            writeError(response, 400, "Missing or invalid Mcp-Session-Id header");
            return;
        }
        Long lastEventId;
        try {
            lastEventId = parseLastEventId(request.getHeader(LAST_EVENT_ID_HEADER));
        } catch (IllegalArgumentException e) {
            writeError(response, 400, e.getMessage());
            return;
        }
        if (lastEventId != null) {
            // Replay missed events and continue normal polling.
            String missed = handler.getMissedEvents(sessionId, lastEventId);
            response.getWriter().write(missed);
            response.getWriter().flush();
        }
        if (!SSE_CONNECTIONS.tryAcquire()) {
            writeError(response, 429, "Too many active SSE connections");
            return;
        }
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("Connection", "keep-alive");
            response.setStatus(200);
            long nextEventId = 1L;
            response.getWriter().write(formatSseEvent(nextEventId++, "connected",
                    "{\"sessionId\":\"" + escapeSseData(sessionId) + "\"}"));
            response.getWriter().flush();
            long start = System.currentTimeMillis();
            // Intentional event-polling loop: wake every second to check for new notifications.
            // Timeout is 5 minutes (300000 ms). Not a busy-spin; Thread.sleep() yields CPU.
            //noinspection BusyWait
            while (!Thread.currentThread().isInterrupted() && handler.hasSession(sessionId)
                    && System.currentTimeMillis() - start < 300000L) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                String notification;
                boolean sent = false;
                while ((notification = handler.pollPendingNotification(sessionId)) != null) {
                    response.getWriter().write(formatSseEvent(nextEventId++, "message", escapeSseData(notification)));
                    sent = true;
                }
                if (!sent) response.getWriter().write(formatSseEvent(nextEventId++, "ping", "{}"));
                response.getWriter().flush();
            }
        } finally {
            SSE_CONNECTIONS.release();
        }
    }

    private void handleDelete(Request request, Response response) throws IOException {
        if (isInvalidOrigin(request)) {
            writeError(response, 403, "Forbidden Origin");
            return;
        }
        if (isUnauthorized(request)) {
            writeError(response, 401, "Unauthorized");
            return;
        }
        String sessionId = request.getHeader(SESSION_HEADER);
        if (sessionId == null || !handler.hasSession(sessionId)) {
            writeError(response, 404, "Unknown MCP session");
            return;
        }
        handler.terminateSession(sessionId);
        response.setStatus(204);
    }

    private boolean isUnauthorized(Request request) {
        String configured = apiKeySupplier == null ? null : apiKeySupplier.get();
        if (configured == null || configured.trim().isEmpty()) return false;
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return true;
        byte[] expected = configured.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actual = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return !MessageDigest.isEqual(expected, actual);
    }

    private String readBody(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        StringBuilder body = new StringBuilder();
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxRequestBodyBytes) throw new RequestBodyTooLargeException();
            body.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static final class RequestBodyTooLargeException extends IOException {
        private RequestBodyTooLargeException() {
        }
    }


    private static void writeError(Response response, int status, String message) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(status);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", status);
        error.put("message", message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", null);
        payload.put("error", error);
        response.getWriter().write(GSON.toJson(payload));
    }
}
