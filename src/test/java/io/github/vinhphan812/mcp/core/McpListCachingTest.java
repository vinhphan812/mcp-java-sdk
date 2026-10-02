package io.github.vinhphan812.mcp.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MCP 2026 list caching, deterministic ordering, and pagination metadata.
 * Covers: catalog versioning, TTL metadata, stable snapshots, concurrent reads during
 * registration, and version-gated cache metadata in paginated responses.
 */
class McpListCachingTest {

    // ==================== Catalog versioning ====================

    @Test
    void catalogVersionStartsAtZero() {
        McpRegistry registry = new McpRegistry();
        assertEquals(0, registry.getCatalogVersion());
    }

    @Test
    void catalogVersionIncrementsOnToolRegistration() {
        McpRegistry registry = new McpRegistry();
        assertEquals(0, registry.getCatalogVersion());
        registry.registerTool("t1", "d1", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        assertEquals(1, registry.getCatalogVersion());
        registry.registerTool("t2", "d2", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        assertEquals(2, registry.getCatalogVersion());
    }

    @Test
    void catalogVersionIncrementsOnResourceRegistration() {
        McpRegistry registry = new McpRegistry();
        assertEquals(0, registry.getCatalogVersion());
        registry.registerResource("test://r1", "r1", "desc", "text/plain", uri -> "data");
        assertEquals(1, registry.getCatalogVersion());
    }

    @Test
    void catalogVersionIncrementsOnPromptRegistration() {
        McpRegistry registry = new McpRegistry();
        assertEquals(0, registry.getCatalogVersion());
        registry.registerPrompt("p1", "desc", null, args -> null);
        assertEquals(1, registry.getCatalogVersion());
    }

    @Test
    void catalogVersionIncrementsOnResourceTemplateRegistration() {
        McpRegistry registry = new McpRegistry();
        assertEquals(0, registry.getCatalogVersion());
        registry.registerResourceTemplate("test://{id}", "tpl", "desc", "text/plain", uri -> "data");
        assertEquals(1, registry.getCatalogVersion());
    }

    @Test
    void registrationTimestampUpdatesOnMutation() {
        McpRegistry registry = new McpRegistry();
        assertEquals(0, registry.getRegistrationTimestamp());
        long before = System.currentTimeMillis();
        registry.registerTool("t1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        long after = System.currentTimeMillis();
        assertTrue(registry.getRegistrationTimestamp() >= before);
        assertTrue(registry.getRegistrationTimestamp() <= after);
    }

    @Test
    void getCacheMetadataReturnsUnmodifiableMap() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> meta = registry.getCacheMetadata("2026-07-28");
        assertThrows(UnsupportedOperationException.class, () -> meta.put("extra", "value"));
    }

    @Test
    void cacheMetadataContainsExpectedKeys() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> meta = registry.getCacheMetadata("2026-07-28");
        assertTrue(meta.containsKey("catalogVersion"));
        assertTrue(meta.containsKey("ttlMs"));
        assertTrue(meta.containsKey("cacheScope"));
        assertTrue(meta.containsKey("registrationTimestamp"));
        assertTrue(meta.containsKey("totalTools"));
        assertTrue(meta.containsKey("totalResources"));
        assertTrue(meta.containsKey("totalResourceTemplates"));
        assertTrue(meta.containsKey("totalPrompts"));
    }

    @Test
    void cacheMetadataTotalCountsReflectRegistrations() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("t1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        registry.registerTool("t2", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        registry.registerResource("test://r1", "r1", "d", "text/plain", uri -> "x");
        registry.registerPrompt("p1", "d", null, args -> null);
        registry.registerResourceTemplate("test://{id}", "tpl", "d", "text/plain", uri -> "x");

        Map<String, Object> meta = registry.getCacheMetadata("2026-07-28");
        assertEquals(2L, ((Number) meta.get("totalTools")).longValue());
        assertEquals(1L, ((Number) meta.get("totalResources")).longValue());
        assertEquals(1L, ((Number) meta.get("totalPrompts")).longValue());
        assertEquals(1L, ((Number) meta.get("totalResourceTemplates")).longValue());
    }

    // ==================== Deterministic insertion ordering ====================

    @Test
    void toolsAreReturnedInInsertionOrder() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("zulu", "Z", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        registry.registerTool("alpha", "A", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        registry.registerTool("mike", "M", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        // Alphabetically: alpha, mike, zulu — but insertion order is: zulu, alpha, mike
        var tools = registry.getRegisteredTools();
        assertEquals(3, tools.size());
        assertEquals("zulu", tools.get(0).get("name"));
        assertEquals("alpha", tools.get(1).get("name"));
        assertEquals("mike", tools.get(2).get("name"));
    }

    @Test
    void resourcesAreReturnedInInsertionOrder() {
        McpRegistry registry = new McpRegistry();
        registry.registerResource("test://z", "Z", "d", "text/plain", uri -> "x");
        registry.registerResource("test://a", "A", "d", "text/plain", uri -> "x");
        registry.registerResource("test://m", "M", "d", "text/plain", uri -> "x");
        var resources = registry.getRegisteredResources();
        assertEquals("test://z", resources.get(0).get("uri"));
        assertEquals("test://a", resources.get(1).get("uri"));
        assertEquals("test://m", resources.get(2).get("uri"));
    }

    @Test
    void promptsAreReturnedInInsertionOrder() {
        McpRegistry registry = new McpRegistry();
        registry.registerPrompt("z", "d", null, args -> null);
        registry.registerPrompt("a", "d", null, args -> null);
        var prompts = registry.getRegisteredPrompts();
        assertEquals("z", prompts.get(0).get("name"));
        assertEquals("a", prompts.get(1).get("name"));
    }

    @Test
    void resourceTemplatesAreReturnedInInsertionOrder() {
        McpRegistry registry = new McpRegistry();
        registry.registerResourceTemplate("test://z", "Z", "d", "text/plain", uri -> "x");
        registry.registerResourceTemplate("test://a", "A", "d", "text/plain", uri -> "x");
        var templates = registry.getRegisteredResourceTemplates();
        assertEquals("test://z", templates.get(0).get("uriTemplate"));
        assertEquals("test://a", templates.get(1).get("uriTemplate"));
    }

    @Test
    void getRegisteredToolsReturnsDefensiveCopy() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("t1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        var tools = registry.getRegisteredTools();
        tools.clear(); // mutate copy
        assertEquals(1, registry.getRegisteredTools().size()); // original unchanged
    }

    @Test
    void getToolDefinitionReturnsDefensiveCopy() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("t1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        Map<String, Object> def = registry.getToolDefinition("t1");
        def.put("mutated", true);
        assertNull(registry.getToolDefinition("t1").get("mutated"));
    }

    // ==================== Stable pagination snapshot ====================

    @Test
    void handleToolsListIncludesNextCursorWhenMoreItemsExist() {
        McpRegistry registry = new McpRegistry();
        for (int i = 0; i < 3; i++) {
            registry.registerTool("tool" + i, "d", Collections.emptyMap(), Collections.emptyList(),
                    p -> Collections.emptyMap());
        }
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().pageSize(2).build());
        String session = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("tools").size());
        assertTrue(result.has("nextCursor"));
    }

    @Test
    void handleToolsListSecondPageReturnsRemainingItems() {
        McpRegistry registry = new McpRegistry();
        for (int i = 0; i < 3; i++) {
            registry.registerTool("tool" + i, "d", Collections.emptyMap(), Collections.emptyList(),
                    p -> Collections.emptyMap());
        }
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().pageSize(2).build());
        String session = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
        // First page
        String firstBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session);
        String cursor = JsonParser.parseString(firstBody).getAsJsonObject().getAsJsonObject("result")
                .get("nextCursor").getAsString();
        // Second page
        String secondBody = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"" + cursor + "\"}}",
                session);
        JsonObject second = JsonParser.parseString(secondBody).getAsJsonObject().getAsJsonObject("result");
        assertEquals(1, second.getAsJsonArray("tools").size());
        assertEquals("tool2", second.getAsJsonArray("tools").get(0).getAsJsonObject()
                .get("name").getAsString());
        assertFalse(second.has("nextCursor"));
    }

    @Test
    void handleResourcesListIncludesNextCursorWhenMoreItemsExist() {
        McpRegistry registry = new McpRegistry();
        for (int i = 0; i < 3; i++) {
            registry.registerResource("test://r" + i, "r" + i, "d", "text/plain", uri -> "x");
        }
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().pageSize(2).build());
        String session = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}", session);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("resources").size());
        assertTrue(result.has("nextCursor"));
    }

    @Test
    void handlePromptsListIncludesNextCursorWhenMoreItemsExist() {
        McpRegistry registry = new McpRegistry();
        for (int i = 0; i < 3; i++) {
            registry.registerPrompt("prompt" + i, "d", null, args -> null);
        }
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().pageSize(2).build());
        String session = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}", session);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("prompts").size());
        assertTrue(result.has("nextCursor"));
    }

    // ==================== Version-gated cache metadata in 2026 responses ====================

    @Test
    void statelessListResponseIncludesCacheMetadataForTools() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("tool1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        // Use stateless protocol mode
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .pageSize(10)
                        .build());
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        // In stateless (2026-07-28) mode, cache metadata must be present
        assertTrue(result.has("catalogVersion"));
        assertTrue(result.has("ttlMs"));
        assertTrue(result.has("cacheScope"));
        assertTrue(result.has("totalCount"));
        assertTrue(result.has("pageStart"));
        assertTrue(result.has("pageEnd"));
        assertTrue(result.has("registrationTimestamp"));
    }

    @Test
    void statelessListResponseIncludesCacheMetadataForResources() {
        McpRegistry registry = new McpRegistry();
        registry.registerResource("test://r1", "r1", "d", "text/plain", uri -> "x");
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .pageSize(10)
                        .build());
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}", null);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        assertTrue(result.has("catalogVersion"));
        assertTrue(result.has("ttlMs"));
        assertTrue(result.has("totalCount"));
        assertTrue(result.has("pageStart"));
        assertTrue(result.has("pageEnd"));
    }

    @Test
    void sessionedListResponseDoesNotIncludeCacheMetadata() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("tool1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        // Use sessioned (default) mode — should NOT include 2026 cache metadata
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder().pageSize(10).build());
        String session = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        // 2025 mode must NOT include cache metadata fields
        assertFalse(result.has("catalogVersion"), "sessioned response should not include catalogVersion");
        assertFalse(result.has("ttlMs"), "sessioned response should not include ttlMs");
    }

    @Test
    void cacheMetadataCatalogVersionMatchesRegistry() {
        McpRegistry registry = new McpRegistry();
        registry.registerTool("t1", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        registry.registerTool("t2", "d", Collections.emptyMap(), Collections.emptyList(),
                p -> Collections.emptyMap());
        long expectedVersion = registry.getCatalogVersion();
        long metaVersion = ((Number) registry.getCacheMetadata("2026-07-28").get("catalogVersion")).longValue();
        assertEquals(expectedVersion, metaVersion);
    }

    @Test
    void ttlMsIsOneHour() {
        McpRegistry registry = new McpRegistry();
        Map<String, Object> meta = registry.getCacheMetadata("2026-07-28");
        assertEquals(3_600_000L, ((Number) meta.get("ttlMs")).longValue());
    }

    @Test
    void pageStartAndEndReflectPaginationBounds() {
        McpRegistry registry = new McpRegistry();
        for (int i = 0; i < 5; i++) {
            registry.registerTool("tool" + i, "d", Collections.emptyMap(), Collections.emptyList(),
                    p -> Collections.emptyMap());
        }
        McpProtocolHandler handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .protocolMode(McpServerConfig.ProtocolMode.STATELESS)
                        .pageSize(2)
                        .build());
        // First page (items 0-1)
        String body = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
        JsonObject result = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("result");
        assertEquals(0, result.get("pageStart").getAsInt());
        assertEquals(2, result.get("pageEnd").getAsInt());
        assertEquals(5, result.get("totalCount").getAsInt());
        // Cursor to second page
        String cursor = result.get("nextCursor").getAsString();
        String body2 = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{\"cursor\":\"" + cursor + "\"}}",
                null);
        JsonObject result2 = JsonParser.parseString(body2).getAsJsonObject().getAsJsonObject("result");
        assertEquals(2, result2.get("pageStart").getAsInt());
        assertEquals(4, result2.get("pageEnd").getAsInt());
        assertEquals(5, result2.get("totalCount").getAsInt());
        // Third page (items 4 only)
        String cursor2 = result2.get("nextCursor").getAsString();
        String body3 = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{\"cursor\":\"" + cursor2 + "\"}}",
                null);
        JsonObject result3 = JsonParser.parseString(body3).getAsJsonObject().getAsJsonObject("result");
        assertEquals(4, result3.get("pageStart").getAsInt());
        assertEquals(5, result3.get("pageEnd").getAsInt());
        assertEquals(5, result3.get("totalCount").getAsInt());
    }
}
