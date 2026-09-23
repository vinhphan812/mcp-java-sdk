package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.annotations.*;
import io.github.vinhphan812.mcp.api.McpReflectionRegistrar;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpExampleRegistrationTest {
    @Tools
    static class ToolsProvider {
        @McpTool(name = "greet")
        public Map<String, Object> greet() {
            return new LinkedHashMap<>();
        }
    }

    @Resources
    static class ResourcesProvider {
        // noinspection SameReturnValue
        @McpResource(uri = "demo://readme")
        public String readme(String uri) {
            return "readme";
        }

        @McpResourceTemplate(uriTemplate = "demo://users/{id}")
        public String user(String uri) {
            return uri;
        }
    }

    @Prompts
    static class PromptsProvider {
        @McpPrompt(name = "explain")
        public Map<String, Object> explain() {
            return new LinkedHashMap<>();
        }
    }

    @Test
    void annotatedProvidersRegisterToolResourceTemplateAndPrompt() {
        McpRegistry registry = new McpRegistry();
        McpReflectionRegistrar.register(new ToolsProvider(), registry);
        McpReflectionRegistrar.register(new ResourcesProvider(), registry);
        McpReflectionRegistrar.register(new PromptsProvider(), registry);

        assertEquals(1, registry.getRegisteredTools().size());
        assertEquals(1, registry.getRegisteredResources().size());
        assertEquals(1, registry.getRegisteredResourceTemplates().size());
        assertEquals(1, registry.getRegisteredPrompts().size());
    }
}
