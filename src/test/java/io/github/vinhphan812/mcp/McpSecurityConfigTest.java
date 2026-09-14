package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ADR-0011 security configuration options.
 */
@SuppressWarnings("unused")
class McpSecurityConfigTest {

    @Test
    void testOverflowListenerSetAndRetrieved() {
        McpProtocolHandler.QueueOverflowListener listener = sessionId -> { };
        McpServerConfig config = McpServerConfig.builder()
                .overflowListener(listener)
                .build();
        assertSame(listener, config.getOverflowListener());
    }

    @Test
    void testAuthorizationSetAndRetrieved() {
        McpAuthorization authorization = (scopes, conf, args) -> null;
        McpServerConfig config = McpServerConfig.builder()
                .authorization(authorization)
                .build();
        assertSame(authorization, config.getAuthorization());
    }

    @Test
    void testDefaultOverflowListenerIsNull() {
        McpServerConfig config = McpServerConfig.builder().build();
        assertNull(config.getOverflowListener());
    }

    @Test
    void testDefaultAuthorizationIsNull() {
        McpServerConfig config = McpServerConfig.builder().build();
        assertNull(config.getAuthorization());
    }
}
