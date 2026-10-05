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

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the MCP 2026-07-28 listens/subscribe and listens/unsubscribe protocol
 * via {@link McpProtocolHandler}.
 *
 * <p>Test categories:
 * <ul>
 *   <li>Open listen: successful subscription, topics, token returned</li>
 *   <li>Change notification delivery: registry change triggers notification</li>
 *   <li>Request close / unsubscribe: cleanup, double-unsubscribe</li>
 *   <li>Concurrent listeners: multiple simultaneous subscriptions</li>
 *   <li>Overflow: bounded buffer drops oldest events</li>
 *   <li>Shutdown: subscriptions cleared on shutdown</li>
 *   <li>Mixed-era isolation: listen does not affect sessions, sessions do not affect listen</li>
 *   <li>Error cases: empty topics, missing token</li>
 * </ul>
 */
class Mcp2026SubscriptionsTest {

    private McpProtocolHandler handler;
    private McpRegistry registry;
    private final Gson mapper = new Gson();

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
        handler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .maxListenerBufferSize(5)
                        .build());
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String subscribe(String... topics) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/subscribe\",\"params\":{\"topics\":[");
        for (int i = 0; i < topics.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(topics[i]).append("\"");
        }
        sb.append("],\"_meta\":{\"subscriptionId\":\"test-sub\"}}}");
        return handler.handleRequest(sb.toString());
    }

    private String subscribeRaw(String body) {
        return handler.handleRequest(body);
    }

    private String subscribeOnHandler(McpProtocolHandler h, String... topics) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/subscribe\",\"params\":{\"topics\":[");
        for (int i = 0; i < topics.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(topics[i]).append("\"");
        }
        sb.append("]}}");
        return h.handleRequest(sb.toString());
    }

    private java.util.List<String> drainAllOnHandler(McpProtocolHandler h, String token) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String body;
        while ((body = h.pollListenerNotification(token)) != null) {
            out.add(body);
        }
        return out;
    }

    private String unsubscribe(String token) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"listens/unsubscribe\","
                + "\"params\":{\"token\":\"" + token + "\"}}";
        return handler.handleRequest(body);
    }

    private String parseToken(String response) {
        // Find "token":"<value>"
        int start = response.indexOf("\"token\":\"");
        if (start < 0) return null;
        start += "\"token\":\"".length();
        int end = response.indexOf("\"", start);
        return response.substring(start, end);
    }

    // ── Open listen ───────────────────────────────────────────────────────

    @Test
    void listenSubscribeReturnsTokenAndTopics() {
        String resp = subscribe("tools", "resources", "prompts", "resources/updated");
        assertTrue(resp.contains("\"token\":"), "response must contain token: " + resp);
        String token = parseToken(resp);
        assertNotNull(token, "token must be non-null");
        assertTrue(resp.contains("\"topics\":"), "response must contain topics: " + resp);
        assertTrue(resp.contains("\"bufferSize\":"), "response must contain bufferSize: " + resp);
        assertTrue(resp.contains("\"maxCapacity\":"), "response must contain maxCapacity: " + resp);
        assertTrue(resp.contains("\"subscription\":"), "response must contain subscription wrapper: " + resp);
    }

    @Test
    void listenSubscribeNoSessionRequired() {
        // listens/subscribe must NOT set Mcp-Session-Id — response session is null
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/subscribe\","
                        + "\"params\":{\"topics\":[\"tools\"]}}", null);
        assertNull(r.getSessionId(), "listens/subscribe must not return a session ID");
    }

    @Test
    void listenUnsubscribeNoSessionRequired() {
        String resp = subscribe("tools");
        String token = parseToken(resp);
        McpProtocolHandler.McpResponse r = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"listens/unsubscribe\","
                        + "\"params\":{\"token\":\"" + token + "\"}}", null);
        assertNull(r.getSessionId(), "listens/unsubscribe must not return a session ID");
    }

    @Test
    void listenSubscribeRequiresAtLeastOneTopic() {
        String resp = subscribe();
        assertTrue(resp.contains("\"error\""), "empty topics must return error: " + resp);
        assertTrue(resp.contains("-32602"), "must be invalid params: " + resp);
    }

    @Test
    void listenSubscribeAcceptsSingleTopic() {
        String resp = subscribe("tools");
        assertTrue(resp.contains("\"result\""), "single topic must succeed: " + resp);
        String token = parseToken(resp);
        assertNotNull(token);
    }

    @Test
    void listenSubscribeAcceptsResourcesUpdatedTopic() {
        String resp = subscribe("resources/updated");
        assertTrue(resp.contains("\"result\""), "resources/updated topic must succeed: " + resp);
    }

    @Test
    void listenSubscribeBufferSizeClampedToMax() {
        // Server maxListenerBufferSize = 5
        String resp = subscribeRaw("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/subscribe\","
                + "\"params\":{\"topics\":[\"tools\"],\"_meta\":{\"bufferSize\":999}}}");
        assertTrue(resp.contains("\"result\""), "must succeed: " + resp);
        // bufferSize must be clamped to maxCapacity
        assertTrue(resp.contains("\"bufferSize\":5") || resp.contains("\"bufferSize\": 5"),
                "bufferSize must be clamped to 5: " + resp);
    }

    @Test
    void listenSubscribeHonorsClientBufferSizeHint() {
        // Client requests buffer size of 3, server max is 5 — should get 3
        String resp = subscribeRaw("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/subscribe\","
                + "\"params\":{\"topics\":[\"tools\"],\"_meta\":{\"bufferSize\":3}}}");
        assertTrue(resp.contains("\"result\""), "must succeed: " + resp);
        assertTrue(resp.contains("\"bufferSize\":3") || resp.contains("\"bufferSize\": 3"),
                "bufferSize must be 3: " + resp);
    }

    // ── Notification delivery ───────────────────────────────────────────────

    @Test
    void listenDeliversToolListChangedNotification() {
        final McpRegistry reg = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(reg,
                McpServerConfig.builder().maxListenerBufferSize(10).build());
        try {
            String resp = subscribeOnHandler(h, "tools");
            String token = parseToken(resp);
            assertNotNull(token, "must get token: " + resp);

            reg.registerTool("test-tool", "A test tool",
                    Collections.emptyMap(), Collections.emptyList(),
                    params -> Collections.emptyMap());

            String notif = h.pollListenerNotification(token);
            assertNotNull(notif, "notification must be queued");
            assertTrue(notif.contains("notifications/tools/list_changed"), "must be tools/list_changed: " + notif);
            assertTrue(notif.startsWith("event: message"), "must be SSE formatted: " + notif);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void listenDeliversResourceListChangedNotification() {
        final McpRegistry reg = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(reg,
                McpServerConfig.builder().maxListenerBufferSize(10).build());
        try {
            String resp = subscribeOnHandler(h, "resources");
            String token = parseToken(resp);
            assertNotNull(token);
            reg.registerResource("test://resource", "Test Resource",
                    "A test resource", handler1 -> "content");
            String notif = h.pollListenerNotification(token);
            assertNotNull(notif, "must receive resource list changed: " + notif);
            assertTrue(notif.contains("notifications/resources/list_changed"), "must be resources/list_changed: " + notif);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void listenDeliversPromptListChangedNotification() {
        final McpRegistry reg = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(reg,
                McpServerConfig.builder().maxListenerBufferSize(10).build());
        try {
            String resp = subscribeOnHandler(h, "prompts");
            String token = parseToken(resp);
            assertNotNull(token);
            reg.registerPrompt("test-prompt", "A test prompt",
                    null, params -> Collections.emptyMap());
            String notif = h.pollListenerNotification(token);
            assertNotNull(notif, "must receive prompt list changed: " + notif);
            assertTrue(notif.contains("notifications/prompts/list_changed"), "must be prompts/list_changed: " + notif);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void listenDeliversResourceUpdatedNotification() {
        final McpRegistry reg = new McpRegistry();
        McpProtocolHandler h = new McpProtocolHandler(reg,
                McpServerConfig.builder().maxListenerBufferSize(10).build());
        try {
            String resp = subscribeOnHandler(h, "resources/updated");
            String token = parseToken(resp);
            assertNotNull(token);
            h.notifyResourceUpdated("test://resource");
            String notif = h.pollListenerNotification(token);
            assertNotNull(notif, "must receive resource updated notification");
            assertTrue(notif.contains("notifications/resources/updated"), "must be resources/updated: " + notif);
        } finally {
            h.shutdown();
        }
    }

    @Test
    void listenIgnoresUnsubscribedTopics() {
        // Subscribe only to "tools"
        String resp = subscribe("tools");
        String token = parseToken(resp);

        // Register a resource — should NOT notify this listener
        registry.registerResource("test://resource", "Test Resource",
                "A test resource", handler1 -> "content");

        String notif = handler.pollListenerNotification(token);
        assertNull(notif, "listener subscribed to tools only must not receive resources/list_changed");
    }

    @Test
    void listenDeliversToAllMatchingSubscriptions() {
        String resp1 = subscribe("tools");
        String resp2 = subscribe("tools");
        String token1 = parseToken(resp1);
        String token2 = parseToken(resp2);

        registry.registerTool("test-tool", "A test tool",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());

        String notif1 = handler.pollListenerNotification(token1);
        String notif2 = handler.pollListenerNotification(token2);
        assertNotNull(notif1, "first listener must receive notification");
        assertNotNull(notif2, "second listener must receive notification");
    }

    // ── Unsubscribe / close cleanup ───────────────────────────────────────

    @Test
    void unsubscribeReturnsUnsubscribedTrue() {
        String resp = subscribe("tools");
        String token = parseToken(resp);
        String unsub = unsubscribe(token);
        assertTrue(unsub.contains("\"unsubscribed\":true"), "must return unsubscribed:true: " + unsub);
    }

    @Test
    void unsubscribeClearsNotificationQueue() {
        String resp = subscribe("tools");
        String token = parseToken(resp);

        registry.registerTool("tool1", "Tool 1",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());
        registry.registerTool("tool2", "Tool 2",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());

        // Consume first notification
        handler.pollListenerNotification(token);

        // Unsubscribe
        unsubscribe(token);

        // Second notification must not appear after unsubscribe
        String notif = handler.pollListenerNotification(token);
        assertNull(notif, "no notifications after unsubscribe");
    }

    @Test
    void unsubscribeUnknownTokenIsIdempotent() {
        // Must not throw, must return success
        String resp = unsubscribe("nonexistent-token-xyz");
        assertTrue(resp.contains("\"unsubscribed\":true"), "unknown token must still return success: " + resp);
    }

    @Test
    void unsubscribeRequiresToken() {
        String resp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/unsubscribe\","
                        + "\"params\":{}}");
        assertTrue(resp.contains("\"error\""), "missing token must return error: " + resp);
    }

    // ── Concurrent listeners ──────────────────────────────────────────────

    @Test
    void multipleListenersAreIndependent() {
        String resp1 = subscribe("tools");
        String resp2 = subscribe("resources");
        String resp3 = subscribe("tools", "prompts");
        String token1 = parseToken(resp1);
        String token2 = parseToken(resp2);
        String token3 = parseToken(resp3);

        registry.registerTool("test-tool", "A test tool",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());

        // token1 (tools) gets it, token2 (resources) does not
        assertNotNull(handler.pollListenerNotification(token1), "tools listener must receive");
        assertNull(handler.pollListenerNotification(token2), "resources listener must not receive");

        registry.registerResource("test://resource", "A resource",
                "desc", handler1 -> "x");

        assertNull(handler.pollListenerNotification(token1), "tools listener must not receive resource notif");
        assertNotNull(handler.pollListenerNotification(token2), "resources listener must receive");
        assertNotNull(handler.pollListenerNotification(token3), "prompts+tools listener must receive");
    }

    @Test
    void listenerDoesNotRequireSession() {
        // Even if sessions are full, listen must still work
        // Create a session
        handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}");

        // listens/subscribe must work independently of sessions
        String resp = subscribe("tools");
        assertTrue(resp.contains("\"result\""), "listen must work independently of sessions");
    }

    // ── Overflow / backpressure ───────────────────────────────────────────

    @Test
    void overflowDropsOldestEvents() {
        // Test SubscriptionManager directly for full isolation
        SubscriptionManager mgr = new SubscriptionManager(3);
        Set<String> topics = Collections.singleton("tools");

        SubscriptionManager.ListenerOpenResult r = mgr.subscribe(topics, "test-sub", 3);
        assertEquals(3, r.bufferSize(), "bufferSize must be 3");
        String token = r.token();

        // Emit 5 notifications — buffer capacity is 3
        for (int i = 1; i <= 5; i++) {
            mgr.notifyRegistryChanged("tools", "notifications/tools/list_changed", null, mapper);
        }

        List<String> all = mgr.drain(token);
        assertEquals(3, all.size(),
                "buffer capacity 3: expected 3 notifications, got " + all.size());

        mgr.shutdown();
    }

    // ── drainListenerNotifications ─────────────────────────────────────────

    @Test
    void drainReturnsAllQueuedNotifications() {
        // Test SubscriptionManager.drain() directly for full isolation
        SubscriptionManager mgr = new SubscriptionManager(10);
        Set<String> topics = Collections.singleton("tools");

        SubscriptionManager.ListenerOpenResult r = mgr.subscribe(topics, "test-sub", 10);
        String token = r.token();

        // Queue 3 notifications
        for (int i = 1; i <= 3; i++) {
            mgr.notifyRegistryChanged("tools", "notifications/tools/list_changed", null, mapper);
        }

        // drain() must return all notifications atomically
        List<String> all = mgr.drain(token);
        assertEquals(3, all.size(),
                "drain must return all 3 notifications: got " + all.size());

        // After drain, queue must be empty
        List<String> empty = mgr.drain(token);
        assertTrue(empty.isEmpty(), "second drain must be empty");

        mgr.shutdown();
    }

    // ── Shutdown cleanup ──────────────────────────────────────────────────

    @Test
    void shutdownClearsAllListeners() {
        String resp1 = subscribe("tools");
        String resp2 = subscribe("resources");
        String token1 = parseToken(resp1);
        String token2 = parseToken(resp2);

        handler.shutdown();

        assertNull(handler.pollListenerNotification(token1), "must be cleared after shutdown");
        assertNull(handler.pollListenerNotification(token2), "must be cleared after shutdown");
    }

    @Test
    void newNotificationsNotQueuedAfterShutdown() {
        String resp = subscribe("tools");
        String token = parseToken(resp);
        handler.shutdown();

        registry.registerTool("late-tool", "Late tool",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());

        assertNull(handler.pollListenerNotification(token), "no events queued after shutdown");
    }

    // ── Mixed-era isolation ───────────────────────────────────────────────

    @Test
    void listenDoesNotAffectSessions() {
        // Subscribe as listen
        String listenResp = subscribe("tools");
        String token = parseToken(listenResp);

        // Open a session
        String sessResp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
        assertTrue(sessResp.contains("\"result\""), "session must open");

        // Register a tool — must affect both listen and session
        registry.registerTool("mixed-tool", "Mixed tool",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());

        // Listen must receive
        assertNotNull(handler.pollListenerNotification(token), "listen must receive");
    }

    @Test
    void sessionDoesNotAffectListen() {
        // Open a session first
        handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}");

        // Subscribe as listen
        String listenResp = subscribe("tools");
        String token = parseToken(listenResp);

        // Session-based subscription (old 2025 style)
        handler.handleRequest("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\","
                + "\"params\":{\"uri\":\"test://x\"}}");

        // Tool registration — listen must receive its own notifications
        registry.registerTool("listen-tool", "Listen tool",
                Collections.emptyMap(), Collections.emptyList(),
                params -> Collections.emptyMap());

        assertNotNull(handler.pollListenerNotification(token), "listen must receive after session subscription");
    }

    @Test
    void listenSubscriptionIsolatedFromResourceSubscription() {
        // Subscribe to resources/updated via listen
        String listenResp = subscribe("resources/updated");
        String listenToken = parseToken(listenResp);

        // Subscribe via old 2025 session (different mechanism)
        String sessResp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}");
        assertTrue(sessResp.contains("\"result\""));
        String sessId = extractSessionId(sessResp);

        handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\","
                        + "\"params\":{\"uri\":\"test://session-resource\"}}",
                sessId);

        // Notify resource update
        handler.notifyResourceUpdated("test://session-resource");

        // Both listen and session should receive
        String listenNotif = handler.pollListenerNotification(listenToken);
        assertNotNull(listenNotif, "listen must receive resource update");
    }

    private String extractSessionId(String resp) {
        int start = resp.indexOf("\"sessionId\":\"");
        if (start < 0) start = resp.indexOf("\"sessionId\" : \"");
        if (start < 0) return null;
        start += "\"sessionId\":\"".length();
        int end = resp.indexOf("\"", start);
        return resp.substring(start, end);
    }

    // ── drainListenerNotifications ─────────────────────────────────────────

    private static int countOccurrences(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }

    @Test
    void drainEmptyQueueReturnsEmptyString() {
        String resp = subscribe("tools");
        String token = parseToken(resp);

        String drained = handler.drainListenerNotifications(token);
        assertEquals("", drained, "empty drain must return empty string");
    }

    @Test
    void drainUnknownTokenReturnsEmptyString() {
        String drained = handler.drainListenerNotifications("unknown-token");
        assertEquals("", drained, "unknown token drain must return empty string");
    }

    // ── Max buffer size configuration ────────────────────────────────────

    @Test
    void maxListenerBufferSizeFromConfig() {
        // Configured to 5 in setUp — verify it works
        String resp = subscribe("tools");
        assertTrue(resp.contains("\"result\""), "subscribe must succeed with configured buffer size");
    }

    @Test
    void zeroBufferSizeRejectedByBuilder() {
        // Builder must reject zero
        try {
            McpServerConfig.builder().maxListenerBufferSize(0);
            fail("Builder must reject zero maxListenerBufferSize");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxListenerBufferSize"));
        }
    }

    @Test
    void negativeBufferSizeRejectedByBuilder() {
        try {
            McpServerConfig.builder().maxListenerBufferSize(-5);
            fail("Builder must reject negative");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxListenerBufferSize"));
        }
    }

    @Test
    void oneBufferSizeAcceptedByBuilder() {
        // Must not throw
        McpServerConfig cfg = McpServerConfig.builder().maxListenerBufferSize(1).build();
        assertEquals(1, cfg.maxListenerBufferSize);
    }

    // ── Error cases ──────────────────────────────────────────────────────

    @Test
    void listenSubscribeWithUnknownTopicDoesNotError() {
        // Unknown topics are silently ignored per spec
        String resp = subscribe("tools", "unknown-topic-xyz");
        assertTrue(resp.contains("\"result\""), "unknown topic must not error: " + resp);
    }

    @Test
    void listenUnsubscribeRequiresParams() {
        String resp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/unsubscribe\"}");
        assertTrue(resp.contains("\"error\""), "missing params must return error: " + resp);
    }

    @Test
    void listenUnsubscribeRequiresNonEmptyToken() {
        String resp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"listens/unsubscribe\","
                        + "\"params\":{\"token\":\"\"}}");
        assertTrue(resp.contains("\"error\""), "empty token must return error: " + resp);
    }

    // ── Backward compatibility ───────────────────────────────────────────

    @Test
    void legacySessionResourceSubscriptionStillWorks() {
        // Open session using handleRequestResponse to get session ID from header
        McpProtocolHandler.McpResponse sessResp = handler.handleRequestResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}", null);
        assertNotNull(sessResp.getSessionId(), "session ID must be in response header");
        assertTrue(sessResp.getBody().contains("\"result\""), "session must open");
        String sessId = sessResp.getSessionId();

        // Old-style subscribe
        String subResp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\","
                        + "\"params\":{\"uri\":\"test://legacy\"}}", sessId);
        assertTrue(subResp.contains("\"result\""), "legacy subscribe must work: " + subResp);

        // Old-style unsubscribe
        String unsubResp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/unsubscribe\","
                        + "\"params\":{\"uri\":\"test://legacy\"}}", sessId);
        assertTrue(unsubResp.contains("\"result\""), "legacy unsubscribe must work: " + unsubResp);
    }

    @Test
    void listenSubscribeDoesNotAdvertiseInServerDiscover() {
        // listens/subscribe is not in server/discover — it's a 2026 extension
        String resp = handler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                        + "\"protocolVersion\":\"2026-07-28\"}");
        assertTrue(resp.contains("\"result\""), "server/discover must succeed: " + resp);
        // listens/subscribe is NOT part of server capabilities — it's always available
        assertFalse(resp.contains("listens"), "server/discover must not advertise listen capability");
    }
}
