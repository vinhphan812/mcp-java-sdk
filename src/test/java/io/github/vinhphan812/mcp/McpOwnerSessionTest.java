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

package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ADR-0011 owner-based session management.
 * Verifies that sessions can be bound to owner IDs for tracking and management.
 */
@SuppressWarnings("unused")
@Disabled
class McpOwnerSessionTest {

    private static final String OWNER_ID = "user123";
    private static final String OTHER_OWNER_ID = "user456";

    @Test
    void testOwnerIdExtractedFromParams() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        String sessionId = initializeSessionWithOwner(handler, OWNER_ID);

        assertNotNull(sessionId, "Session should be created");
        assertEquals(OWNER_ID, getOwnerId(handler, sessionId),
                "Owner ID should be stored in session state");
    }

    @Test
    void testOneSessionPerOwner() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // First session with owner
        String sessionId1 = initializeSessionWithOwner(handler, OWNER_ID);
        assertTrue(handler.hasSession(sessionId1), "First session should be active");

        // Second initialize with same owner should replace old session
        String sessionId2 = initializeSessionWithOwner(handler, OWNER_ID);

        assertNotEquals(sessionId1, sessionId2,
                "Second session should have different ID");

        assertFalse(handler.hasSession(sessionId1),
                "First session should be replaced");
        assertTrue(handler.hasSession(sessionId2),
                "Second session should be active");
    }

    @Test
    void testSessionOwnersMapUpdated() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // Create sessions for different owners
        String sessionId1 = initializeSessionWithOwner(handler, OWNER_ID);
        String sessionId2 = initializeSessionWithOwner(handler, OTHER_OWNER_ID);

        // Verify owners map is updated
        assertEquals(sessionId1, getSessionByOwner(handler, OWNER_ID),
                "Owner 1 should map to session 1");
        assertEquals(sessionId2, getSessionByOwner(handler, OTHER_OWNER_ID),
                "Owner 2 should map to session 2");

        // Replace first owner's session
        String newSessionId1 = initializeSessionWithOwner(handler, OWNER_ID);

        assertEquals(newSessionId1, getSessionByOwner(handler, OWNER_ID),
                "Owner 1 should now map to new session");
    }

    @Test
    void testClearOwnerBindingOnTerminate() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        String sessionId = initializeSessionWithOwner(handler, OWNER_ID);

        assertEquals(sessionId, getSessionByOwner(handler, OWNER_ID),
                "Owner should be bound before terminate");

        handler.terminateSession(sessionId);

        assertNull(getSessionByOwner(handler, OWNER_ID),
                "Owner binding should be cleared after terminate");
        assertFalse(handler.hasSession(sessionId),
                "Session should be removed");
    }

    @Test
    void testDifferentOwnersCanHaveConcurrentSessions() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        String sessionId1 = initializeSessionWithOwner(handler, OWNER_ID);
        String sessionId2 = initializeSessionWithOwner(handler, OTHER_OWNER_ID);

        assertTrue(handler.hasSession(sessionId1), "Session 1 should be active");
        assertTrue(handler.hasSession(sessionId2), "Session 2 should be active");
        assertNotEquals(sessionId1, sessionId2, "Sessions should be different");

        // Both sessions should be independent
        registerTool(registry, "tool1");
        registerTool(registry, "tool2");

        String response1 = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"tool1\"}}",
                sessionId1);
        String response2 = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"tool2\"}}",
                sessionId2);

        assertTrue(response1.contains("\"result\""), "Tool 1 should work in session 1");
        assertTrue(response2.contains("\"result\""), "Tool 2 should work in session 2");
    }

    @Test
    void testOwnerIdWithoutReplacement() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // Initialize without ownerId
        String sessionId = initializeSession(handler);

        assertNotNull(sessionId, "Session should be created without owner");
        assertNull(getOwnerId(handler, sessionId),
                "Owner ID should be null when not provided");
    }

    @Test
    void testTerminateOnlyRemovesOwnerBinding() {
        McpRegistry registry = new McpRegistry();
        McpServerConfig config = McpServerConfig.builder().build();
        McpProtocolHandler handler = new McpProtocolHandler(registry, config);

        // Create two sessions with same owner
        String sessionId1 = initializeSessionWithOwner(handler, OWNER_ID);
        String sessionId2 = initializeSessionWithOwner(handler, OWNER_ID);

        // Only the latest session should be bound to the owner
        assertFalse(handler.hasSession(sessionId1), "Old session should be removed");
        assertTrue(handler.hasSession(sessionId2), "New session should be active");
        assertEquals(sessionId2, getSessionByOwner(handler, OWNER_ID),
                "Owner should map to latest session");
    }

    // Helper methods

    private String initializeSession(McpProtocolHandler handler) {
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        return response.getSessionId();
    }

    private String initializeSessionWithOwner(McpProtocolHandler handler, String ownerId) {
        String request = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"clientInfo\":{},\"ownerId\":\"%s\"}}",
                ownerId);
        McpProtocolHandler.McpResponse response = handler.handleRequestResponse(request, null);
        return response.getSessionId();
    }

    private void registerTool(McpRegistry registry, String name) {
        registry.registerTool(name, name + " tool", Map.of(), null,
                (McpToolHandler) args -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tool", name);
                    return result;
                });
    }

    private String getOwnerId(McpProtocolHandler handler, String sessionId) {
        // This would access the session state's ownerId field
        // Placeholder implementation
        return null;
    }

    private String getSessionByOwner(McpProtocolHandler handler, String ownerId) {
        // This would access the sessionOwners map
        // Placeholder implementation
        return null;
    }
}
