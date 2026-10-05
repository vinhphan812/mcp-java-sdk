/*
 * Unit-level diagnostic: tests McpProtocolHandler directly (no HTTP transport).
 */
package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class Mcp2026HandlerDiagTest {

    private McpProtocolHandler handler;

    @BeforeEach
    void setUp() {
        handler = new McpProtocolHandler(new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());
    }

    // ── 2026-mode core tests ───────────────────────────────────────────────

    @Test
    void initializeRejectsIn2026Mode() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertTrue(r.getBody().contains("-32601"), "Should be methodNotFound: " + r.getBody());
        assertTrue(r.getBody().contains("2026-07-28"), "Error should mention 2026: " + r.getBody());
        assertNull(r.getSessionId(), "No session in 2026 mode");
    }

    @Test
    void initializedNotificationSilentIn2026Mode() {
        String req = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNull(r.getBody(), "Notification returns null body");
        assertNull(r.getSessionId(), "No session in 2026 mode");
    }

    @Test
    void pingWorksIn2026ModeWithoutSession() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNotNull(r.getBody(), "Ping returns body: " + r.getBody());
        assertTrue(r.getBody().contains("\"result\""), "Ping success: " + r.getBody());
        assertNull(r.getSessionId(), "No session in 2026 mode");
    }

    @Test
    void toolsListWorksIn2026ModeWithoutSession() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNotNull(r.getBody(), "tools/list returns body: " + r.getBody());
        assertTrue(r.getBody().contains("\"result\""), "tools/list success: " + r.getBody());
        assertNull(r.getSessionId(), "No session in 2026 mode");
    }

    @Test
    void serverDiscoverWorksIn2026ModeWithoutSession() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNotNull(r.getBody(), "server/discover returns body: " + r.getBody());
        assertTrue(r.getBody().contains("\"result\""), "server/discover success: " + r.getBody());
        assertTrue(r.getBody().contains("\"2026-07-28\""), "Advertises 2026: " + r.getBody());
        assertNull(r.getSessionId(), "No session in 2026 mode");
    }

    // ── 2025 backward-compatibility ───────────────────────────────────────────

    @Test
    void initializeCreatesSessionIn2025Mode() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNotNull(r.getBody(), "Initialize returns body: " + r.getBody());
        assertTrue(r.getBody().contains("\"result\""), "Initialize success: " + r.getBody());
        assertNotNull(r.getSessionId(), "Session created in 2025 mode: " + r.getSessionId());
    }

    @Test
    void pingWorksIn2025ModeWithoutSession() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNotNull(r.getBody(), "Ping returns body: " + r.getBody());
        assertTrue(r.getBody().contains("\"result\""), "Ping success: " + r.getBody());
        assertNull(r.getSessionId(), "No session set on ping response");
    }

    @Test
    void toolsListRequiresSessionIn2025Mode() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        assertNotNull(r.getBody(), "tools/list returns body: " + r.getBody());
        assertTrue(r.getBody().contains("Missing or invalid MCP session"),
                "tools/list requires session in 2025: " + r.getBody());
    }

    @Test
    void toolsListWorksIn2025ModeWithSession() {
        // First initialize
        McpProtocolHandler.McpResponse init = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}", null, null);
        assertNotNull(init.getSessionId());
        // Then tools/list with session
        String req = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, init.getSessionId(), null);
        assertNotNull(r.getBody(), "tools/list returns body: " + r.getBody());
        assertTrue(r.getBody().contains("\"result\""), "tools/list success: " + r.getBody());
    }

    // ── _meta / progressToken ───────────────────────────────────────────────

    @Test
    void toolsCallParsesProgressTokenFromMeta() {
        // Use 2026 mode so no session is required; progressToken parsing is version-agnostic
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"missing\",\"protocolVersion\":\"2026-07-28\","
                + "\"_meta\":{\"progressToken\":\"tok-abc\"}}}";
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(req, null, null);
        // Should get unknown tool error, NOT a progressToken parsing error
        assertTrue(r.getBody().contains("Unknown tool") || r.getBody().contains("-32602"),
                "Progress token parsed correctly: " + r.getBody());
        assertNull(r.getSessionId(), "No session in 2026 mode");
    }
}
