package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpTasksTest {
    private final Gson gson = new Gson();

    private McpProtocolHandler handler() {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("task-server").serverVersion("1.0")
                .resources(false).prompts(false).tasks(true).build();
        return new McpProtocolHandler(new McpRegistry(), config);
    }

    private McpProtocolHandler disabledHandler() {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("task-server").serverVersion("1.0")
                .resources(false).prompts(false).tasks(false).build();
        return new McpProtocolHandler(new McpRegistry(), config);
    }

    private String initialize(McpProtocolHandler handler) {
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        assertNotNull(response.getBody());
        return response.getSessionId();
    }

    private JsonObject request(McpProtocolHandler handler, String session, int id, String method, String params) {
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                        + "\",\"params\":" + params + "}", session);
        return gson.fromJson(body, JsonObject.class);
    }

    @Test
    void rejectsInvalidTaskConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask(" ", McpTask.Status.WORKING, 1, 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", null, 1, 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", McpTask.Status.WORKING, -1, 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", McpTask.Status.WORKING, 2, 1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", McpTask.Status.WORKING, 1, 1, "result", null));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", McpTask.Status.COMPLETED, 1, 1, null, "error"));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", McpTask.Status.FAILED, 1, 1, null, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new McpTask("task", McpTask.Status.CANCELLED, 1, 1, null, null));
    }

    @Test
    void rejectsInvalidTaskRegistrationAndFailureErrors() {
        McpRegistry registry = new McpRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.registerTask(null));
        assertThrows(IllegalArgumentException.class, () -> registry.failTask("missing", " "));
        McpTask task = registry.createTask();
        assertThrows(IllegalArgumentException.class, () -> registry.failTask(task.getTaskId(), null));
        assertThrows(IllegalArgumentException.class, () -> registry.failTask(task.getTaskId(), "\t"));
    }

    @Test
    void initializeAdvertisesTasksCapability() {
        McpProtocolHandler handler = handler();
        JsonObject body = gson.fromJson(handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getBody(), JsonObject.class);
        assertTrue(body.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));
    }

    @Test
    void disabledTasksReturnCapabilityErrorsForAllTaskMethods() {
        McpProtocolHandler handler = disabledHandler();
        String session = initialize(handler);
        for (String method : new String[]{"tasks/get", "tasks/result", "tasks/cancel"}) {
            JsonObject response = request(handler, session, 2, method, "{}");
            assertEquals(-32601, response.getAsJsonObject("error").get("code").getAsInt());
            assertEquals("MCP capability is disabled: tasks",
                    response.getAsJsonObject("error").get("message").getAsString());
        }
    }

    @Test
    void malformedTaskParamsReturnDeterministicInvalidParamsErrors() {
        McpProtocolHandler handler = handler();
        String session = initialize(handler);
        String[] params = {"null", "[]", "{}", "{\"taskId\":null}",
                "{\"taskId\":1}", "{\"taskId\":\" \"}", "{\"taskId\":\"missing\"}"};
        for (String rawParams : params) {
            JsonObject response = request(handler, session, 2, "tasks/get", rawParams);
            assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt(), rawParams);
            assertTrue(response.getAsJsonObject("error").get("message").getAsString()
                    .startsWith("Invalid params: taskId is required")
                    || response.getAsJsonObject("error").get("message").getAsString()
                    .equals("Unknown task: missing"), rawParams);
        }
    }

    @Test
    void createGetCancelAndResultExposeBoundedLifecycle() {
        McpProtocolHandler handler = handler();
        String session = initialize(handler);
        McpTask task = handler.createTask();

        JsonObject working = request(handler, session, 2, "tasks/get", "{\"taskId\":\"" + task.getTaskId() + "\"}");
        assertEquals("working", working.getAsJsonObject("result").get("status").getAsString());

        JsonObject cancelled = request(handler, session, 3, "tasks/cancel", "{\"taskId\":\"" + task.getTaskId() + "\"}");
        assertEquals("cancelled", cancelled.getAsJsonObject("result").get("status").getAsString());

        JsonObject result = request(handler, session, 4, "tasks/result", "{\"taskId\":\"" + task.getTaskId() + "\"}");
        assertEquals("Task cancelled", result.getAsJsonObject("result").get("error").getAsString());
    }

    @Test
    void completedAndFailedTasksReturnResultAndError() {
        McpProtocolHandler handler = handler();
        String session = initialize(handler);
        McpTask completed = handler.createTask();
        handler.completeTask(completed.getTaskId(), "value");
        JsonObject result = request(handler, session, 2, "tasks/result", "{\"taskId\":\"" + completed.getTaskId() + "\"}");
        assertEquals("value", result.getAsJsonObject("result").get("result").getAsString());

        McpTask failed = handler.createTask();
        handler.failTask(failed.getTaskId(), "boom");
        JsonObject error = request(handler, session, 3, "tasks/result", "{\"taskId\":\"" + failed.getTaskId() + "\"}");
        assertEquals("boom", error.getAsJsonObject("result").get("error").getAsString());
    }

    @Test
    void workingResultAndRepeatedCancelReturnProtocolErrors() {
        McpProtocolHandler handler = handler();
        String session = initialize(handler);
        McpTask task = handler.createTask();
        JsonObject working = request(handler, session, 2, "tasks/result", "{\"taskId\":\"" + task.getTaskId() + "\"}");
        assertEquals(-32001, working.get("error").getAsJsonObject().get("code").getAsInt());
        request(handler, session, 3, "tasks/cancel", "{\"taskId\":\"" + task.getTaskId() + "\"}");
        JsonObject repeated = request(handler, session, 4, "tasks/cancel", "{\"taskId\":\"" + task.getTaskId() + "\"}");
        assertEquals(-32002, repeated.get("error").getAsJsonObject().get("code").getAsInt());
    }
}
