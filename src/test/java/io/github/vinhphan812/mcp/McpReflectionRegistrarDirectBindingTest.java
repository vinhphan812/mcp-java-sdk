package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpTool;
import io.github.vinhphan812.mcp.annotations.Tools;
import io.github.vinhphan812.mcp.api.McpReflectionRegistrar;
import io.github.vinhphan812.mcp.api.handler.McpCompletionProvider;
import io.github.vinhphan812.mcp.api.handler.McpPromptHandler;
import io.github.vinhphan812.mcp.api.handler.McpResourceHandler;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.spi.McpRegistrar;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unused")
class McpReflectionRegistrarDirectBindingTest {
    @Test
    void bindsStringIntAndBooleanParametersAndRetainsMapCompatibility() throws Exception {
        CapturingRegistrar registrar = new CapturingRegistrar();
        McpReflectionRegistrar.register(new DirectTools(), registrar);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", "Ada");
        arguments.put("count", 3);
        arguments.put("enabled", true);
        assertEquals("Ada:3:true", registrar.tools.get("direct").call(arguments).get("result"));

        Map<String, Object> mapArguments = new LinkedHashMap<>();
        mapArguments.put("value", "kept");
        assertEquals(mapArguments, registrar.tools.get("map").call(mapArguments).get("result"));
    }

    @Test
    void rejectsMissingRequiredAndInvalidDirectParameterTypes() {
        CapturingRegistrar registrar = new CapturingRegistrar();
        McpReflectionRegistrar.register(new DirectTools(), registrar);

        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("name", "Ada");
        missing.put("count", 3);
        String missingMessage = assertThrows(IllegalArgumentException.class,
                () -> registrar.tools.get("direct").call(missing)).getMessage();
        assertTrue(missingMessage.contains("Missing required parameter 'enabled'"));

        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("name", "Ada");
        invalid.put("count", "three");
        invalid.put("enabled", true);
        String message = assertThrows(IllegalArgumentException.class,
                () -> registrar.tools.get("direct").call(invalid)).getMessage();
        assertTrue(message.contains("Invalid parameter 'count'"));
    }

    @Tools
    static class DirectTools {
        @McpTool(name = "direct")
        public Map<String, Object> direct(
                @McpParam(name = "name", required = true) String name,
                @McpParam(name = "count", type = "integer", required = true) int count,
                @McpParam(name = "enabled", type = "boolean", required = true) boolean enabled) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", name + ":" + count + ":" + enabled);
            return result;
        }

        @McpTool(name = "map")
        public Map<String, Object> map(Map<String, Object> arguments) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", arguments);
            return result;
        }
    }

    private static final class CapturingRegistrar implements McpRegistrar {
        private final Map<String, McpToolHandler> tools = new LinkedHashMap<>();

        @Override
        public void registerTool(String name, String description, Map<String, Object> inputSchema,
                                 List<String> required, McpToolHandler handler) {
            tools.put(name, handler);
        }

        @Override
        public void registerTool(String name, String description, Map<String, Object> inputSchema,
                                 List<String> required, Map<String, Object> outputSchema,
                                 McpToolHandler handler) {
            tools.put(name, handler);
        }

        @Override
        public void registerResource(String uri, String name, String description, String mimeType,
                                     McpResourceHandler handler) {
        }

        @Override
        public void registerResourceTemplate(String uriTemplate, String name, String description,
                                             String mimeType, McpResourceHandler handler) {
        }

        @Override
        public void registerPrompt(String name, String description, List<Map<String, Object>> arguments,
                                   McpPromptHandler handler) {
        }

        @Override
        public void registerCompletionProvider(String referenceType, McpCompletionProvider provider) {
        }

        @Override
        public void notifyResourceUpdated(String uri) {
        }
    }
}
