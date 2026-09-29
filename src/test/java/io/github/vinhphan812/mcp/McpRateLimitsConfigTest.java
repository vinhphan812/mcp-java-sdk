package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpSecurityDefaults;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Tests configurable ADR-0011 rate limits. */
class McpRateLimitsConfigTest {
    @Test
    void defaultsMatchSecurityDefaults() {
        RateLimits limits = RateLimits.defaults();
        assertEquals(McpSecurityDefaults.MAX_CONCURRENT_SESSIONS, limits.maxConcurrentSessions);
        assertEquals(McpSecurityDefaults.CATEGORY_READ_BURST_LIMIT, limits.readBurst);
        assertEquals(McpSecurityDefaults.CATEGORY_ADMIN_CONCURRENT_CAP, limits.adminConcurrent);
        assertEquals(McpSecurityDefaults.DESTRUCTIVE_CAP_SHUTDOWN, limits.shutdownCap);
        assertEquals(McpSecurityDefaults.ABUSE_SCORE_BLOCK_THRESHOLD, limits.abuseScoreBlockThreshold);
    }

    @Test
    void destructiveDefaultsAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> McpSecurityDefaults.DESTRUCTIVE_TOOLS.add("unexpected-tool"));
        assertEquals(6, McpSecurityDefaults.DESTRUCTIVE_TOOLS.size());
    }

    @Test
    void destructiveToolOverridesAreDefensiveCopies() {
        java.util.Set<String> override = new java.util.LinkedHashSet<>();
        override.add("custom-dangerous-tool");

        RateLimits limits = RateLimits.builder().destructiveTools(override).build();
        override.add("added-after-build");

        assertEquals(Collections.singleton("custom-dangerous-tool"), limits.destructiveTools);
        assertThrows(UnsupportedOperationException.class,
                () -> limits.destructiveTools.add("unexpected-tool"));
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
        assertSame(limits, config.rateLimits);
        assertEquals(2, config.rateLimits.maxConcurrentSessions);
        assertEquals(5, config.rateLimits.maxRequestsPerIpPerMinute);
        assertEquals(4, config.rateLimits.readBurst);
        assertEquals(Collections.singleton("custom-dangerous-tool"), config.rateLimits.destructiveTools);
    }

    @Test
    void nullRateLimitsUseDefaults() {
        McpServerConfig config = McpServerConfig.builder().rateLimits(null).build();
        assertEquals(McpSecurityDefaults.CATEGORY_WRITE_BURST_LIMIT, config.rateLimits.writeBurst);
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RateLimits.builder().read(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> RateLimits.builder().sessionTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> RateLimits.builder().shutdown(1, 0));
    }
}
