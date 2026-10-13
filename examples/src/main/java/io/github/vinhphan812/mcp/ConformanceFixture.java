/*
 * MCP 2026-07-28 conformance fixture.
 *
 * This class is the server-under-test for the official `@modelcontextprotocol/conformance`
 * runner. It is NOT part of the SDK JAR — it is compiled and executed only inside the
 * `conformance` CI job.
 *
 *   java -cp <runtime JAR> io.github.vinhphan812.mcp.ConformanceFixture [--api-key=<token>]
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
 *   resources/list + resources/read + resourceTemplates/list + resourceTemplates/get
 *   prompts/list + prompts/get
 *   ping
 *   server/discover
 *   completion (SEP-2299 § Optional §SEP-2299-completion)
 *
 * Not implemented (extension / out-of-scope):
 *   Sampling, roots, logging, tasks — optional per MCP 2026-07-28.
 *   Elicitation (SEP-2322 § Optional — enabled; full MRTR input_required in #7)
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
import io.github.vinhphan812.mcp.api.dto.McpBlobContent;
import io.github.vinhphan812.mcp.api.handler.McpBlobResourceHandler;
import io.github.vinhphan812.mcp.api.handler.McpCompletionProvider;
import io.github.vinhphan812.mcp.api.utils.ElicitationNeededException;
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

        // ── Conformance runner named tools ──────────────────────────────────

        @McpTool(name = "test_simple_text", description = "Echo back the input text as a simple text result")
        public Map<String, Object> testSimpleText(
                @McpParam(name = "input", description = "Text to echo", required = false)
                String input) {
            return textResult(input != null ? input : "Conformance fixture text");
        }

        @McpTool(name = "test_image_content", description = "Return an image content result")
        public Map<String, Object> testImageContent(
                @McpParam(name = "caption", description = "Image caption", required = false)
                String caption) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> img = new LinkedHashMap<>();
            img.put("type", "image");
            // Minimal 1x1 transparent PNG (rfc9292 data URL)
            img.put("data", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
            img.put("mimeType", "image/png");
            if (caption != null) img.put("alt", caption);
            List<Map<String, Object>> contents = new ArrayList<>();
            contents.add(img);
            result.put("content", contents);
            return result;
        }

        @McpTool(name = "test_audio_content", description = "Return an audio content result")
        public Map<String, Object> testAudioContent(
                @McpParam(name = "transcript", description = "Audio transcript", required = false)
                String transcript) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("type", "audio");
            // Minimal WAV header (44 bytes) — silence
            byte[] silence = new byte[44];
            // WAV RIFF header
            silence[0] = 'R'; silence[1] = 'I'; silence[2] = 'F'; silence[3] = 'F';
            // File size - 8 (placeholder)
            silence[4] = 36; silence[5] = 0; silence[6] = 0; silence[7] = 0;
            silence[8] = 'W'; silence[9] = 'A'; silence[10] = 'V'; silence[11] = 'E';
            silence[12] = 'f'; silence[13] = 'm'; silence[14] = 't'; silence[15] = ' ';
            silence[16] = 16; silence[17] = 0; silence[18] = 0; silence[19] = 0;
            silence[20] = 1; silence[21] = 0; // PCM
            silence[22] = 1; silence[23] = 0; // Mono
            silence[24] = -1; silence[25] = -1; silence[26] = -1; silence[27] = -1; // 8000 Hz
            silence[28] = -1; silence[29] = -1; silence[30] = -1; silence[31] = -1; // byte rate
            silence[32] = 1; silence[33] = 0; silence[34] = 8; silence[35] = 0; // block align, bps
            silence[36] = 'd'; silence[37] = 'a'; silence[38] = 't'; silence[39] = 'a';
            String base64 = java.util.Base64.getEncoder().encodeToString(silence);
            audio.put("data", "data:audio/wav;base64," + base64);
            audio.put("mimeType", "audio/wav");
            if (transcript != null) audio.put("transcript", transcript);
            List<Map<String, Object>> contents = new ArrayList<>();
            contents.add(audio);
            result.put("content", contents);
            return result;
        }

        @McpTool(name = "test_embedded_resource", description = "Return an embedded resource content result")
        public Map<String, Object> testEmbeddedResource(
                @McpParam(name = "resourceUri", description = "URI of the resource to embed", required = false)
                String resourceUri) {
            Map<String, Object> result = new LinkedHashMap<>();
            Map<String, Object> emb = new LinkedHashMap<>();
            emb.put("type", "resource");
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("uri", resourceUri != null ? resourceUri : "test://static-text");
            resource.put("mimeType", "text/plain");
            resource.put("text", "Embedded resource content for " + (resourceUri != null ? resourceUri : "test://static-text"));
            emb.put("resource", resource);
            List<Map<String, Object>> contents = new ArrayList<>();
            contents.add(emb);
            result.put("content", contents);
            return result;
        }

        @McpTool(name = "test_multiple_content_types", description = "Return multiple content types in one result")
        public Map<String, Object> testMultipleContentTypes() {
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> contents = new ArrayList<>();

            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", "Hello from mixed content tool");
            contents.add(text);

            Map<String, Object> img = new LinkedHashMap<>();
            img.put("type", "image");
            img.put("data", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
            img.put("mimeType", "image/png");
            contents.add(img);
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("uri", "test://static-text");
            resource.put("mimeType", "text/plain");
            resource.put("text", "Mixed content resource");
            Map<String, Object> embedded = new LinkedHashMap<>();
            embedded.put("type", "resource");
            embedded.put("resource", resource);
            contents.add(embedded);

            result.put("content", contents);
            result.put("resultType", "complete");
            return result;
        }

        @McpTool(name = "test_error_handling", description = "Always returns an error")
        public Map<String, Object> testErrorHandling(
                @McpParam(name = "message", description = "Error message", required = false)
                String message) {
            throw new RuntimeException(message != null ? message : "intentional error");
        }

        @McpTool(name = "test_elicitation", description = "Tool that requires client-side input")
        public Map<String, Object> testElicitation(
                @McpParam(name = "id", description = "Task ID", required = true)
                String id) {
            throw new ElicitationNeededException(id, "Need input for task " + id);
        }

        @McpTool(name = "test_tool_with_progress", description = "Tool that supports progress notifications")
        public Map<String, Object> testToolWithProgress() {
            return textResult("Task completed successfully");
        }

        // ── Existing demo tools ──────────────────────────────────────────────

        @McpTool(name = "greet", description = "Return a personalised greeting string")
        public Map<String, Object> greet(
                @McpParam(name = "name", description = "Name to greet", required = true)
                String name) {
            return textResult("Hello, " + name + "!");
        }

        @McpTool(name = "echo", description = "Echo back the input arguments as a structured result")
        public Map<String, Object> echo(
                @McpParam(name = "value", description = "Value to echo", required = false)
                String value) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("echoed", value != null ? value : "");
            return result;
        }

        @McpTool(name = "add", description = "Add two integers and return the sum")
        public Map<String, Object> add(
                @McpParam(name = "a", description = "First operand", type = "integer", required = true)
                int a,
                @McpParam(name = "b", description = "Second operand", type = "integer", required = true)
                int b) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sum", a + b);
            return result;
        }

        @McpTool(name = "error-tool", description = "Always returns an error — used by conformance tests to verify error handling")
        public Map<String, Object> alwaysError(
                @McpParam(name = "message", description = "Error message", required = false)
                String message) {
            throw new RuntimeException(message != null ? message : "intentional error");
        }
    }

    @Resources
    public static final class DemoResources {

        @McpResource(uri = "static://readme", name = "Readme",
                description = "Static readme resource", mimeType = "text/plain")
        public String readme(String ignoredUri) {
            return "MCP Java SDK conformance fixture.\n"
                    + "Server: mcp-java-sdk\n"
                    + "Transport: Streamable HTTP\n"
                    + "Protocol: 2026-07-28";
        }

        @McpResource(uri = "static://capabilities", name = "Capabilities",
                description = "Server capabilities summary", mimeType = "application/json")
        public String capabilities(String ignoredUri) {
            return "{\"protocol\":\"2026-07-28\",\"transport\":\"streamable-http\","
                    + "\"capabilities\":[\"tools\",\"resources\",\"prompts\",\"ping\",\"server/discover\"]}";
        }

        // ── Conformance runner named resources ──────────────────────────────

        @McpResource(uri = "test://static-text", name = "Static Text",
                description = "A static text resource for conformance testing", mimeType = "text/plain")
        public String staticText(String ignoredUri) {
            return "This is static text content from the conformance fixture.";
        }

        @McpResourceTemplate(uriTemplate = "test://template/{id}/data", name = "Template Resource",
                description = "A templated resource for conformance testing", mimeType = "application/json")
        public String templateResource(String resolvedUri) {
            String[] segments = resolvedUri != null ? resolvedUri.split("/") : new String[0];
            String id = segments.length >= 2 ? segments[segments.length - 2] : "unknown";
            return "Template resource content for id: " + id;
        }
    }

    @Prompts
    public static final class DemoPrompts {

        // ── Conformance runner named prompts ────────────────────────────────

        @McpPrompt(name = "test_simple_prompt", description = "A simple test prompt")
        public Map<String, Object> testSimplePrompt() {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "This is a simple test prompt.");
            msg.put("content", content);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", "Simple test prompt");
            result.put("messages", Collections.singletonList(msg));
            return result;
        }

        @McpPrompt(name = "test_prompt_with_arguments", description = "A test prompt with arguments")
        public Map<String, Object> testPromptWithArguments(
                @McpParam(name = "arg1", description = "First argument", required = true)
                String arg1,
                @McpParam(name = "arg2", description = "Second argument", required = true)
                String arg2) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "text");
            content.put("text", "Hello " + arg1 + " and " + arg2 + "!");
            msg.put("content", content);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", "Prompt with arguments");
            result.put("messages", Collections.singletonList(msg));
            return result;
        }

        @McpPrompt(name = "test_prompt_with_embedded_resource", description = "A test prompt with an embedded resource")
        public Map<String, Object> testPromptWithEmbeddedResource() {
            List<Map<String, Object>> messages = new ArrayList<>();

            Map<String, Object> textMsg = new LinkedHashMap<>();
            textMsg.put("role", "user");
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("text", "Please summarize the following resource:");
            textMsg.put("content", textContent);
            messages.add(textMsg);

            Map<String, Object> embMsg = new LinkedHashMap<>();
            embMsg.put("role", "user");
            Map<String, Object> embContent = new LinkedHashMap<>();
            embContent.put("type", "resource");
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("uri", "test://static-text");
            resource.put("mimeType", "text/plain");
            resource.put("text", "Embedded resource content for conformance testing.");
            embContent.put("resource", resource);
            embMsg.put("content", embContent);
            messages.add(embMsg);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", "Prompt with embedded resource");
            result.put("messages", messages);
            return result;
        }

        @McpPrompt(name = "test_prompt_with_image", description = "A test prompt with an image")
        public Map<String, Object> testPromptWithImage(
                @McpParam(name = "description", description = "Image description", required = false)
                String description) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "user");
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("type", "image");
            // Minimal 1x1 transparent PNG
            content.put("data", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
            content.put("mimeType", "image/png");
            if (description != null) content.put("alt", description);
            msg.put("content", content);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", "Prompt with image");
            result.put("messages", Collections.singletonList(msg));
            return result;
        }

        // ── Existing demo prompt ────────────────────────────────────────────

        @McpPrompt(name = "greet-prompt", description = "Ask a model to generate a greeting")
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

    // ── Completion provider (SEP-2299) ─────────────────────────────────────

    public static final class DemoCompletions implements McpCompletionProvider {
        @Override
        public Map<String, Object> complete(Map<String, Object> reference, Map<String, Object> argument) {
            Map<String, Object> result = new LinkedHashMap<>();
            List<String> values = new ArrayList<>();
            values.add("option-a");
            values.add("option-b");
            values.add("option-c");
            result.put("values", values);
            return result;
        }
    }

    // ── Result helpers ──────────────────────────────────────────────────────

    private static Map<String, Object> textResult(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text != null ? text : "");
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(content);
        result.put("content", contents);
        result.put("resultType", "complete");
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
                .completions(true)
                .logging(false)
                .elicitation(true)
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

            // Register completion provider (SEP-2299).
            // Register under "ref/prompt" to match the MCP 2026 ref.type "ref/prompt".
            server.getRegistry().registerCompletionProvider("ref/prompt", new DemoCompletions());
            // Also register under "prompt" for backward compatibility.
            server.getRegistry().registerCompletionProvider("prompt", new DemoCompletions());

            // Register the binary resource — needs McpBlobResourceHandler (not via annotation)
            server.getRegistry().registerBlobResource(
                    "test://static-binary",
                    "Static Binary",
                    "A static binary (blob) resource for conformance testing",
                    "image/png",
                    new McpBlobResourceHandler() {
                        @Override
                        public String read(String uri) throws Exception {
                            // Return a text representation for the text read path
                            return "Binary resource at " + uri;
                        }
                        @Override
                        public McpBlobContent readBlob(String uri) throws Exception {
                            // Minimal 1x1 transparent PNG
                            String base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
                            return McpBlobContent.of(uri, "image/png", base64);
                        }
                    });

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
