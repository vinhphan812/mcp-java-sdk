package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpPrompt;
import io.github.vinhphan812.mcp.annotations.McpResource;
import io.github.vinhphan812.mcp.annotations.McpResourceTemplate;
import io.github.vinhphan812.mcp.annotations.McpTool;
import io.github.vinhphan812.mcp.annotations.Prompts;
import io.github.vinhphan812.mcp.annotations.Resources;
import io.github.vinhphan812.mcp.annotations.Tools;
import io.github.vinhphan812.mcp.api.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detailed standalone Java 8 MCP server with tool, resource, template, and prompt. */
public final class GrizzlyExample {
    private GrizzlyExample() { }

    @Tools
    public static final class DemoTools {
        @McpTool(name = "greet", description = "Create a greeting for a person")
        public Map<String, Object> greet(
                @McpParam(name = "name", description = "Person name", required = true)
                Map<String, Object> arguments) {
            String name = String.valueOf(arguments.get("name"));
            return textResult("Hello, " + name + "!");
        }
    }

    @Resources
    public static final class DemoResources {
        @McpResource(uri = "demo://readme", name = "Demo README",
                description = "Static documentation resource", mimeType = "text/plain")
        public String readme(String ignoredUri) {
            return "MCP Java SDK demo\nTools, resources, templates, and prompts.";
        }

        @McpResourceTemplate(uriTemplate = "demo://users/{userId}", name = "User profile",
                description = "Loads a demo user profile", mimeType = "application/json")
        public String userProfile(String uri) {
            String userId = uri.substring(uri.lastIndexOf('/') + 1);
            return "{\"userId\":\"" + userId + "\",\"displayName\":\"Demo User\"}";
        }
    }

    @Prompts
    public static final class DemoPrompts {
        @McpPrompt(name = "explain-user", description = "Build a prompt explaining user profile")
        public Map<String, Object> explainUser(
                @McpParam(name = "userId", description = "User identifier", required = true)
                Map<String, Object> arguments) {
            String userId = String.valueOf(arguments.get("userId"));
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "Explain profile from demo://users/" + userId);
            message.put("content", content);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", "Generated user explanation prompt");
            result.put("messages", Collections.singletonList(message));
            return result;
        }
    }

    private static Map<String, Object> textResult(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(content);
        result.put("content", contents);
        return result;
    }

    public static void main(String[] args) throws Exception {
        McpServer server = McpServer.builder()
                .config(McpServerConfig.builder()
                        .serverName("grizzly-example")
                        .serverVersion("1.0.0")
                        .protocolVersion("2025-11-25")
                        .tools(true).resources(true).prompts(true)
                        .resourceSubscriptions(true)
                        .build())
                .host("127.0.0.1")
                .port(3011)
                .endpoint("/mcp")
                .build()
                .register(new DemoTools())
                .register(new DemoResources())
                .register(new DemoPrompts());

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("MCP server listening at " + server.getUrl());
        System.out.println("Registered tools: " + server.getRegistry().getRegisteredTools());
        System.out.println("Registered resources: " + server.getRegistry().getRegisteredResources());
        System.out.println("Registered templates: " + server.getRegistry().getRegisteredResourceTemplates());
        System.out.println("Registered prompts: " + server.getRegistry().getRegisteredPrompts());
        Thread.currentThread().join();
    }
}
