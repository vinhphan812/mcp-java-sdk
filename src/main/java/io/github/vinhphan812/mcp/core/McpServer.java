package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.McpReflectionRegistrar;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.transport.HttpTransportProvider;

import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/** Portable MCP server bootstrap combining registry, protocol handler, and transport. */
public final class McpServer implements AutoCloseable {
    private final McpRegistry registry;
    private final McpProtocolHandler protocolHandler;
    private final HttpTransportProvider transport;

    private McpServer(Builder builder) {
        registry = builder.registry == null ? new McpRegistry() : builder.registry;
        protocolHandler = new McpProtocolHandler(registry,
                builder.config == null ? McpServerConfig.builder().build() : builder.config);
        transport = new HttpTransportProvider(protocolHandler)
                .host(builder.host).port(builder.port).endpoint(builder.endpoint);
        if (builder.allowedOrigins != null) {
            transport.allowedOrigins(builder.allowedOrigins);
        }
        if (builder.apiKeySupplier != null) {
            transport.apiKeySupplier(builder.apiKeySupplier);
        } else if (builder.apiKey != null) {
            transport.apiKey(builder.apiKey);
        }
    }

    /** Creates server builder with default registry, localhost host, and MCP endpoint.
     * @return new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Register annotated provider before starting server.
     * @param provider provider containing MCP annotations.
     * @return this server. */
    public McpServer register(Object provider) {
        McpReflectionRegistrar.register(provider, registry);
        return this;
    }

    /** Register several annotated providers in declaration order.
     * @param providers providers containing MCP annotations.
     * @return this server.
     * @throws IllegalArgumentException if providers is null. */
    public McpServer registerAll(Object... providers) {
        if (providers == null) throw new IllegalArgumentException("providers cannot be null");
        for (Object provider : providers) register(provider);
        return this;
    }

    /** Starts transport and begins accepting requests. */
    public void start() {
        transport.start();
    }

    /** Stops transport, closes active sessions, and stops the cleanup thread. */
    public void stop() {
        protocolHandler.shutdown();
        protocolHandler.closeAllSessions();
        transport.stop();
    }

    /** Returns whether transport is running.
     * @return true when running. */
    public boolean isRunning() {
        return transport.isRunning();
    }

    /** Returns endpoint URL, or null before startup.
     * @return server URL or null. */
    public String getUrl() {
        return transport.getUrl();
    }

    /** Returns server registry.
     * @return registry used by protocol handler. */
    public McpRegistry getRegistry() {
        return registry;
    }

    /** Returns protocol handler.
     * @return server protocol handler. */
    public McpProtocolHandler getProtocolHandler() {
        return protocolHandler;
    }

    /**
     * Returns configured transport.
     * @return transport provider. */
    public HttpTransportProvider getTransport() {
        return transport;
    }

    /** Stops server and releases transport resources. */
    @Override
    public void close() {
        stop();
    }

    /**
     * Fluent builder for {@link McpServer}.
     *
     * <p>The builder produces an immutable {@code McpServer} on {@link #build}.
     * The server is not started automatically; call {@link McpServer#start()} after
     * registration.
     *
     * <h2>Transport address</h2>
     *
     * <p>The {@link #host(String) host} and {@link #port(int) port} control which network
     * interface and port the underlying HTTP server binds to. Use {@code port(0)} to select
     * an OS-chosen ephemeral port; retrieve the actual port via {@link McpServer#getUrl()}
     * <strong>after</strong> calling {@link McpServer#start()}.
     *
     * <p>Binding to {@code 0.0.0.0} exposes the server on every IPv4 interface. This is
     * <strong>not recommended</strong> without a reverse proxy or firewall in front of the
     * server — the SDK itself does not perform TLS. For production deployments, bind to
     * {@code 127.0.0.1} (the default) and terminate TLS at the reverse proxy. See
     * {@code docs/transport/TRANSPORT-STREAMABLE-HTTP.md} for the recommended deployment
     * topology.
     *
     * <p><strong>Important:</strong> the bind address controls TCP-level reachability, not
     * the CORS {@code Origin} policy. The two are independent — see {@link
     * #allowedOrigins(Set)} for the CORS layer.
     *
     * <h2>Authentication</h2>
     *
     * <p>Bearer authentication is enabled by supplying either {@link #apiKey(String) apiKey}
     * or {@link #apiKeySupplier(Supplier) apiKeySupplier}. The supplier form is preferred
     * because it is re-evaluated on every request and can read a rotating secret from an
     * environment variable or secret store.
     *
     * <p>Do not hard-code credentials in source code or configuration files that are checked
     * into version control.
     *
     * <h2>CORS / Origin policy</h2>
     *
     * <p>The built-in loopback rule accepts any {@code Origin} whose host resolves to
     * {@code 127.0.0.0/8} or {@code ::1} regardless of port. Use {@link
     * #allowedOrigins(Set)} to add additional non-loopback origins. When the caller
     * provides a non-blank {@code Origin} header that does not match either the loopback
     * rule or the explicit allowlist, the server responds with HTTP 403.
     *
     * <p>Origin validation is entirely independent of Bearer authentication. A request with
     * a valid origin but an invalid or missing bearer token still returns 401. A request
     * with an invalid origin but a valid bearer token still returns 403 (auth is not
     * evaluated).
     *
     * <p>See ADR-0021 for the full CORS policy specification.
     *
     * @see McpServerConfig.Builder
     * @see <a href="https://github.com/vinhphan812/mcp-java-sdk/blob/master/docs/transport/TRANSPORT-STREAMABLE-HTTP.md">
     *      Transport documentation — TLS reverse-proxy topology</a>
     * @see <a href="https://github.com/vinhphan812/mcp-java-sdk/blob/master/docs/adr/ADR-0021-cors-loopback-origin-policy.md">
     *      ADR-0021 — CORS Loopback Origin Policy</a>
     */
    public static final class Builder {
        private McpRegistry registry;
        private McpServerConfig config;
        private String host = "127.0.0.1";
        private int port = 3011;
        private String endpoint = "/mcp";
        private String apiKey;
        private Supplier<String> apiKeySupplier;
        private Set<String> allowedOrigins;

        /** Sets a pre-configured registry.
         *  @param value registry instance, or {@code null} to use the default.
         *  @return this builder. */
        public Builder registry(McpRegistry value) {
            registry = value;
            return this;
        }

        /** Sets the protocol and capability configuration.
         *  @param value immutable configuration from {@link McpServerConfig#builder()}, or
         *               {@code null} to use the default configuration.
         *  @return this builder. */
        public Builder config(McpServerConfig value) {
            config = value;
            return this;
        }

        /** Sets the TCP bind address.
         *
         * <ul>
         *   <li>{@code "127.0.0.1"} (the default) — loopback only; reachable only from the
         *       same host. Suitable for local development and for servers sitting behind a
         *       reverse proxy that terminates TLS.
         *   <li>{@code "0.0.0.0"} — all IPv4 interfaces; the server is network-reachable.
         *       <strong>Do not use this without a firewall or TLS in front of the server.</strong>
         *   <li>A specific interface address — binds to that interface only.
         * </ul>
         *
         * <p>The bind address is independent of the CORS {@code Origin} policy — see {@link
         * #allowedOrigins(Set)}.
         *
         *  @param value host name or IP address; must not be blank.
         *  @return this builder. */
        public Builder host(String value) {
            host = value;
            return this;
        }

        /** Sets the TCP listen port.
         *
         * <ul>
         *   <li>Any positive integer — fixed port.
         *   <li>{@code 0} — OS-chosen ephemeral port. Use {@link McpServer#getUrl()} after
         *       {@link McpServer#start()} to retrieve the actual port.
         * </ul>
         *
         *  @param value port number, or {@code 0} for ephemeral.
         *  @return this builder. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Sets the HTTP path that handles MCP JSON-RPC requests.
         *
         * <p>The path is relative to the host:port. For example, the default {@code "/mcp"}
         * with host {@code "127.0.0.1"} and port {@code 3011} produces the server URL
         * {@code http://127.0.0.1:3011/mcp}.
         *
         *  @param value URL path; must start with {@code '/'}.
         *  @return this builder. */
        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        /**
         * Configures Bearer authentication with a fixed token.
         *
         * <p><strong>Prefer {@link #apiKeySupplier(Supplier)}</strong> in production — the
         * supplier is re-evaluated on every request and can read a rotating secret.
         * Hard-coding credentials is not recommended.
         *
         * <p>When the supplied value is {@code null} or blank, Bearer authentication is
         * disabled.
         *
         *  @param value bearer token; may be {@code null} to disable authentication.
         *  @return this builder. */
        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        /**
         * Configures Bearer authentication via a supplier that is called on every request.
         *
         * <p>Use this to load the token from an environment variable, a secret store, or
         * a vault. The supplier is invoked for each inbound request, so it can return
         * rotating or short-lived credentials safely.
         *
         * <p>Example loading from an environment variable:
         * <pre>{@code
         * .apiKeySupplier(() -> System.getenv("MCP_API_KEY"))
         * }</pre>
         *
         * <p>When the supplier returns {@code null} or a blank string, authentication is
         * bypassed for that request.
         *
         *  @param supplier token supplier; evaluated on every HTTP request.
         *  @return this builder. */
        public Builder apiKeySupplier(Supplier<String> supplier) {
            apiKeySupplier = supplier;
            return this;
        }

        /**
         * Adds explicit non-loopback allowed origins in addition to the built-in loopback
         * rule.
         *
         * <p><strong>When to use this:</strong> only when the server must accept requests
         * from origins that are <em>not</em> {@code localhost}, {@code 127.0.0.0/8}, or
         * {@code ::1}. For example, a browser-based MCP client served from
         * {@code https://my-app.example.com} connecting to a server on the same domain.
         *
         * <p><strong>When <em>not</em> to use this:</strong>
         * <ul>
         *   <li>For loopback clients — they are accepted automatically by the built-in rule.
         *   <li>To restrict access by IP address — that is a network/firewall concern, not
         *       a CORS concern. Use a firewall or bind address instead.
         *   <li>To enforce TLS — the SDK does not handle TLS; use a reverse proxy.
         * </ul>
         *
         * <p>Allowed values are exact origin strings, e.g. {@code "https://app.example.com"}.
         * Wildcards and regular expressions are not supported. Each value is normalised
         * (lowercased, trimmed) at configuration time.
         *
         * <p>See ADR-0021 for the full policy specification.
         *
         *  @param origins allowed non-loopback origins; may be empty (means only the
         *                 built-in loopback rule applies); must not be {@code null}.
         *  @return this builder.
         *  @throws IllegalArgumentException if origins is {@code null}.
         */
        public Builder allowedOrigins(Set<String> origins) {
            if (origins == null) throw new IllegalArgumentException("allowedOrigins cannot be null");
            allowedOrigins = origins;
            return this;
        }

        /** Builds server.
         * @return configured server. */
        public McpServer build() {
            return new McpServer(this);
        }
    }
}
