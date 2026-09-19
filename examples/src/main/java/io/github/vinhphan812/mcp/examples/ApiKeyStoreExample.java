package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.security.DefaultApiKeyStore;

public final class ApiKeyStoreExample {
    public static void configure(McpServerConfig.Builder builder) {
        builder.apiKeyStore(new DefaultApiKeyStore("initial-api-key", "auth.json", 3600));
    }
}
