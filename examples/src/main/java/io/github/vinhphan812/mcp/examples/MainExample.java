package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.api.handler.McpCompletionProvider;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.core.McpServer;
import io.github.vinhphan812.mcp.examples.tools.ToolExample;
import io.github.vinhphan812.mcp.examples.tools.ToolWithConfirmationExample;
import io.github.vinhphan812.mcp.examples.tools.ToolWithInputSchemaExample;
import io.github.vinhphan812.mcp.examples.tools.ToolWithScopesExample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Standalone Java 8 MCP server demonstrating the SDK's main registrations. */
public final class MainExample {
    private static final Logger LOGGER = Logger.getLogger(MainExample.class.getName());

    private MainExample() {
    }

    private static McpCompletionProvider demoCompletionProvider() {
        return (reference, argument) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            String value = String.valueOf(argument.get("value"));
            result.put("values", Collections.singletonList(value + "-completion"));
            return result;
        };
    }

    public static Map<String, Object> promptResult(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        message.put("content", content);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "Generated example prompt");
        result.put("messages", Collections.singletonList(message));
        return result;
    }

    public static void main(String[] args) throws Exception {
        McpRegistry registry = new McpRegistry();
        registry.registerCompletionProvider("demo", demoCompletionProvider());
        Map<String, Object> experimental = new LinkedHashMap<>();
        experimental.put("grizzly-example", Collections.singletonMap("demo", true));

        McpServerConfig.Builder configBuilder = McpServerConfig.builder()
                .serverName("grizzly-example")
                .serverVersion("1.0.0")
                .protocolVersion("2025-11-25")
                .tools(true).resources(true).prompts(true)
                .logging(true).completions(true).tasks(true)
                .experimental(experimental)
                .resourceSubscriptions(true);

        RateLimitExample.configure(configBuilder);
        MiddlewareExample.configure(configBuilder);
        ApiKeyStoreExample.configure(configBuilder);
        AuthorizationExample.configure(configBuilder);

        McpServer server = McpServer.builder()
                .registry(registry)
                .config(configBuilder.build())
                .host("127.0.0.1")
                .port(3011)
                .endpoint("/mcp")
                .build()
                .register(new ToolExample())
                .register(new ToolWithInputSchemaExample())
                .register(new ToolWithConfirmationExample())
                .register(new ToolWithScopesExample())
                .register(new ResourceExample())
                .register(new ResourceTemplateExample())
                .register(new PromptExample())
                .register(new PromptWithArgsExample());

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        LOGGER.info("MCP server listening at " + server.getUrl());
        LOGGER.info("Registered tools: " + server.getRegistry().getRegisteredTools());
        LOGGER.info("Registered resources: " + server.getRegistry().getRegisteredResources());
        LOGGER.info("Registered templates: " + server.getRegistry().getRegisteredResourceTemplates());
        LOGGER.info("Registered prompts: " + server.getRegistry().getRegisteredPrompts());
        Thread.currentThread().join();
    }
}
