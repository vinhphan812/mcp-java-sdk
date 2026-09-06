package io.github.vinhphan812.mcp;

import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpProtocolHandlerTest {
    @Test
    void initializeCreatesSessionAndAdvertisesConfig() {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("test-server")
                .serverVersion("2.0.0")
                .resources(false)
                .prompts(false)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        assertNotNull(response.getBody());
        assertNotNull(response.getSessionId());
        assertTrue(handler.hasSession(response.getSessionId()));
        JsonObject body = new com.google.gson.Gson().fromJson(response.getBody(), JsonObject.class);
        assertEquals("test-server", body.getAsJsonObject("result")
                .getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    void invalidJsonRpcEnvelopeReturnsInvalidRequest() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
        String response = handler.handleRequest("{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", null);
        assertTrue(response.contains("-32600"));
    }

    @Test
    void unknownToolReturnsJsonRpcInvalidParamsError() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
        McpProtocolHandler.McpResponse initialized = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String response = handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\"}}", initialized.getSessionId());
        assertTrue(response.contains("-32602"));
    }

    @Test
    void rejectsProtectedRequestWithoutSession() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
        String body = handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
        assertTrue(body.contains("Missing or invalid MCP session"));
    }
}
