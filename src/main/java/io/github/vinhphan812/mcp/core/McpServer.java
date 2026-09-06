package io.github.vinhphan812.mcp.core;

import io.github.vinhphan812.mcp.api.McpReflectionRegistrar;
import io.github.vinhphan812.mcp.api.McpServerConfig;
import io.github.vinhphan812.mcp.transport.GrizzlyStreamableServerTransportProvider;

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
        if (builder.apiKey != null) transport.apiKey(builder.apiKey);
    }

    public static Builder builder() { return new Builder(); }

    /** Register annotated provider before starting server. */
    public McpServer register(Object provider) {
        McpReflectionRegistrar.register(provider, registry);
        return this;
    }

    /** Register several annotated providers in declaration order. */
    public McpServer registerAll(Object... providers) {
        if (providers == null) throw new IllegalArgumentException("providers cannot be null");
        for (Object provider : providers) register(provider);
        return this;
    }

    public void start() { transport.start(); }
    public void stop() { transport.stop(); }
    public boolean isRunning() { return transport.isRunning(); }
    public String getUrl() { return transport.getUrl(); }
    public McpRegistry getRegistry() { return registry; }
    public McpProtocolHandler getProtocolHandler() { return protocolHandler; }
    public GrizzlyStreamableServerTransportProvider getTransport() { return transport; }
    @Override public void close() { stop(); }

    public static final class Builder {
        private McpRegistry registry;
        private McpServerConfig config;
        private String host = "127.0.0.1";
        private int port = 3011;
        private String endpoint = "/mcp";
        private String apiKey;

        public Builder registry(McpRegistry value) { registry = value; return this; }
        public Builder config(McpServerConfig value) { config = value; return this; }
        public Builder host(String value) { host = value; return this; }
        public Builder port(int value) { port = value; return this; }
        public Builder endpoint(String value) { endpoint = value; return this; }
        /** Configure Bearer auth. Do not hard-code secrets; load from environment or secret store. */
        public Builder apiKey(String value) { apiKey = value; return this; }
        public McpServer build() { return new McpServer(this); }
    }
}
