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
import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.dto.SkillResource;
import io.github.vinhphan812.mcp.api.dto.SkillResourceEntry;
import io.github.vinhphan812.mcp.api.spi.McpSkillsExtension;
import static io.github.vinhphan812.mcp.api.spi.McpSkillsExtension.buildResourceEntries;
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
            List<SkillDTO> page = skillFiles.keySet().stream()
                    .skip(offset)
                    .limit(limit)
                    .map(name -> new SkillDTO(
                            "skill://" + name + "/SKILL.md",
                            name,
                            name,
                            "Test skill",
                            Map.of(),
                            buildResourceEntries("skill://" + name,
                                    skillFiles.get(name).keySet(), this)))
                    .collect(java.util.stream.Collectors.toList());
            return page;
        }

        @Override
        public SkillDTO getSkill(String uri) {
            // Canonical URI ends with /SKILL.md: skill://refunds/SKILL.md
            String name = uri.replace("skill://", "").replace("/SKILL.md", "").replace("/", "");
            if (skillFiles.containsKey(name)) {
                return new SkillDTO(
                        "skill://" + name + "/SKILL.md", name, name, "Test skill",
                        Map.of(),
                        buildResourceEntries("skill://" + name,
                                skillFiles.get(name).keySet(), this));
            }
            return null;
        }

        @Override
        public String readManifestFile(String uri, String fileName) {
            byte[] bytes = readManifestFileBytes(uri, fileName);
            if (bytes == null) return null;
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public byte[] readManifestFileBytes(String uri, String fileName) {
            // Extract the skill name: full path after skill://
            // skill://refunds             -> "refunds"
            // skill://refunds/            -> "refunds"
            // skill://a/b                 -> "a/b"
            // skill://a/b/scripts/x.py    -> "a/b"
            // uri here is the discovered skillRoot (e.g. "skill://a/b")
            String name = uri.replace("skill://", "").replaceAll("/$", "");
            Map<String, String> files = skillFiles.get(name);
            if (files == null) return null;
            String content = files.get(fileName);
            if (content == null) return null;
            return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public List<DirectoryEntry> listDirectory(String skillRootUri) {
            String remainder = skillRootUri.replace("skill://", "");
            int slash = remainder.indexOf('/');
            String name = slash >= 0 ? remainder.substring(0, slash) : remainder;
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
                        @Override public String mimeType() {
                            if (isDir) return "inode/directory";
                            String name = childName.replaceAll("/$", "");
                            int dot = name.lastIndexOf('.');
                            if (dot < 0) return null;
                            String ext = name.substring(dot + 1).toLowerCase();
                            switch (ext) {
                                case "md": return "text/markdown";
                                case "txt": return "text/plain";
                                case "json": return "application/json";
                                case "yaml": case "yml": return "text/yaml";
                                case "sh": return "text/x-shellscript";
                                case "py": return "text/x-python";
                                default: return null;
                            }
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
                Map.of("uri", "skill://refunds/SKILL.md"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> contents = (List<Map<String, String>>) result.get("contents");
        assertEquals(1, contents.size());
        assertEquals("skill://refunds/SKILL.md", contents.get(0).get("uri"));
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
        assertEquals("text/x-python", contents.get(0).get("mimeType"));
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

        // Verify SKILL.md is present with correct MIME type
        boolean hasSkillMd = resources.stream()
                .anyMatch(e -> "SKILL.md".equals(e.get("name"))
                        && "text/markdown".equals(e.get("mimeType")));
        assertTrue(hasSkillMd, "SKILL.md must be listed with mimeType=text/markdown");

        // Verify scripts/ is present as a directory with inode/directory MIME type
        boolean hasScripts = resources.stream()
                .anyMatch(e -> "scripts".equals(e.get("name"))
                        && "inode/directory".equals(e.get("mimeType")));
        assertTrue(hasScripts, "scripts/ must be listed with mimeType=inode/directory");
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

    // ==================== Traversal protection ====================

    @Test
    void normalizePath_rejectsDoubleEncodedTraversal() throws Exception {
        // %252e%252e -> %2e%2e after first decode -> .. after second decode
        // Multi-pass decode catches this: after first decode, %2e%2e still contains
        // %XX, so we decode again, yielding "..", which is rejected.
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "%252e%252e"),
                "%252e%252e (double-encoded ..) must be rejected by multi-pass decode");
        assertNull(m.invoke(null, "%252e%252e%252f"),
                "%252e%252e%252f (double-encoded ../) must be rejected");
    }

    @Test
    void normalizePath_rejectsEncodedSlash() throws Exception {
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "scripts%2fdeploy.sh"),
                "encoded slash %2f must be rejected");
        assertNull(m.invoke(null, "scripts%2Fdeploy.sh"),
                "encoded slash %2F must be rejected");
    }

    @Test
    void normalizePath_rejectsMalformedPercentEncoding() throws Exception {
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "file%ZZ"), "malformed %ZZ must be rejected");
        assertNull(m.invoke(null, "file%"), "incomplete % at end must be rejected");
        assertNull(m.invoke(null, "file%2"), "incomplete %X at end must be rejected");
        assertNull(m.invoke(null, "file%2G"), "invalid hex digit G must be rejected");
    }

    @Test
    void normalizePath_rejectsBackslash() throws Exception {
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "scripts\\deploy.sh"), "backslash must be rejected");
        assertNull(m.invoke(null, "..\\file"), "backslash in traversal must be rejected");
    }

    @Test
    void normalizePath_rejectsRawDotDot() throws Exception {
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "../file"), "raw .. must be rejected");
        assertNull(m.invoke(null, "foo/../bar"), "embedded .. must be rejected");
        assertNull(m.invoke(null, "foo..bar"), "foo..bar must be rejected (contains ..)");
    }

    @Test
    void normalizePath_acceptsValidPaths() throws Exception {
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertEquals("scripts/check.py", m.invoke(null, "scripts/check.py"));
        assertEquals("README.MD", m.invoke(null, "README.MD"));  // case preserved
        assertEquals("src/main.java", m.invoke(null, "src/main.java"));
        // Valid percent-encoded space (%20)
        assertEquals("file name.txt", m.invoke(null, "file%20name.txt"));
        assertEquals("foo/bar", m.invoke(null, "foo/bar"));
    }

    @Test
    void normalizePath_rejectsLeadingTrailingSlash() throws Exception {
        java.lang.reflect.Method m = McpProtocolHandler.class.getDeclaredMethod(
                "normalizePath", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "/foo"), "leading slash must be rejected");
        assertNull(m.invoke(null, "foo/"), "trailing slash must be rejected");
    }

    // ==================== Blob wire shape ====================


    // ==================== Stable spec: cache contract ====================

    @Test
    void skillsList_hasCacheContract() {
        String manifestContent = "# Test\n";
        Map<String, String> files = Map.of("SKILL.md", manifestContent);
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("test", files));
        McpProtocolHandler handler = makeHandler(ext);

        Map<String, Object> result = send(handler, "skills/list", Map.of());

        assertEquals("complete", result.get("resultType"),
                "skills/list must include resultType: complete");
        assertNotNull(result.get("ttlMs"), "skills/list must include ttlMs");
        assertNotNull(result.get("cacheScope"), "skills/list must include cacheScope");
    }

    @Test
    void skillsGet_hasCacheContract() {
        String manifestContent = "# Test\n";
        Map<String, String> files = Map.of("SKILL.md", manifestContent);
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("test", files));
        McpProtocolHandler handler = makeHandler(ext);

        Map<String, Object> result = send(handler, "skills/get",
                Map.of("uri", "skill://test/SKILL.md"));

        assertEquals("complete", result.get("resultType"),
                "skills/get must include resultType: complete");
        assertNotNull(result.get("ttlMs"), "skills/get must include ttlMs");
        assertNotNull(result.get("cacheScope"), "skills/get must include cacheScope");
    }

    // ==================== Stable spec: directory file:// URI rejection ====================

    @Test
    void directoryRead_rejectsFileUri() {
        String manifestContent = "# Test\n";
        Map<String, String> files = Map.of("SKILL.md", manifestContent);
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("test", files));
        McpProtocolHandler handler = makeHandler(ext);

        String body = GSON.toJson(Map.of(
                "jsonrpc", "2.0", "id", 1,
                "method", "resources/directory/read",
                "params", Map.of("uri", "file:///etc/passwd",
                        "protocolVersion", "2026-07-28")));
        assertRpcError(handler.handleRequest(body), -32602);
    }

    // ==================== Stable spec: empty directory ====================

    @Test
    void directoryRead_emptyDirectory_returnsEmptyResources() {
        // A skill with only SKILL.md (no companion files)
        String emptyContent = "# Empty Skill\n";
        Map<String, String> files = Map.of("SKILL.md", emptyContent);
        McpSkillsExtension ext = new TestSkillsExtension(Map.of("empty", files));
        McpProtocolHandler handler = makeHandler(ext);

        Map<String, Object> result = send(handler, "resources/directory/read",
                Map.of("uri", "skill://empty"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");
        // SKILL.md should be in the list
        assertFalse(resources.isEmpty(), "directory with SKILL.md should not be empty");
    }

    // ==================== Stable spec: longest-match skill root ====================

    @Test
    void resourcesRead_longestMatchSkillRoot() {
        // skill://a and skill://a/b overlapping: longest root wins
        String aContent = "# Skill A\n";
        Map<String, String> filesA = Map.of("SKILL.md", aContent);
        String abContent = "# Skill A/B\n";
        Map<String, String> filesAB = Map.of("SKILL.md", abContent);
        McpSkillsExtension ext = new TestSkillsExtension(
                Map.of("a", filesA, "a/b", filesAB));
        McpProtocolHandler handler = makeHandler(ext);

        // skill://a/b should resolve to skill://a/b (longest match)
        Map<String, Object> result = send(handler, "resources/read",
                Map.of("uri", "skill://a/b/SKILL.md"));
        @SuppressWarnings("unchecked")
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("contents")).get(0)).get("text");
        assertTrue(text.contains("Skill A/B"), "skill://a/b should match skill://a/b, not skill://a");
    }

    // ==================== Stable spec: SkillResourceEntry ====================

    @Test
    void skillResourceEntry_toMap() {
        SkillResourceEntry entry = new SkillResourceEntry(
                "skill://test/README.md", "README.md", "sha256:abc123", 42);
        Map<String, Object> map = entry.toMap();
        assertEquals("skill://test/README.md", map.get("uri"));
        // B4: stable spec intentionally omits name from wire
        assertNull(map.get("name"));
        assertEquals("sha256:abc123", map.get("digest"));
        assertEquals(42L, map.get("size"));
    }

    @Test
    void skillResourceEntry_dynamic() {
        SkillResourceEntry dyn = SkillResourceEntry.dynamic();
        assertEquals("dynamic", dyn.uri);
        assertEquals("dynamic", dyn.name);
        assertNull(dyn.digest);
        assertEquals(-1L, dyn.size);
    }

    @Test
    void skillResourceEntry_equality() {
        SkillResourceEntry a = new SkillResourceEntry("u", "n", "d", 1L);
        SkillResourceEntry b = new SkillResourceEntry("u", "n", "d", 1L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void skillResourceEntry_toMap_dynamicOmitsDigestSize() {
        SkillResourceEntry dyn = SkillResourceEntry.dynamic();
        Map<String, Object> map = dyn.toMap();
        assertEquals("dynamic", map.get("uri"));
        assertFalse(map.containsKey("digest"), "dynamic entry must not include digest");
        assertFalse(map.containsKey("size"), "dynamic entry must not include size");
    }

    @Test
    void sha256Hex_textAndBytes() {
        String text = "Hello, world!";
        String fromText = McpSkillsExtension.sha256Hex(text);
        String fromBytes = McpSkillsExtension.sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(fromText, fromBytes, "sha256Hex must produce same result for text and bytes");
        assertTrue(fromText.startsWith("sha256:"), "digest must start with sha256:");
        assertEquals(71, fromText.length(), "sha256: + 64 hex chars = 71");
    }

    @Test
    void buildResourceEntries_includesSkillMdFirst() {
        java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        files.put("SKILL.md", "# Manifest\n");
        files.put("README.md", "Readme content");
        files.put("scripts/check.py", "#!/usr/bin/env python3\n");
        java.util.Map<String, java.util.Map<String, String>> skillFiles =
                new java.util.HashMap<>();
        skillFiles.put("test", new java.util.LinkedHashMap<>(files));
        TestSkillsExtension ext = new TestSkillsExtension(skillFiles);
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test", files.keySet(), ext);
        assertFalse(entries.isEmpty(), "entries must not be empty");
        assertEquals("SKILL.md", entries.get(0).name,
                "SKILL.md must be the first entry");
        assertTrue(entries.get(0).digest.startsWith("sha256:"), "digest must be present");
        assertTrue(entries.get(0).size > 0, "size must be positive");
    }

    @Test
    void buildResourceEntries_deduplicates() {
        // Test that LinkedHashMap (used internally by buildResourceEntries) deduplicates
        // by URI — first occurrence wins.
        java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        files.put("SKILL.md", "# Manifest\n");
        files.put("README.md", "Readme content");
        files.put("SKILL.md", "# Manifest dup\n");  // duplicate key - first wins
        java.util.Map<String, java.util.Map<String, String>> skillFiles =
                new java.util.HashMap<>();
        skillFiles.put("test", new java.util.LinkedHashMap<>(files));
        TestSkillsExtension ext = new TestSkillsExtension(skillFiles);
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test", files.keySet(), ext);
        long skillMdCount = entries.stream().filter(e -> "SKILL.md".equals(e.name)).count();
        assertEquals(1L, skillMdCount, "SKILL.md must appear exactly once");
    }

    @Test
    void blobResource_usesBlobFieldNotDataField() {
        // Create a binary skill resource
        SkillResource pngResource = SkillResource.blob("image/png", new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47  // PNG magic bytes
        });

        // Verify DTO properties
        assertTrue(pngResource.isBlob());
        assertFalse(pngResource.isText());
        assertEquals("image/png", pngResource.mimeType());
        assertArrayEquals(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, pngResource.blob());
        assertNull(pngResource.text());

        // Verify text resource shape
        SkillResource textResource = SkillResource.text("text/plain", "hello");
        assertTrue(textResource.isText());
        assertFalse(textResource.isBlob());
        assertEquals("hello", textResource.text());
        assertNull(textResource.blob());
    }

    // ==================== Binary file digest/size integrity ====================

    /**
     * Verifies that buildResourceEntries computes digest from raw bytes, not UTF-8 re-encoded.
     * A binary file containing 0x80 (UTF-8 BOM) must produce the same digest as
     * the raw file bytes — not the digest of its (invalid) UTF-8 interpretation.
     */
    @Test
    void buildResourceEntries_binaryFile_usesRawBytesForDigest() {
        // Simulate a binary file with a byte that is NOT valid UTF-8
        byte[] binaryContent = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  // PNG magic
                (byte) 0x80  // byte with high bit set, not valid UTF-8
        };
        // Create a mini extension that returns the raw binary bytes
        McpSkillsExtension binaryExt = new McpSkillsExtension() {
            @Override public List<SkillDTO> listSkills(int offset, int limit) { return List.of(); }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) {
                if ("logo.png".equals(fileName)) return binaryContent;
                return null;
            }
        };
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test",
                        List.of("logo.png"), binaryExt);
        assertEquals(1, entries.size());
        SkillResourceEntry entry = entries.get(0);
        // Digest must match SHA-256 of raw bytes (not UTF-8 decode of 0x80)
        String expectedDigest = McpSkillsExtension.sha256Hex(binaryContent);
        assertEquals(expectedDigest, entry.digest,
                "Digest must be SHA-256 of raw bytes, not UTF-8 re-encoded string");
        // Size must be exact byte count
        assertEquals(binaryContent.length, entry.size,
                "Size must be exact byte count of raw content");
    }

    /**
     * Verifies that a text file's digest matches SHA-256 of its UTF-8 bytes.
     * This confirms the sha256Hex(String) and sha256Hex(byte[]) overloads are consistent.
     */
    @Test
    void buildResourceEntries_textFile_digestMatchesUtf8Bytes() {
        String text = "# Title\n\nHello, 世界";
        byte[] utf8Bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expectedDigest = McpSkillsExtension.sha256Hex(utf8Bytes);
        McpSkillsExtension textExt = new McpSkillsExtension() {
            @Override public List<SkillDTO> listSkills(int offset, int limit) { return List.of(); }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) {
                if ("README.md".equals(fileName)) return utf8Bytes;
                return null;
            }
        };
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test",
                        List.of("README.md"), textExt);
        assertEquals(1, entries.size());
        assertEquals(expectedDigest, entries.get(0).digest);
        assertEquals(utf8Bytes.length, entries.get(0).size);
    }

    // ==================== Resources boundary tests ====================

    /**
     * Verifies that a skill with many resources (512+) does not overflow.
     * Tests that the buildResourceEntries loop handles large file lists.
     */
    @Test
    void buildResourceEntries_handles512Resources() {
        List<String> fileNames = new java.util.ArrayList<>();
        byte[] dummyBytes = "x".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Add 512 files
        for (int i = 0; i < 512; i++) {
            fileNames.add("file_" + String.format("%04d", i) + ".txt");
        }
        // SKILL.md is prepended, so add 512 companion files
        McpSkillsExtension ext = new McpSkillsExtension() {
            @Override public List<SkillDTO> listSkills(int offset, int limit) { return List.of(); }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) {
                if ("SKILL.md".equals(fileName)) {
                    return "# Test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                return dummyBytes;
            }
        };
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test", fileNames, ext);
        // SKILL.md + 512 files = 513 entries
        assertEquals(513, entries.size(), "SKILL.md + 512 companion files = 513 entries");
        assertEquals("SKILL.md", entries.get(0).name, "SKILL.md must be first");
    }

    /**
     * Verifies that a skill with 513 resources (boundary case) also works.
     */
    @Test
    void buildResourceEntries_handles513Resources() {
        List<String> fileNames = new java.util.ArrayList<>();
        byte[] dummyBytes = "x".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Add 513 files
        for (int i = 0; i < 513; i++) {
            fileNames.add("file_" + String.format("%04d", i) + ".txt");
        }
        McpSkillsExtension ext = new McpSkillsExtension() {
            @Override public List<SkillDTO> listSkills(int offset, int limit) { return List.of(); }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) {
                if ("SKILL.md".equals(fileName)) {
                    return "# Test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                return dummyBytes;
            }
        };
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test", fileNames, ext);
        assertEquals(514, entries.size(), "SKILL.md + 513 companion files = 514 entries");
        assertEquals("SKILL.md", entries.get(0).name, "SKILL.md must be first");
    }

    /**
     * Verifies total size limit: 16 MiB boundary.
     * Tests that files summing to >16MiB can be processed without overflow.
     */
    @Test
    void buildResourceEntries_totalSizeStaysWithin16MiB() {
        // Each "file" is 1MB; 20 files = 20MB total. The loop must not overflow long.
        List<String> fileNames = new java.util.ArrayList<>();
        byte[] mb = new byte[1024 * 1024];  // 1 MiB
        for (int i = 0; i < 20; i++) {
            fileNames.add("chunk_" + i + ".bin");
        }
        McpSkillsExtension ext = new McpSkillsExtension() {
            @Override public List<SkillDTO> listSkills(int offset, int limit) { return List.of(); }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) {
                if ("SKILL.md".equals(fileName)) {
                    return "# Test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                return mb;
            }
        };
        List<SkillResourceEntry> entries =
                McpSkillsExtension.buildResourceEntries("skill://test", fileNames, ext);
        // SKILL.md (9 bytes UTF-8) + 20 x 1MB = 20MB + 9 bytes
        assertEquals(21, entries.size(), "SKILL.md + 20 x 1MiB files");
        // Check that size accumulation works (long, not int overflow)
        long totalSize = entries.stream().mapToLong(e -> e.size).sum();
        // SKILL.md is 7 bytes in UTF-8: "# Test\n"
        long expected = 7 + 20L * 1024L * 1024L;
        assertEquals(expected, totalSize,
                "Total size must be exact sum without overflow (long arithmetic)");
    }

    // ==================== Deeply nested percent encoding ====================

    /**
     * Verifies that the MCP handler rejects deeply nested percent encoding
     * in skill URIs (beyond 16 levels of decoding).
     */
    @Test
    void normalizePath_rejectsDeeplyNestedPercentEncoding() {
        // Build a deeply nested path: each level adds %2F (encoded /)
        StringBuilder deep = new StringBuilder("skill://test/skill-one");
        for (int i = 0; i < 20; i++) {
            deep.append("/").append("%2Ffile").append(i);
        }
        TestSkillsExtension ext = new TestSkillsExtension(
                Map.of("skill-one", Map.of("SKILL.md", "# Test\n")));
        McpProtocolHandler handler = makeHandler(ext);
        // Use the send helper which throws on error; catch it to assert error was thrown
        try {
            send(handler, "resources/read", Map.of("uri", deep.toString()));
            fail("Deeply nested percent encoding must be rejected");
        } catch (AssertionError e) {
            // Expected: send() throws AssertionError for RPC errors
            assertTrue(e.getMessage().contains("RPC error"),
                    "Expected RPC error for deeply nested percent encoding: " + e.getMessage());
        }
    }

    // ==================== Wire contract: resources="dynamic" literal ====================

    /**
     * Verifies that a dynamic skill serialises resources as the literal string "dynamic",
     * not as an array or null.
     */
    @Test
    void skillDTO_toMap_dynamicSerialisesAsLiteralDynamic() {
        // Create a dynamic skill using the dynamic() sentinel entry.
        // The toMap() method detects SkillResourceEntry.dynamic() and serialises
        // resources as the literal string "dynamic".
        SkillDTO dynamic = new SkillDTO(
                "skill://dynamic/test/SKILL.md",
                "DynamicSkill",
                "A dynamic test skill",
                "Dynamic",
                Map.of(),
                List.of(SkillResourceEntry.dynamic()));
        Map<String, Object> map = dynamic.toMap();
        assertEquals("dynamic", map.get("resources"),
                "Dynamic skill must serialize resources as literal 'dynamic'");
    }

    // ==================== Pagination terminal page ====================

    /**
     * Verifies that totalSkills()=MAX_VALUE does NOT cause a false nextCursor
     * when limit+1 detects no extra items (terminal page).
     * This is the key fix for the pagination endless-cursor audit finding.
     */
    @Test
    void listSkills_terminalPage_noNextCursor_despiteMaxValueTotal() {
        // Create extension with totalSkills=MAX_VALUE but only 3 items
        McpSkillsExtension ext = new McpSkillsExtension() {
            private final List<SkillDTO> all = List.of(
                    new SkillDTO("skill://s1/SKILL.md", "s1", "S1", "S1", Map.of(), List.of()),
                    new SkillDTO("skill://s2/SKILL.md", "s2", "S2", "S2", Map.of(), List.of()),
                    new SkillDTO("skill://s3/SKILL.md", "s3", "S3", "S3", Map.of(), List.of())
            );
            @Override public List<SkillDTO> listSkills(int offset, int limit) {
                if (offset >= all.size()) return List.of();
                return all.subList(offset, Math.min(offset + limit, all.size()));
            }
            @Override public int totalSkills() {
                return Integer.MAX_VALUE;  // Simulate un-overridden default
            }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) { return null; }
        };
        ListSkillsResult result = ext.listSkills("", 3);
        assertEquals(3, result.skills.size(),
                "Should return all 3 skills");
        assertNull(result.nextCursor,
                "Terminal page (offset=0, limit=3, hasExtra=false) must NOT have nextCursor, " +
                "even though totalSkills()=MAX_VALUE");
    }

    /**
     * Verifies that the nextCursor IS present when there IS a next page
     * (limit+1 returns extra item).
     */
    @Test
    void listSkills_hasNextPage_hasNextCursor() {
        McpSkillsExtension ext = new McpSkillsExtension() {
            private final List<SkillDTO> all = List.of(
                    new SkillDTO("skill://s1/SKILL.md", "s1", "S1", "S1", Map.of(), List.of()),
                    new SkillDTO("skill://s2/SKILL.md", "s2", "S2", "S2", Map.of(), List.of()),
                    new SkillDTO("skill://s3/SKILL.md", "s3", "S3", "S3", Map.of(), List.of())
            );
            @Override public List<SkillDTO> listSkills(int offset, int limit) {
                if (offset >= all.size()) return List.of();
                return all.subList(offset, Math.min(offset + limit, all.size()));
            }
            @Override public int totalSkills() { return 3; }
            @Override public SkillDTO getSkill(String uri) { return null; }
            @Override public byte[] readManifestFileBytes(String uri, String fileName) { return null; }
        };
        ListSkillsResult result = ext.listSkills("", 2);
        assertEquals(2, result.skills.size());
        assertNotNull(result.nextCursor, "Page 1 (limit=2, total=3) must have nextCursor");
        assertEquals("2", result.nextCursor);
    }
}
