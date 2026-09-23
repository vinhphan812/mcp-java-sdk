package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpResourceHandler;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ADR-0011 queue overflow handling.
 */
@SuppressWarnings("unused")
class McpQueueOverflowTest {

    @Test
    void testQueueOverflowWithListener() {
        final boolean[] overflowCalled = {false};
        final String[] overflowSession = {null};
        McpProtocolHandler.QueueOverflowListener listener = sessionId -> {
            overflowCalled[0] = true;
            overflowSession[0] = sessionId;
        };

        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .tools(true)
                .resources(true)
                .resourceSubscriptions(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config, listener, null);

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        // Subscribe to a resource
        registry.registerResource("demo://test", "Test", "Test resource", new McpResourceHandler() {
            public String read(String uri) {
                return "{}";
            }
        });
        handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"demo://test\"}}",
                sessionId);

        // Overflow the queue
        for (int i = 0; i < 100; i++) {
            handler.notifyResourceUpdated("demo://test");
        }
        // 101st notification should trigger overflow
        handler.notifyResourceUpdated("demo://test");

        assertTrue(overflowCalled[0], "Overflow listener should have been called");
        assertEquals(sessionId, overflowSession[0], "Overflow should report correct session ID");
    }

    @Test
    void testQueueOverflowWithoutListenerThrows() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .tools(true)
                .resources(true)
                .resourceSubscriptions(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config, null, null);

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        registry.registerResource("demo://test", "Test", "Test resource", new McpResourceHandler() {
            public String read(String uri) {
                return "{}";
            }
        });
        handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"demo://test\"}}",
                sessionId);

        // Overflow the queue — should throw without listener
        for (int i = 0; i < 100; i++) {
            handler.notifyResourceUpdated("demo://test");
        }
        assertThrows(McpProtocolHandler.QueueOverflowException.class, () -> {
            handler.notifyResourceUpdated("demo://test");
        });
    }

    @Test
    void testQueueAcceptsUpTo100Notifications() {
        final boolean[] overflowCalled = {false};
        McpProtocolHandler.QueueOverflowListener listener = sessionId -> {
            overflowCalled[0] = true;
        };

        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .tools(true)
                .resources(true)
                .resourceSubscriptions(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config, listener, null);

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        registry.registerResource("demo://test", "Test", "Test resource", new McpResourceHandler() {
            public String read(String uri) {
                return "{}";
            }
        });
        handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"demo://test\"}}",
                sessionId);

        // 100 notifications should be OK
        for (int i = 0; i < 100; i++) {
            handler.notifyResourceUpdated("demo://test");
        }
        assertFalse(overflowCalled[0], "Overflow should not trigger at exactly 100 notifications");
    }
}
