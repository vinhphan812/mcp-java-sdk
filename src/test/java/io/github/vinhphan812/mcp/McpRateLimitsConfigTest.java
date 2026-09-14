package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests configurable ADR-0011 rate limits. */
class McpRateLimitsConfigTest {
    @Test
    void defaultsMatchProtocolConstants() {
        RateLimits limits = RateLimits.defaults();
        assertEquals(McpProtocolHandler.MAX_CONCURRENT_SESSIONS, limits.maxConcurrentSessions);
        assertEquals(McpProtocolHandler.CATEGORY_READ_BURST_LIMIT, limits.readBurst);
        assertEquals(McpProtocolHandler.CATEGORY_ADMIN_CONCURRENT_CAP, limits.adminConcurrent);
        assertEquals(McpProtocolHandler.DESTRUCTIVE_CAP_SHUTDOWN, limits.shutdownCap);
        assertEquals(McpProtocolHandler.ABUSE_SCORE_BLOCK_THRESHOLD, limits.abuseScoreBlockThreshold);
    }

    @Test
    void customRateLimitsAreStoredInConfig() {
        RateLimits limits = RateLimits.builder()
                .maxConcurrentSessions(2)
                .maxRequestsPerIpPerMinute(5)
                .read(4, 10, 1)
                .shutdown(1, 1000L)
                .destructiveTools(Collections.singleton("custom-dangerous-tool"))
                .build();

        McpServerConfig config = McpServerConfig.builder().rateLimits(limits).build();
        assertTrue(config.rateLimits == limits);
        assertEquals(2, config.rateLimits.maxConcurrentSessions);
        assertEquals(5, config.rateLimits.maxRequestsPerIpPerMinute);
        assertEquals(4, config.rateLimits.readBurst);
        assertEquals(Collections.singleton("custom-dangerous-tool"), config.rateLimits.destructiveTools);
    }

    @Test
    void nullRateLimitsUseDefaults() {
        McpServerConfig config = McpServerConfig.builder().rateLimits(null).build();
        assertEquals(McpProtocolHandler.CATEGORY_WRITE_BURST_LIMIT, config.rateLimits.writeBurst);
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RateLimits.builder().read(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> RateLimits.builder().sessionTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> RateLimits.builder().shutdown(1, 0));
    }
}
