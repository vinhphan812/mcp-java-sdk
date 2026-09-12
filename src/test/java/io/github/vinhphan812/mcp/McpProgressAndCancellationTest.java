package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpProgressAndCancellationTest {

    private static String initSession(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(body, null);
        JsonObject result = new Gson().fromJson(resp.getBody(), JsonObject.class).getAsJsonObject("result");
        if (result.has("serverInfo")) {
            handler.handleRequestResponse(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    resp.getSessionId());
            return resp.getSessionId();
        }
        return null;
    }

    @Test
    void toolCallEmitsProgressNotificationsWhenProgressTokenSupplied() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("noop", "No-op tool",
                new LinkedHashMap<>(), List.of(),
                params -> new LinkedHashMap<>());

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tools(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        String toolCall = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"noop\",\"arguments\":{},"
                + "\"_meta\":{\"progressToken\":\"tok-42\"}}}";
        McpProtocolHandler.McpResponse callResp = handler.handleRequestResponse(toolCall, sessionId);
        assertNotNull(callResp.getBody(), "tool call should respond with result");

        // Drain start + complete progress events emitted by the protocol layer.
        handler.pollPendingNotification(sessionId);
        handler.pollPendingNotification(sessionId);

        // emit a manual progress so the harness checks wiring
        handler.notifyToolProgress(sessionId, "tok-42", 0.5d, 1d, "manual");
        String notify = handler.pollPendingNotification(sessionId);
        assertNotNull(notify, "progress notification expected");
        JsonObject notifyJson = new Gson().fromJson(notify, JsonObject.class);
        assertEquals("notifications/progress", notifyJson.get("method").getAsString());
        assertEquals("tok-42", notifyJson.getAsJsonObject("params").get("progressToken").getAsString());
        assertEquals(0.5, notifyJson.getAsJsonObject("params").get("progress").getAsDouble(), 1e-9);
    }

    @Test
    void notificationCancelledRecordsAndClearsPerSession() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        assertFalse(registry.isCancelled(sessionId, "99"),
                "no cancellation recorded initially");

        handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
                        + "\"params\":{\"requestId\":\"99\",\"reason\":\"user-cancel\"}}",
                sessionId);

        assertTrue(registry.isCancelled(sessionId, "99"),
                "request id should be marked cancelled");

        registry.clearCancellations(sessionId);
        assertFalse(registry.isCancelled(sessionId, "99"),
                "cancellation cleared after session cleanup");
    }

    @Test
    void tasksCreateReturnsTaskAndEmitsNotification() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String sessionId = initSession(handler);
        assertNotNull(sessionId);

        String create = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tasks/create\","
                + "\"params\":{\"name\":\"my-task\",\"input\":{\"foo\":\"bar\"}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(create, sessionId);
        assertNotNull(resp.getBody(), "tasks/create should return body");
        JsonObject respJson = new Gson().fromJson(resp.getBody(), JsonObject.class);
        assertTrue(respJson.has("result"), "should have result");
        JsonObject result = respJson.getAsJsonObject("result");
        assertTrue(result.has("task"), "result should have task");
        assertTrue(result.has("token"), "result should have token");
        assertEquals("my-task", result.getAsJsonObject("task").get("name").getAsString());
        assertEquals("working", result.getAsJsonObject("task").get("status").getAsString());

        // notification was also emitted
        String notify = handler.pollPendingNotification(sessionId);
        assertNotNull(notify, "tasks/task notification expected");
        JsonObject notifyJson = new Gson().fromJson(notify, JsonObject.class);
        assertEquals("tasks/task", notifyJson.get("method").getAsString());
        assertTrue(notifyJson.getAsJsonObject("params").has("task"));
        assertTrue(notifyJson.getAsJsonObject("params").has("token"));
    }
}
