package io.github.vinhphan812.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
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
        public List<SkillDTO> listSkills(int offset, int limit) {
            int end = Math.min(offset + limit, skills.size());
            if (offset >= skills.size()) return Collections.emptyList();
            return new ArrayList<>(skills.subList(offset, end));
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
            if (!"SKILL.md".equals(fileName)) return null;
            for (SkillDTO skill : skills) {
                if (uri.equals(skill.uri)) {
                    return "# Test Skill\n\nThis is a test skill manifest.\n";
                }
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
    void skillsList_respectsPagination() {
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolVersion("2026-07-28")
                        .skillsExtension(skillsExtension)
                        .build());
        String sessionId = initSession(handler);

        String req = "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"skills/list\",\"params\":{\"limit\":1}}";
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
        JsonObject result = resp.getAsJsonObject("result");
        assertEquals(1, result.getAsJsonArray("skills").size());
        assertTrue(result.has("nextCursor"), "nextCursor should be present for page 1 of 2");
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

        // With new URI parsing: skillRoot=skill://test/skill-two, relativePath=""
        assertEquals("skill://test/skill-two", skillsExtension.lastReadUri);
        assertEquals("SKILL.md", skillsExtension.lastReadFile);
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
        JsonObject resp = GSON.fromJson(
                handler.handleRequestResponse(req, sessionId).getBody(),
                JsonObject.class);
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
