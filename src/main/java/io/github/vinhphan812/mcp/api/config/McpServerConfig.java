package io.github.vinhphan812.mcp.api.config;

import io.github.vinhphan812.mcp.api.logging.JulMcpLogger;
import io.github.vinhphan812.mcp.api.logging.McpLogger;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable protocol metadata and capability configuration for an MCP server.
 */
public final class McpServerConfig {
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
     * Experimental capability metadata advertised by server initialize responses.
     */
    public final Map<String, Object> experimental;
    /** Maximum number of items returned in one paginated response. */
    public final int pageSize;
    /** Listener for notification queue overflow events. */
    public final McpProtocolHandler.QueueOverflowListener overflowListener;
    /** Tool authorisation handler. */
    public final McpAuthorization authorization;
    /** Immutable rate-limit and security configuration. */
    public final RateLimits rateLimits;

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
        experimental = Collections.unmodifiableMap(new LinkedHashMap<>(builder.experimental));
        pageSize = builder.pageSize;
        overflowListener = builder.overflowListener;
        authorization = builder.authorization;
        rateLimits = builder.rateLimits == null ? RateLimits.defaults() : builder.rateLimits;
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
        private String serverName = "mcp-server";
        private String serverVersion = "1.0.0";
        private boolean tools = true, resources = true, resourceSubscriptions = true, prompts = true;
        private boolean logging, completions, tasks;
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
        private McpProtocolHandler.QueueOverflowListener overflowListener;
        private McpAuthorization authorization;
        private RateLimits rateLimits;

        /** Sets maximum page size for paginated responses.
         * @param value positive maximum item count
         * @return this builder
         */
        public Builder pageSize(int value) {
            if (value <= 0) throw new IllegalArgumentException("pageSize must be positive");
            pageSize = value;
            return this;
        }

        /** Sets protocol version advertised during initialization.
         * @param value nonblank protocol version
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
         * Sets the queue overflow listener. When the notification queue for a session reaches
         * {@link McpProtocolHandler#MAX_PENDING_NOTIFICATIONS_PER_SESSION}, the listener is
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
