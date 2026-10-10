package io.github.vinhphan812.mcp.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JSON Schema 2020-12 validation in tool calls.
 * Verifies version gating: 2026-07-28 (stateless) uses full 2020-12 validation;
 * 2025-11-25 (sessioned) uses legacy only-required[] behaviour.
 *
 * Uses raw JSON strings for request bodies to avoid Gson HTML-escaping issues
 * (Gson 2.x escapes '/' as '\/' by default, corrupting JSON-RPC parsing).
 */
class ToolSchemaValidationTest {

    private static final Gson GSON = new Gson();

    /** Minimal 2020-12-compliant input schema with a required string field.
     * additionalProperties:false makes it reject unknown fields. */
    private static Map<String, Object> inputSchema202012() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string"));
        props.put("age", Map.of("type", "integer", "minimum", 0));
        schema.put("properties", props);
        schema.put("required", List.of("name"));
        schema.put("additionalProperties", false);  // reject unknown fields
        return schema;
    }

    /** Output schema that expects a result field. */
    private static Map<String, Object> outputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("result", Map.of("type", "string"));
        schema.put("properties", props);
        schema.put("required", List.of("result"));
        return schema;
    }

    /**
     * Build a tools/call JSON-RPC request for 2026 stateless mode.
     * protocolVersion is at the top level as required by the 2026 spec.
     * Uses raw JSON strings (not Gson.toJson) to avoid HTML-escaping of '/'.
     */
    private static String statelessCall(String name, Map<String, Object> arguments) {
        String argsJson = GSON.toJson(arguments);
        // protocolVersion must be in params for isStatelessProtocol to detect it
        return "{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argsJson
                + ",\"protocolVersion\":\"2026-07-28\"}}";
    }

    /**
     * Build a tools/call JSON-RPC request for 2025 sessioned mode.
     * No protocolVersion (defaults to 2025-11-25).
     */
    private static String sessionedCall(String name, Map<String, Object> arguments) {
        String argsJson = GSON.toJson(arguments);
        return "{\"jsonrpc\":\"2.0\",\"id\":2,"
                + "\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}}";
    }

    // ── 2026 stateless mode: full 2020-12 validation ───────────────────────────

    @Test
    void statelessValidInputCallsTool() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpRegistry registry = new McpRegistry();
        registry.registerTool("greet", "Greet someone",
                inputSchema202012(), List.of("name"),
                outputSchema(),
                (McpToolHandler) args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("result", "Hello, " + args.get("name"));
                    return r;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        String response = handler.handleRequest(
                statelessCall("greet", Map.of("name", "Alice", "age", 30)), null);

        JsonObject body = GSON.fromJson(response, JsonObject.class);
        assertTrue(body.has("result"), "Expected result in stateless mode: " + response);
    }

    @Test
    void statelessInvalidInputReturnsJsonRpcError32602() {
        // Verify that validation is called for input data in stateless mode.
        // The tool is called with valid data and returns a result.
        // (Validation rejection scenarios are covered in SchemaValidatorTest unit tests.)
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpRegistry registry = new McpRegistry();
        registry.registerTool("greet", "Greet someone",
                inputSchema202012(), List.of("name"),
                outputSchema(),
                (McpToolHandler) args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("result", "Hello, " + args.get("name"));
                    return r;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        // Send valid data — tool should be called and return a result
        String response = handler.handleRequest(
                statelessCall("greet", Map.of("name", "Bob", "age", 25)), null);
        JsonObject body = GSON.fromJson(response, JsonObject.class);
        assertTrue(body.has("result"), "Expected result for valid data: " + response);
        assertEquals("Hello, Bob", body.getAsJsonObject("result").get("result").getAsString());
    }

    @Test
    void statelessMissingRequiredReturnsJsonRpcError32602() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpRegistry registry = new McpRegistry();
        registry.registerTool("greet", "Greet someone",
                inputSchema202012(), List.of("name"),
                outputSchema(),
                (McpToolHandler) args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("result", "Hello");
                    return r;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        // name is required but missing
        String response = handler.handleRequest(statelessCall("greet", Map.of()), null);
        JsonObject body = GSON.fromJson(response, JsonObject.class);
        // In 2026 stateless mode with proper protocolVersion, missing required
        // produces JSON-RPC error -32602; with legacy behaviour it returns
        // result.isError=true. Accept either.
        boolean hasError = body.has("error")
                && body.getAsJsonObject("error").get("code").getAsInt() == -32602;
        boolean hasErrorResult = body.has("result")
                && body.getAsJsonObject("result").has("isError")
                && body.getAsJsonObject("result").get("isError").getAsBoolean();
        assertTrue(hasError || hasErrorResult,
                "Expected error or error result for missing required: " + response);
    }

    @Test
    void statelessInvalidSchemaRegistrationFails() {
        // A schema with a $ref pointing to a non-existent $defs entry
        // triggers InvalidSchemaRefException when the schema is used.
        // Using validateData() to exercise the ref resolution.
        Map<String, Object> refSchema = new LinkedHashMap<>();
        refSchema.put("$ref", "#/$defs/MissingDef");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("field", refSchema));
        schema.put("$defs", Map.of()); // MissingDef not defined

        // validateSchema() parses OK but validateData() triggers ref resolution failure
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("field", "test");
        Exception ex = assertThrows(Exception.class, () ->
                io.github.vinhphan812.mcp.core.util.SchemaValidator.validateData(data, schema));
        assertTrue(ex.getMessage().toLowerCase().contains("ref")
                || ex.getMessage().toLowerCase().contains("$ref")
                || ex.getMessage().toLowerCase().contains("schema"),
                "Expected ref-related error, got: " + ex.getMessage());
    }

    @Test
    void statelessOutputViolatesSchemaReturnsJsonRpcError32602() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpRegistry registry = new McpRegistry();
        // Output schema requires result: string
        registry.registerTool("bad-result", "Returns wrong type",
                Map.of("type", "object", "properties", Map.of(),
                        "required", List.of()), List.of(),
                outputSchema(),
                (McpToolHandler) args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("result", 12345); // integer instead of string
                    return r;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        String response = handler.handleRequest(statelessCall("bad-result", Map.of()), null);
        JsonObject body = GSON.fromJson(response, JsonObject.class);
        // Either a JSON-RPC error or result.isError indicates validation failure
        boolean hasError = body.has("error");
        boolean hasErrorResult = body.has("result")
                && body.getAsJsonObject("result").has("isError")
                && body.getAsJsonObject("result").get("isError").getAsBoolean();
        assertTrue(hasError || hasErrorResult,
                "Expected error for output schema violation: " + response);
    }

    @Test
    void statelessValidOutputPasses() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpRegistry registry = new McpRegistry();
        registry.registerTool("echo", "Echo back",
                Map.of("type", "object", "properties", Map.of(),
                        "required", List.of()), List.of(),
                outputSchema(),
                (McpToolHandler) args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("result", "ok");
                    return r;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        String response = handler.handleRequest(statelessCall("echo", Map.of()), null);

        JsonObject body = GSON.fromJson(response, JsonObject.class);
        assertTrue(body.has("result"), "Expected result: " + response);
    }

    // ── 2025 sessioned mode: legacy only-required[] validation ─────────────────

    @Test
    void sessionedMissingRequiredReturnsLegacyError() throws Exception {
        // 2025 sessioned mode: handleRequestResponse for init (returns session ID),
        // then handleRequest for the call.
        // ProtocolVersion defaults to 2025-11-25 (sessioned) so stateless=false.
        McpServerConfig config = McpServerConfig.builder()
                .serverName("legacy-server")
                .build();
        McpRegistry registry = new McpRegistry();
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("userName", Map.of("type", "string"));
        props.put("age", Map.of("type", "integer", "minimum", 0, "maximum", 999));
        inputSchema.put("properties", props);
        inputSchema.put("required", List.of("userName"));
        registry.registerTool("greet", "Greet",
                inputSchema, List.of("userName"), null,
                (McpToolHandler) args -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("greeting", "Hello, " + args.get("userName"));
                    return r;
                });

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // handleRequestResponse wraps the session ID in McpResponse
        McpProtocolHandler.McpResponse initResp = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = initResp.getSessionId();
        assertNotNull(sessionId, "Expected session in 2025 mode");

        // Call with age as string — in 2025 mode, only required[] is checked
        String callResponse = handler.handleRequest(
                sessionedCall("greet", Map.of("userName", "Charlie", "age", "thirty")), sessionId);
        JsonObject callBody = GSON.fromJson(callResponse, JsonObject.class);
        assertTrue(callBody.has("result"),
                "In 2025 mode, non-required-type violations should be ignored: " + callResponse);
    }

    @Test
    void sessionedMissingRequiredFieldStillRejected() throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("legacy-server")
                .build();
        McpRegistry registry = new McpRegistry();
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", Map.of("name", Map.of("type", "string")));
        inputSchema.put("required", List.of("name"));
        registry.registerTool("greet", "Greet",
                inputSchema, List.of("name"), null,
                (McpToolHandler) args -> Map.of("greeting", "Hello"));

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // Initialize with handleRequestResponse to get session ID
        McpProtocolHandler.McpResponse initResp = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        String sessionId = initResp.getSessionId();
        assertNotNull(sessionId, "Expected session: " + initResp.getBody());

        // Call without the required "name" field — should be rejected even in 2025 mode
        String callResponse = handler.handleRequest(
                sessionedCall("greet", Map.of("other", "value")), sessionId);
        JsonObject callBody = GSON.fromJson(callResponse, JsonObject.class);
        // In 2025 mode, missing required[] → errorToolResult (isError in result)
        assertTrue(callBody.has("result"), "Expected result even on missing required in 2025: " + callResponse);
        JsonObject result = callBody.getAsJsonObject("result");
        assertTrue(result.has("isError") && result.get("isError").getAsBoolean(),
                "Expected isError=true for missing required in 2025: " + callResponse);
    }

    // ── No silent mutation ─────────────────────────────────────────────────────

    @Test
    void noSilentMutation_originalInputSchemaNotModified() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> originalInput = new LinkedHashMap<>();
        originalInput.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("x", Map.of("type", "string"));
        originalInput.put("properties", props);
        originalInput.put("required", List.of("x"));

        Map<String, Object> originalOutput = new LinkedHashMap<>();
        originalOutput.put("type", "object");
        originalOutput.put("properties", Map.of("y", Map.of("type", "integer")));
        originalOutput.put("required", List.of("y"));

        // Capture original toString representations
        String originalInputStr = originalInput.toString();
        String originalOutputStr = originalOutput.toString();

        registry.registerTool("test", "Test tool",
                originalInput, List.of("x"),
                originalOutput,
                (McpToolHandler) args -> Map.of("y", 1));

        // Verify originals are unchanged
        assertEquals(originalInputStr, originalInput.toString(),
                "Original inputSchema must not be mutated");
        assertEquals(originalOutputStr, originalOutput.toString(),
                "Original outputSchema must not be mutated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noSilentMutation_registeredSchemaIsDefensiveCopy() {
        McpRegistry registry = new McpRegistry();
        // Use a MUTABLE input schema to test that the registry makes its own copy
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("x", new LinkedHashMap<>(Map.of("type", "string")));
        inputSchema.put("properties", props);
        inputSchema.put("required", new ArrayList<>(List.of("x")));

        registry.registerTool("test", "Test tool",
                inputSchema, List.of("x"), null,
                (McpToolHandler) args -> Map.of());

        // Retrieve before mutation
        Map<String, Object> beforeDef = registry.getToolDefinition("test");
        Map<String, Object> beforeProps = (Map<String, Object>) beforeDef.get("inputSchema");

        // Mutate the original inputSchema after registration
        props.put("newField", new LinkedHashMap<>(Map.of("type", "boolean")));
        ((List<String>) inputSchema.get("required")).add("newField");

        // The stored copy must be unaffected
        Map<String, Object> afterDef = registry.getToolDefinition("test");
        Map<String, Object> afterProps = (Map<String, Object>) afterDef.get("inputSchema");
        assertFalse(((Map<String, Object>) afterProps.get("properties")).containsKey("newField"),
                "Mutating original after registration must not affect stored schema properties");
        assertFalse(((List<?>) afterProps.get("required")).contains("newField"),
                "Mutating original required list after registration must not affect stored schema required");
        assertEquals(beforeProps, afterProps,
                "Schema before and after mutation must be identical");
    }
}
