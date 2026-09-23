package io.github.vinhphan812.mcp;

import com.google.gson.annotations.SerializedName;
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

        // Address POJO: Gson round-trip from JSON Map to typed parameter
        Map<String, Object> addressJson = new LinkedHashMap<>();
        addressJson.put("street", "123 Main St");
        addressJson.put("city", "Hanoi");
        addressJson.put("country", "Vietnam");
        Map<String, Object> addrResult = registrar.tools.get("format-address").call(
                Map.of("address", addressJson));
        assertEquals("123 Main St, Hanoi, Vietnam", addrResult.get("formatted"));
        assertEquals("123 Main St", addrResult.get("street"));
        assertEquals("Hanoi", addrResult.get("city"));
        assertEquals("Vietnam", addrResult.get("country"));

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

        @McpTool(name = "format-address")
        public Map<String, Object> formatAddress(
                @McpParam(name = "address", required = true) Address address) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("formatted", address.getStreet() + ", " + address.getCity() + ", " + address.getCountry());
            result.put("street", address.getStreet());
            result.put("city", address.getCity());
            result.put("country", address.getCountry());
            return result;
        }
    }

    static class Address {
        @SerializedName("street")
        private final String street;
        @SerializedName("city")
        private final String city;
        @SerializedName("country")
        private final String country;

        Address(String street, String city, String country) {
            this.street = street;
            this.city = city;
            this.country = country;
        }

        String getStreet() {
            return street;
        }

        String getCity() {
            return city;
        }

        String getCountry() {
            return country;
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
