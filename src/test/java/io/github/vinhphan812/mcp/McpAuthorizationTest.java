package io.github.vinhphan812.mcp;

import org.junit.jupiter.api.Test;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Disabled;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unused")
class McpAuthorizationTest {

    private static McpToolHandler makeTool(String name) {
        return args -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tool", name);
            return r;
        };
    }

    @Test
    void testAuthorizationNullAllowsAll() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .authorization(null)
                .tools(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        registry.registerTool("test_tool", "Test tool",
                new LinkedHashMap<>(), null, makeTool("test_tool"));

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"test_tool\"}}",
                sessionId);
        assertFalse(response.contains("\"error\""), "Tool should be allowed when authorization is null");
        assertTrue(response.contains("\"result\""), "Tool should return a result");
    }

    @Test
    void testAuthorizationDenialReturned() {
        McpAuthorization authorization = (scopes, conf, args) -> "Access denied";

        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .authorization(authorization)
                .tools(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        registry.registerTool("test_tool", "Test tool",
                new LinkedHashMap<>(), null, makeTool("test_tool"));

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"test_tool\"}}",
                sessionId);
        assertTrue(response.contains("Access denied"), "Response should contain denial message");
        assertTrue(response.contains("isError"), "Response should mark isError=true");
    }

    @Test
    void testAuthorizationNullAllowsTool() {
        final boolean[] called = {false};
        McpAuthorization authorization = (scopes, conf, args) -> {
            called[0] = true;
            return null;
        };

        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .authorization(authorization)
                .tools(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        registry.registerTool("test_tool", "Test tool",
                new LinkedHashMap<>(), null, makeTool("test_tool"));

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"test_tool\"}}",
                sessionId);

        assertTrue(called[0], "Authorization.denial() should have been called");
        assertTrue(response.contains("\"result\""), "Tool should be executed and return result");
    }

    @Test
    @Disabled("registerTool with scopes signature not implemented")
    void testAdminScopeDenied() {
        // TODO: Implement registerTool with scopes parameter
        fail("Not implemented");
    }
}
