/*
 * Copyright 2025 Phan Thanh Vinh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vinhphan812.mcp.core;

import com.google.gson.Gson;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.spi.McpSkillsExtension;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Skills extension integration: companion file serving,
 * directory response shape, capability gating, and null safety.
 */
public class McpSkillsExtensionTest {

    private static final Gson GSON = new Gson();

    // ─── Fixtures ───────────────────────────────────────────────────────────────

    /** A skills extension that supports directoryRead and serves companion files. */
    static class TestSkillsExtension implements McpSkillsExtension {
        private final Map<String, Map<String, String>> skillFiles;

        TestSkillsExtension(Map<String, Map<String, String>> skillFiles) {
            this.skillFiles = skillFiles;
        }

        @Override
        public boolean supportsDirectoryRead() {
            return true;
        }

        @Override
        public List<SkillDTO> listSkills(int offset, int limit) {
            return skillFiles.keySet().stream()
                    .skip(offset)
                    .limit(limit)
                    .map(name -> new SkillDTO(
                            "skill://" + name,          // uri
                            name,                       // name
                            name,                       // title
                            "Test skill",               // description
                            Map.of(),                   // manifest
                            Map.of("SKILL.md", ""),     // files
                            Map.of()))                  // digests
                    .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public SkillDTO getSkill(String uri) {
            String name = uri.replace("skill://", "").replace("/", "");
            if (skillFiles.containsKey(name)) {
                return new SkillDTO(
                        "skill://" + name, name, name, "Test skill",
                        Map.of(), Map.of("SKILL.md", ""), Map.of());
            }
            return null;
        }

        @Override
        public String readManifestFile(String uri, String fileName) {
            // Normalize the skill name from the URI consistently with listSkills:
            // skill://refunds        -> "refunds"
            // skill://refunds/       -> "refunds"
            // skill://refunds/scripts/check.py -> "refunds"
            String name = uri.replace("skill://", "");
            int slash = name.indexOf('/');
            name = slash >= 0 ? name.substring(0, slash) : name;
            Map<String, String> files = skillFiles.get(name);
            if (files == null) return null;
            return files.get(fileName);
        }

        @Override
        public List<DirectoryEntry> listDirectory(String skillRootUri) {
            String name = skillRootUri.replace("skill://", "").replace("/", "");
            Map<String, String> files = skillFiles.get(name);
            if (files == null) return List.of();
            return files.keySet().stream()
                    .filter(f -> !f.isEmpty())
                    .map(fileName -> new DirectoryEntry() {
                        private final String childName = fileName;
                        private final boolean isDir = fileName.endsWith("/");
                        @Override public String uri() {
                            String base = skillRootUri.replaceAll("/+$", "");
                            String child = childName.replaceAll("/$", "");
                            return base + "/" + child;
                        }
                        @Override public String name() {
                            return childName.replaceAll("/$", "");
                        }
                        @Override public boolean isDirectory() {
                            return isDir;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /** A skills extension with directoryRead disabled. */
    static class NoDirectoryReadExtension extends TestSkillsExtension {
        NoDirectoryReadExtension() {
            super(Map.of("refunds", Map.of("SKILL.md", "# Refunds\n")));
        }

        @Override
        public boolean supportsDirectoryRead() {
            return false;
        }
    }

    private McpProtocolHandler makeHandler(McpSkillsExtension ext) {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("test")
                .skillsExtension(ext)
                .build();
        McpRegistry registry = new McpRegistry();
        return new McpProtocolHandler(registry, config);
    }

    private McpProtocolHandler makeHandlerNoSkills() {
        McpServerConfig config = McpServerConfig.builder()
                .serverName("test")
                .build();
        McpRegistry registry = new McpRegistry();
        return new McpProtocolHandler(registry, config);
    }

    private Map<String, Object> send(McpProtocolHandler handler, String method, Map<String, Object> params) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        // Always use 2026-07-28 stateless mode for test requests so they don't
        // require a pre-existing session.
        Map<String, Object> mutableParams = params == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(params);
        if (!mutableParams.containsKey("protocolVersion")) {
            mutableParams.put("protocolVersion", "2026-07-28");
        }
        request.put("params", mutableParams);
        String body = GSON.toJson(request);
        String resp = handler.handleRequest(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(resp, Map.class);
        if (rpc.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> err = (Map<String, Object>) rpc.get("error");
            // Gson deserialises integer JSON values as Double when the target is Object;
            // cast to Number to get a stable numeric comparison.
            Number code = (Number) err.get("code");
            throw new AssertionError("RPC error " + code + ": " + err.get("message"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        return result;
    }

    private void assertRpcError(String response, int expectedCode) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(response, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) rpc.get("error");
        Number code = (Number) err.get("code");
        assertEquals(expectedCode, code.intValue(),
                "Expected code " + expectedCode + " but got " + code + ": " + err.get("message"));
    }

    // ─── Capability advertisement ────────────────────────────────────────────────

    @Test
    void advertiseDirectoryReadWhenSupported() {
        Map<String, String> files = Map.of("SKILL.md", "# Refunds\n");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        // Use server/discover (stateless 2026 mode) to check capability advertisement
        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "server/discover",
                "protocolVersion", "2026-07-28"));
        String resp = handler.handleRequest(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>)
                ((Map<String, Object>) result.get("capabilities")).get("resources");
        assertTrue((Boolean) resources.get("directoryRead"),
                "directoryRead should be advertised when extension supports it");
    }

    @Test
    void noDirectoryReadWhenNotSupported() {
        McpProtocolHandler handler = makeHandler(new NoDirectoryReadExtension());

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "server/discover",
                "protocolVersion", "2026-07-28"));
        String resp = handler.handleRequest(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>)
                ((Map<String, Object>) result.get("capabilities")).get("resources");
        assertNull(resources.get("directoryRead"),
                "directoryRead should NOT be advertised when extension does not support it");
    }

    @Test
    void noDirectoryReadWhenNoSkillsExtension() {
        McpProtocolHandler handler = makeHandlerNoSkills();

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "server/discover",
                "protocolVersion", "2026-07-28"));
        String resp = handler.handleRequest(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>)
                ((Map<String, Object>) result.get("capabilities")).get("resources");
        assertNull(resources.get("directoryRead"),
                "directoryRead should NOT be advertised when no skills extension is configured");
    }

    // ─── Companion file serving ─────────────────────────────────────────────────

    @Test
    void resourcesReadServesSkillRootAsManifest() {
        Map<String, String> files = Map.of("SKILL.md", "# Refunds\n\nPolicy for refunds.");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        Map<String, Object> result = send(handler, "resources/read",
                Map.of("uri", "skill://refunds"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> contents = (List<Map<String, String>>) result.get("contents");
        assertEquals(1, contents.size());
        assertEquals("skill://refunds", contents.get(0).get("uri"));
        assertEquals("text/markdown", contents.get(0).get("mimeType"));
        assertEquals("# Refunds\n\nPolicy for refunds.", contents.get(0).get("text"));
    }

    @Test
    void resourcesReadServesCompanionFile() {
        Map<String, String> files = Map.of(
                "SKILL.md", "# Refunds\n",
                "scripts/check.py", "#!/usr/bin/env python3\nprint('check')");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        Map<String, Object> result = send(handler, "resources/read",
                Map.of("uri", "skill://refunds/scripts/check.py"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> contents = (List<Map<String, String>>) result.get("contents");
        assertEquals(1, contents.size());
        assertEquals("skill://refunds/scripts/check.py", contents.get(0).get("uri"));
        assertEquals("#!/usr/bin/env python3\nprint('check')", contents.get(0).get("text"));
    }

    @Test
    void resourcesReadSkillRootWithTrailingSlash() {
        Map<String, String> files = Map.of("SKILL.md", "# Refunds\n");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        // skill://refunds/ is equivalent to skill://refunds
        Map<String, Object> result = send(handler, "resources/read",
                Map.of("uri", "skill://refunds/"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> contents = (List<Map<String, String>>) result.get("contents");
        assertEquals("# Refunds\n", contents.get(0).get("text"));
    }

    @Test
    void resourcesReadPathTraversalRejected() {
        Map<String, String> files = Map.of(
                "SKILL.md", "# Refunds\n",
                "scripts/check.py", "secret");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "resources/read",
                "params", Map.of("uri", "skill://refunds/../../scripts/check.py",
                        "protocolVersion", "2026-07-28")));
        String resp = handler.handleRequest(body);
        assertRpcError(resp, -32602);
    }

    @Test
    void resourcesReadUnknownSkillFile() {
        Map<String, String> files = Map.of("SKILL.md", "# Refunds\n");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "resources/read",
                "params", Map.of("uri", "skill://refunds/nonexistent.txt",
                        "protocolVersion", "2026-07-28")));
        String resp = handler.handleRequest(body);
        assertRpcError(resp, -32602);
    }

    @Test
    void resourcesReadSkillsNotConfigured() {
        McpProtocolHandler handler = makeHandlerNoSkills();

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "resources/read",
                "params", Map.of("uri", "skill://refunds",
                        "protocolVersion", "2026-07-28")));
        String resp = handler.handleRequest(body);
        assertRpcError(resp, -32601);
    }

    // ─── Directory response shape ────────────────────────────────────────────────

    @Test
    void directoryReadReturnsResourcesField() {
        Map<String, String> files = Map.of(
                "SKILL.md", "# Refunds\n",
                "scripts/", "",
                "scripts/check.py", "#!/usr/bin/env python3\n");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        Map<String, Object> result = send(handler, "resources/directory/read",
                Map.of("uri", "skill://refunds"));

        assertTrue(result.containsKey("resources"),
                "Result must contain 'resources' field");
        assertFalse(result.containsKey("entries"),
                "Result must NOT contain 'entries' field (deprecated)");
        assertEquals("complete", result.get("resultType"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");
        assertFalse(resources.isEmpty(), "resources list should not be empty");

        // Verify SKILL.md is present
        boolean hasSkillMd = resources.stream()
                .anyMatch(e -> "SKILL.md".equals(e.get("name")) && Boolean.FALSE.equals(e.get("isDirectory")));
        assertTrue(hasSkillMd, "SKILL.md must be listed as a non-directory resource");

        // Verify scripts/ is present as a directory
        boolean hasScripts = resources.stream()
                .anyMatch(e -> "scripts".equals(e.get("name")) && Boolean.TRUE.equals(e.get("isDirectory")));
        assertTrue(hasScripts, "scripts/ must be listed as a directory resource");
    }

    @Test
    void directoryReadWithTrailingSlash() {
        Map<String, String> files = Map.of("SKILL.md", "# Refunds\n");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        // URI with trailing slash must be handled gracefully
        Map<String, Object> result = send(handler, "resources/directory/read",
                Map.of("uri", "skill://refunds/"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");
        assertFalse(resources.isEmpty());
    }

    // ─── directoryRead capability gating ───────────────────────────────────────

    @Test
    void directoryReadDisabled_returnsMethodNotFound() {
        McpProtocolHandler handler = makeHandler(new NoDirectoryReadExtension());

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "resources/directory/read",
                "params", Map.of("uri", "skill://refunds",
                        "protocolVersion", "2026-07-28")));
        String resp = handler.handleRequest(body);
        assertRpcError(resp, -32601);
    }

    @Test
    void directoryReadSkillsNotConfigured_returnsMethodNotFound() {
        McpProtocolHandler handler = makeHandlerNoSkills();

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "resources/directory/read",
                "params", Map.of("uri", "skill://refunds",
                        "protocolVersion", "2026-07-28")));
        String resp = handler.handleRequest(body);
        assertRpcError(resp, -32601);
    }

    // ─── server/discover capability ─────────────────────────────────────────────

    @Test
    void serverDiscoverAdvertisesDirectoryReadWhenSupported() {
        Map<String, String> files = Map.of("SKILL.md", "# Refunds\n");
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("refunds", files));
        McpProtocolHandler handler = makeHandler(ext);

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "server/discover",
                "protocolVersion", "2026-07-28"));
        String resp = handler.handleRequest(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>)
                ((Map<String, Object>) result.get("capabilities")).get("resources");
        assertTrue((Boolean) resources.get("directoryRead"),
                "server/discover should advertise directoryRead when supported");
    }

    @Test
    void serverDiscoverNoDirectoryReadWhenNotSupported() {
        McpProtocolHandler handler = makeHandler(new NoDirectoryReadExtension());

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "server/discover",
                "protocolVersion", "2026-07-28"));
        String resp = handler.handleRequest(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpc = GSON.fromJson(resp, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>)
                ((Map<String, Object>) result.get("capabilities")).get("resources");
        assertNull(resources.get("directoryRead"),
                "server/discover should NOT advertise directoryRead when not supported");
    }
}
