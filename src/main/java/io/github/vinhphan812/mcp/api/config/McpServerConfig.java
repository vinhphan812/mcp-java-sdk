package io.github.vinhphan812.mcp.api.config;

import io.github.vinhphan812.mcp.api.logging.JulMcpLogger;
import io.github.vinhphan812.mcp.api.logging.McpLogger;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.api.spi.McpTaskExtension;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable protocol metadata and capability configuration for an MCP server.
 *
 * <p>Use {@link #builder()} to construct. All fields are immutable once the
 * configuration is built.
 *
 * <h2>Protocol modes</h2>
 *
 * <p>{@link ProtocolMode#SESSIONED} (the default) uses stateful sessions with a
 * {@code Mcp-Session-Id} header. Each client connection is tracked and can receive
 * push notifications over a long-lived SSE stream. Compatible with protocol versions
 * {@code 2024-11-05} through {@code 2025-11-25}.
 *
 * <p>{@link ProtocolMode#STATELESS} uses per-request semantics from the
 * {@code 2026-07-28} protocol revision. Sessions are not tracked server-side;
 * each POST is self-contained. The server automatically sets its protocol version to
 * {@code 2026-07-28} when this mode is selected.
 *
 * <h2>Transport modes</h2>
 *
 * <p>{@link Builder#streamableHttp(boolean)} is stored in the configuration but
 * has <strong>no runtime effect in this release</strong>. The transport layer
 * selects between modern Streamable HTTP and legacy HTTP+SSE exclusively through
 * {@link io.github.vinhphan812.mcp.transport.TransportMode} on
 * {@link io.github.vinhphan812.mcp.api.McpServer.Builder#transportMode(TransportMode)},
 * not through this flag. This flag is reserved for a future release that wires
 * it end-to-end. Until then, callers who need a specific transport mode must use
 * {@code McpServer.builder().transportMode(TransportMode.STREAMABLE_HTTP)} directly.
 *
 * <p>{@link Builder#streaming(boolean)} controls whether the server advertises the
 * {@code streaming: {}} capability in its {@code initialize} response. Clients use this
 * to discover SSE support. Defaults to {@code true}.
 *
 * <h2>Trusted-proxy setups</h2>
 *
 * <p>When the server runs behind a reverse proxy that adds {@code X-Forwarded-For},
 * set {@link Builder#trustXForwardedFor(boolean) trustXForwardedFor(true)} so the
 * SDK uses the real client IP instead of the proxy's address for rate-limiting and
 * session binding.
 *
 * <p>When {@link Builder#bindSessionToIp(boolean) bindSessionToIp(true)} is also set,
 * each session is locked to the client IP seen at {@code initialize} time
 * (AUTH-03 / session-fixation mitigation). A request arriving from a different IP
 * is rejected with JSON-RPC error {@code -32602 (Invalid params)}. This requires
 * {@code trustXForwardedFor = true} to work correctly behind a proxy.
 *
 * <h2>Rate limits</h2>
 *
 * <p>See {@link RateLimits} for the full tunable surface. The builder initialises
 * all rate-limit fields to the values declared in {@link McpSecurityDefaults}.
 *
 * @see McpServer
 * @see RateLimits
 * @see McpSecurityDefaults
 */
public final class McpServerConfig {
    /** Session-oriented or stateless MCP protocol operation mode. */
    public enum ProtocolMode { SESSIONED, STATELESS }

    /** Configured protocol operation mode. */
    public final ProtocolMode protocolMode;
    /** Negotiated MCP protocol version. */
    public final String protocolVersion;
    /** Advertised server name. */
    public final String serverName;
    /** Advertised server version. */
    public final String serverVersion;
    /** Whether tools capability is enabled. */
    public final boolean tools;
    /** Whether resources capability is enabled. */
    public final boolean resources;
    /** Whether resource subscription capability is enabled. */
    public final boolean resourceSubscriptions;
    /** Whether prompts capability is enabled. */
    public final boolean prompts;
    /** Whether logging capability is enabled. */
    public final boolean logging;
    /**
     * Local application logger; defaults to a JDK-backed logger.
     */
    public final McpLogger logger;
    /** Whether completion capability is enabled. */
    public final boolean completions;
    /** Whether task capability is enabled. */
    public final boolean tasks;
    /**
     * Pluggable extension for the Tasks capability, or {@code null} to use the
     * built-in task store.  When present the extension's
     * {@link io.github.vinhphan812.mcp.api.spi.McpTaskExtension#supports(String)}
     * determines version-gated behaviour.
     */
    public final McpTaskExtension tasksExtension;
    /**
     * Experimental capability metadata advertised by server initialize responses.
     */
    public final Map<String, Object> experimental;
    /** Maximum number of items returned in one paginated response. */
    public final int pageSize;
    /** Listener for notification queue overflow events. */
    public final McpProtocolHandler.QueueOverflowListener overflowListener;
    /** Maximum SSE notification events held in the session queue before oldest are evicted. */
    public final int maxQueuedEvents;
    /** Tool authorisation handler. */
    public final McpAuthorization authorization;
    /** Immutable rate-limit and security configuration. */
    public final RateLimits rateLimits;
    /**
     * When true, the transport layer extracts the client IP from the X-Forwarded-For header
     * instead of using the remote socket address. Only enable when the server runs behind a
     * trusted reverse proxy. Default is false.
     */
    public final boolean trustXForwardedFor;

    /**
     * When {@code true}, each MCP session is bound to the client IP address recorded at
     * {@code initialize} time. Subsequent requests arriving from a different IP are rejected
     * with error -32602 (Invalid params). Default is {@code false}.
     *
     * <p>Addresses AUTH-03 / session-fixation prevention: binding the session to the
     * originating IP makes it significantly harder for an attacker who steals a session token
     * to use it from a different network location.
     *
     * <p><strong>Note:</strong> when the server is behind a reverse proxy you should also
     * set {@code trustXForwardedFor = true} so the real client IP is used instead of the
     * proxy address.
     */
    public final boolean bindSessionToIp;
    /**
     * Whether streaming (SSE) capability is enabled. When true, the server advertises
     * {@code streaming: {}} in its initialize response, allowing MCP clients to discover
     * SSE support. Default is true.
     */
    public final boolean streaming;
    /**
     * Whether Streamable HTTP mode is enabled. When true, the server uses modern
     * Streamable HTTP transport (single POST endpoint with server-driven streaming).
     * When false, uses legacy HTTP+SSE mode (POST + GET + DELETE endpoints).
     * Default is true.
     */
    public final boolean streamableHttp;

    /**
     * Returns the configured queue overflow listener.
     * @return overflow listener, or null if not configured
     */
    public McpProtocolHandler.QueueOverflowListener getOverflowListener() {
        return overflowListener;
    }

    /**
     * Returns the configured tool authorisation handler.
     * @return authorisation handler, or null if not configured
     */
    public McpAuthorization getAuthorization() {
        return authorization;
    }

    private McpServerConfig(Builder builder) {
        protocolVersion = builder.protocolVersion;
        protocolMode = builder.protocolMode;
        serverName = builder.serverName;
        serverVersion = builder.serverVersion;
        tools = builder.tools;
        resources = builder.resources;
        resourceSubscriptions = builder.resourceSubscriptions;
        prompts = builder.prompts;
        logging = builder.logging;
        logger = builder.logger;
        completions = builder.completions;
        tasks = builder.tasks;
        tasksExtension = builder.tasksExtension;
        experimental = Collections.unmodifiableMap(new LinkedHashMap<>(builder.experimental));
        pageSize = builder.pageSize;
        overflowListener = builder.overflowListener;
        maxQueuedEvents = builder.maxQueuedEvents;
        authorization = builder.authorization;
        rateLimits = builder.rateLimits == null ? RateLimits.defaults() : builder.rateLimits;
        trustXForwardedFor = builder.trustXForwardedFor;
        bindSessionToIp = builder.bindSessionToIp;
        streaming = builder.streaming;
        streamableHttp = builder.streamableHttp;
    }

    /** Creates a builder for server configuration.
     * @return new configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for immutable MCP server configuration.
     *
     * <p>Initialises all fields to the values declared in {@link McpSecurityDefaults}
     * except {@code serverName} (default: {@code "mcp-server"}) and
     * {@code serverVersion} (default: {@code "1.0.1"}).
     *
     * <p>Calling {@link #protocolMode(ProtocolMode)} with
     * {@link ProtocolMode#STATELESS} automatically sets the protocol version to
     * {@code "2026-07-28"}. Calling {@link #protocolVersion(String)} with a non-blank
     * version does <em>not</em> change the protocol mode — the caller is responsible
     * for keeping the two in sync.
     *
     * @see McpServerConfig
     * @see McpSecurityDefaults
     */
    public static final class Builder {
        private String protocolVersion = "2025-11-25";
        private ProtocolMode protocolMode = ProtocolMode.SESSIONED;
        private String serverName = "mcp-server";
        private String serverVersion = "1.0.1";
        private boolean tools = true, resources = true, resourceSubscriptions = true, prompts = true;
        private boolean logging, completions, tasks;
        private McpTaskExtension tasksExtension;
        private McpLogger logger = new JulMcpLogger("mcp-server");
        private Map<String, Object> experimental = new LinkedHashMap<>();

        /**
         * Supplies the application logger used by the protocol handler.
         * @param value logger, or null to use the default logger
         * @return this builder
         */
        public Builder logger(McpLogger value) {
            logger = value == null ? new JulMcpLogger(serverName) : value;
            return this;
        }

        private int pageSize = 50;
        private int maxQueuedEvents = McpSecurityDefaults.MAX_QUEUED_EVENTS;
        private McpProtocolHandler.QueueOverflowListener overflowListener;
        private McpAuthorization authorization;
        private RateLimits rateLimits;
        private boolean trustXForwardedFor = false;
        private boolean bindSessionToIp = false;
        private boolean streaming = true;
        private boolean streamableHttp = true;

        /** Sets maximum page size for paginated responses.
         * @param value positive maximum item count
         * @return this builder
         */
        public Builder pageSize(int value) {
            if (value <= 0) throw new IllegalArgumentException("pageSize must be positive");
            pageSize = value;
            return this;
        }

        /** Sets maximum queued SSE notification events per session before oldest are evicted.
         * @param value positive event count
         * @return this builder */
        public Builder maxQueuedEvents(int value) {
            if (value <= 0) throw new IllegalArgumentException("maxQueuedEvents must be positive");
            maxQueuedEvents = value;
            return this;
        }

        /**
         * Selects sessioned or stateless protocol handling.
         *
         * <ul>
         *   <li>{@link ProtocolMode#SESSIONED} (default) — stateful sessions with
         *       {@code Mcp-Session-Id} tracking. Supports SSE push notifications
         *       over a long-lived GET connection. Compatible with protocol
         *       {@code 2024-11-05} through {@code 2025-11-25}.
         *   <li>{@link ProtocolMode#STATELESS} — per-request semantics from the
         *       {@code 2026-07-28} protocol revision. Sessions are not tracked
         *       server-side. Automatically sets {@code protocolVersion} to
         *       {@code "2026-07-28"}.
         * </ul>
         *
         * @param value sessioned or stateless mode; {@code null} resolves to SESSIONED.
         * @return this builder
         */
        public Builder protocolMode(ProtocolMode value) {
            protocolMode = value == null ? ProtocolMode.SESSIONED : value;
            if (protocolMode == ProtocolMode.STATELESS) protocolVersion = "2026-07-28";
            return this;
        }

        /**
         * Sets the protocol version string advertised in the {@code initialize} response.
         *
         * <p><strong>Tip:</strong> prefer {@link #protocolMode(ProtocolMode)} with
         * {@link ProtocolMode#STATELESS} when targeting the {@code 2026-07-28} protocol,
         * as it automatically sets this field. Setting the version manually does
         * <em>not</em> change the protocol mode — callers are responsible for keeping
         * the two in sync.
         *
         * @param value non-blank version string, e.g. {@code "2025-11-25"}.
         * @return this builder
         */
        public Builder protocolVersion(String value) {
            protocolVersion = requireText(value, "protocolVersion");
            return this;
        }

        /** Sets server name advertised during initialization.
         * @param value nonblank server name
         * @return this builder
         */
        public Builder serverName(String value) {
            serverName = requireText(value, "serverName");
            if (logger instanceof JulMcpLogger) logger = new JulMcpLogger(serverName);
            return this;
        }

        /** Sets server version advertised during initialization.
         * @param value nonblank server version
         * @return this builder
         */
        public Builder serverVersion(String value) {
            serverVersion = requireText(value, "serverVersion");
            return this;
        }

        /** Enables or disables tools capability.
         * @param value whether tools are enabled
         * @return this builder
         */
        public Builder tools(boolean value) {
            tools = value;
            return this;
        }

        /** Enables or disables resources capability.
         * @param value whether resources are enabled
         * @return this builder
         */
        public Builder resources(boolean value) {
            resources = value;
            return this;
        }

        /** Enables or disables resource subscriptions.
         * @param value whether subscriptions are enabled
         * @return this builder
         */
        public Builder resourceSubscriptions(boolean value) {
            resourceSubscriptions = value;
            return this;
        }

        /** Enables or disables prompts capability.
         * @param value whether prompts are enabled
         * @return this builder
         */
        public Builder prompts(boolean value) {
            prompts = value;
            return this;
        }

        /** Enables server logging/setLevel and notifications/message.
         * @param value whether logging is enabled
         * @return this builder
         */
        public Builder logging(boolean value) {
            logging = value;
            return this;
        }

        /** Enables completion/complete and completion capability advertisement.
         * @param value whether completions are enabled
         * @return this builder
         */
        public Builder completions(boolean value) {
            completions = value;
            return this;
        }

        /** Enables or disables tasks capability.
         * @param value whether tasks are enabled
         * @return this builder
         */
        public Builder tasks(boolean value) {
            tasks = value;
            return this;
        }

        /**
         * Sets the pluggable Tasks extension.
         *
         * <p>When set, the extension's {@link McpTaskExtension#supports(String)} controls
         * whether the built-in task methods are enabled for each protocol version.
         * The extension also receives all task-method dispatch callbacks.
         *
         * @param extension the extension, or {@code null} to use the built-in task store
         * @return this builder
         */
        public Builder tasksExtension(McpTaskExtension extension) {
            this.tasksExtension = extension;
            return this;
        }

        /**
         * Sets the queue overflow listener. When the notification queue for a session reaches
         * {@code MAX_PENDING_NOTIFICATIONS_PER_SESSION}, the listener is
         * notified. If no listener is configured, a {@link McpProtocolHandler.QueueOverflowException}
         * is thrown.
         *
         * @param listener the overflow listener, or null to disable
         * @return this builder
         */
        public Builder overflowListener(McpProtocolHandler.QueueOverflowListener listener) {
            this.overflowListener = listener;
            return this;
        }

        /**
         * Sets the tool authorisation handler. When configured, the handler's
         * {@link McpAuthorization#denial(String[], boolean, Map)} method is called before each
         * tool invocation. Return null to allow; return a denial message to reject.
         * If not configured, all tools are allowed.
         *
         * @param authorization the authorisation handler, or null to allow all tools
         * @return this builder
         */
        public Builder authorization(McpAuthorization authorization) {
            this.authorization = authorization;
            return this;
        }

        /** Sets immutable rate-limit and security configuration.
         * @param rateLimits custom limits, or null for defaults
         * @return this builder
         */
        public Builder rateLimits(RateLimits rateLimits) {
            this.rateLimits = rateLimits;
            return this;
        }

        /**
         * When true, the transport layer extracts the client IP from the X-Forwarded-For header
         * instead of using the remote socket address. Only enable when the server runs behind a
         * trusted reverse proxy. Default is false.
         * @param value true to trust the X-Forwarded-For header
         * @return this builder
         */
        public Builder trustXForwardedFor(boolean value) {
            this.trustXForwardedFor = value;
            return this;
        }

        /**
         * When {@code true}, each MCP session is bound to the client IP address recorded at
         * {@code initialize} time. Subsequent requests arriving from a different IP are rejected
         * with error -32602 (Invalid params). Default is {@code false}.
         *
         * <p>Addresses AUTH-03 / session-fixation prevention: binding the session to the
         * originating IP makes it significantly harder for an attacker who steals a session token
         * to use it from a different network location.
         *
         * <p><strong>Note:</strong> when the server is behind a reverse proxy you should also
         * set {@code trustXForwardedFor = true} so the real client IP is used instead of the
         * proxy address.
         * @param value true to bind sessions to their originating IP address
         * @return this builder
         */
        public Builder bindSessionToIp(boolean value) {
            this.bindSessionToIp = value;
            return this;
        }

        /**
         * Enables or disables streaming (SSE) capability advertisement in initialize response.
         * When enabled, the server advertises {@code streaming: {}} to allow MCP clients
         * to discover SSE support. Default is true.
         * @param value whether streaming is enabled
         * @return this builder
         */
        public Builder streaming(boolean value) {
            this.streaming = value;
            return this;
        }

        /**
         * Stores the Streamable HTTP preference but has <strong>no runtime effect</strong>
         * in this release. The transport layer selects the transport mode exclusively via
         * {@link io.github.vinhphan812.mcp.api.McpServer.Builder#transportMode(TransportMode)}.
         * This flag is reserved for a future release that wires it end-to-end.
         * Default is true.
         * @param value whether Streamable HTTP mode is preferred
         * @return this builder
         */
        public Builder streamableHttp(boolean value) {
            this.streamableHttp = value;
            return this;
        }

        /** Sets experimental capability metadata without enabling request handling.
         * @param value experimental capability metadata
         * @return this builder
         */
        public Builder experimental(Map<String, Object> value) {
            experimental = value == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(value);
            return this;
        }

        /** Builds immutable server configuration.
         * @return configured server settings
         */
        public McpServerConfig build() {
            if (!resources) resourceSubscriptions = false;
            return new McpServerConfig(this);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + " cannot be empty");
            return value;
        }
    }
}
