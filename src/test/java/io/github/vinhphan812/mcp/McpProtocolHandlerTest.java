package io.github.vinhphan812.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;

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
}
