package io.github.vinhphan812.mcp.cmd;

import io.github.vinhphan812.mcp.annotations.McpPrompt;
import io.github.vinhphan812.mcp.annotations.McpResource;
import io.github.vinhphan812.mcp.annotations.McpResourceTemplate;
import io.github.vinhphan812.mcp.annotations.McpTool;
import io.github.vinhphan812.mcp.annotations.Prompts;
import io.github.vinhphan812.mcp.annotations.Resources;
import io.github.vinhphan812.mcp.annotations.Tools;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpRegistry;
import io.github.vinhphan812.mcp.core.McpServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Minimal MCP server for conformance testing.
 * Registered tools, resources, and prompts cover the scenarios required
 * by the official MCP Conformance test suite (active suite, spec 2025-11-25).
 *
 * Usage:
 *   java -cp mcp-java-sdk.jar io.github.vinhphan812.mcp.cmd.ConformanceHarness
 *
 * The server binds to 0.0.0.0:8080/mcp (configurable via HOST/PORT env).
 */
public final class ConformanceHarness {

    private static final Logger LOGGER = Logger.getLogger(ConformanceHarness.class.getName());

    private ConformanceHarness() {}

    @Tools
    public static final class HarnessTools {
        @McpTool(name = "greet", description = "Return a greeting for the given name")
        public Map<String, Object> greet(String name) {
            return Map.of(
                "content", List.of(Map.of("type", "text", "text", "Hello, " + name + "!")),
                "isError", false
            );
        }

        @McpTool(name = "calculate", description = "Return sum of two numbers")
        public Map<String, Object> calculate(double a, double b) {
            return Map.of(
                "content", List.of(Map.of("type", "text", "text", String.valueOf(a + b))),
                "isError", false
            );
        }

        @McpTool(name = "get-user", description = "Return a demo user object")
        public Map<String, Object> getUser(String userId) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", userId);
            user.put("name", "Test User");
            return Map.of(
                "content", List.of(Map.of("type", "text", "text", "User: " + userId)),
                "isError", false
            );
        }

        @McpTool(name = "raise-error", description = "Always returns an error")
        public Map<String, Object> raiseError(String message) {
            return Map.of(
                "content", List.of(Map.of("type", "text", "text", "Error: " + message)),
                "isError", true
            );
        }
    }

    @Resources
    public static final class HarnessResources {
        @McpResource(uri = "test://readme", name = "Test README",
                description = "Conformance test resource", mimeType = "text/plain")
        public String readme(String uri) {
            return "Conformance test resource content";
        }

        @McpResource(uri = "test://hello", name = "Hello", description = "Simple text resource")
        public String hello(String uri) {
            return "Hello, world!";
        }

        @McpResourceTemplate(uriTemplate = "test://users/{id}", name = "User resource",
                description = "User by ID", mimeType = "application/json")
        public String userById(String uri) {
            return "{\"id\": \"1\", \"name\": \"Test User\"}";
        }
    }

    @Prompts
    public static final class HarnessPrompts {
        @McpPrompt(name = "greet-prompt", description = "Return a greeting prompt")
        public Map<String, Object> greetPrompt(String name) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "Greet " + name);
            msg.put("content", content);
            return Map.of(
                "description", "Greeting for " + name,
                "messages", List.of(msg)
            );
        }
    }

    public static void main(String[] args) throws Exception {
        String host = System.getenv("HOST");
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null) {
            port = Integer.parseInt(portEnv);
        }

        LOGGER.info("Starting ConformanceHarness on " + host + ":" + port);

        McpServer server = McpServer.builder()
                .config(McpServerConfig.builder()
                        .serverName("conformance-harness")
                        .serverVersion("1.0.0")
                        .protocolVersion("2025-11-25")
                        .tools(true)
                        .resources(true)
                        .prompts(true)
                        .logging(true)
                        .completions(true)
                        .tasks(true)
                        .resourceSubscriptions(true)
                        .build())
                .host(host != null ? host : "0.0.0.0")
                .port(port)
                .endpoint("/mcp")
                .build()
                .register(new HarnessTools())
                .register(new HarnessResources())
                .register(new HarnessPrompts());

        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        LOGGER.info("ConformanceHarness listening at " + server.getUrl());
        Thread.currentThread().join();
    }
}
