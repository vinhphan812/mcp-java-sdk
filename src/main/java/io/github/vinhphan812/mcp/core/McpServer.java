package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.McpReflectionRegistrar;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.transport.GrizzlyStreamableServerTransportProvider;

import java.util.function.Supplier;

/** Portable MCP server bootstrap combining registry, protocol handler, and transport. */
public final class McpServer implements AutoCloseable {
    private final McpRegistry registry;
    private final McpProtocolHandler protocolHandler;
    private final GrizzlyStreamableServerTransportProvider transport;

    private McpServer(Builder builder) {
        registry = builder.registry == null ? new McpRegistry() : builder.registry;
        protocolHandler = new McpProtocolHandler(registry,
                builder.config == null ? McpServerConfig.builder().build() : builder.config);
        transport = new GrizzlyStreamableServerTransportProvider(protocolHandler)
                .host(builder.host).port(builder.port).endpoint(builder.endpoint);
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

    /** Returns configured transport.
     * @return Grizzly transport provider. */
    public GrizzlyStreamableServerTransportProvider getTransport() {
        return transport;
    }

    /** Stops server and releases transport resources. */
    @Override
    public void close() {
        stop();
    }

    /** Fluent builder for {@link McpServer}. */
    public static final class Builder {
        private McpRegistry registry;
        private McpServerConfig config;
        private String host = "127.0.0.1";
        private int port = 3011;
        private String endpoint = "/mcp";
        private String apiKey;
        private Supplier<String> apiKeySupplier;

        /** Sets registry.
         * @param value registry instance.
         * @return this builder. */
        public Builder registry(McpRegistry value) {
            registry = value;
            return this;
        }

        /** Sets server configuration.
         * @param value immutable configuration.
         * @return this builder. */
        public Builder config(McpServerConfig value) {
            config = value;
            return this;
        }

        /** Sets bind host.
         * @param value host name or address.
         * @return this builder. */
        public Builder host(String value) {
            host = value;
            return this;
        }

        /** Sets listen port.
         * @param value port, with 0 selecting an ephemeral port.
         * @return this builder. */
        public Builder port(int value) {
            port = value;
            return this;
        }

        /** Sets MCP endpoint path.
         * @param value endpoint path.
         * @return this builder. */
        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        /** Configure Bearer auth. Do not hard-code secrets; load from environment or secret store.
         * @param value bearer key.
         * @return this builder. */
        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        /** Configure Bearer auth via external supplier. Do not hard-code secrets; load from environment or secret store.
         * @param supplier bearer key supplier.
         * @return this builder. */
        public Builder apiKeySupplier(Supplier<String> supplier) {
            apiKeySupplier = supplier;
            return this;
        }

        /** Builds server.
         * @return configured server. */
        public McpServer build() {
            return new McpServer(this);
        }
    }
}
