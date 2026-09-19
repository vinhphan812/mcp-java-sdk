package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.api.events.QueueOverflowPolicy;

public final class RateLimitExample {
    public static void configure(McpServerConfig.Builder builder) {
        builder.rateLimits(RateLimits.builder()
                .sessionTimeoutMs(300000)
                .rateLimitWindows(60000, 1000)
                .overflowPolicy(QueueOverflowPolicy.DROP_OLDEST)
                .read(10, 5, 2)
                .write(5, 2, 1)
                .build());
    }
}
