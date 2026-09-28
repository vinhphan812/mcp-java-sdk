package io.github.vinhphan812.mcp.transport;

import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.utils.McpError;
import io.github.vinhphan812.mcp.api.utils.McpGson;
import io.github.vinhphan812.mcp.api.utils.McpHttpHeaders;
import io.github.vinhphan812.mcp.api.utils.McpJsonRpc;
import io.github.vinhphan812.mcp.api.utils.McpMethodNames;
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

/**
 * Transport handler for the MCP server over HTTP.
 *
 * <p>Implements HTTP routing for the MCP JSON-RPC protocol: {@code POST /endpoint}
 * for JSON-RPC request/response, {@code GET /endpoint} for server-sent event
 * (SSE) notification streaming, and {@code DELETE /endpoint} for session teardown.
 *
 * <p>Authentication is optional and configured via an API key supplier; when
 * supplied, the {@code Authorization: Bearer <key>} header is validated per-request.
 * CORS origin checking, request body size limits, and X-Forwarded-For trust
 * are also configurable.
 *
 * <p>SSE streaming uses a semaphore to cap concurrent connections; when the
 * limit is exhausted a {@code 429 Too Many Requests} response is returned.
 *
 * <p>Use the {@link Builder} to construct a configured instance.
 */
public final class McpHttpHandler extends HttpHandler {

    private static final int DEFAULT_MAX_SSE = 4;

    // ── fields ────────────────────────────────────────────────────────────────
    private final McpProtocolHandler handler;
    private final String endpoint;
    private final Supplier<String> apiKeySupplier;
    private final Set<String> allowedOrigins;
    private final int maxRequestBodyBytes;
    private final Semaphore sseConnections;
    private final boolean trustXForwardedFor;
    private final TransportMode transportMode;

    // ── Builder ──────────────────────────────────────────────────────────────

    /** Builds a configured {@link McpHttpHandler}. */
    public static final class Builder {
        private final McpProtocolHandler handler;
        private String endpoint = "/mcp";
        private Supplier<String> apiKeySupplier;
        private Set<String> allowedOrigins = Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList("http://localhost", "http://127.0.0.1", "https://localhost")));
        private int maxRequestBodyBytes = 1024 * 1024;
        private int maxSseConnections = DEFAULT_MAX_SSE;
        private boolean trustXForwardedFor;
        private TransportMode transportMode = TransportMode.AUTO;

        public Builder(McpProtocolHandler handler) {
            if (handler == null) throw new IllegalArgumentException("handler cannot be null");
            this.handler = handler;
        }

        public Builder endpoint(String v) {
            if (v == null || v.trim().isEmpty())
                throw new IllegalArgumentException("endpoint cannot be empty");
            this.endpoint = v.trim().startsWith("/") ? v.trim() : "/" + v.trim();
            return this;
        }

        public Builder apiKeySupplier(Supplier<String> s) {
            this.apiKeySupplier = s;
            return this;
        }

        public Builder apiKey(String v) {
            this.apiKeySupplier = v == null ? null : () -> v;
            return this;
        }

        public Builder trustXForwardedFor(boolean v) {
            this.trustXForwardedFor = v;
            return this;
        }

        public Builder transportMode(TransportMode m) {
            this.transportMode = Objects.requireNonNull(m);
            return this;
        }

        public Builder allowedOrigins(Set<String> v) {
            if (v == null) throw new IllegalArgumentException("allowedOrigins cannot be null");
            Set<String> n = new HashSet<>();
            for (String o : v) {
                if (o == null || o.trim().isEmpty())
                    throw new IllegalArgumentException("allowedOrigins cannot contain blank values");
                n.add(o.trim().toLowerCase(Locale.ROOT));
            }
            this.allowedOrigins = Collections.unmodifiableSet(n);
            return this;
        }

        public Builder maxRequestBodyBytes(int v) {
            if (v <= 0) throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
            this.maxRequestBodyBytes = v;
            return this;
        }

        public Builder maxSseConnections(int v) {
            if (v <= 0) throw new IllegalArgumentException("maxSseConnections must be positive");
            this.maxSseConnections = v;
            return this;
        }

        public McpHttpHandler build() {
            return new McpHttpHandler(this);
        }
    }

    // ── constructors ──────────────────────────────────────────────────────

    /** Single canonical constructor accepting a fully-populated builder. */
    public McpHttpHandler(Builder b) {
        this.handler = b.handler;
        this.endpoint = b.endpoint;
        this.apiKeySupplier = b.apiKeySupplier;
        this.allowedOrigins = b.allowedOrigins;
        this.maxRequestBodyBytes = b.maxRequestBodyBytes;
        this.sseConnections = new Semaphore(b.maxSseConnections);
        this.trustXForwardedFor = b.trustXForwardedFor;
        this.transportMode = b.transportMode;
    }

    // Convenience delegating constructors — preserve API compatibility
    public McpHttpHandler(McpProtocolHandler h) {
        this(new Builder(h));
    }

    public McpHttpHandler(McpProtocolHandler h, String e, Supplier<String> s) {
        this(new Builder(h).endpoint(e).apiKeySupplier(s));
    }

    public McpHttpHandler(McpProtocolHandler h, String e, Supplier<String> s,
                          Set<String> ao, int maxBody) {
        this(new Builder(h).endpoint(e).apiKeySupplier(s).allowedOrigins(ao).maxRequestBodyBytes(maxBody));
    }

    public McpHttpHandler(McpProtocolHandler h, String e, Supplier<String> s,
                          Set<String> ao, int maxBody, boolean trustFwd) {
        this(new Builder(h).endpoint(e).apiKeySupplier(s).allowedOrigins(ao)
                .maxRequestBodyBytes(maxBody).trustXForwardedFor(trustFwd));
    }

    public McpHttpHandler(McpProtocolHandler h, String e, Supplier<String> s,
                          Set<String> ao, int maxBody, int maxSse) {
        this(new Builder(h).endpoint(e).apiKeySupplier(s).allowedOrigins(ao)
                .maxRequestBodyBytes(maxBody).maxSseConnections(maxSse));
    }

    public McpHttpHandler(McpProtocolHandler h, String e, Supplier<String> s,
                          Set<String> ao, int maxBody, int maxSse, boolean trustFwd) {
        this(new Builder(h).endpoint(e).apiKeySupplier(s).allowedOrigins(ao)
                .maxRequestBodyBytes(maxBody).maxSseConnections(maxSse)
                .trustXForwardedFor(trustFwd));
    }

    public McpHttpHandler(McpProtocolHandler h, String e, Supplier<String> s,
                          Set<String> ao, int maxBody, int maxSse,
                          boolean trustFwd, TransportMode mode) {
        this(new Builder(h).endpoint(e).apiKeySupplier(s).allowedOrigins(ao)
                .maxRequestBodyBytes(maxBody).maxSseConnections(maxSse)
                .trustXForwardedFor(trustFwd).transportMode(mode));
    }

    // ── routing ────────────────────────────────────────────────────────────
    @Override
    public void service(Request request, Response response) throws Exception {
        String uri = request.getRequestURI();
        if (!endpoint.equals(uri)) {
            writeError(response, 404, "Unknown MCP endpoint");
            return;
        }
        switch (request.getMethod().toString().toUpperCase(Locale.ROOT)) {
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

    // ── shared validation ────────────────────────────────────────────────

    /** Returns true on pass; writes a 4xx error and returns false on failure. */
    private boolean validateRequest(Request request, Response response) throws IOException {
        if (isInvalidOrigin(request)) {
            writeError(response, 403, "Forbidden Origin");
            return false;
        }
        if (isUnauthorized(request)) {
            writeError(response, 401, "Unauthorized");
            return false;
        }
        return true;
    }

    private boolean isInvalidOrigin(Request request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.trim().isEmpty()) return false;
        for (String a : allowedOrigins) if (a.equalsIgnoreCase(origin.trim())) return false;
        return true;
    }

    private boolean isUnauthorized(Request request) {
        String key = apiKeySupplier == null ? null : apiKeySupplier.get();
        if (key == null || key.trim().isEmpty()) return false;
        String hdr = request.getHeader(McpHttpHeaders.AUTH);
        if (hdr == null || !hdr.regionMatches(true, 0, "Bearer ", 0, 7)) return true;
        byte[] exp = key.trim().getBytes(StandardCharsets.UTF_8);
        byte[] act = hdr.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return !MessageDigest.isEqual(exp, act);
    }

    private boolean requiresSession(String method) {
        return !McpMethodNames.INITIALIZE.equals(method)
                && !McpMethodNames.NOTIF_INITIALIZED.equals(method)
                && !McpMethodNames.PING.equals(method);
    }

    private String getClientIp(Request request) {
        if (trustXForwardedFor) {
            String fwd = request.getHeader(McpHttpHeaders.X_FORWARDED_FOR);
            if (fwd != null && !fwd.trim().isEmpty()) return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // ── SSE helpers ───────────────────────────────────────────────────────
    private static String escapeSseData(String s) {
        return SseEventFormatter.escapeSseData(s);
    }

    /** SSE ping frame. */
    private static String ssePing() { return SseEventFormatter.ping(); }

    /** SSE connected frame. */
    private static String sseConnected(String sid) { return SseEventFormatter.connected(sid); }

    /** SSE message frame. */
    private static String sseMessage(String id, String body) { return SseEventFormatter.message(id, body); }

    /** Sets SSE response headers. Call before any body write. */
    private void setSseHeaders(Response response, boolean keepAlive) {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", keepAlive ? "keep-alive" : "close");
    }

    static Long parseLastEventId(String v) {
        if (v == null || v.trim().isEmpty()) return null;
        try {
            long id = Long.parseLong(v.trim());
            if (id < 0) throw new NumberFormatException("negative");
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer", e);
        }
    }

    // ── POST ──────────────────────────────────────────────────────────────
    private void handlePost(Request request, Response response) throws IOException {
        if (!validateRequest(request, response)) return;

        if (!validContentType(request)) {
            writeError(response, 415, "Content-Type must be application/json");
            return;
        }
        if (!validProtocolHeader(request)) {
            writeError(response, 400, "Unsupported MCP protocol version");
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

        JsonObject req;
        try {
            req = McpGson.get().fromJson(body, JsonObject.class);
        } catch (RuntimeException e) {
            writeError(response, 400, "Malformed JSON request body");
            return;
        }

        if (req == null || !req.has("method") || !req.get("method").isJsonPrimitive()
                || !req.get("method").getAsJsonPrimitive().isString()) {
            writeError(response, 400, "Invalid JSON-RPC request body");
            return;
        }

        String method = req.get("method").getAsString();
        String sessionId = request.getHeader(McpHttpHeaders.SESSION);
        String clientIp = getClientIp(request);

        if (requiresSession(method) && !handler.hasSession(sessionId)) {
            writeError(response, 400, "Missing or invalid Mcp-Session-Id header");
            return;
        }

        McpProtocolHandler.McpResponse result = handler.handleRequestResponse(body, sessionId, clientIp);

        if (result.getBody() == null) {
            response.setStatus(202);
            return;
        }

        // Server-driven SSE streaming on POST
        if (isModernClient(request) && sessionId != null && handler.hasPendingNotifications(sessionId)) {
            handlePostStreaming(response, result);
            return;
        }

        // Normal JSON response
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (result.getSessionId() != null) response.setHeader(McpHttpHeaders.SESSION, result.getSessionId());
        response.setStatus(200);
        response.getWriter().write(result.getBody());
    }

    private boolean validContentType(Request request) {
        String v = request.getHeader("Content-Type");
        return v != null && v.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private boolean acceptsPostResponse(Request request) {
        String v = request.getHeader("Accept");
        if (v == null) return false;
        String l = v.toLowerCase(Locale.ROOT);
        return l.contains("application/json") && l.contains("text/event-stream");
    }

    private boolean validProtocolHeader(Request request) {
        return handler.supportsProtocolVersion(request.getHeader(McpHttpHeaders.PROTOCOL));
    }

    // ── POST streaming ─────────────────────────────────────────────────────

    /** POST response as SSE: initial JSON then live notification polling. */
    private void handlePostStreaming(Response response, McpProtocolHandler.McpResponse result) throws IOException {
        if (!sseConnections.tryAcquire()) {
            writeError(response, McpError.httpTooManyRequests("Too many active SSE connections"));
            return;
        }
        String sessionId = result.getSessionId();
        try {
            setSseHeaders(response, true);
            response.setStatus(200);
            if (sessionId != null) response.setHeader(McpHttpHeaders.SESSION, sessionId);
            // First event: the JSON-RPC response
            response.getWriter().write(sseMessage("0", result.getBody()));
            response.getWriter().flush();
            // Live polling loop
            runSsePollingLoop(response, sessionId);
        } catch (IOException e) { /* client disconnected */ }
        finally { sseConnections.release(); }
    }

    // ── SSE polling loop (shared by POST streaming and GET legacy) ─────────
    /** Live SSE polling: send pending events or ping every second, up to 5 minutes. */
    private void runSsePollingLoop(Response response, String sessionId) throws IOException {
        long start = System.currentTimeMillis();
        //noinspection BusyWait
        while (!Thread.currentThread().isInterrupted() && handler.hasSession(sessionId)
                && System.currentTimeMillis() - start < 300_000L) {
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
            String line; boolean sent = false;
            while ((line = handler.pollSseEvent(sessionId)) != null) {
                response.getWriter().write(line); sent = true;
            }
            if (!sent) response.getWriter().write(ssePing());
            response.getWriter().flush();
        }
    }

    // ── transport mode detection ──────────────────────────────────────────
    private boolean isModernClient(Request request) {
        if (transportMode == TransportMode.AUTO) {
            String ver = request.getHeader(McpHttpHeaders.PROTOCOL);
            if (ver != null && handler.supportsProtocolVersion(ver)) return true;
            String acc = request.getHeader("Accept");
            if (acc != null && !acc.toLowerCase(Locale.ROOT).contains("text/event-stream")) return true;
            return acc == null;
        }
        return transportMode == TransportMode.STREAMABLE_HTTP;
    }

    private TransportMode effectiveMode(Request request) {
        return transportMode == TransportMode.AUTO
                ? (isModernClient(request) ? TransportMode.STREAMABLE_HTTP : TransportMode.HTTP_SSE)
                : transportMode;
    }

    // ── GET ───────────────────────────────────────────────────────────────
    private void handleGet(Request request, Response response) throws IOException {
        if (!validateRequest(request, response)) return;
        String sessionId = request.getHeader(McpHttpHeaders.SESSION);
        if (sessionId == null || !handler.hasSession(sessionId)) {
            writeError(response, 400, "Missing or invalid Mcp-Session-Id header");
            return;
        }
        Long lastEventId;
        try {
            lastEventId = parseLastEventId(request.getHeader(McpHttpHeaders.LAST_EVENT_ID));
        } catch (IllegalArgumentException e) {
            writeError(response, 400, e.getMessage());
            return;
        }

        if (effectiveMode(request) == TransportMode.STREAMABLE_HTTP) {
            handleGetReplayOnly(response, sessionId, lastEventId);
            return;
        }

        // Legacy HTTP+SSE: long-lived stream
        if (!sseConnections.tryAcquire()) {
            writeError(response, McpError.httpTooManyRequests("Too many active SSE connections"));
            return;
        }
        try {
            setSseHeaders(response, true);
            response.setStatus(200);
            if (lastEventId != null) response.getWriter().write(handler.getMissedEvents(sessionId, lastEventId));
            response.getWriter().write(sseConnected(sessionId));
            response.getWriter().flush();
            runSsePollingLoop(response, sessionId);
        } catch (IOException e) { /* client disconnected */ }
        finally { sseConnections.release(); }
    }

    private void handleGetReplayOnly(Response response, String sessionId, Long lastEventId) throws IOException {
        setSseHeaders(response, false);
        response.setStatus(200);
        if (lastEventId != null) response.getWriter().write(handler.getMissedEvents(sessionId, lastEventId));
        response.getWriter().write(sseConnected(sessionId));
        response.getWriter().flush();
    }

    // ── DELETE ──────────────────────────────────────────────────────────
    private void handleDelete(Request request, Response response) throws IOException {
        if (!validateRequest(request, response)) return;
        String sessionId = request.getHeader(McpHttpHeaders.SESSION);
        if (sessionId == null || !handler.hasSession(sessionId)) {
            writeError(response, 404, "Unknown MCP session");
            return;
        }
        handler.terminateSession(sessionId);
        response.setStatus(204);
    }

    // ── body reading ──────────────────────────────────────────────────────
    private String readBody(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        StringBuilder sb = new StringBuilder();
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxRequestBodyBytes) throw new RequestBodyTooLargeException();
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static final class RequestBodyTooLargeException extends IOException {
        private RequestBodyTooLargeException() {
        }
    }

    // ── error helpers ────────────────────────────────────────────────────
    private static void writeError(Response r, int status, String msg) throws IOException {
        writeError(r, status, msg, null, null, null);
    }

    /** Writes an HTTP error using the error map returned by {@link McpError}. */
    @SuppressWarnings("unchecked")
    private static void writeError(Response r, Map<String, Object> error) throws IOException {
        int status = (Integer) error.get("code");
        String message = (String) error.get("message");
        r.setContentType("application/json");
        r.setCharacterEncoding("UTF-8");
        r.setStatus(status);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", McpJsonRpc.VERSION);
        payload.put("id", null);
        payload.put("error", error);
        r.getWriter().write(McpGson.get().toJson(payload));
    }

    private static void writeRateLimitedError(Response r, int status, String msg,
                                              int limit, int remaining, long reset) throws IOException {
        writeError(r, status, msg, limit, remaining, reset);
    }

    private static void writeError(Response r, int status, String msg,
                                   Integer limit, Integer remaining, Long reset) throws IOException {
        r.setContentType("application/json");
        r.setCharacterEncoding("UTF-8");
        r.setStatus(status);
        if (status == 429 && limit != null) {
            r.setHeader(McpHttpHeaders.RATE_LIMIT_LIMIT, String.valueOf(limit));
            r.setHeader(McpHttpHeaders.RATE_LIMIT_REMAINING, String.valueOf(remaining));
            r.setHeader(McpHttpHeaders.RATE_LIMIT_RESET, String.valueOf(reset));
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", status);
        error.put("message", msg);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", McpJsonRpc.VERSION);
        payload.put("id", null);
        payload.put("error", error);
        r.getWriter().write(McpGson.get().toJson(payload));
    }
}
