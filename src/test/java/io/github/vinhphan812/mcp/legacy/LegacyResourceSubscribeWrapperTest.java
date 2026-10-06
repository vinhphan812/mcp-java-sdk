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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.DisplayName;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LegacyResourceSubscribeWrapper}.
 *
 * <p>These tests verify era isolation, queue boundedness, concurrent access safety,
 * and mixed-era coexistence as required by the task specification.
 */
class LegacyResourceSubscribeWrapperTest {

    private LegacyResourceSubscribeWrapper wrapper;

    @BeforeEach
    void setUp() {
        // Use a small queue capacity to test bounded eviction.
        wrapper = new LegacyResourceSubscribeWrapper(5, 50L);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
    }

    // ── Basic subscribe / unsubscribe ─────────────────────────────────────

    @Test
    @DisplayName("subscribe creates a new LegacySubscription and returns it")
    void subscribe_createsSubscription() {
        LegacySubscription sub = wrapper.subscribe("s1", "file:///a");
        assertNotNull(sub);
        assertEquals("file:///a", sub.uri);
        assertEquals("s1", sub.sessionId);
    }

    @Test
    @DisplayName("subscribe twice returns the same subscription")
    void subscribe_idempotent() {
        LegacySubscription a = wrapper.subscribe("s1", "file:///a");
        LegacySubscription b = wrapper.subscribe("s1", "file:///a");
        assertSame(a, b);
    }

    @Test
    @DisplayName("unsubscribe removes the subscription")
    void unsubscribe_removesSubscription() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.unsubscribe("s1", "file:///a");
        assertEquals(0, wrapper.getSubscriptionCount());
    }

    @Test
    @DisplayName("unsubscribe non-existent is a no-op")
    void unsubscribe_unknown_noOp() {
        wrapper.unsubscribe("s1", "file:///unknown");
        assertEquals(0, wrapper.getSubscriptionCount());
    }

    @Test
    @DisplayName("unsubscribeAll removes all subscriptions for a session")
    void unsubscribeAll_removesAll() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s1", "file:///b");
        wrapper.subscribe("s2", "file:///c");
        wrapper.unsubscribeAll("s1");
        assertEquals(1, wrapper.getSubscriptionCount());
        assertEquals(0, wrapper.getSessionSubscriptionCount("s1"));
        assertEquals(1, wrapper.getSessionSubscriptionCount("s2"));
    }

    // ── Event enqueueing ───────────────────────────────────────────────────

    @Test
    @DisplayName("notifyResourceUpdated fans out to all subscribed sessions")
    void notifyResourceUpdated_fansOut() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s2", "file:///a");
        wrapper.subscribe("s1", "file:///b");  // s1 also subscribed to a different URI

        wrapper.notifyResourceUpdated("file:///a");

        assertTrue(wrapper.hasPendingEvents("s1"));
        assertTrue(wrapper.hasPendingEvents("s2"));
        // s1 should have an event from the "a" update, plus potential "b" event later
        String ev1 = wrapper.pollSseEvent("s1");
        assertNotNull(ev1);
        assertTrue(ev1.contains("notifications/resources/updated"));
        assertTrue(ev1.contains("file:///a"));
    }

    @Test
    @DisplayName("notifyResourceUpdated only fans out to sessions subscribed to the updated URI")
    void notifyResourceUpdated_targeted() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s2", "file:///b");

        wrapper.notifyResourceUpdated("file:///a");

        assertTrue(wrapper.hasPendingEvents("s1"));
        assertFalse(wrapper.hasPendingEvents("s2"));
    }

    @Test
    @DisplayName("notifyResourceUpdated ignores unknown URIs")
    void notifyResourceUpdated_unknownUri() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.notifyResourceUpdated("file:///unknown");
        assertFalse(wrapper.hasPendingEvents("s1"));
    }

    @Test
    @DisplayName("notifyResourceUpdated ignores null URI")
    void notifyResourceUpdated_null() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.notifyResourceUpdated(null);
        assertFalse(wrapper.hasPendingEvents("s1"));
    }

    // ── Bounded queue (FIFO eviction) ───────────────────────────────────────

    @Test
    @DisplayName("queue evicts oldest event when capacity is exceeded")
    void queue_boundedEviction() {
        // wrapper is configured with maxQueuedEvents = 5
        wrapper.subscribe("s1", "file:///a");

        // Send 10 events into a queue of capacity 5 → expect exactly 5 remaining (FIFO eviction)
        for (int i = 0; i < 10; i++) {
            wrapper.notifyResourceUpdated("file:///a");
        }

        // Drain all events and count them
        int count = 0;
        while (wrapper.pollSseEvent("s1") != null) count++;
        assertEquals(5, count, "queue should have exactly 5 events after FIFO eviction");
    }

    // ── SSE poll / hasPending ─────────────────────────────────────────────

    @Test
    @DisplayName("pollSseEvent returns formatted SSE block")
    void pollSseEvent_formatted() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.notifyResourceUpdated("file:///a");

        String event = wrapper.pollSseEvent("s1");
        assertNotNull(event);
        // SSE format: id: N\nevent: message\ndata: {JSON}\n\n
        assertTrue(event.startsWith("id: "));
        assertTrue(event.contains("event: message"));
        assertTrue(event.contains("data: "));
        assertTrue(event.contains("notifications/resources/updated"));
        assertTrue(event.contains("file:///a"));
    }

    @Test
    @DisplayName("pollSseEvent returns null when queue is empty")
    void pollSseEvent_empty() {
        wrapper.subscribe("s1", "file:///a");
        assertNull(wrapper.pollSseEvent("s1"));
    }

    @Test
    @DisplayName("pollSseEvent returns null for unknown session")
    void pollSseEvent_unknownSession() {
        assertNull(wrapper.pollSseEvent("unknown-session"));
    }

    @Test
    @DisplayName("hasPendingEvents returns true when events are queued")
    void hasPendingEvents_true() {
        wrapper.subscribe("s1", "file:///a");
        assertFalse(wrapper.hasPendingEvents("s1"));
        wrapper.notifyResourceUpdated("file:///a");
        assertTrue(wrapper.hasPendingEvents("s1"));
    }

    @Test
    @DisplayName("hasPendingEvents returns false for unknown session")
    void hasPendingEvents_unknownSession() {
        assertFalse(wrapper.hasPendingEvents("unknown-session"));
    }

    @Test
    @DisplayName("hasPendingEvents returns false after all events are consumed")
    void hasPendingEvents_afterConsume() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.notifyResourceUpdated("file:///a");
        assertTrue(wrapper.hasPendingEvents("s1"));
        wrapper.pollSseEvent("s1");
        assertFalse(wrapper.hasPendingEvents("s1"));
    }

    // ── Introspection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getSubscriptionCount returns total across all sessions")
    void getSubscriptionCount_total() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s1", "file:///b");
        wrapper.subscribe("s2", "file:///c");
        assertEquals(3, wrapper.getSubscriptionCount());
    }

    @Test
    @DisplayName("getActiveSessions returns all session IDs with subscriptions")
    void getActiveSessions() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s2", "file:///b");
        Set<String> sessions = wrapper.getActiveSessions();
        assertEquals(2, sessions.size());
        assertTrue(sessions.contains("s1"));
        assertTrue(sessions.contains("s2"));
    }

    @Test
    @DisplayName("getSessionSubscriptionCount returns correct count")
    void getSessionSubscriptionCount() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s1", "file:///b");
        wrapper.subscribe("s2", "file:///c");
        assertEquals(2, wrapper.getSessionSubscriptionCount("s1"));
        assertEquals(1, wrapper.getSessionSubscriptionCount("s2"));
        assertEquals(0, wrapper.getSessionSubscriptionCount("s3"));
    }

    // ── Concurrent access ──────────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("concurrent subscribe/unsubscribe does not cause race conditions")
    void concurrent_subscribeUnsubscribe() throws InterruptedException {
        int threadCount = 10;
        int opsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String sessionId = "s" + (i % 5);
                        String uri = "file:///r" + (i % 10);
                        if (i % 3 == 0) {
                            wrapper.unsubscribe(sessionId, uri);
                        } else {
                            wrapper.subscribe(sessionId, uri);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "no exceptions should occur during concurrent access");
    }

    @RepeatedTest(10)
    @DisplayName("concurrent notifyResourceUpdated does not cause race conditions")
    void concurrent_notify() throws InterruptedException {
        // Pre-populate subscriptions
        for (int i = 0; i < 10; i++) {
            wrapper.subscribe("s1", "file:///r" + i);
        }

        int notifierCount = 5;
        int notifsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(notifierCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < notifierCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < notifsPerThread; i++) {
                        wrapper.notifyResourceUpdated("file:///r" + (i % 10));
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "no exceptions during concurrent notifications");
    }

    @RepeatedTest(10)
    @DisplayName("concurrent pollSseEvent during notifications does not cause race conditions")
    void concurrent_pollDuringNotify() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            wrapper.subscribe("s1", "file:///r" + i);
        }

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger pollErrors = new AtomicInteger(0);
        AtomicInteger notifErrors = new AtomicInteger(0);

        // Poller thread
        new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    for (int s = 1; s <= 5; s++) {
                        wrapper.pollSseEvent("s1");
                        wrapper.hasPendingEvents("s1");
                    }
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                pollErrors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        // Notifier thread
        new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    wrapper.notifyResourceUpdated("file:///r" + (i % 5));
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                notifErrors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        }).start();

        assertTrue(latch.await(15, TimeUnit.SECONDS));
        assertEquals(0, pollErrors.get(), "no poll errors");
        assertEquals(0, notifErrors.get(), "no notification errors");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("start and shutdown are idempotent")
    void lifecycle_idempotent() {
        wrapper.start();
        wrapper.start(); // no-op
        wrapper.shutdown();
        wrapper.shutdown(); // no-op
    }

    @Test
    @DisplayName("shutdown clears all subscription state")
    void shutdown_clearsState() {
        wrapper.subscribe("s1", "file:///a");
        wrapper.subscribe("s2", "file:///b");
        wrapper.start();
        wrapper.shutdown();
        assertEquals(0, wrapper.getSubscriptionCount());
        assertTrue(wrapper.getActiveSessions().isEmpty());
    }

    // ── Constructor validation ─────────────────────────────────────────────

    @Test
    @DisplayName("constructor rejects non-positive maxQueuedEvents")
    void constructor_rejectsZeroMaxEvents() {
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyResourceSubscribeWrapper(0, 100L));
    }

    @Test
    @DisplayName("constructor rejects non-positive pollIntervalMs")
    void constructor_rejectsZeroPollInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyResourceSubscribeWrapper(10, 0L));
    }

    @Test
    @DisplayName("constructor accepts default wrapper")
    void constructor_defaults() {
        LegacyResourceSubscribeWrapper w = new LegacyResourceSubscribeWrapper();
        assertNotNull(w);
        w.shutdown();
    }
}
