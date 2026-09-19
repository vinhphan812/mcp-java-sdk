package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;

public final class AuthorizationExample {
    public static void configure(McpServerConfig.Builder builder) {
        builder.authorization((requiredScopes, confirmationRequired, args) -> null);
    }
}
