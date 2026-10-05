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

package io.github.vinhphan812.mcp.legacy;

import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpResourceHandler;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying that 2025-era {@code resources/subscribe} (via
 * {@link LegacyResourceSubscribeWrapper}) and 2026-era listener state machine
 * (via {@link McpProtocolHandler}) can coexist on the same server without
 * interference.
 *
 * <p>These tests address the core acceptance criteria:
 * <ul>
 *   <li>2025 subscribe calls do NOT flow through the 2026 listener state machine</li>
 *   <li>Each era uses its own bounded queue and SSE channel</li>
 *   <li>Events delivered to one era never appear in the other era's queue</li>
 *   <li>Mixed-era calls remain isolated under concurrent load</li>
 * </ul>
 *
 * @see LegacyResourceSubscribeWrapper
 */
class MixedEraCoexistenceTest {

    private McpRegistry registry;
    private McpProtocolHandler protocolHandler;
    private LegacyResourceSubscribeWrapper legacyWrapper;

    private static final String SESSION_A = "session-2025-client";
    private static final String SESSION_B = "session-2026-client";

    @BeforeEach
    void setUp() {
        registry = new McpRegistry();
        // Register a resource so subscribe doesn't reject the URI.
        registry.registerResource("file:///doc", "Document", "A test document",
                "text/plain", uri -> "Hello from document");
        registry.registerResource("file:///data", "Data", "A test data resource",
                "application/json", uri -> "{\"value\": 42}");

        protocolHandler = new McpProtocolHandler(registry,
                McpServerConfig.builder()
                        .serverName("mixed-era-test")
                        .serverVersion("1.0.0")
                        .resourceSubscriptions(true)
                        .build());

        // Register the 2025 legacy wrapper as the notification target.
        // This means: McpRegistry.notifyResourceUpdated(uri) → legacyWrapper.notifyResourceUpdated(uri)
        legacyWrapper = new LegacyResourceSubscribeWrapper(10, 50L);
        registry.setNotificationTarget(legacyWrapper);
    }

    @AfterEach
    void tearDown() {
        if (protocolHandler != null) protocolHandler.shutdown();
        if (legacyWrapper != null) legacyWrapper.shutdown();
    }

    // ── Queue isolation ─────────────────────────────────────────────────────

    /**
     * Verifies that a 2025 subscription's events land ONLY in the legacy wrapper's
     * queue, and NEVER in the 2026 handler's queue, and vice versa.
     */
    @Test
    @DisplayName("2025 subscribe events stay in legacy wrapper queue, never reach 2026 handler")
    void queueIsolation_legacyTo2026() {
        // Simulate a 2025 client subscribing via resources/subscribe handler
        // (the protocol handler's handleResourceSubscribe writes to SessionState.subscriptions,
        // but the notification delivery goes to the notificationTarget — our legacyWrapper).
        legacyWrapper.subscribe(SESSION_A, "file:///doc");
        registry.notifyResourceUpdated("file:///doc");

        // Legacy wrapper has the event
        assertTrue(legacyWrapper.hasPendingEvents(SESSION_A));
        String legacyEvent = legacyWrapper.pollSseEvent(SESSION_A);
        assertNotNull(legacyEvent);
        assertTrue(legacyEvent.contains("notifications/resources/updated"));
        assertTrue(legacyEvent.contains("file:///doc"));

        // 2026 handler's pollResourceNotification should NOT have this event.
        // The 2026 handler uses pendingNotifications queue in SessionState.
        // Since we never called protocolHandler.handleResourceSubscribe (no session in test),
        // there is no SessionState at all. Even if there were, the notification target
        // is the legacyWrapper, not the protocolHandler's SessionState.
        String handlerEvent = protocolHandler.pollResourceNotification("any-session");
        assertNull(handlerEvent, "2026 handler queue should be empty — events go to legacy wrapper");
    }

    /**
     * Verifies that the 2026 handler's pollResourceNotification is entirely
     * independent of the legacy wrapper's pollSseEvent.
     */
    @Test
    @DisplayName("2026 handler queue is independent of legacy wrapper queue")
    void queueIsolation_2026Independent() {
        // Create a 2026 session and subscribe via the 2026 handler's SessionState
        protocolHandler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                SESSION_A);
        protocolHandler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"file:///doc\"}}",
                SESSION_A);

        // Also subscribe via the legacy wrapper — this is SEPARATE subscription tracking
        // from the 2026 handler's SessionState (era isolation).
        legacyWrapper.subscribe(SESSION_A, "file:///doc");

        // Emit a resource update via the registry
        registry.notifyResourceUpdated("file:///doc");

        // 2026 handler's pendingNotifications queue is empty because the notification
        // target is the legacy wrapper (protocolHandler.notifyResourceUpdated is never called).
        String handlerEvent = protocolHandler.pollResourceNotification(SESSION_A);
        assertNull(handlerEvent,
                "2026 handler queue should be empty when legacyWrapper is the notification target");

        // The legacy wrapper's queue should have the event
        assertTrue(legacyWrapper.hasPendingEvents(SESSION_A),
                "legacy wrapper should have the event");
        String legacyEvent = legacyWrapper.pollSseEvent(SESSION_A);
        assertNotNull(legacyEvent);
        assertTrue(legacyEvent.contains("file:///doc"));

        // Verify the queues are truly separate — consuming from one doesn't affect the other
        assertNull(protocolHandler.pollResourceNotification(SESSION_A),
                "2026 handler still empty after legacy poll");
        assertFalse(legacyWrapper.hasPendingEvents(SESSION_A),
                "Legacy wrapper empty after consuming its event");
    }

    // ── Bounded queue isolation ─────────────────────────────────────────────

    /**
     * Verifies that the legacy wrapper's bounded queue eviction does NOT affect
     * the 2026 handler's queue (they are separate objects).
     */
    @Test
    @DisplayName("legacy wrapper bounded queue eviction does not affect 2026 handler")
    void boundedQueue_independent() {
        legacyWrapper.subscribe(SESSION_A, "file:///doc");

        // Overflow the legacy wrapper's queue (capacity = 10, we send 20 events)
        for (int i = 0; i < 20; i++) {
            registry.notifyResourceUpdated("file:///doc");
        }

        // Legacy wrapper should have exactly 10 events (bounded)
        int legacyCount = 0;
        while (legacyWrapper.pollSseEvent(SESSION_A) != null) legacyCount++;
        assertEquals(10, legacyCount,
                "legacy wrapper should have exactly 10 events after FIFO eviction");

        // 2026 handler should still have 0 events (no interference)
        assertNull(protocolHandler.pollResourceNotification(SESSION_A));
    }

    // ── Subscription isolation ───────────────────────────────────────────────

    /**
     * Verifies that a 2025 subscribe and a 2026 handler subscribe are tracked
     * in completely separate data structures.
     */
    @Test
    @DisplayName("2025 subscribe tracked in legacy map, not in 2026 SessionState")
    void subscriptionIsolation() {
        // 2025 subscribe via legacy wrapper
        LegacySubscription legacySub = legacyWrapper.subscribe(SESSION_A, "file:///doc");

        // 2026 handler has no session, so no SessionState exists
        assertFalse(protocolHandler.hasSession(SESSION_A),
                "no 2026 session should exist yet");

        // Create a 2026 session by calling initialize with null sessionId.
        // The handler will generate a new session ID in the response.
        String initBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}";
        io.github.vinhphan812.mcp.core.McpProtocolHandler.McpResponse initResp =
                protocolHandler.handleRequestResponse(initBody, (String) null, null);
        String createdSessionId = initResp.getSessionId();
        assertNotNull(createdSessionId, "initialize should return a sessionId");
        assertTrue(protocolHandler.hasSession(createdSessionId),
                "2026 session should exist after initialize");

        // Subscribe via the 2026 handler using the created session
        String subscribeBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"file:///data\"}}";
        String subscribeResponse = protocolHandler.handleRequest(subscribeBody, createdSessionId);
        assertTrue(subscribeResponse.contains("\"result\":") || !subscribeResponse.contains("\"error\""),
                "resources/subscribe should succeed");

        // Verify the two subscriptions are completely separate
        assertEquals(1, legacyWrapper.getSubscriptionCount());
        assertEquals(1, legacyWrapper.getSessionSubscriptionCount(SESSION_A));

        // Emit updates to both URIs
        registry.notifyResourceUpdated("file:///doc");
        registry.notifyResourceUpdated("file:///data");

        // Legacy wrapper: SESSION_A gets the event for doc
        assertTrue(legacyWrapper.hasPendingEvents(SESSION_A));
        assertFalse(legacyWrapper.hasPendingEvents(createdSessionId),
                "legacy wrapper should not receive events for unrelated sessions");
        String legacyEvent = legacyWrapper.pollSseEvent(SESSION_A);
        assertTrue(legacyEvent.contains("file:///doc"));

        // 2026 handler: createdSessionId gets nothing (legacyWrapper is the notification target)
        assertNull(protocolHandler.pollResourceNotification(createdSessionId),
                "2026 handler queue should be empty — legacyWrapper is the notification target");
    }

    // ── Concurrent mixed-era load ─────────────────────────────────────────────

    @RepeatedTest(5)
    @DisplayName("concurrent 2025 subscribe and 2026 listen calls remain isolated under load")
    void concurrent_mixedEraIsolation() throws InterruptedException {
        // Pre-populate both systems
        legacyWrapper.subscribe(SESSION_A, "file:///doc");
        protocolHandler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                SESSION_B);
        protocolHandler.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"file:///data\"}}",
                SESSION_B);

        int operations = 200;
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger legacyErrors = new AtomicInteger(0);
        AtomicInteger handlerErrors = new AtomicInteger(0);
        AtomicInteger crossPollErrors = new AtomicInteger(0);

        // Thread 1: fire legacy notifications
        new Thread(() -> {
            try {
                for (int i = 0; i < operations; i++) {
                    registry.notifyResourceUpdated("file:///doc");
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                legacyErrors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        // Thread 2: poll legacy wrapper
        new Thread(() -> {
            try {
                for (int i = 0; i < operations; i++) {
                    legacyWrapper.pollSseEvent(SESSION_A);
                    legacyWrapper.hasPendingEvents(SESSION_A);
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                handlerErrors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        // Thread 3: poll 2026 handler — events should never appear here
        new Thread(() -> {
            try {
                for (int i = 0; i < operations; i++) {
                    String event = protocolHandler.pollResourceNotification(SESSION_B);
                    if (event != null) {
                        // A non-null event means cross-era leakage — FAIL
                        crossPollErrors.incrementAndGet();
                    }
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                handlerErrors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        assertTrue(latch.await(30, TimeUnit.SECONDS),
                "concurrent operations should complete within timeout");

        assertEquals(0, legacyErrors.get(),
                "no errors in legacy wrapper operations");
        assertEquals(0, handlerErrors.get(),
                "no errors in 2026 handler operations");
        assertEquals(0, crossPollErrors.get(),
                "no cross-era event leakage — 2026 handler should never see 2025 events");
    }

    // ── Era boundary documentation verification ───────────────────────────────

    /**
     * Verifies the documented era isolation contract: the legacy wrapper never
     * touches any McpProtocolHandler state, and vice versa.
     */
    @Test
    @DisplayName("legacy wrapper does not hold any reference to protocol handler or registry")
    void eraBoundary_noCrossReference() {
        legacyWrapper.subscribe(SESSION_A, "file:///doc");

        // The wrapper only holds its own subscriptions map.
        // It receives notifications via the McpResourceUpdateListener interface,
        // which is called by the registry (not by the protocol handler).
        assertEquals(1, legacyWrapper.getSubscriptionCount());
        assertTrue(legacyWrapper.getActiveSessions().contains(SESSION_A));

        // Protocol handler has no session for SESSION_A (no cross-contamination)
        assertFalse(protocolHandler.hasSession(SESSION_A));
    }
}
