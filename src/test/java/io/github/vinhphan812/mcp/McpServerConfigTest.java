package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.McpServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConfigTest {
    @Test
    void defaultsArePortableAndConsistent() {
        McpServerConfig config = McpServerConfig.builder().build();
        assertEquals("2025-11-25", config.protocolVersion);
        assertTrue(config.tools);
        assertTrue(config.resources);
        assertTrue(config.resourceSubscriptions);
        assertTrue(config.prompts);
    }

    @Test
    void subscriptionsDisableWithResources() {
        McpServerConfig config = McpServerConfig.builder()
                .resources(false)
                .resourceSubscriptions(true)
                .build();
        assertFalse(config.resourceSubscriptions);
    }

    @Test
    void rejectsBlankMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> McpServerConfig.builder().serverName(" ").build());
    }
}
