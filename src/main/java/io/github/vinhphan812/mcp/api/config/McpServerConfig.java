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
     * Default is true
     */
    public final boolean streamableHttp;
    /**
     * Whether elicitation capability is enabled. When true, the server can send
     * requests to the client and receive responses (MRTR/elicitation).
     * Default is false.
     */
    public final boolean elicitation;
    /**
     * Default timeout for elicitation requests in milliseconds.
     * Used when client doesn't specify a timeout.
     * Default is 60000 (60 seconds).
     */
    public final long elicitationTimeoutMs;

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
        elicitation = builder.elicitation;
        elicitationTimeoutMs = builder.elicitationTimeoutMs;
    }

    /** Creates a builder for server configuration.
     * @return new configuration builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for immutable MCP server configuration.
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
        private boolean elicitation = false;
        private long elicitationTimeoutMs = 60_000L;

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

        /** Sets protocol version advertised during initialization.
         * @param value nonblank protocol version
         * @return this builder
         */
        /** Selects sessioned or stateless protocol handling. */
        public Builder protocolMode(ProtocolMode value) {
            protocolMode = value == null ? ProtocolMode.SESSIONED : value;
            if (protocolMode == ProtocolMode.STATELESS) protocolVersion = "2026-07-28";
            return this;
        }

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
         * Enables or disables Streamable HTTP mode. When enabled, the server uses modern
         * Streamable HTTP transport (single POST endpoint with server-driven streaming).
         * When disabled, uses legacy HTTP+SSE mode (POST + GET + DELETE endpoints).
         * Default is true.
         * @param value whether Streamable HTTP mode is enabled
         * @return this builder
         */
        public Builder streamableHttp(boolean value) {
            this.streamableHttp = value;
            return this;
        }

        /**
         * Enables or disables elicitation capability. When enabled, the server can send
         * requests to the client (MRTR/elicitation) and receive responses.
         * Default is false.
         * @param value whether elicitation is enabled
         * @return this builder
         */
        public Builder elicitation(boolean value) {
            this.elicitation = value;
            return this;
        }

        /**
         * Sets the default timeout for elicitation requests in milliseconds.
         * When the server sends an elicitation request to the client, if no response
         * arrives within this timeout the pending request is cancelled.
         * Default is 60000 (60 seconds).
         * @param value timeout in milliseconds, must be positive
         * @return this builder
         */
        public Builder elicitationTimeoutMs(long value) {
            if (value <= 0) throw new IllegalArgumentException("elicitationTimeoutMs must be positive");
            this.elicitationTimeoutMs = value;
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
