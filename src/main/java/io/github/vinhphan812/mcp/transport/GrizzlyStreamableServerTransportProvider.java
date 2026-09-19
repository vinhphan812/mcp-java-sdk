package io.github.vinhphan812.mcp.transport;

import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Owns lifecycle of a Grizzly MCP Streamable HTTP server.
 */
public final class GrizzlyStreamableServerTransportProvider implements AutoCloseable {
    private static final String LISTENER_NAME = "mcp-http";
    private final McpProtocolHandler handler;
    private String host = "127.0.0.1";
    private int port = 3011;
    private String endpoint = "/mcp";
    private String urlScheme = "http";
    private Supplier<String> apiKeySupplier;
    private Set<String> allowedOrigins = new HashSet<>(Arrays.asList("http://localhost", "http://127.0.0.1", "https://localhost"));
    private int maxRequestBodyBytes = 1024 * 1024;
    private HttpServer server;

    /**
     * Creates transport for protocol handler.
     *
     * @param handler handler serving MCP requests.
     * @throws IllegalArgumentException if handler is null.
     */
    public GrizzlyStreamableServerTransportProvider(McpProtocolHandler handler) {
        if (handler == null) throw new IllegalArgumentException("handler cannot be null");
        this.handler = handler;
    }

    /**
     * Sets bind host.
     *
     * @param value host name or address.
     * @return this provider.
     * @throws IllegalArgumentException if value is blank.
     */
    public GrizzlyStreamableServerTransportProvider host(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("host cannot be empty");
        host = value.trim();
        return this;
    }

    /**
     * Sets listen port.
     *
     * @param value port from 0 through 65535.
     * @return this provider.
     * @throws IllegalArgumentException if value is outside range.
     */
    public GrizzlyStreamableServerTransportProvider port(int value) {
        if (value < 0 || value > 65535) throw new IllegalArgumentException("port must be between 0 and 65535");
        port = value;
        return this;
    }

    /**
     * Sets HTTP endpoint path, adding leading slash when absent.
     *
     * @param value endpoint path.
     * @return this provider.
     * @throws IllegalArgumentException if value is blank.
     */
    public GrizzlyStreamableServerTransportProvider endpoint(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("endpoint cannot be empty");
        endpoint = value.trim().startsWith("/") ? value.trim() : "/" + value.trim();
        return this;
    }

    /**
     * Sets the URL scheme used in {@link #getUrl()}.
     * Defaults to {@code "http"}. Set to {@code "https"} when the transport is
     * behind TLS termination.
     *
     * @param value scheme string, normally {@code "http"} or {@code "https"}.
     * @return this provider.
     * @throws IllegalArgumentException if value is blank.
     */
    public GrizzlyStreamableServerTransportProvider scheme(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("scheme cannot be empty");
        urlScheme = value.trim().toLowerCase();
        return this;
    }

    /**
     * Configure accepted browser origins. Values are normalized case-insensitively.
     *
     * @param values allowed origin values.
     * @return this provider.
     * @throws IllegalArgumentException if values is null.
     */
    public GrizzlyStreamableServerTransportProvider allowedOrigins(java.util.Set<String> values) {
        if (values == null) throw new IllegalArgumentException("allowedOrigins cannot be null");
        allowedOrigins = new java.util.HashSet<>(values);
        return this;
    }

    /**
     * Limit POST request bodies in bytes.
     *
     * @param value positive byte limit.
     * @return this provider.
     * @throws IllegalArgumentException if value is not positive.
     */
    public GrizzlyStreamableServerTransportProvider maxRequestBodyBytes(int value) {
        if (value <= 0) throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        maxRequestBodyBytes = value;
        return this;
    }

    /**
     * Enable Bearer authentication with a constant key.
     * Keep the key outside source control.
     *
     * @param value bearer key, or null to disable authentication.
     * @return this provider.
     */
    public GrizzlyStreamableServerTransportProvider apiKey(String value) {
        apiKeySupplier = value == null ? null : () -> value;
        return this;
    }

    /**
     * Enable Bearer authentication with an external supplier.
     * The supplier is called on every request to retrieve the current key.
     * Keep the key outside source control; prefer this over {@link #apiKey(String)}
     * when the secret may rotate.
     *
     * @param supplier bearer key supplier, or null to disable authentication.
     * @return this provider.
     */
    public GrizzlyStreamableServerTransportProvider apiKeySupplier(Supplier<String> supplier) {
        apiKeySupplier = supplier;
        return this;
    }

    /**
     * Starts Grizzly listener; repeated calls while running are no-ops.
     *
     * @throws IOExceptionUnchecked if startup fails.
     */
    public synchronized void start() throws IOExceptionUnchecked {
        if (isRunning()) return;
        try {
            McpGrizzlyHandler httpHandler = new McpGrizzlyHandler(handler, endpoint, apiKeySupplier, allowedOrigins, maxRequestBodyBytes, 4, handler.getConfig().trustXForwardedFor);
            server = new HttpServer();
            server.addListener(new NetworkListener(LISTENER_NAME, host, port));
            server.getServerConfiguration().addHttpHandler(httpHandler, endpoint);
            server.start();
        } catch (Exception e) {
            server = null;
            throw new IOExceptionUnchecked("Unable to start MCP Grizzly transport", e);
        }
    }

    /**
     * Stops listener and closes all protocol sessions.
     */
    public synchronized void stop() {
        if (server != null) server.shutdownNow();
        server = null;
        handler.closeAllSessions();
    }

    /**
     * Stops transport.
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * Returns whether listener is started.
     *
     * @return true when running.
     */
    public synchronized boolean isRunning() {
        return server != null && server.isStarted();
    }

    /**
     * Returns bound port, or -1 before startup.
     *
     * @return actual port or -1.
     */
    public synchronized int getActualPort() {
        return isRunning() ? server.getListener(LISTENER_NAME).getPort() : -1;
    }

    /**
     * Returns endpoint URL, or null before startup.
     *
     * @return URL or null.
     */
    public synchronized String getUrl() {
        return isRunning() ? urlScheme + "://" + host + ":" + getActualPort() + endpoint : null;
    }

    /**
     * Returns protocol handler served by transport.
     *
     * @return protocol handler.
     */
    public McpProtocolHandler getProtocolHandler() {
        return handler;
    }

    /**
     * Unchecked lifecycle exception to keep start convenient for examples.
     */
    public static final class IOExceptionUnchecked extends RuntimeException {
        /**
         * Creates lifecycle exception.
         *
         * @param message failure description.
         * @param cause   startup cause.
         */
        public IOExceptionUnchecked(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
