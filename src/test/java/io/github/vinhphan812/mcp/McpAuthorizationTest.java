package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ADR-0011 McpAuthorization SPI.
 */
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
    void testAdminScopeDenied() {
        final boolean[] called = {false};
        final String[][] receivedScopes = {null};
        McpAuthorization authorization = (scopes, conf, args) -> {
            called[0] = true;
            receivedScopes[0] = scopes;
            return null;
        };

        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .authorization(authorization)
                .tools(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        // Use overload with scopes
        registry.registerTool("admin_tool", "Admin tool",
                new LinkedHashMap<>(), null,
                List.of("admin"), false,
                args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("status", "admin action executed");
                    return r;
                });

        String sessionId = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"admin_tool\"}}",
                sessionId);

        assertTrue(called[0], "Authorization.denial() should have been called");
        assertNotNull(receivedScopes[0], "Scopes should have been provided");
        assertTrue(Arrays.asList(receivedScopes[0]).contains("admin"),
                "Admin scope should be included");
    }
}
