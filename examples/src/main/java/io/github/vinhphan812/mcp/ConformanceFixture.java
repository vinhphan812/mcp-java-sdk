/*
 * MCP 2026-07-28 conformance fixture.
 *
 * This class is the server-under-test for the official `@modelcontextprotocol/conformance`
 * runner. It is NOT part of the SDK JAR — it is compiled and executed only inside the
 * `conformance` CI job (examples/conformance fixture):
 *
 *   java -cp <runtime JAR> ConformanceFixture [--api-key=<token>]
 *
 * Design constraints:
 *   - Runs on an ephemeral port (0) so CI can spin up multiple instances.
 *   - Prints the base URL to stdout before accepting requests, so the CI script can
 *     capture it before the conformance runner connects.
 *   - Terminates cleanly on interrupt or when stdin closes.
 *   - Does NOT depend on any file-based configuration; all behaviour is self-contained.
 *
 * Capabilities (protocolVersion 2026-07-28):
 *   tools/list + tools/call
 *   resources/list + resources/read + resourceTemplates/list
 *   prompts/list
 *   ping
 *   server/discover
 *
 * Not implemented (extension / out-of-scope):
 *   Sampling, roots, logging, tasks, elicitation — optional per MCP 2026-07-28.
 */
package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.annotations.McpParam;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConformanceFixture {

    private static final Logger LOGGER = Logger.getLogger(ConformanceFixture.class.getName());

    private ConformanceFixture() {}

    // ── Registered components ─────────────────────────────────────────────────

    @Tools
    public static final class DemoTools {

        @McpTool(
                name = "greet",
                description = "Return a personalised greeting string")
        public Map<String, Object> greet(
                @McpParam(name = "name", description = "Name to greet", required = true)
                String name) {
            return textResult("Hello, " + name + "!");
        }

        @McpTool(
                name = "echo",
                description = "Echo back the input arguments as a structured result")
        public Map<String, Object> echo(
                @McpParam(name = "value", description = "Value to echo", required = false)
                String value) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("echoed", value != null ? value : "");
            return result;
        }

        @McpTool(
                name = "add",
                description = "Add two integers and return the sum")
        public Map<String, Object> add(
                @McpParam(name = "a", description = "First operand", type = "integer", required = true)
                int a,
                @McpParam(name = "b", description = "Second operand", type = "integer", required = true)
                int b) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sum", a + b);
            return result;
        }

        @McpTool(
                name = "error-tool",
                description = "Always returns an error — used by conformance tests to verify error handling")
        public Map<String, Object> alwaysError(
                @McpParam(name = "message", description = "Error message", required = false)
                String message) {
            throw new RuntimeException(message != null ? message : "intentional error");
        }
    }

    @Resources
    public static final class DemoResources {

        @McpResource(
                uri = "static://readme",
                name = "Readme",
                description = "Static readme resource",
                mimeType = "text/plain")
        public String readme(String ignoredUri) {
            return "MCP Java SDK conformance fixture.\n"
                    + "Server: mcp-java-sdk\n"
                    + "Transport: Streamable HTTP\n"
                    + "Protocol: 2026-07-28";
        }

        @McpResource(
                uri = "static://capabilities",
                name = "Capabilities",
                description = "Server capabilities summary",
                mimeType = "application/json")
        public String capabilities(String ignoredUri) {
            return "{\"protocol\":\"2026-07-28\",\"transport\":\"streamable-http\","
                    + "\"capabilities\":[\"tools\",\"resources\",\"prompts\",\"ping\",\"server/discover\"]}";
        }
    }

    @Prompts
    public static final class DemoPrompts {

        @McpPrompt(
                name = "greet-prompt",
                description = "Ask a model to generate a greeting")
        public Map<String, Object> greetPrompt(
                @McpParam(name = "name", description = "Name of person to greet", required = true)
                String name) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "Write a friendly greeting for " + name + ".");
            msg.put("content", content);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", "Greeting prompt for " + name);
            result.put("messages", Collections.singletonList(msg));
            return result;
        }
    }

    // ── Result helpers ──────────────────────────────────────────────────────

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

    // ── Main ────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // Parse optional --api-key flag.
        String apiKey = null;
        for (String arg : args) {
            if (arg.startsWith("--api-key=")) {
                apiKey = arg.substring("--api-key=".length());
            }
        }

        McpRegistry registry = new McpRegistry();

        // Suppress Grizzly access log chatter to keep stdout clean for CI parsing.
        System.setProperty("org.glassfish.grizzly.http.server.HttpHandler.log", "false");

        McpServerConfig config = McpServerConfig.builder()
                .serverName("conformance-fixture")
                .serverVersion("1.0.0")
                .protocolVersion("2026-07-28")
                .tools(true)
                .resources(true)
                .prompts(true)
                .logging(false)
                .build();

        McpServer.Builder builder = McpServer.builder()
                .registry(registry)
                .config(config)
                .host("127.0.0.1")
                .port(0)                        // ephemeral port — OS chooses
                .endpoint("/mcp");

        if (apiKey != null && !apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        }

        try (McpServer server = builder.build()) {
            server
                .register(new DemoTools())
                .register(new DemoResources())
                .register(new DemoPrompts());

            server.start();
            String url = server.getUrl();

            // Print the URL on its own line — CI script greps for this.
            System.out.println("CONFORMANCE_FIXTURE_URL=" + url);
            System.out.flush();
            LOGGER.info("Conformance fixture listening at " + url);

            // Block until interrupted (SIGTERM / Ctrl+C). The CI step kills this process
            // by PID after the conformance runner exits. Using Thread.sleep rather than
            // stdin.readLine avoids background-process edge cases on Windows/CI runners
            // where stdin is closed immediately.
            Thread.sleep(Long.MAX_VALUE);
        }
        LOGGER.info("Conformance fixture stopped.");
    }
}
