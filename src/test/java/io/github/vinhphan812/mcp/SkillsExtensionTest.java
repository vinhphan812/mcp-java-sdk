package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import io.github.vinhphan812.mcp.api.spi.McpSkillsExtension;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        TestSkillsExtension() {
            skills.add(new SkillDTO(
                    "skill://test/skill-one",
                    "skill-one",
                    "Test Skill One",
                    "A test skill",
                    Map.of("category", "test"),
                    Map.of("SKILL.md", "src/skills/skill-one/SKILL.md"),
                    Map.of()));
            skills.add(new SkillDTO(
                    "skill://test/skill-two",
                    "skill-two",
                    "Test Skill Two",
                    "Another test skill",
                    Map.of("category", "test"),
                    Map.of("SKILL.md", "src/skills/skill-two/SKILL.md"),
                    Map.of()));
        }

        @Override
        public boolean supportsDirectoryRead() {
            return true;
        }

        @Override
        public ListSkillsResult listSkills(String cursor, int limit) {
            int offset = 0;
            if (cursor != null && !cursor.isEmpty()) {
                try { offset = Integer.parseInt(cursor); } catch (NumberFormatException ignored) {}
            }
            int end = Math.min(offset + limit, skills.size());
            if (offset >= skills.size()) return new ListSkillsResult(Collections.<SkillDTO>emptyList(), null);
            List<SkillDTO> page = new ArrayList<>(skills.subList(offset, end));
            String nextCursor = (end < skills.size()) ? String.valueOf(end) : null;
            return new ListSkillsResult(page, nextCursor);
        }

        @Override
        public SkillDTO getSkill(String uri) {
            return skills.stream()
                    .filter(s -> s.uri.equals(uri))
                    .findFirst().orElse(null);
        }

        @Override
        public String readManifestFile(String uri, String fileName) {
            lastReadUri = uri;
            lastReadFile = fileName;
            // uri is the skill root (e.g. "skill://test/skill-one")
            // fileName is the relative path (e.g. "SKILL.md" or "scripts/check.py")
            if ("SKILL.md".equals(fileName)) {
                for (SkillDTO skill : skills) {
                    if (uri.equals(skill.uri)) {
                        return "# Test Skill\n\nThis is a test skill manifest.\n";
                    }
                }
            } else if ("scripts/deploy.sh".equals(fileName) && uri.equals("skill://test/skill-one")) {
                return "#!/bin/sh\necho deploying\n";
            } else if ("config.yaml".equals(fileName) && uri.equals("skill://test/skill-two")) {
                return "key: value\n";
            }
            return null;
        }

        @Override
        public List<DirectoryEntry> listDirectory(String skillRootUri) {
            // Return SKILL.md as the only entry
            return List.of(new DirectoryEntry() {
                @Override public String uri() { return skillRootUri + "/SKILL.md"; }
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
        String req2 = "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"skills/list\",\"params\":{\"cursor\":\"" + cursor + "\",\"limit\":10}}";
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

        // All skills fit in one page
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

        String req = "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"skills/get\","
                + "\"params\":{\"uri\":\"skill://test/skill-one\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        assertEquals("skill://test/skill-one", result.get("uri").getAsString());
        assertEquals("skill-one", result.get("name").getAsString());
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
                + "\"params\":{\"uri\":\"skill://unknown/skill\"}}";
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
                + "\"params\":{\"uri\":\"skill://test/skill-one\"}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        assertTrue(resp.has("error"));
        assertEquals(-32601, resp.getAsJsonObject("error").get("code").getAsInt());
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
        assertEquals("skill://test/skill-one", result.getAsJsonArray("contents")
                .get(0).getAsJsonObject().get("uri").getAsString());
        assertTrue(result.getAsJsonArray("contents").get(0).getAsJsonObject()
                .get("text").getAsString().contains("Test Skill"));
    }

    @Test
    void resourcesRead_callsReadManifestFile_withCorrectParams() {
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

        // skillRoot=skill://test/skill-two, relativePath="SKILL.md"
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
        // skillRoot passed to extension, not the full URI
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
        JsonObject respSh = GSON.fromJson(
                handler.handleRequestResponse(reqSh, sessionId).getBody(),
                JsonObject.class);
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
        assertNotNull(rawResp.getBody(), "Response body should not be null: " + rawResp.getStreamingSubscriptionId());
        JsonObject resp = GSON.fromJson(rawResp.getBody(), JsonObject.class);
        if (resp.has("error") && !resp.get("error").isJsonNull()) {
            fail("Unexpected error: " + resp.getAsJsonObject("error").toString());
        }
        assertTrue(resp.has("result") && !resp.get("result").isJsonNull(),
                "Response missing result: " + resp.toString());
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.has("error"));
        assertEquals("complete", result.get("resultType").getAsString());
        // New response shape: "resources" field (not "entries")
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

    // Note: coexistence with Tasks tested via McpTaskExtensionTest which runs Skills
    // and Tasks simultaneously. Full integration test would require McpTaskExtension
    // setup which is covered in McpTaskExtensionTest.
}
