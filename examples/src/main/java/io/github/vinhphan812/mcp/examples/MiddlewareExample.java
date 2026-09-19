package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;

public final class MiddlewareExample {
    public static void configure(McpServerConfig.Builder builder) {
        builder.apiKeyMiddleware(apiKey -> {
            if (!"secret-key".equals(apiKey)) {
                throw new SecurityException("Invalid API key");
            }
        });
    }
}
