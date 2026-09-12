package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class McpListChangedNotificationTest {
    @Test
    void successfulRegistrationsNotifyExistingSessionByListType() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry);
        String session = handler.handleRequestResponse(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();

        registry.registerTool("tool", "tool", Collections.emptyMap(),
                Collections.emptyList(), params -> Collections.emptyMap());
        registry.registerResource("test://resource", "resource", "resource", "text/plain", uri -> "ok");
        registry.registerResourceTemplate("test://{id}", "template", "template", "text/plain", uri -> "ok");
        registry.registerPrompt("prompt", "prompt", null, arguments -> Collections.emptyMap());

        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}",
                handler.pollPendingNotification(session));
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/list_changed\"}",
                handler.pollPendingNotification(session));
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/resources/list_changed\"}",
                handler.pollPendingNotification(session));
        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/prompts/list_changed\"}",
                handler.pollPendingNotification(session));
        assertNull(handler.pollPendingNotification(session));
    }

    @Test
    void failedDuplicateRegistrationDoesNotNotify() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry);
        String session = handler.handleRequestResponse(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
        registry.registerTool("tool", "tool", null, null, params -> Collections.emptyMap());
        assertThrows(IllegalArgumentException.class, () -> registry.registerTool(
                "tool", "duplicate", null, null, params -> Collections.emptyMap()));
        assertNotNull(handler.pollPendingNotification(session));
        assertNull(handler.pollPendingNotification(session));
    }
}
