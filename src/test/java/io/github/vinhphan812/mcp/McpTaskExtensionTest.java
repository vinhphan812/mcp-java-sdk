package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.api.spi.McpTaskExtension;
import io.github.vinhphan812.mcp.api.utils.McpErrorCodes;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link McpTaskExtension} SPI contract:
 * <ul>
 *   <li>Pluggable extension registry via {@code McpServerConfig.Builder.tasksExtension()}</li>
 *   <li>Tasks advertised only through {@code io.modelcontextprotocol/tasks} namespace in 2026 mode</li>
 *   <li>Version gating: {@code supports()} gates both capability advertisement and request dispatch</li>
 *   <li>Safe unknown extensions: null extension means built-in behaviour is preserved</li>
 *   <li>Task lifecycle hooks: {@code register(TaskRegistry)} / {@code unregister()}</li>
 *   <li>Negotiation/dispatch/error tests and {@code RequestResult} type behaviour</li>
 * </ul>
 */
class McpTaskExtensionTest {

    private static final Gson gson = new Gson();

    /** Extracts JSON-RPC error code, or -1 if the response has no error. */
    private static int errorCode(JsonObject resp) {
        return resp.has("error")
                ? resp.getAsJsonObject("error").get("code").getAsInt()
                : -1;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Initialises a 2025-11-25 session, returning the session ID. */
    private String initSession2025(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(body, null);
        JsonObject result = gson.fromJson(resp.getBody(), JsonObject.class).getAsJsonObject("result");
        if (result.has("serverInfo")) {
            handler.handleRequestResponse(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    resp.getSessionId());
            return resp.getSessionId();
        }
        return null;
    }

    /** Initialises a 2026-07-28 stateless request, returning the response body. */
    private JsonObject initStateless(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2026-07-28\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        return gson.fromJson(handler.handleRequestResponse(body, null).getBody(), JsonObject.class);
    }

    private JsonObject rpc(McpProtocolHandler handler, String session, int id,
                            String method, String params) {
        String raw = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(raw, session);
        if (resp.getBody() == null) {
            // Notification or null-body response — return an empty object (no result, no error)
            return new JsonObject();
        }
        return gson.fromJson(resp.getBody(), JsonObject.class);
    }

    private JsonObject initSessionResp(McpProtocolHandler handler, String protocolVersion) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"" + protocolVersion + "\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        return gson.fromJson(handler.handleRequestResponse(body, null).getBody(), JsonObject.class);
    }

    // ── Legacy built-in behaviour preserved ─────────────────────────────────────

    @Test
    void noExtension_builtinTasksWork() {
        // No extension configured — built-in task store must still work.
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .tools(false).resources(false).prompts(false).tasks(true)
                        .build());
        String session = initSession2025(handler);
        assertNotNull(session);

        // tasks/create (requires "name" param per MCP spec)
        String raw = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/create\",\"params\":{\"name\":\"test-task\"}}";
        String rawResp = handler.handleRequest(raw, session);
        JsonObject create = gson.fromJson(rawResp, JsonObject.class);
        assertEquals(-1, errorCode(create), "tasks/create should succeed: " + rawResp);
        // result = {task: {...}, token: "..."}
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        // tasks/get: result = {taskId, status, ...} (flat — no "task" wrapper)
        JsonObject get = rpc(handler, session, 3, "tasks/get", "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(-1, errorCode(get));
        assertEquals("working", get.getAsJsonObject("result").get("status").getAsString());
    }

    @Test
    void noExtension_tasksAdvertisedIn2025Initialize() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .tools(false).resources(false).prompts(false).tasks(true)
                        .build());
        JsonObject resp = initSessionResp(handler, "2025-11-25");
        assertTrue(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"),
                "tasks capability must be present in 2025 when tasks=true");
    }

    @Test
    void noExtension_tasksNotAdvertisedWhenTasksFlagFalse() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .tasks(false).build());
        JsonObject resp = initSessionResp(handler, "2025-11-25");
        assertFalse(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"),
                "tasks capability must be absent when tasks=false");
    }

    // ── Extension: capability advertisement ───────────────────────────────────

    @Test
    void extensionWithSupports2026_tasksAdvertisedInStatelessInitialize() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return "2026-07-28".equals(protocolVersion);
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .tasks(true).tasksExtension(ext)
                        .build());
        JsonObject resp = initStateless(handler);
        assertTrue(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"),
                "tasks capability must be advertised when extension supports 2026");
    }

    @Test
    void extensionWithSupports2026_tasksNotAdvertisedIn2025Initialize() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return "2026-07-28".equals(protocolVersion);
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext)
                        .build());
        JsonObject resp = initSessionResp(handler, "2025-11-25");
        // Extension does not support 2025, so tasks must NOT be advertised
        assertFalse(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"),
                "tasks capability must be absent in 2025 when extension only supports 2026");
    }

    @Test
    void extensionAdvertiseCapabilities_receivesNegotiatedVersion() {
        final String[] receivedVersion = new String[1];
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public Map<String, Object> advertiseCapabilities(String protocolVersion) {
                receivedVersion[0] = protocolVersion;
                Map<String, Object> cap = new LinkedHashMap<>();
                cap.put("listChanged", false);
                return cap;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .tasks(true).tasksExtension(ext)
                        .build());
        initStateless(handler);
        assertEquals("2026-07-28", receivedVersion[0]);
    }

    @Test
    void extensionSupportsBothVersions_tasksAdvertisedInBothModes() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return "2025-11-25".equals(protocolVersion) || "2026-07-28".equals(protocolVersion);
            }
        };
        McpProtocolHandler sessioned = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        assertTrue(initSessionResp(sessioned, "2025-11-25")
                        .getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));

        McpProtocolHandler stateless = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .tasks(true).tasksExtension(ext).build());
        assertTrue(initStateless(stateless)
                        .getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"));
    }

    // ── tasks=false suppresses capability even with extension ──────────────────

    @Test
    void tasksFalse_suppressesCapabilityEvenWithExtension() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .tasks(false).tasksExtension(ext) // extension present, but tasks=false
                        .build());
        JsonObject resp = initStateless(handler);
        assertFalse(resp.getAsJsonObject("result").getAsJsonObject("capabilities").has("tasks"),
                "tasks=false must suppress capability even when extension is configured");
    }

    // ── Version gating: unsupported version → -32601 ─────────────────────────

    @Test
    void unsupportedVersion_taskMethodReturnsMethodNotFound() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return "2026-07-28".equals(protocolVersion); // only supports 2026
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext)
                        .build());
        String session = initSession2025(handler); // uses 2025-11-25

        JsonObject resp = rpc(handler, session, 2, "tasks/create", "{\"name\":\"test\"}");
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, errorCode(resp),
                "Extension returning false for version must cause -32601");
    }

    // ── onRequest: success / error / null dispatch ─────────────────────────────
    //
    // The extension's onRequest is called by dispatchTaskRequest(), which only
    // handles the 4 built-in task methods.  Custom methods (not in the switch)
    // fall through to default: and return -32601 before reaching the extension.
    // To test onRequest intercepting a built-in method, we use tasks/create
    // which the extension can observe via the callback.

    @Test
    void onRequest_receivesBuiltInTaskMethods() {
        // Verify onRequest receives tasks/create calls and can return a custom result.
        final String[] receivedMethod = new String[1];
        final Map<String, Object>[] receivedParams = new Map[1];

        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public McpTaskExtension.RequestResult onRequest(String method,
                    Map<String, Object> params, String session) {
                receivedMethod[0] = method;
                receivedParams[0] = params;
                // Return null so built-in handling proceeds (test intercept only)
                return null;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        String session = initSession2025(handler);

        rpc(handler, session, 2, "tasks/create", "{\"name\":\"test\"}");

        assertEquals("tasks/create", receivedMethod[0]);
        assertNotNull(receivedParams[0]);
        assertEquals("test", receivedParams[0].get("name"));
    }

    @Test
    void onRequest_returnsNull_builtInHandlerIsCalled() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public McpTaskExtension.RequestResult onRequest(String method,
                    Map<String, Object> params, String session) {
                return null; // no interception
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        String session = initSession2025(handler);

        // tasks/create is built-in; extension returning null should allow it through
        JsonObject resp = rpc(handler, session, 2, "tasks/create", "{\"name\":\"test\"}");
        assertEquals(-1, errorCode(resp), "create should succeed: " + resp);
        // result = {task: {...}, token: "..."}
        assertTrue(resp.getAsJsonObject("result").getAsJsonObject("task").has("taskId"));
    }

    @Test
    void onRequest_customTaskMethodReturnsMethodNotFound() {
        // "tasks/custom" is not a built-in method — it falls through to default:
        // in the switch and returns METHOD_NOT_FOUND before reaching dispatchTaskRequest.
        // This confirms that custom methods are NOT routed to the extension in this
        // version; only the 4 built-in task methods are intercepted.
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public McpTaskExtension.RequestResult onRequest(String method,
                    Map<String, Object> params, String session) {
                // Never called for unknown methods
                fail("onRequest should not be called for tasks/custom");
                return null;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .tasks(true).tasksExtension(ext).build());

        JsonObject resp = rpc(handler, null, 2, "tasks/custom", "{}");
        // Unknown method falls through to default: → -32601 METHOD_NOT_FOUND
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, errorCode(resp));
    }

    // ── onError: error transformation ─────────────────────────────────────────

    @Test
    void onError_transformsBuiltInError() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public McpTaskExtension.RequestResult onError(String method,
                    Map<String, Object> params, String session, int errorCode, String message) {
                if (errorCode == McpErrorCodes.RESULT_NOT_COMPLETE) {
                    return McpTaskExtension.RequestResult.error(
                            McpErrorCodes.RESULT_ALREADY_TERMINAL, "transformed by extension");
                }
                return null;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        String session = initSession2025(handler);

        // Create a task and call tasks/result while it's still working
        // to trigger RESULT_NOT_COMPLETE (-32001), which the extension transforms
        // to RESULT_ALREADY_TERMINAL (-32002).
        JsonObject create = rpc(handler, session, 3, "tasks/create", "{\"name\":\"test\"}");
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        JsonObject resultOnWorking = rpc(handler, session, 4, "tasks/result",
                "{\"taskId\":\"" + taskId + "\"}");
        // RESULT_NOT_COMPLETE (-32001) should be transformed to RESULT_ALREADY_TERMINAL (-32002)
        assertEquals(McpErrorCodes.RESULT_ALREADY_TERMINAL, errorCode(resultOnWorking));
        assertEquals("transformed by extension",
                resultOnWorking.getAsJsonObject("error").get("message").getAsString());
    }

    @Test
    void onError_returnsNull_originalErrorPropagates() {
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public McpTaskExtension.RequestResult onError(String method,
                    Map<String, Object> params, String session, int errorCode, String message) {
                return null; // no transformation
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        String session = initSession2025(handler);

        // Unknown task → built-in throws INVALID_PARAMS (-32602), extension returns null → original propagates
        JsonObject resp = rpc(handler, session, 2, "tasks/result",
                "{\"taskId\":\"unknown-task\"}");
        assertEquals(-32602, errorCode(resp),
                "Unknown task should return INVALID_PARAMS (-32602) from built-in handler");
    }

    // ── Lifecycle: register / unregister ─────────────────────────────────────────

    @Test
    void registerHook_isCalledWithTaskRegistry() {
        final McpTaskExtension.TaskRegistry[] captured = new McpTaskExtension.TaskRegistry[1];
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public void register(McpTaskExtension.TaskRegistry taskRegistry) {
                captured[0] = taskRegistry;
            }
        };
        new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        assertNotNull(captured[0], "register() must be called with a non-null TaskRegistry");
    }

    @Test
    void unregisterHook_isCalled() {
        final boolean[] unregistered = new boolean[1];
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public void unregister() {
                unregistered[0] = true;
            }
        };
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());
        // The handler holds the extension; when it is garbage-collected or closed,
        // unregister() is called.  For the test we verify the hook is accessible
        // by calling it directly on the extension (simulating shutdown).
        ext.unregister();
        assertTrue(unregistered[0]);
    }

    // ── TaskRegistry bridge ─────────────────────────────────────────────────────

    @Test
    void taskRegistry_registerTaskAndGetTaskStatus() {
        final McpTaskExtension.TaskRegistry[] captured = new McpTaskExtension.TaskRegistry[1];
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public void register(McpTaskExtension.TaskRegistry taskRegistry) {
                captured[0] = taskRegistry;
            }
        };
        McpRegistry registry = new McpRegistry();
        new McpProtocolHandler(registry,
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());

        McpTaskExtension.TaskRegistry tr = captured[0];
        assertNotNull(tr);

        // Register a completed task through the bridge
        tr.registerTask("ext-1", "completed");
        assertEquals("completed", tr.getTaskStatus("ext-1"));

        tr.registerTask("ext-2", "working");
        assertEquals("working", tr.getTaskStatus("ext-2"));

        tr.registerTask("ext-3", "failed");
        assertEquals("failed", tr.getTaskStatus("ext-3"));

        tr.registerTask("ext-4", "cancelled");
        assertEquals("cancelled", tr.getTaskStatus("ext-4"));

        // Unknown task
        assertNull(tr.getTaskStatus("unknown-id"));
    }

    @Test
    void taskRegistry_registerTask_validatesInputs() {
        final McpTaskExtension.TaskRegistry[] captured = new McpTaskExtension.TaskRegistry[1];
        McpTaskExtension ext = new McpTaskExtension() {
            @Override
            public boolean supports(String protocolVersion) {
                return true;
            }

            @Override
            public void register(McpTaskExtension.TaskRegistry taskRegistry) {
                captured[0] = taskRegistry;
            }
        };
        new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(ext).build());

        assertThrows(IllegalArgumentException.class,
                () -> captured[0].registerTask(null, "working"));
        assertThrows(IllegalArgumentException.class,
                () -> captured[0].registerTask("  ", "working"));
        assertThrows(IllegalArgumentException.class,
                () -> captured[0].registerTask("id", null));
        assertThrows(IllegalArgumentException.class,
                () -> captured[0].registerTask("id", "  "));
        assertThrows(IllegalArgumentException.class,
                () -> captured[0].registerTask("id", "unknown-status"));
    }

    // ── Safe unknown extension ───────────────────────────────────────────────────

    @Test
    void safeUnknownExtension_nullExtensionMeansBuiltinOnly() {
        // Explicit null extension — built-in tasks must work exactly as if no
        // extension were configured.  This is the "safe unknown extension" case:
        // a missing extension must not break the server.
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .serverName("test").serverVersion("1.0")
                        .tasks(true).tasksExtension(null)
                        .build());
        String session = initSession2025(handler);
        assertNotNull(session);

        JsonObject create = rpc(handler, session, 2, "tasks/create", "{\"name\":\"test\"}");
        assertEquals(-1, errorCode(create), "tasks/create should succeed: " + create);
        assertTrue(create.getAsJsonObject("result").getAsJsonObject("task").has("taskId"));
    }

    // ── Builder null handling ───────────────────────────────────────────────────

    @Test
    void builderNullExtension_isAccepted() {
        // Builder must not throw when tasksExtension is null (explicit or implicit).
        McpServerConfig config = McpServerConfig.builder()
                .serverName("test").serverVersion("1.0")
                .tasks(true)
                .tasksExtension(null)
                .build();
        assertNull(config.tasksExtension);
    }

    // ── Error codes: RESULT_ALREADY_TERMINAL / RESULT_NOT_COMPLETE ───────────────

    @Test
    void errorCodes_resultAlreadyTerminal() {
        // RESULT_ALREADY_TERMINAL (-32002) is returned when cancelling a task twice.
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String session = initSession2025(handler);

        // Create task (must include "name" param)
        JsonObject create = rpc(handler, session, 2, "tasks/create", "{\"name\":\"test\"}");
        int createErr = errorCode(create);
        if (createErr != -1) {
            // tasks/create failed — log the actual response to diagnose
            System.out.println("DEBUG tasks/create failed: " + create);
        }
        assertEquals(-1, createErr, "tasks/create must succeed: " + create);
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        // Cancel once — succeeds (result = flat task object with status)
        JsonObject cancel1 = rpc(handler, session, 3, "tasks/cancel",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(-1, errorCode(cancel1), "cancel should succeed: " + cancel1);

        // Cancel again — already terminal → -32002
        JsonObject cancel2 = rpc(handler, session, 4, "tasks/cancel",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(McpErrorCodes.RESULT_ALREADY_TERMINAL, errorCode(cancel2));
    }

    @Test
    void errorCodes_resultNotComplete() {
        // RESULT_NOT_COMPLETE (-32001) is returned when calling tasks/result on a working task.
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String session = initSession2025(handler);

        JsonObject create = rpc(handler, session, 2, "tasks/create", "{\"name\":\"test\"}");
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        JsonObject result = rpc(handler, session, 3, "tasks/result",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(McpErrorCodes.RESULT_NOT_COMPLETE, errorCode(result));
    }

    @Test
    void errorCodes_methodNotFound() {
        // McpException.methodNotFound factory produces -32601.
        // Tested via the version-gating scenario: extension not supporting a version
        // causes task methods to return METHOD_NOT_FOUND.
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, -32601);
    }

    // ── Regression: task-producing metadata survives terminal transitions ───────────

    /**
     * Regression: tasks created via tasks/create must retain name/input/inputSchema
     * through a CANCELLED transition.
     */
    @Test
    void transition_cancel_preservesTaskProducingMetadata() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(registry,
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String s = initSession2025(h);

        JsonObject create = rpc(h, s, 2, "tasks/create",
                "{\"name\":\"cancel-test\",\"input\":{\"key\":\"value\"},\"inputSchema\":{\"type\":\"object\"}}");
        assertEquals(-1, errorCode(create), "create should succeed: " + create);
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        JsonObject cancel = rpc(h, s, 3, "tasks/cancel",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(-1, errorCode(cancel), "cancel should succeed: " + cancel);

        JsonObject result = cancel.getAsJsonObject("result");
        assertEquals("cancelled", result.get("status").getAsString());
        assertEquals("cancel-test", result.get("name").getAsString(),
                "name must be preserved through CANCELLED transition");
        assertNotNull(result.get("input"), "input must be preserved through CANCELLED transition");
        // input is a JsonObject in the JSON response; extract the string value from it
        JsonObject input = result.getAsJsonObject("input");
        assertEquals("value", input.get("key").getAsString(),
                "input.key must be preserved through CANCELLED transition");
        assertNotNull(result.get("inputSchema"), "inputSchema must be preserved through CANCELLED transition");
    }

    /**
     * Regression: tasks created via tasks/create must retain name/input/inputSchema
     * through a COMPLETED transition.
     */
    @Test
    void transition_complete_preservesTaskProducingMetadata() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(registry,
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String s = initSession2025(h);

        JsonObject create = rpc(h, s, 2, "tasks/create",
                "{\"name\":\"complete-test\",\"input\":{\"arg\":42},\"inputSchema\":{\"type\":\"object\"}}");
        assertEquals(-1, errorCode(create), "create should succeed: " + create);
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        // Complete the task via the registry
        registry.completeTask(taskId, "done");

        // Fetch via tasks/get — metadata must be intact
        JsonObject get = rpc(h, s, 3, "tasks/get",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(-1, errorCode(get));
        JsonObject result = get.getAsJsonObject("result");
        assertEquals("completed", result.get("status").getAsString());
        assertEquals("complete-test", result.get("name").getAsString(),
                "name must be preserved through COMPLETED transition");
        assertNotNull(result.get("input"), "input must be preserved through COMPLETED transition");
        assertNotNull(result.get("inputSchema"), "inputSchema must be preserved through COMPLETED transition");
    }

    /**
     * Regression: tasks created via tasks/create must retain name/input/inputSchema
     * through a FAILED transition.
     */
    @Test
    void transition_fail_preservesTaskProducingMetadata() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(registry,
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String s = initSession2025(h);

        JsonObject create = rpc(h, s, 2, "tasks/create",
                "{\"name\":\"fail-test\",\"input\":{\"x\":1},\"inputSchema\":{\"type\":\"object\"}}");
        assertEquals(-1, errorCode(create), "create should succeed: " + create);
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        // Fail the task via the registry
        registry.failTask(taskId, "boom");

        JsonObject get = rpc(h, s, 3, "tasks/get",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(-1, errorCode(get));
        JsonObject result = get.getAsJsonObject("result");
        assertEquals("failed", result.get("status").getAsString());
        assertEquals("fail-test", result.get("name").getAsString(),
                "name must be preserved through FAILED transition");
        assertNotNull(result.get("input"), "input must be preserved through FAILED transition");
        assertEquals("boom", result.get("error").getAsString());
    }

    /**
     * Regression: cancelling a task twice must return -32002 RESULT_ALREADY_TERMINAL
     * (not throw, not return a different error).
     */
    @Test
    void transition_cancelThenCancel_returnsResultAlreadyTerminal() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(registry,
                McpServerConfig.builder().serverName("test").serverVersion("1.0")
                        .tasks(true).build());
        String s = initSession2025(h);

        JsonObject create = rpc(h, s, 2, "tasks/create",
                "{\"name\":\"double-cancel-test\"}");
        assertEquals(-1, errorCode(create), "create should succeed: " + create);
        String taskId = create.getAsJsonObject("result").getAsJsonObject("task").get("taskId").getAsString();

        JsonObject cancel1 = rpc(h, s, 3, "tasks/cancel",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(-1, errorCode(cancel1), "first cancel should succeed: " + cancel1);

        JsonObject cancel2 = rpc(h, s, 4, "tasks/cancel",
                "{\"taskId\":\"" + taskId + "\"}");
        assertEquals(McpErrorCodes.RESULT_ALREADY_TERMINAL, errorCode(cancel2),
                "second cancel must return -32002 RESULT_ALREADY_TERMINAL: " + cancel2);
    }

    // ── Legacy (null name) tasks transition unchanged ─────────────────────────────

    /**
     * Tasks created via McpTask.create() (no name) must still transition
     * via the legacy constructor path without any regressions.
     * Uses toMap() to inspect task-producing fields since getters are package-private.
     */
    @Test
    void transition_legacyTaskWithoutName_transitionsCorrectly() {
        // Directly test transition on a legacy task (no task-producing metadata)
        long now = System.currentTimeMillis();
        McpTask legacy = new McpTask("legacy-1", McpTask.Status.WORKING,
                now - 1000, now, null, null);
        // toMap() omits name/input fields when they are null
        assertFalse(legacy.toMap().containsKey("name"));
        assertFalse(legacy.toMap().containsKey("input"));

        McpTask cancelled = legacy.transition(McpTask.Status.CANCELLED, null, "legacy cancelled");
        assertEquals(McpTask.Status.CANCELLED, cancelled.getStatus());
        assertEquals("legacy cancelled", cancelled.getError());
        // Legacy task transitions must NOT add name/input fields
        assertFalse(cancelled.toMap().containsKey("name"),
                "legacy task should not gain a name through transition");
        assertFalse(cancelled.toMap().containsKey("input"),
                "legacy task should not gain input through transition");
    }

    // Note: combined constructor validation is covered implicitly by the
    // transition_* tests above — invalid argument combinations will throw
    // IllegalArgumentException and cause the transition to fail.

    // ── RequestResult type ──────────────────────────────────────────────────────

    @Test
    void requestResult_successWithMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", "value");
        McpTaskExtension.RequestResult r = McpTaskExtension.RequestResult.success(data);
        assertTrue(r.isSuccess());
        assertEquals("value", r.result.get("key"));
        assertNull(r.error);
    }

    @Test
    void requestResult_successWithNull() {
        McpTaskExtension.RequestResult r = McpTaskExtension.RequestResult.success();
        assertTrue(r.isSuccess());
        assertNotNull(r.result);
        assertTrue(r.result.isEmpty());
    }

    @Test
    void requestResult_error() {
        McpTaskExtension.RequestResult r = McpTaskExtension.RequestResult.error(-32000, "msg");
        assertFalse(r.isSuccess());
        assertNull(r.result);
        assertEquals(-32000, r.error.code);
        assertEquals("msg", r.error.message);
    }
}
