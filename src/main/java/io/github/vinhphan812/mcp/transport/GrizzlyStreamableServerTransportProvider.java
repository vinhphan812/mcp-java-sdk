package io.github.vinhphan812.mcp.transport;

import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;

/** Owns the lifecycle of a Grizzly MCP Streamable HTTP server. */
public final class GrizzlyStreamableServerTransportProvider implements AutoCloseable {
    private static final String LISTENER_NAME = "mcp-http";
    private McpProtocolHandler handler;
    private String host = "127.0.0.1";
    private int port = 3011;
    private String endpoint = "/mcp";
    private String apiKey;
    private java.util.Set<String> allowedOrigins = new java.util.HashSet<>(java.util.Arrays.asList(
            "http://localhost", "http://127.0.0.1", "https://localhost"));
    private int maxRequestBodyBytes = 1024 * 1024;
    private HttpServer server;

    public GrizzlyStreamableServerTransportProvider(McpProtocolHandler handler) {
        if (handler == null) throw new IllegalArgumentException("handler cannot be null");
        this.handler = handler;
    }

    public GrizzlyStreamableServerTransportProvider host(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("host cannot be empty");
        host = value.trim(); return this;
    }

    public GrizzlyStreamableServerTransportProvider port(int value) {
        if (value < 0 || value > 65535) throw new IllegalArgumentException("port must be between 0 and 65535");
        port = value; return this;
    }

    public GrizzlyStreamableServerTransportProvider endpoint(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("endpoint cannot be empty");
        endpoint = value.trim().startsWith("/") ? value.trim() : "/" + value.trim(); return this;
    }

    /** Configure accepted browser origins. Values are normalized case-insensitively. */
    public GrizzlyStreamableServerTransportProvider allowedOrigins(java.util.Set<String> values) {
        if (values == null) throw new IllegalArgumentException("allowedOrigins cannot be null");
        allowedOrigins = new java.util.HashSet<>(values);
        return this;
    }

    /** Limit POST request bodies in bytes. */
    public GrizzlyStreamableServerTransportProvider maxRequestBodyBytes(int value) {
        if (value <= 0) throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        maxRequestBodyBytes = value;
        return this;
    }

    /** Enable Bearer authentication. Keep the key outside source control. */
    public GrizzlyStreamableServerTransportProvider apiKey(String value) {
        apiKey = value; return this;
    }

    public synchronized void start() throws IOExceptionUnchecked {
        if (isRunning()) return;
        try {
            McpGrizzlyHandler httpHandler = new McpGrizzlyHandler(handler, endpoint, () -> apiKey,
                    allowedOrigins, maxRequestBodyBytes);
            server = new HttpServer();
            server.addListener(new NetworkListener(LISTENER_NAME, host, port));
            server.getServerConfiguration().addHttpHandler(httpHandler, endpoint);
            server.start();
        } catch (Exception e) {
            server = null;
            throw new IOExceptionUnchecked("Unable to start MCP Grizzly transport", e);
        }
    }

    public synchronized void stop() {
        if (server != null) server.shutdownNow();
        server = null;
        handler.closeAllSessions();
    }

    @Override public void close() { stop(); }
    public synchronized boolean isRunning() { return server != null && server.isStarted(); }
    public synchronized int getActualPort() { return isRunning() ? server.getListener(LISTENER_NAME).getPort() : -1; }
    public synchronized String getUrl() { return isRunning() ? "http://" + host + ":" + getActualPort() + endpoint : null; }
    public McpProtocolHandler getProtocolHandler() { return handler; }

    /** Unchecked lifecycle exception to keep start() convenient for examples. */
    public static final class IOExceptionUnchecked extends RuntimeException {
        public IOExceptionUnchecked(String message, Throwable cause) { super(message, cause); }
    }
}
