package io.github.vinhphan812.mcp;

import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
    void initializeAdvertisesEnabledServerCapabilities() {
        Map<String, Object> experimental = new java.util.LinkedHashMap<>();
        experimental.put("demo", java.util.Collections.singletonMap("enabled", true));
        McpServerConfig config = McpServerConfig.builder()
                .tools(false).resources(false).prompts(false)
                .logging(true).completions(true).tasks(true)
                .experimental(experimental).build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        JsonObject capabilities = new com.google.gson.Gson().fromJson(response.getBody(), JsonObject.class)
                .getAsJsonObject("result").getAsJsonObject("capabilities");

        assertTrue(capabilities.has("logging"));
        assertTrue(capabilities.has("completions"));
        assertTrue(capabilities.has("tasks"));
        assertTrue(capabilities.getAsJsonObject("experimental")
                .getAsJsonObject("demo").get("enabled").getAsBoolean());
        assertFalse(capabilities.has("tools"));
        assertFalse(capabilities.has("resources"));
        assertFalse(capabilities.has("prompts"));
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
        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\"}}",
                initialized.getSessionId());
        assertTrue(response.contains("-32602"));
    }

    @Test
    void rejectsProtectedRequestWithoutSession() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
        String body = handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
        assertTrue(body.contains("Missing or invalid MCP session"));
    }

    @Test
    void completionCompleteUsesRegisteredProvider() {
        McpRegistry registry = new McpRegistry();
        registry.registerCompletionProvider("ref", (reference, argument) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("values", Collections.singletonList(argument.get("value") + "-done"));
            return result;
        });
        McpServerConfig config = McpServerConfig.builder().completions(true).build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);
        McpProtocolHandler.McpResponse initialized = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"completion/complete\",\"params\":{\"ref\":{\"type\":\"ref\"},\"argument\":{\"name\":\"x\",\"value\":\"abc\"}}}",
                initialized.getSessionId());
        JsonObject body = new com.google.gson.Gson().fromJson(response, JsonObject.class);
        assertEquals("abc-done", body.getAsJsonObject("result").getAsJsonArray("values").get(0).getAsString());
    }

    @Test
    void loggingSetLevelAcceptsValidLevel() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder().logging(true).build());
        McpProtocolHandler.McpResponse initialized = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"logging/setLevel\",\"params\":{\"level\":\"WARNING\"}}",
                initialized.getSessionId());
        assertTrue(response.contains("\"result\""));
        assertFalse(response.contains("-32602"));
    }

    @Test
    void notificationsMessageProducesNoResponse() {
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
        McpProtocolHandler.McpResponse initialized = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"data\":\"hello\"}}",
                initialized.getSessionId());
        assertNull(response.getBody());
        assertEquals(initialized.getSessionId(), response.getSessionId());
    }

    // ── Stateless protocol (2026-07-28) ────────────────────────────────────────

    @Test
    void statelessInitializeDoesNotCreateSession() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .serverName("stateless-server")
                .serverVersion("3.0.0")
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        assertNotNull(response.getBody());
        // No session should be created in stateless mode
        assertNull(response.getSessionId());
        assertFalse(handler.hasSession(response.getSessionId())); // null check
        JsonObject body = new com.google.gson.Gson().fromJson(response.getBody(), JsonObject.class);
        assertEquals("2026-07-28", body.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals("stateless-server", body.getAsJsonObject("result")
                .getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    void statelessToolsListWithoutInitialize() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);

        // tools/list works without prior initialize in stateless mode
        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", null);
        assertTrue(response.contains("\"result\""), "Expected success response, got: " + response);
        assertFalse(response.contains("-32600"), "Should not be invalid request: " + response);
    }

    @Test
    void statelessToolsCallWithoutInitialize() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpRegistry registry = new McpRegistry();
        Map<String, Object> echoTool = new LinkedHashMap<>();
        echoTool.put("description", "Echoes input");
        echoTool.put("inputSchema", Collections.singletonMap("properties",
                Collections.singletonMap("message", Collections.singletonMap("type", "string"))));
        echoTool.put("execute", (java.util.function.Function<Map<String, Object>, Map<String, Object>>) args -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("echo", args.get("message"));
            return result;
        });
        registry.registerToolProvider("echo", echoTool);

        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // tools/call works without prior initialize in stateless mode
        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"message\":\"hello stateless\"}}}", null);
        assertTrue(response.contains("\"result\""), "Expected success response, got: " + response);
        assertFalse(response.contains("Missing or invalid MCP session"), "Should not require session: " + response);
        assertTrue(response.contains("hello stateless"), "Should return echoed value: " + response);
    }

    @Test
    void statelessPromptsListWithoutInitialize() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .prompts(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\",\"params\":{}}", null);
        assertTrue(response.contains("\"result\""), "Expected success response, got: " + response);
    }

    @Test
    void statelessInitializeAdvertisesNoSubscribeCapability() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .resources(true)
                .resourceSubscriptions(true)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);

        assertNotNull(response.getBody());
        JsonObject caps = new com.google.gson.Gson().fromJson(response.getBody(), JsonObject.class)
                .getAsJsonObject("result").getAsJsonObject("capabilities")
                .getAsJsonObject("resources");
        // subscribe should be absent in stateless mode
        assertFalse(caps.has("subscribe"), "subscribe should not be advertised in stateless mode");
    }

    @Test
    void sessionedToolsListRequiresInitialize() {
        // Verify sessioned (default) mode still requires initialize for tools/list
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.SESSIONED)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);

        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{}}", null);
        assertTrue(response.contains("Missing or invalid MCP session"),
                "SESSIONED mode should require session: " + response);
    }

    @Test
    void supportsProtocolVersionRecognizesStateless() {
        McpServerConfig config = McpServerConfig.builder()
                .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                .build();
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry(), config);
        assertTrue(handler.supportsProtocolVersion("2026-07-28"));
        assertTrue(handler.supportsProtocolVersion("2025-11-25")); // still accepts sessioned versions
        assertTrue(handler.supportsProtocolVersion(null));      // null always accepted
    }

    @Test
    void statelessInitializeWithNonStringProtocolVersionReturnsError() {
        // protocolVersion at top-level body (not inside params) must be a string.
        // Handler rejects it before ever reaching method dispatch.
        McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
        String response = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"protocolVersion\":123}", null);
        assertTrue(response.contains("-32602"),
                "Expected -32602 (Invalid params) for non-string protocolVersion, got: " + response);
        assertTrue(response.contains("protocolVersion must be a string"),
                "Expected error message 'protocolVersion must be a string', got: " + response);
    }
}
