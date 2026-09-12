package io.github.vinhphan812.mcp.examples;

import io.github.vinhphan812.mcp.annotations.McpParam;
import io.github.vinhphan812.mcp.annotations.McpPrompt;
import io.github.vinhphan812.mcp.annotations.McpResource;
import io.github.vinhphan812.mcp.annotations.McpResourceTemplate;
import io.github.vinhphan812.mcp.annotations.McpTool;
import io.github.vinhphan812.mcp.annotations.Prompts;
import io.github.vinhphan812.mcp.annotations.Resources;
import io.github.vinhphan812.mcp.annotations.Tools;
import io.github.vinhphan812.mcp.api.handler.McpCompletionProvider;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.core.McpServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone Java 8 MCP server demonstrating the SDK's main registrations. */
public final class GrizzlyExample {
    private GrizzlyExample() { }

    @Tools
    public static final class DemoTools {
        @McpTool(name = "greet", description = "Create a greeting for a person")
        public Map<String, Object> greet(
                @McpParam(name = "name", description = "Person name", required = true)
                String name) {
            return textResult("Hello, " + name + "!");
        }

        @McpTool(name = "calculate-total", description = "Calculate a line-item total")
        public Map<String, Object> calculateTotal(
                @McpParam(name = "item", description = "Item label", required = true)
                String item,
                @McpParam(name = "quantity", description = "Number of items", type = "integer", required = true)
                int quantity,
                @McpParam(name = "unitPrice", description = "Price of one item", type = "number", required = true)
                double unitPrice) {
            double total = quantity * unitPrice;
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("item", item);
            structured.put("quantity", quantity);
            structured.put("unitPrice", unitPrice);
            structured.put("total", total);
            return structuredTextResult("Item: " + item + ", quantity: " + quantity
                    + ", unit price: " + unitPrice + ", total: " + total, structured);
        }

        @McpTool(name = "user-summary", description = "Summarise a demo user profile")
        public Map<String, Object> userSummary(
                @McpParam(name = "userId", description = "User identifier", required = true)
                String userId) {
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("userId", userId);
            structured.put("profileUri", "demo://users/" + userId);
            structured.put("displayName", "Demo User " + userId);
            structured.put("summary", "Demo User " + userId + " is an example profile.");
            return structuredTextResult("User " + userId + " profile: demo://users/" + userId
                    + ". Demo User " + userId + " is an example profile.", structured);
        }
    }

    @Resources
    public static final class DemoResources {
        @McpResource(uri = "demo://readme", name = "Demo README",
                description = "Static documentation resource", mimeType = "text/plain")
        public String readme(String ignoredUri) {
            return "MCP Java SDK demo\nTools, resources, templates, and prompts.";
        }

        @McpResource(uri = "demo://catalog", name = "Demo catalogue",
                description = "Static catalogue used by the example", mimeType = "application/json")
        public String catalog(String ignoredUri) {
            return "{\"items\":[{\"id\":\"coffee\",\"unitPrice\":3.5},{\"id\":\"tea\",\"unitPrice\":2.5}]}";
        }

        @McpResourceTemplate(uriTemplate = "demo://users/{userId}", name = "User profile",
                description = "Loads a demo user profile", mimeType = "application/json")
        public String userProfile(String uri) {
            String userId = uri.substring(uri.lastIndexOf('/') + 1);
            return "{\"userId\":\"" + userId + "\",\"displayName\":\"Demo User\"}";
        }

        @McpResourceTemplate(uriTemplate = "demo://orders/{orderId}", name = "Order receipt",
                description = "Loads a demo order receipt", mimeType = "application/json")
        public String orderReceipt(String uri) {
            String orderId = uri.substring(uri.lastIndexOf('/') + 1);
            return "{\"orderId\":\"" + orderId + "\",\"status\":\"ready\",\"currency\":\"GBP\"}";
        }
    }

    @Prompts
    public static final class DemoPrompts {
        @McpPrompt(name = "explain-user", description = "Ask a model to explain a user profile")
        public Map<String, Object> explainUser(
                @McpParam(name = "userId", description = "User identifier", required = true)
                String userId) {
            return promptResult("Explain the profile in demo://users/" + userId + " in plain language.");
        }

        @McpPrompt(name = "review-order", description = "Ask a model to review an order")
        public Map<String, Object> reviewOrder(
                @McpParam(name = "orderId", description = "Order identifier", required = true)
                String orderId,
                @McpParam(name = "focus", description = "Review focus", required = false)
                String focus) {
            String requestedFocus = focus == null || focus.trim().isEmpty() ? "items and status" : focus;
            return promptResult("Review demo://orders/" + orderId + " with focus on " + requestedFocus + ".");
        }
    }

    private static McpCompletionProvider demoCompletionProvider() {
        return (reference, argument) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            String value = String.valueOf(argument.get("value"));
            result.put("values", Collections.singletonList(value + "-completion"));
            return result;
        };
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

    private static Map<String, Object> structuredTextResult(String text, Map<String, Object> structuredContent) {
        Map<String, Object> result = textResult(text);
        result.put("structuredContent", structuredContent);
        return result;
    }

    private static Map<String, Object> promptResult(String text) {
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

        McpServer server = McpServer.builder()
                .registry(registry)
                .config(McpServerConfig.builder()
                        .serverName("grizzly-example")
                        .serverVersion("1.0.0")
                        .protocolVersion("2025-11-25")
                        .tools(true).resources(true).prompts(true)
                        .logging(true).completions(true).tasks(true)
                        .experimental(experimental)
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
