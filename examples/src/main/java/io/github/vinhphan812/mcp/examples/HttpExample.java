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

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Standalone Java 8 MCP server demonstrating the SDK's main registrations. */
public final class HttpExample {
    private static final Logger LOGGER = Logger.getLogger(HttpExample.class.getName());

    private HttpExample() {
    }

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

        @McpTool(name = "search-catalog", description = "Search the demo catalogue by item name")
        public Map<String, Object> searchCatalog(
                @McpParam(name = "query", description = "Search text", required = true)
                String query) {
            String term = query.toLowerCase();
            List<Map<String, Object>> matches = new ArrayList<>();
            if ("coffee".contains(term)) matches.add(catalogItem("coffee", 3.5, "beverage"));
            if ("tea".contains(term)) matches.add(catalogItem("tea", 2.5, "beverage"));
            if ("sandwich".contains(term)) matches.add(catalogItem("sandwich", 6.0, "food"));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("query", query);
            structured.put("matches", matches);
            return structuredTextResult("Found " + matches.size() + " catalogue item(s) for '" + query + "'.", structured);
        }

        @McpTool(name = "validate-order", description = "Validate a demo order quantity and unit price")
        public Map<String, Object> validateOrder(
                @McpParam(name = "quantity", description = "Number of items", type = "integer", required = true)
                int quantity,
                @McpParam(name = "unitPrice", description = "Price of one item", type = "number", required = true)
                double unitPrice) {
            boolean valid = quantity > 0 && unitPrice >= 0;
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("valid", valid);
            structured.put("quantity", quantity);
            structured.put("unitPrice", unitPrice);
            structured.put("message", valid ? "Order values are valid." : "Quantity must be positive and unit price must not be negative.");
            return structuredTextResult(valid ? "Order is valid." : "Order is invalid.", structured);
        }

        @McpTool(name = "format-address", description = "Format an address for display")
        public Map<String, Object> formatAddress(
                @McpParam(name = "address", description = "Full address object", required = true)
                Address address) {
            String formatted = address.getStreet() + ", " + address.getCity() + ", "
                    + address.getCountry();
            return textResult(formatted);
        }

        private static Map<String, Object> catalogItem(String id, double unitPrice, String category) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("unitPrice", unitPrice);
            item.put("category", category);
            return item;
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

        @McpResource(uri = "demo://policies", name = "Demo policies",
                description = "Example service policies", mimeType = "text/markdown")
        public String policies(String ignoredUri) {
            return "# Demo policies\n\n- Orders are validated before submission.\n- Prices are expressed in GBP.\n- This resource is static example data.";
        }

        @McpResourceTemplate(uriTemplate = "demo://products/{productId}", name = "Product detail",
                description = "Loads a demo product", mimeType = "application/json")
        public String productDetail(String uri) {
            String productId = uri.substring(uri.lastIndexOf('/') + 1);
            return "{\"productId\":\"" + productId + "\",\"name\":\"Demo " + productId + "\",\"available\":true}";
        }

        @McpResourceTemplate(uriTemplate = "demo://users/{userId}/preferences", name = "User preferences",
                description = "Loads preferences for a demo user", mimeType = "application/json")
        public String userPreferences(String uri) {
            String path = uri.substring(uri.indexOf("users/") + 6);
            String userId = path.substring(0, path.indexOf('/'));
            return "{\"userId\":\"" + userId + "\",\"language\":\"en-GB\",\"notifications\":true}";
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

        @McpPrompt(name = "summarise-catalog", description = "Ask a model to summarise the catalogue")
        public Map<String, Object> summariseCatalog(
                @McpParam(name = "audience", description = "Intended audience", required = false)
                String audience) {
            String target = audience == null || audience.trim().isEmpty() ? "a general audience" : audience;
            return promptResult("Summarise demo://catalog for " + target + ". Include prices and item categories.");
        }

        @McpPrompt(name = "troubleshoot-service", description = "Ask a model to troubleshoot a service issue")
        public Map<String, Object> troubleshootService(
                @McpParam(name = "symptom", description = "Observed symptom", required = true)
                String symptom) {
            return promptResult("Help troubleshoot this service symptom: " + symptom
                    + ". Check demo://policies and suggest safe next steps.");
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
        experimental.put("http-example", Collections.singletonMap("demo", true));

        McpServer server = McpServer.builder()
                .registry(registry)
                .config(McpServerConfig.builder()
                        .serverName("http-example")
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
        LOGGER.info("MCP server listening at " + server.getUrl());
        LOGGER.info("Registered tools: " + server.getRegistry().getRegisteredTools());
        LOGGER.info("Registered resources: " + server.getRegistry().getRegisteredResources());
        LOGGER.info("Registered templates: " + server.getRegistry().getRegisteredResourceTemplates());
        LOGGER.info("Registered prompts: " + server.getRegistry().getRegisteredPrompts());
        Thread.currentThread().join();
    }

    /** POJO demonstrating Gson deserialisation for @McpParam complex types. */
    public static class Address {
        @SerializedName("street")
        private final String street;
        @SerializedName("city")
        private final String city;
        @SerializedName("country")
        private final String country;

        public Address(String street, String city, String country) {
            this.street = street;
            this.city = city;
            this.country = country;
        }

        public String getStreet() {
            return street;
        }

        public String getCity() {
            return city;
        }

        public String getCountry() {
            return country;
        }
    }
}
