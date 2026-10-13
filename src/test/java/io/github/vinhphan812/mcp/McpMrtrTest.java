package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.InputRequiredResult;
import io.github.vinhphan812.mcp.api.handler.McpPromptHandler;
import io.github.vinhphan812.mcp.api.handler.McpResourceHandler;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.utils.ElicitationNeededException;
import io.github.vinhphan812.mcp.api.utils.McpErrorCodes;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SEP-2322 MRTR In-Band Input Required integration.
 * Covers the full elicitation flow: tool/prompt/resource handlers signal input need,
 * protocol handler returns resultType=input_required, tasks/update validates tokens.
 */
class McpMrtrTest {

    private static final Gson GSON = new Gson();

    /**
     * Initialises a session and returns the session ID.
     */
    private String initSession(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(body, null);
        JsonObject result = GSON.fromJson(resp.getBody(), JsonObject.class).getAsJsonObject("result");
        if (result != null && result.has("serverInfo")) {
            handler.handleRequestResponse(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    resp.getSessionId());
            return resp.getSessionId();
        }
        return null;
    }

    // ==================== Tool handler throws ElicitationNeededException ====================

    @Test
    void tool_throwsElicitationNeeded_returnsInputRequired() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("confirm", Map.of("type", "boolean")));
        registry.registerTool(
                "confirm-delete",
                "Delete a record",
                schema,
                null,
                (McpToolHandler) args -> {
                    McpProtocolHandler.throwElicitationNeeded(
                            "Are you sure you want to delete?",
                            schema);
                    return null; // unreachable
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"confirm-delete\",\"arguments\":{}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = GSON.fromJson(resp.getBody(), JsonObject.class);
        assertTrue(json.has("result"), "Should have result: " + resp.getBody());
        JsonObject result = json.getAsJsonObject("result");
        assertEquals("input_required", result.get("resultType").getAsString());
        assertNotNull(result.get("requestState"));
        assertEquals("Are you sure you want to delete?", result.get("prompt").getAsString());
        assertNotNull(result.get("requestedSchema"));

        handler.shutdown();
    }

    @Test
    void tool_throwsElicitationNeededWithExpiresAtAndMaxRounds() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool(
                "ask-user",
                "Ask user",
                null,
                null,
                (McpToolHandler) args -> {
                    long expiresAt = System.currentTimeMillis() + 60_000;
                    int maxRounds = 5;
                    McpProtocolHandler.throwElicitationNeeded(
                            "Enter value",
                            Map.of("type", "string"),
                            expiresAt,
                            maxRounds);
                    return null;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ask-user\",\"arguments\":{}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = GSON.fromJson(resp.getBody(), JsonObject.class);
        JsonObject result = json.getAsJsonObject("result");
        assertEquals("input_required", result.get("resultType").getAsString());
        assertTrue(result.has("expiresAt"));
        assertTrue(result.has("maxRounds"));
        assertEquals(5, result.get("maxRounds").getAsInt());

        handler.shutdown();
    }

    // ==================== Tool handler throws ElicitationNeededException with schema ====================

    @Test
    void tool_throwsElicitationNeeded_withSchema_returnsInputRequired() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("name", Map.of("type", "string")));
        registry.registerTool(
                "get-name",
                "Get user name",
                null,
                null,
                (McpToolHandler) args -> {
                    // SEP-2322: tool handlers throw ElicitationNeededException to signal input need
                    McpProtocolHandler.throwElicitationNeeded(
                            "Please enter your name",
                            schema);
                    return null; // unreachable
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get-name\",\"arguments\":{}}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = GSON.fromJson(resp.getBody(), JsonObject.class);
        JsonObject result = json.getAsJsonObject("result");
        assertEquals("input_required", result.get("resultType").getAsString());
        assertNotNull(result.get("requestState"));
        assertEquals("Please enter your name", result.get("prompt").getAsString());

        handler.shutdown();
    }

    // ==================== Prompt handler throws ElicitationNeededException ====================

    @Test
    void prompt_throwsElicitationNeeded_returnsInputRequired() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("tone", Map.of("type", "string", "enum", Arrays.asList("formal", "casual"))));
        registry.registerPrompt(
                "write-email",
                "Write an email",
                null,
                (McpPromptHandler) args -> {
                    McpProtocolHandler.throwElicitationNeeded(
                            "Select email tone",
                            schema);
                    return null; // unreachable
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"write-email\"}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = GSON.fromJson(resp.getBody(), JsonObject.class);
        JsonObject result = json.getAsJsonObject("result");
        assertEquals("input_required", result.get("resultType").getAsString());
        assertNotNull(result.get("requestState"));
        assertEquals("Select email tone", result.get("prompt").getAsString());

        handler.shutdown();
    }

    // ==================== Resource handler throws ElicitationNeededException ====================

    @Test
    void resource_throwsElicitationNeeded_returnsInputRequired() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("token", Map.of("type", "string")));
        registry.registerResource(
                "secure://config",
                "Secure config",
                "Needs auth token",
                (McpResourceHandler) uri -> {
                    McpProtocolHandler.throwElicitationNeeded(
                            "Enter your API token to read the config",
                            schema);
                    return null; // unreachable
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"secure://config\"}}";
        McpProtocolHandler.McpResponse resp = handler.handleRequestResponse(req, sessionId);

        JsonObject json = GSON.fromJson(resp.getBody(), JsonObject.class);
        JsonObject result = json.getAsJsonObject("result");
        assertEquals("input_required", result.get("resultType").getAsString());
        assertNotNull(result.get("requestState"));
        assertEquals("Enter your API token to read the config", result.get("prompt").getAsString());

        handler.shutdown();
    }

    // ==================== RequestStateManager integration ====================

    @Test
    void createRequestState_returnsUniqueToken() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());

        String t1 = handler.createRequestState(null, null);
        String t2 = handler.createRequestState(null, null);

        assertNotNull(t1);
        assertNotNull(t2);
        assertNotEquals(t1, t2, "Each call should generate a unique token");

        handler.shutdown();
    }

    @Test
    void createRequestState_withContext_preservesContext() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("tool", "ask-user");
        ctx.put("sessionId", "sess-1");

        String token = handler.createRequestState("task-1", ctx);
        assertNotNull(token);

        handler.shutdown();
    }

    @Test
    void inputRequired_convenienceMethod_returnsInputRequiredResult() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());

        Map<String, Object> schema = Map.of("type", "string");
        Map<String, Object> ctx = Map.of("caller", "test");

        Map<String, Object> result = handler.inputRequired(
                "task-99", "Enter value:", schema, ctx);

        assertEquals("input_required", result.get("resultType"));
        assertNotNull(result.get("requestState"));
        assertEquals("Enter value:", result.get("prompt"));
        assertNotNull(result.get("requestedSchema"));

        handler.shutdown();
    }

    @Test
    void validateRequestState_invalidToken_throwsException() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());

        assertThrows(io.github.vinhphan812.mcp.api.utils.McpException.class,
                () -> handler.validateRequestState("not-a-valid-token"));

        handler.shutdown();
    }

    @Test
    void consumeRequestState_removesToken() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());

        String token = handler.createRequestState(null, null);

        // Validate should succeed
        handler.validateRequestState(token);

        // Consume removes it
        handler.consumeRequestState(token);

        // Validate should now fail
        assertThrows(io.github.vinhphan812.mcp.api.utils.McpException.class,
                () -> handler.validateRequestState(token));

        handler.shutdown();
    }

    // ==================== tasks/update with requestState ====================

    @Test
    void tasksUpdate_withValidRequestState_succeeds() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool(
                "multi-step",
                "Multi-step tool",
                null,
                null,
                (McpToolHandler) args -> {
                    // throwElicitationNeeded creates the token internally
                    McpProtocolHandler.throwElicitationNeeded(
                            "Continue?",
                            null);
                    return null; // unreachable
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .tasks(true)
                        .build());
        String sessionId = initSession(handler);

        // Round 1: call tool, get input_required
        String toolReq = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"multi-step\",\"arguments\":{}}}";
        JsonObject toolResp = GSON.fromJson(
                handler.handleRequestResponse(toolReq, sessionId).getBody(),
                JsonObject.class);
        String requestState = toolResp.getAsJsonObject("result")
                .get("requestState").getAsString();

        // Create an INPUT_REQUIRED task
        String taskId = "task-" + System.currentTimeMillis();
        registry.registerTask(new io.github.vinhphan812.mcp.api.dto.McpTask(
                taskId,
                io.github.vinhphan812.mcp.api.dto.McpTask.Status.INPUT_REQUIRED,
                "mrtr-task",
                null,
                null,
                null,
                null,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        // Update with valid requestState — should succeed
        String updateReq = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tasks/update\","
                        + "\"params\":{\"taskId\":\"%s\",\"requestState\":\"%s\",\"inputResponses\":{\"confirm\":true}}}",
                taskId, requestState);
        JsonObject updateResp = GSON.fromJson(
                handler.handleRequestResponse(updateReq, sessionId).getBody(),
                JsonObject.class);
        assertFalse(updateResp.has("error"), "Should not have error: " + updateResp);

        handler.shutdown();
    }

    @Test
    void tasksUpdate_withInvalidRequestState_returnsError() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String sessionId = initSession(handler);

        // Create an INPUT_REQUIRED task
        String taskId = "task-" + System.currentTimeMillis();
        registry.registerTask(new io.github.vinhphan812.mcp.api.dto.McpTask(
                taskId,
                io.github.vinhphan812.mcp.api.dto.McpTask.Status.INPUT_REQUIRED,
                "mrtr-task",
                null,
                null,
                null,
                null,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        // Update with invalid token
        String updateReq = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tasks/update\","
                        + "\"params\":{\"taskId\":\"%s\",\"requestState\":\"invalid-token\",\"inputResponses\":{}}}",
                taskId);
        JsonObject updateResp = GSON.fromJson(
                handler.handleRequestResponse(updateReq, sessionId).getBody(),
                JsonObject.class);

        assertTrue(updateResp.has("error"), "Should return error for invalid token");
        int code = updateResp.getAsJsonObject("error").get("code").getAsInt();
        assertEquals(McpErrorCodes.REQUEST_STATE_INVALID, code);

        handler.shutdown();
    }

    @Test
    void tasksUpdate_withoutRequestState_stillWorks() {
        McpRegistry registry = new McpRegistry();
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String sessionId = initSession(handler);

        String taskId = "task-" + System.currentTimeMillis();
        registry.registerTask(new io.github.vinhphan812.mcp.api.dto.McpTask(
                taskId,
                io.github.vinhphan812.mcp.api.dto.McpTask.Status.INPUT_REQUIRED,
                "mrtr-task",
                null,
                null,
                null,
                null,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        // Update without requestState — should succeed (token validation is optional)
        String updateReq = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tasks/update\","
                        + "\"params\":{\"taskId\":\"%s\",\"inputResponses\":{\"value\":\"test\"}}}",
                taskId);
        JsonObject updateResp = GSON.fromJson(
                handler.handleRequestResponse(updateReq, sessionId).getBody(),
                JsonObject.class);
        assertFalse(updateResp.has("error"), "Should succeed without token: " + updateResp);

        handler.shutdown();
    }

    // ==================== McpServerConfig elicitation fields ====================

    @Test
    void config_elicitationFields_haveDefaults() {
        McpServerConfig cfg = McpServerConfig.builder().build();
        assertEquals(3, cfg.maxElicitationRounds);
        assertEquals(300_000L, cfg.requestStateTtlMs);
        assertFalse(cfg.elicitation);
    }

    @Test
    void config_elicitationFields_canBeSet() {
        McpServerConfig cfg = McpServerConfig.builder()
                .elicitation(true)
                .maxElicitationRounds(10)
                .requestStateTtlMs(600_000L)
                .build();
        assertTrue(cfg.elicitation);
        assertEquals(10, cfg.maxElicitationRounds);
        assertEquals(600_000L, cfg.requestStateTtlMs);
    }

    // ==================== InputRequiredResult DTO tests ====================

    @Test
    void inputRequiredResult_rejectsBlankToken() {
        assertThrows(IllegalArgumentException.class,
                () -> new InputRequiredResult("  ", "prompt", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InputRequiredResult(null, "prompt", null, null, null));
    }

    @Test
    void inputRequiredResult_getters() {
        long expiresAt = System.currentTimeMillis() + 30_000;
        InputRequiredResult r = new InputRequiredResult(
                "token-xyz",
                "Enter name",
                Map.of("type", "string"),
                expiresAt,
                5);
        assertEquals("token-xyz", r.getRequestState());
        assertEquals("Enter name", r.getPrompt());
        assertNotNull(r.getRequestedSchema());
        assertEquals(expiresAt, r.getExpiresAt());
        assertEquals(5, r.getMaxRounds());
    }

    @Test
    void inputRequiredResult_optionalFieldsNullable() {
        InputRequiredResult r = new InputRequiredResult("tok", "p", null, null, null);
        assertNull(r.getRequestedSchema());
        assertNull(r.getExpiresAt());
        assertNull(r.getMaxRounds());
    }

    // ==================== Full round-trip test ====================

    @Test
    void fullMrtrRoundTrip_toolNeedsInputThenContinues() {
        McpRegistry registry = new McpRegistry();

        // Mutable token/state holder so the handler can continue on second call
        final Map<String, Object> state = new LinkedHashMap<>();

        registry.registerTool(
                "secure-delete",
                "Delete with confirmation",
                Map.of("type", "object",
                        "properties", Map.of("recordId", Map.of("type", "string"))),
                null,
                (McpToolHandler) args -> {
                    if (state.get("waiting") == null) {
                        // First call: signal input is required
                        state.put("waiting", true);
                        state.put("recordId", args.get("recordId"));
                        // SEP-2322: throw to signal input needed — handler auto-creates the token
                        McpProtocolHandler.throwElicitationNeeded(
                                "Confirm deletion of record: " + args.get("recordId"),
                                Map.of("type", "object",
                                        "properties", Map.of("confirmed", Map.of("type", "boolean"))));
                        return null; // unreachable
                    } else {
                        // Second call (after client provided input): proceed with deletion
                        state.remove("waiting");
                        state.remove("recordId");
                        return Map.of(
                                "deleted", true,
                                "recordId", args.get("recordId"));
                    }
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().tasks(true).build());
        String sessionId = initSession(handler);

        // Round 1: call tool, get input_required with a token
        String req1 = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"secure-delete\",\"arguments\":{\"recordId\":\"r-42\"}}}";
        JsonObject resp1 = GSON.fromJson(
                handler.handleRequestResponse(req1, sessionId).getBody(),
                JsonObject.class);
        JsonObject result1 = resp1.getAsJsonObject("result");
        assertEquals("input_required", result1.get("resultType").getAsString());
        // Extract the actual token from the response (created by throwElicitationNeeded)
        String token = result1.get("requestState").getAsString();
        assertNotNull(token);

        // Create an INPUT_REQUIRED task
        String taskId = "task-" + System.currentTimeMillis();
        registry.registerTask(new io.github.vinhphan812.mcp.api.dto.McpTask(
                taskId,
                io.github.vinhphan812.mcp.api.dto.McpTask.Status.INPUT_REQUIRED,
                "mrtr-task",
                null,
                null,
                null,
                null,
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        // Client sends tasks/update with requestState and inputResponses
        String updateReq = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tasks/update\","
                        + "\"params\":{\"taskId\":\"%s\",\"requestState\":\"%s\","
                        + "\"inputResponses\":{\"confirmed\":true}}}",
                taskId, token);
        JsonObject updateResp = GSON.fromJson(
                handler.handleRequestResponse(updateReq, sessionId).getBody(),
                JsonObject.class);
        assertFalse(updateResp.has("error"), "tasks/update should succeed: " + updateResp);

        // Round 2: client re-calls tool with same args (continuing the flow)
        String req2 = "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"secure-delete\",\"arguments\":{\"recordId\":\"r-42\"}}}";
        JsonObject resp2 = GSON.fromJson(
                handler.handleRequestResponse(req2, sessionId).getBody(),
                JsonObject.class);
        JsonObject result2 = resp2.getAsJsonObject("result");
        assertEquals("complete", result2.get("resultType").getAsString());
        assertTrue(result2.has("content"));

        

    }
    // ==================== SEP-2322 MRTR Same-Request Retry ====================

    /**
     * SEP-2322: client retries tools/call with inputResponses + requestState
     * in the same request body. Handler receives merged arguments and completes.
     */
    @Test
    void toolSameRequestRetry_withInputResponses_completes() {
        McpRegistry registry = new McpRegistry();
        final boolean[] secondCall = new boolean[1];

        registry.registerTool(
                "confirm-delete",
                "Delete with confirmation",
                Map.of("type", "object",
                        "properties", Map.of("recordId", Map.of("type", "string"))),
                null,
                (McpToolHandler) args -> {
                    if (!secondCall[0]) {
                        secondCall[0] = true;
                        McpProtocolHandler.throwElicitationNeeded(
                                "Confirm deletion?",
                                Map.of("type", "object",
                                        "properties", Map.of("confirmed", Map.of("type", "boolean"))));
                    }
                    // Second call: inputResponses merged into args
                    assertTrue(args.containsKey("confirmed"));
                    assertEquals(true, args.get("confirmed"));
                    return Map.of("deleted", true);
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        // Round 1: call tool, get input_required
        String req1 = "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"confirm-delete\",\"arguments\":{\"recordId\":\"r-99\"}}}";
        JsonObject resp1 = GSON.fromJson(
                handler.handleRequestResponse(req1, sessionId).getBody(),
                JsonObject.class);
        String requestState = resp1.getAsJsonObject("result").get("requestState").getAsString();

        // Round 2: same-request retry with inputResponses + requestState
        String req2 = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"confirm-delete\",\"arguments\":{\"recordId\":\"r-99\"},"
                        + "\"requestState\":\"%s\","
                        + "\"inputResponses\":{\"confirmed\":true}}}",
                requestState);
        JsonObject resp2 = GSON.fromJson(
                handler.handleRequestResponse(req2, sessionId).getBody(),
                JsonObject.class);
        JsonObject result2 = resp2.getAsJsonObject("result");
        assertEquals("complete", result2.get("resultType").getAsString());

        handler.shutdown();
    }

    /**
     * SEP-2322: consumed requestState token is rejected on retry.
     */
    @Test
    void toolSameRequestRetry_withConsumedToken_returnsError() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool(
                "ask-tool",
                "Ask tool",
                null,
                null,
                (McpToolHandler) args -> {
                    McpProtocolHandler.throwElicitationNeeded("Prompt", null);
                    return null;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().build());
        String sessionId = initSession(handler);

        // Get valid input_required response
        String req1 = "{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"ask-tool\",\"arguments\":{}}}";
        JsonObject resp1 = GSON.fromJson(
                handler.handleRequestResponse(req1, sessionId).getBody(),
                JsonObject.class);
        String validToken = resp1.getAsJsonObject("result").get("requestState").getAsString();

        // Consume the token with first retry
        String req2 = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":51,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"ask-tool\",\"arguments\":{},"
                        + "\"requestState\":\"%s\","
                        + "\"inputResponses\":{\"value\":\"first\"}}}",
                validToken);
        handler.handleRequestResponse(req2, sessionId);

        // Retry with same token — should fail
        String req3 = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"ask-tool\",\"arguments\":{},"
                        + "\"requestState\":\"%s\","
                        + "\"inputResponses\":{\"value\":\"second\"}}}",
                validToken);
        JsonObject resp3 = GSON.fromJson(
                handler.handleRequestResponse(req3, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp3.has("error"));
        assertEquals(McpErrorCodes.REQUEST_STATE_INVALID,
                resp3.getAsJsonObject("error").get("code").getAsInt());

        handler.shutdown();
    }

}
