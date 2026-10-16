package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.dto.SkillResource;
import io.github.vinhphan812.mcp.api.spi.McpSkillsExtension;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SEP-2640 Skills extension.
 */
class SkillsExtensionTest {

    private static final Gson GSON = new Gson();

    private McpRegistry registry;
    private TestSkillsExtension skillsExtension;

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
        skillsExtension = new TestSkillsExtension();
    }

    private String initSession(McpProtocolHandler handler) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2026-07-28\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        JsonObject result = GSON.fromJson(
                handler.handleRequestResponse(body, null).getBody(),
                JsonObject.class).getAsJsonObject("result");
        if (result != null && result.has("serverInfo")) {
            handler.handleRequestResponse(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    result.get("sessionId").getAsString());
            return result.get("sessionId").getAsString();
        }
        return null;
    }

    private static class TestSkillsExtension implements McpSkillsExtension {
        private final List<SkillDTO> skills = new ArrayList<>();
        private String lastReadUri;
        private String lastReadFile;
        /** Tracks last bytes read via readManifestFileBytes */
        private byte[] lastReadBytes;

        // Static shared file content — initialized once, before any SkillDTO construction.
        private static final java.util.Map<String, java.util.Map<String, String>> FILE_CONTENTS;
        static {
            java.util.Map<String, java.util.Map<String, String>> m = new java.util.LinkedHashMap<>();
            m.put("skill://test/skill-one", java.util.Map.of(
                    "SKILL.md", "# Test Skill\n\nThis is a test skill manifest.\n",
                    "scripts/deploy.sh", "#!/bin/sh\necho deploying\n"));
            m.put("skill://test/skill-two", java.util.Map.of(
                    "SKILL.md", "# Test Skill\n\nThis is a test skill manifest.\n",
                    "config.yaml", "key: value\n"));
            FILE_CONTENTS = java.util.Collections.unmodifiableMap(m);
        }

        TestSkillsExtension() {
            // Skills are registered with ROOT URIs (not canonical /SKILL.md URIs).
            // The handler passes canonical URIs to getSkill; we look up by root URI
            // without stripping since skills are stored with root URIs.
            String r1 = "skill://test/skill-one";
            String r2 = "skill://test/skill-two";
            skills.add(new SkillDTO(
                    r1 + "/SKILL.md",
                    "skill-one",
                    "Test Skill One",
                    "A test skill",
                    Map.of("category", "test"),
                    McpSkillsExtension.buildResourceEntries(
                            r1,
                            java.util.Arrays.asList("SKILL.md"), this)));
            skills.add(new SkillDTO(
                    r2 + "/SKILL.md",
                    "skill-two",
                    "Test Skill Two",
                    "Another test skill",
                    Map.of("category", "test"),
                    McpSkillsExtension.buildResourceEntries(
                            r2,
                            java.util.Arrays.asList("SKILL.md"), this)));
        }

        @Override public boolean supportsDirectoryRead() { return true; }

        @Override
        public List<SkillDTO> listSkills(int offset, int limit) {
            if (offset >= skills.size()) return Collections.emptyList();
            int end = Math.min(offset + limit, skills.size());
            return new ArrayList<>(skills.subList(offset, end));
        }

        @Override public int totalSkills() { return skills.size(); }

        @Override
        public SkillDTO getSkill(String uri) {
            // handler passes canonical URI; skills are stored with canonical URIs — direct match
            return skills.stream()
                    .filter(s -> s.uri.equals(uri))
                    .findFirst().orElse(null);
        }

        @Override
        public String readManifestFile(String uri, String fileName) {
            lastReadUri = uri;
            lastReadFile = fileName;
            return getTextContent(uri, fileName);
        }

        @Override
        public byte[] readManifestFileBytes(String uri, String fileName) {
            lastReadUri = uri;
            lastReadFile = fileName;
            String content = getTextContent(uri, fileName);
            if (content != null) {
                lastReadBytes = content.getBytes(StandardCharsets.UTF_8);
                return lastReadBytes;
            }
            lastReadBytes = null;
            return null;
        }

        private String getTextContent(String uri, String fileName) {
            // Strip /SKILL.md to get skill root
            String root = uri.endsWith("/SKILL.md")
                    ? uri.substring(0, uri.length() - "/SKILL.md".length())
                    : uri;
            java.util.Map<String, String> files = FILE_CONTENTS.get(root);
            if (files == null) return null;
            return files.get(fileName);
        }

        @Override
        public SkillResource readSkillResource(String uri, String fileName, String mimeType) {
            lastReadUri = uri;
            lastReadFile = fileName;
            byte[] bytes = readManifestFileBytes(uri, fileName);
            if (bytes == null) return null;
            if ("scripts/deploy.sh".equals(fileName)) {
                return SkillResource.text("text/x-shellscript",
                        new String(bytes, StandardCharsets.UTF_8));
            } else if ("config.yaml".equals(fileName)) {
                return SkillResource.text("text/yaml",
                        new String(bytes, StandardCharsets.UTF_8));
            }
            return SkillResource.text(mimeType != null ? mimeType : "text/plain",
                    new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public List<DirectoryEntry> listDirectory(String skillRootUri) {
            String root = skillRootUri.endsWith("/SKILL.md")
                    ? skillRootUri.substring(0, skillRootUri.length() - "/SKILL.md".length())
                    : skillRootUri;
            root = root.replaceAll("/+$", "");
            final String rootFinal = root;
            return List.of(new DirectoryEntry() {
                @Override public String uri() { return rootFinal + "/SKILL.md"; }
                @Override public String name() { return "SKILL.md"; }
                @Override public boolean isDirectory() { return false; }
            });
        }
    }

    // ==================== Skills List ====================

    @Test
    void skillsList_returnsRegisteredSkills() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"skills/list\",\"params\":{}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        assertTrue(result.has("skills"));
        assertEquals(2, result.getAsJsonArray("skills").size());
        // Verify each skill URI ends with /SKILL.md (canonical URI contract)
        for (int i = 0; i < result.getAsJsonArray("skills").size(); i++) {
            JsonObject skill = result.getAsJsonArray("skills").get(i).getAsJsonObject();
            assertTrue(skill.get("uri").getAsString().endsWith("/SKILL.md"),
                    "Skill URI must end with /SKILL.md: " + skill.get("uri"));
        }
    }

    @Test
    void skillsList_respectsCursorPagination() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        // Page 1: limit=1, should get nextCursor
        String req = "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"skills/list\",\"params\":{\"limit\":1}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(1, result.getAsJsonArray("skills").size());
        assertTrue(result.has("nextCursor"), "nextCursor should be present for page 1 of 2");
        String cursor = result.get("nextCursor").getAsString();

        // Page 2: use cursor, should get remaining skills
        String req2 = "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"skills/list\","
                + "\"params\":{\"cursor\":\"" + cursor + "\",\"limit\":10}}";
        JsonObject resp2 = GSON.fromJson(
                handler.handleRequestResponse(req2, sessionId).getBody(),
                JsonObject.class);
        JsonObject result2 = resp2.getAsJsonObject("result");
        assertEquals(1, result2.getAsJsonArray("skills").size());
        assertFalse(result2.has("nextCursor"), "no nextCursor on last page");
    }

    @Test
    void skillsList_noNextCursor_whenResultsFitInOnePage() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"skills/list\",\"params\":{\"limit\":10}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("skills").size());
        assertFalse(result.has("nextCursor"), "no nextCursor when all results fit");
    }

    // ==================== Skills Get ====================

    @Test
    void skillsGet_returnsSkillByUri() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        // Use canonical URI (ends with /SKILL.md)
        String req = "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"skills/get\","
                + "\"params\":{\"uri\":\"skill://test/skill-one/SKILL.md\","
                + "\"protocolVersion\":\"2026-07-28\"}}";
        String body = handler.handleRequestResponse(req, sessionId).getBody();
        JsonObject resp = GSON.fromJson(body, JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        // B5: stable spec wraps skill in envelope
        JsonObject skill = result.getAsJsonObject("skill");
        assertEquals("skill://test/skill-one/SKILL.md", skill.get("uri").getAsString());
        // B1: stable spec uses frontmatter instead of top-level name
        assertFalse(skill.has("name"), "name must not be at top level");
        assertTrue(skill.has("frontmatter"), "frontmatter must be present");
        // Stable spec: no legacy wire fields
        assertFalse(skill.has("manifest"), "manifest must not be at top level");
        assertFalse(skill.has("files"), "files must not be at top level");
        assertFalse(skill.has("digests"), "digests must not be at top level");
    }

    @Test
    void skillsGet_returnsError_forUnknownSkill() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"skills/get\","
                + "\"params\":{\"uri\":\"skill://unknown/skill/SKILL.md\","
                + "\"protocolVersion\":\"2026-07-28\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"), "should return error for unknown skill");
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void skillsGet_returnsError_whenSkillsNotConfigured() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"skills/get\","
                + "\"params\":{\"uri\":\"skill://test/skill-one/SKILL.md\","
                + "\"protocolVersion\":\"2026-07-28\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"));
        assertEquals(-32601, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void skillsGet_rejectsNonCanonicalUri() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        // URI without /SKILL.md suffix must be rejected
        String req = "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"skills/get\","
                + "\"params\":{\"uri\":\"skill://test/skill-one\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"), "URI without /SKILL.md suffix must be rejected");
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    // ==================== Pagination Boundary Tests ====================

    @Test
    void skillsList_rejectsNegativeCursor() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"skills/list\","
                + "\"params\":{\"cursor\":\"-5\",\"limit\":10}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"), "negative cursor must be rejected");
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void skillsList_rejectsMalformedCursor() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"skills/list\","
                + "\"params\":{\"cursor\":\"abc\",\"limit\":10}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"), "malformed cursor must be rejected");
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void skillsList_terminalPage_hasNoNextCursor() {
        // Tests that when totalSkills == offset + limit (exact terminal page),
        // the nextCursor is null even if totalSkills() returns MAX_VALUE.
        // This is the limit+1 semantics fix.
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        // skillsExtension has 2 skills; request limit=2 starting at offset=0
        String req = "{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"skills/list\","
                + "\"params\":{\"limit\":2}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("skills").size(),
                "Should return exactly 2 skills");
        assertFalse(result.has("nextCursor"),
                "Terminal page (offset=0, limit=2, total=2) must NOT have nextCursor");
    }

    // ==================== Skill Resources (skill://) ====================

    @Test
    void resourcesRead_servesSkillManifest_viaSkillProtocol() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-one\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        assertEquals("complete", result.get("resultType").getAsString());
        assertEquals("skill://test/skill-one",
                result.getAsJsonArray("contents").get(0).getAsJsonObject().get("uri").getAsString());
        assertTrue(result.getAsJsonArray("contents").get(0).getAsJsonObject()
                .get("text").getAsString().contains("Test Skill"));
    }

    @Test
    void resourcesRead_callsReadSkillResource_withCorrectParams() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-two\"}}";
        handler.handleRequestResponse(req, sessionId);

        assertEquals("skill://test/skill-two", skillsExtension.lastReadUri);
        assertEquals("SKILL.md", skillsExtension.lastReadFile);
    }

    // ==================== Companion File Serving ====================

    @Test
    void resourcesRead_servesCompanionFile_withCorrectSkillRoot() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-one/scripts/deploy.sh\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        assertEquals("complete", result.get("resultType").getAsString());
        assertEquals("skill://test/skill-one", skillsExtension.lastReadUri);
        assertEquals("scripts/deploy.sh", skillsExtension.lastReadFile);
        assertTrue(result.getAsJsonArray("contents").get(0).getAsJsonObject()
                .get("text").getAsString().contains("deploying"));
    }

    @Test
    void resourcesRead_returnsCorrectMimeTypes() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        // SKILL.md -> text/markdown
        String reqMd = "{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-one\"}}";
        JsonObject respMd = GSON.fromJson(
                handler.handleRequestResponse(reqMd, sessionId).getBody(),
                JsonObject.class);
        JsonObject resultMd = respMd.getAsJsonObject("result");
        assertEquals("text/markdown", resultMd.getAsJsonArray("contents")
                .get(0).getAsJsonObject().get("mimeType").getAsString());

        // scripts/deploy.sh -> text/x-shellscript
        String reqSh = "{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-one/scripts/deploy.sh\"}}";
        String bodySh = handler.handleRequestResponse(reqSh, sessionId).getBody();
        JsonObject respSh = GSON.fromJson(bodySh, JsonObject.class);
        JsonObject resultSh = respSh.getAsJsonObject("result");
        assertEquals("text/x-shellscript", resultSh.getAsJsonArray("contents")
                .get(0).getAsJsonObject().get("mimeType").getAsString());

        // config.yaml -> text/yaml
        String reqYaml = "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-two/config.yaml\"}}";
        JsonObject respYaml = GSON.fromJson(
                handler.handleRequestResponse(reqYaml, sessionId).getBody(),
                JsonObject.class);
        JsonObject resultYaml = respYaml.getAsJsonObject("result");
        assertFalse(resultYaml.has("error"));
        assertEquals("text/yaml", resultYaml.getAsJsonArray("contents")
                .get(0).getAsJsonObject().get("mimeType").getAsString());
    }

    @Test
    void resourcesRead_returnsError_forUnknownSkillUri() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"skill://unknown/skill\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"));
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    // ==================== Resources Directory Read ====================

    @Test
    void resourcesDirectoryRead_returnsEntries_forValidSkill() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"resources/directory/read\","
                + "\"params\":{\"uri\":\"skill://test/skill-one\"}}";
        McpProtocolHandler.McpResponse rawResp = handler.handleRequestResponse(req, sessionId);
        assertNotNull(rawResp.getBody(), "Response body should not be null");
        JsonObject resp = GSON.fromJson(rawResp.getBody(), JsonObject.class);
        if (resp.has("error") && !resp.get("error").isJsonNull()) {
            fail("Unexpected error: " + resp.getAsJsonObject("error").toString());
        }
        assertTrue(resp.has("result") && !resp.get("result").isJsonNull(),
                "Response missing result: " + resp.toString());
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        assertEquals("complete", result.get("resultType").getAsString());
        assertTrue(result.has("resources"));
        assertEquals(1, result.getAsJsonArray("resources").size());
    }

    @Test
    void resourcesDirectoryRead_returnsError_forUnknownSkill() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"resources/directory/read\","
                + "\"params\":{\"uri\":\"skill://unknown/skill\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"));
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void resourcesDirectoryRead_returnsError_whenUriMissing() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .resources(true)
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"resources/directory/read\","
                + "\"params\":{}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"));
        assertEquals(-32602, resp.getAsJsonObject("error").get("code").getAsInt());
    }
}
