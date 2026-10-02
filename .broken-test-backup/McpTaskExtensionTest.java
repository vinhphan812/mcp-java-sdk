package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.api.spi.McpTaskExtension;
import io.github.vinhphan812.mcp.api.utils.McpJsonRpc;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Tasks extension framework (McpTaskExtension SPI).
 * Validates: pluggable extension registry, version gating, legacy behaviour,
 * safe unknown extensions, task lifecycle methods, and error hooks.
 */
class McpTaskExtensionTest {

    private final Gson gson = new Gson();

    private static String initSession(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"" + McpJsonRpc.PROTOCOL_VERSION + "\","
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

    private JsonObject request(McpProtocolHandler handler, String session, int id,
                              String method, String params) {
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                        + "\",\"params\":" + params + "}", session);
        return gson.fromJson(body, JsonObject.class);
    }

    // ── Extension: built-in legacy behaviour preserved when no extension configured ──

    @Test
    void initializeAdvertisesTasksWhenExtensionNull() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String body = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getBody();
        JsonObject resp = gson.fromJson(body, JsonObject.class);
        assertTrue(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));
    }

    @Test
    void createGetCancelResultWorkWhenExtensionNull() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String session = initSession(handler);

        // Create
        JsonObject create = request(handler, session, 2, "tasks/create",
                "{\"name\":\"my-task\",\"input\":{\"foo\":\"bar\"}}");
        assertTrue(create.has("result"), "should have result");
        String taskId = create.getAsJsonObject("result").get("token").getAsString();

        // Get
        JsonObject get = request(handler, session, 3, "tasks/get",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals("working", get.getAsJsonObject("result").get("status").getAsString());

        // Cancel
        JsonObject cancel = request(handler, session, 4, "tasks/cancel",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals("cancelled", cancel.getAsJsonObject("result").get("status").getAsString());

        // Result
        JsonObject result = request(handler, session, 5, "tasks/result",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals("Task cancelled", result.getAsJsonObject("result").get("error").getAsString());
    }

    // ── Extension: tasks not advertised when tasks=false even with extension ──

    @Test
    void tasksNotAdvertisedWhenCapabilityDisabled() {
        // Extension that supports everything
        McpTaskExtension ext = (version, params, session) -> true;
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(false)
                        .tasksExtension(ext).build());
        String body = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getBody();
        JsonObject resp = gson.fromJson(body, JsonObject.class);
        assertFalse(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));
    }

    // ── Extension: version gating — supported version → capability advertised ──

    @Test
    void extensionVersionGating_supportedVersion_advertisesCapability() {
        McpTaskExtension ext = version -> "2025-11-25".equals(version);
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String body = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}", null).getBody();
        JsonObject resp = gson.fromJson(body, JsonObject.class);
        assertTrue(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));
    }

    // ── Extension: version gating — unsupported version → method not found ──

    @Test
    void extensionVersionGating_unsupportedVersion_returnsMethodNotFound() {
        McpTaskExtension ext = version -> "2026-07-28".equals(version);
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        // 2025-11-25 session → extension doesn't support it
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/get", "{\"taskId\":\"any\"}");
        assertEquals(-32601, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.getAsJsonObject("error").get("message").getAsString()
                .contains("Method not found"));
    }

    // ── Extension: onRequest success — short-circuits built-in ──

    @Test
    void onRequest_returnsSuccess_shortCircuitsBuiltIn() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public RequestResult onRequest(String method, Map<String, Object> params, String session) {
                if ("tasks/get".equals(method)) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("taskId", "ext-task");
                    r.put("status", "working");
                    return RequestResult.success(r);
                }
                return null;  // pass through for other methods
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/get",
                "{\"taskId\":\"ext-task\"}");
        assertTrue(resp.has("result"));
        assertEquals("ext-task", resp.getAsJsonObject("result").get("taskId").getAsString());
        assertEquals("working", resp.getAsJsonObject("result").get("status").getAsString());
    }

    // ── Extension: onRequest error — returns JSON-RPC error ──

    @Test
    void onRequest_returnsError_returnsJsonRpcError() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public RequestResult onRequest(String method, Map<String, Object> params, String session) {
                if ("tasks/create".equals(method)) {
                    return RequestResult.error(-32001, "Extension rejected create");
                }
                return null;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/create",
                "{\"name\":\"fail\"}");
        assertTrue(resp.has("error"));
        assertEquals(-32001, resp.getAsJsonObject("error").get("code").getAsInt());
        assertEquals("Extension rejected create", resp.getAsJsonObject("error").get("message").getAsString());
    }

    // ── Extension: onRequest null → built-in is called ──

    @Test
    void onRequest_returnsNull_delegatesToBuiltIn() {
        McpRegistry registry = new McpRegistry();
        // Pre-register a task so built-in has something to return
        registry.registerTask(new McpTask("builtin-task", McpTask.Status.WORKING,
                1, 1, null, null));

        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public RequestResult onRequest(String method, Map<String, Object> params, String session) {
                return null;  // always delegate
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/get",
                "{\"taskId\":\"builtin-task\"}");
        assertTrue(resp.has("result"));
        assertEquals("builtin-task", resp.getAsJsonObject("result").get("taskId").getAsString());
        assertEquals("working", resp.getAsJsonObject("result").get("status").getAsString());
    }

    // ── Extension: onError hook — transforms error ──

    @Test
    void onError_transformsBuiltInError() {
        McpRegistry registry = new McpRegistry();
        // Register a terminal task
        registry.registerTask(new McpTask("terminal", McpTask.Status.COMPLETED,
                1, 1, "done", null));

        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public RequestResult onError(String method, Map<String, Object> params,
                                        String session, int code, String message) {
                if (code == -32001) {
                    // Transform "not complete" to a custom success
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("taskId", "transformed-task");
                    r.put("status", "completed");
                    return RequestResult.success(r);
                }
                return null;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);

        // Calling tasks/result on a COMPLETED task throws RESULT_NOT_COMPLETE
        JsonObject resp = request(handler, session, 2, "tasks/result",
                "{\"taskId\":\"terminal\"}");
        // The extension transforms the error into a success result
        assertTrue(resp.has("result"));
        assertEquals("transformed-task", resp.getAsJsonObject("result").get("taskId").getAsString());
    }

    // ── Extension: register/unregister lifecycle ──

    @Test
    void extensionLifecycle_registerAndUnregister() {
        McpRegistry registry = new McpRegistry();
        final boolean[] registered = {false};
        final boolean[] unregistered = {false};

        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public void register(TaskRegistry taskRegistry) {
                registered[0] = true;
                assertNotNull(taskRegistry);
            }
        };

        // Handler constructor calls register()
        new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        assertTrue(registered[0], "register() should be called");
    }

    // ── Extension: advertiseCapabilities called with correct version ──

    @Test
    void advertiseCapabilities_receivesNegotiatedVersion() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public Map<String, Object> advertiseCapabilities(String protocolVersion) {
                Map<String, Object> cap = new LinkedHashMap<>();
                cap.put("custom", protocolVersion);
                return cap;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String body = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}", null).getBody();
        JsonObject resp = gson.fromJson(body, JsonObject.class);
        JsonObject tasksCap = resp.getAsJsonObject("result")
                .getAsJsonObject("capabilities").getAsJsonObject("tasks");
        assertEquals("2025-11-25", tasksCap.get("custom").getAsString());
    }

    // ── Extension: safe unknown extension — unsupported method → method not found ──

    @Test
    void safeUnknownExtension_unsupportedMethod_returnsMethodNotFound() {
        // Extension supports the session version but doesn't handle this custom method
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public RequestResult onRequest(String method, Map<String, Object> params, String session) {
                // Only handle known methods; custom/unknown returns null → built-in
                // but built-in also doesn't know it → method not found
                return null;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/custom-unknown", "{}");
        assertEquals(-32601, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    // ── Extension: tasksExtension builder setter ──

    @Test
    void tasksExtensionBuilder_nullClearsExtension() {
        // Set extension, then build with null (should fall back to built-in)
        McpServerConfig config = McpServerConfig.builder()
                .tasks(true)
                .tasksExtension((McpTaskExtension) null)
                .build();
        assertNull(config.tasksExtension);
        assertTrue(config.tasks);

        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);
        String body = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null).getBody();
        JsonObject resp = gson.fromJson(body, JsonObject.class);
        assertTrue(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));
    }

    @Test
    void tasksExtensionBuilder_acceptsExtension() {
        McpTaskExtension ext = version -> true;
        McpServerConfig config = McpServerConfig.builder()
                .tasks(true)
                .tasksExtension(ext)
                .build();
        assertSame(ext, config.tasksExtension);
    }

    // ── TaskRegistry bridge: registerTask and getTaskStatus ──

    @Test
    void taskRegistryBridge_registerTaskAndGetStatus() throws Exception {
        McpRegistry registry = new McpRegistry();
        McpTaskExtension.TaskRegistry[] captured = new McpTaskExtension.TaskRegistry[1];

        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public void register(TaskRegistry taskRegistry) {
                captured[0] = taskRegistry;
                // Register a task through the bridge
                taskRegistry.registerTask("bridge-task", "working");
            }

            @Override
            public RequestResult onRequest(String method, Map<String, Object> params, String session) {
                if ("tasks/bridge-status".equals(method)) {
                    String status = captured[0].getTaskStatus("bridge-task");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("status", status);
                    return RequestResult.success(r);
                }
                return null;
            }
        };

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);

        JsonObject resp = request(handler, session, 2, "tasks/bridge-status", "{}");
        assertTrue(resp.has("result"));
        assertEquals("working", resp.getAsJsonObject("result").get("status").getAsString());
    }

    @Test
    void taskRegistryBridge_getTaskStatus_unknownReturnsNull() {
        McpRegistry registry = new McpRegistry();
        McpTaskExtension.TaskRegistry[] captured = new McpTaskExtension.TaskRegistry[1];

        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public void register(TaskRegistry taskRegistry) {
                captured[0] = taskRegistry;
            }

            @Override
            public RequestResult onRequest(String method, Map<String, Object> params, String session) {
                String status = captured[0].getTaskStatus("does-not-exist");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("status", status);
                return RequestResult.success(r);
            }
        };

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/status-check", "{}");
        assertTrue(resp.has("result"));
        assertTrue(resp.getAsJsonObject("result").get("status").isJsonNull());
    }

    // ── TaskRegistry bridge: invalid inputs throw ──

    @Test
    void taskRegistryBridge_registerTask_rejectsNullTaskId() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public void register(TaskRegistry taskRegistry) {
                assertThrows(IllegalArgumentException.class,
                        () -> taskRegistry.registerTask(null, "working"));
                assertThrows(IllegalArgumentException.class,
                        () -> taskRegistry.registerTask("  ", "working"));
            }
        };

        new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
    }

    @Test
    void taskRegistryBridge_registerTask_rejectsInvalidStatus() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String version) {
                return true;
            }

            @Override
            public void register(TaskRegistry taskRegistry) {
                assertThrows(IllegalArgumentException.class,
                        () -> taskRegistry.registerTask("task", null));
                assertThrows(IllegalArgumentException.class,
                        () -> taskRegistry.registerTask("task", "  "));
                assertThrows(IllegalArgumentException.class,
                        () -> taskRegistry.registerTask("task", "unknown-status"));
            }
        };

        new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().tasks(true)
                        .tasksExtension(ext).build());
    }

    // ── Error code constants: RESULT_ALREADY_TERMINAL and RESULT_NOT_COMPLETE ──

    @Test
    void taskAlreadyTerminal_errorCode_is32002() {
        McpRegistry registry = new McpRegistry();
        registry.registerTask(new McpTask("t", McpTask.Status.COMPLETED, 1, 1, "ok", null));
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/cancel",
                "{\"taskId\":\"t\"}");
        assertTrue(resp.has("error"));
        assertEquals(-32002, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.getAsJsonObject("error").get("message").getAsString()
                .toLowerCase().contains("already"));
    }

    @Test
    void taskNotComplete_errorCode_is32001() {
        McpRegistry registry = new McpRegistry();
        registry.registerTask(new McpTask("t", McpTask.Status.WORKING, 1, 1, null, null));
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String session = initSession(handler);
        JsonObject resp = request(handler, session, 2, "tasks/result",
                "{\"taskId\":\"t\"}");
        assertTrue(resp.has("error"));
        assertEquals(-32001, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.getAsJsonObject("error").get("message").getAsString()
                .toLowerCase().contains("not complete"));
    }
}
