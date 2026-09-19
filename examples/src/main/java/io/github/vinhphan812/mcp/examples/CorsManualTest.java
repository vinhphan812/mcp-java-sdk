package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.transport.GrizzlyStreamableServerTransportProvider;

public final class CorsManualTest {
    public static void main(String[] args) throws Exception {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), McpServerConfig.builder().build());
        try (GrizzlyStreamableServerTransportProvider transport = new GrizzlyStreamableServerTransportProvider(handler)
                .port(3011).endpoint("/mcp")) {
            transport.start();
            System.out.println("Server running at " + transport.getUrl());
            Thread.sleep(60000); // Run for 1 minute
        }
    }
}
