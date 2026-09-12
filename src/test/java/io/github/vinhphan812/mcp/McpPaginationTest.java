package io.github.vinhphan812.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class McpPaginationTest {
    @Test
    void toolsListReturnsFirstAndNextPage() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("one", "one", Collections.emptyMap(), Collections.emptyList(), p -> Collections.emptyMap());
        registry.registerTool("two", "two", Collections.emptyMap(), Collections.emptyList(), p -> Collections.emptyMap());
        McpProtocolHandler handler = new McpProtocolHandler(registry, McpServerConfig.builder().pageSize(1).build());
        String session = handler.handleRequestResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();

        JsonObject first = parse(handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session));
        assertEquals(1, first.getAsJsonObject("result").getAsJsonArray("tools").size());
        String cursor = first.getAsJsonObject("result").get("nextCursor").getAsString();
        JsonObject second = parse(handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"" + cursor + "\"}}", session));
        assertEquals("two", second.getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(second.getAsJsonObject("result").has("nextCursor"));
    }

    @Test
    void resourcesListRejectsInvalidCursor() {
        McpRegistry registry = new McpRegistry();
        registry.registerResource("test://one", "one", "one", "text/plain", uri -> "one");
        McpProtocolHandler handler = new McpProtocolHandler(registry);
        String session = handler.handleRequestResponse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getSessionId();
        String response = handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\",\"params\":{\"cursor\":\"bad\"}}", session);
        assertTrue(response.contains("-32602"));
    }

    private static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }
}
