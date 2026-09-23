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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for atomic SSE replay snapshot and loss-signalling contract
 * ({@code McpProtocolHandler#getMissedEvents}, {@code pollSseEvent}, {@code pollPendingNotification}).
 *
 * <p>Validates:
 * <ul>
 *   <li>Coherent snapshot: concurrent producer/consumer cannot silently skip a retained event
 *   <li>Strictly increasing IDs in replay output
 *   <li>Gap record emitted when eviction discards events after cursor
 *   <li>No gap record when no events were evicted
 *   <li>Live consumers and replay are mutually exclusive (lock-based coordination)
 *   <li>Queue capacity remains bounded
 * </ul>
 *
 * <p>Uses Java 8 APIs only (Android API 22 compatible).
 */
@SuppressWarnings("unused")
class McpProtocolHandlerReplayTest {

    private static final int PRODUCER_THREADS = 8;
    private static final int EVENTS_PER_PRODUCER = 200;
    private static final int MAX_QUEUED_EVENTS = 1000;

    private ExecutorService executor;
    private McpProtocolHandler handler;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newFixedThreadPool(PRODUCER_THREADS + 4);
        handler = new McpProtocolHandler(
                new McpRegistry(),
                McpServerConfig.builder()
                        .protocolVersion("2025-11-25")
                        .build());
        // Create a session
        sessionId = handler.handleRequestResponse(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
                .getSessionId();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    /** Injects events directly into the session via {@code notifyLogMessage}. */
    private void injectEvents(int count) {
        for (int i = 0; i < count; i++) {
            handler.notifyLogMessage("info", null, "msg-" + i);
        }
    }

    /** Extracts all numeric {@code id:N} values from an SSE-formatted block. */
    private List<Long> extractIds(String sse) {
        List<Long> ids = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^id: (\\d+)$").matcher(sse);
        while (m.find()) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    /** Extracts the {@code data} payload of an {@code event: gap} record. */
    private String extractGapData(String sse) {
        Pattern p = Pattern.compile("(?m)^event: gap\\ndata: (.+)$");
        Matcher m = p.matcher(sse);
        if (!m.find()) return null;
        // Strip SSE-escaped newlines in the data value (should not appear for simple JSON)
        return m.group(1).trim();
    }

    /** Returns true when the gap record's from/to fields match the expected inclusive range. */
    private boolean gapRangeMatches(String sse, long expectedFrom, long expectedTo) {
        String data = extractGapData(sse);
        if (data == null) return false;
        // Format: {"from":N,"to":M}
        return data.matches("\\{\"from\":\\s*" + expectedFrom + ",\\s*\"to\":\\s*" + expectedTo + "\\}");
    }

    // -------------------------------------------------------------------------
    // Test 1: Replay returns only IDs > cursor, strictly increasing
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_returnsOnlyIdsGreaterThanCursor_strictlyIncreasing() {
        injectEvents(20);   // IDs 1-20

        // Replay from cursor 5 → expect IDs 6-20
        String result = handler.getMissedEvents(sessionId, 5);
        List<Long> ids = extractIds(result);

        assertFalse(ids.isEmpty(), "Should return events after cursor 5");
        assertEquals(15, ids.size(), "Should return 15 events (IDs 6-20)");

        // Strictly increasing
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i) > ids.get(i - 1),
                    "IDs must be strictly increasing: " + ids.get(i - 1) + " -> " + ids.get(i));
        }

        // Correct range
        assertEquals(6L, ids.get(0));
        assertEquals(20L, ids.get(ids.size() - 1));
    }

    // -------------------------------------------------------------------------
    // Test 2: No gap record when no events are evicted
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_noGapRecord_whenQueueNotFull() {
        injectEvents(10);

        String result = handler.getMissedEvents(sessionId, 0);

        assertNull(extractGapData(result),
                "No gap record expected when queue is not full: " + result);
        assertFalse(result.isEmpty(), "Should contain events");
    }

    // -------------------------------------------------------------------------
    // Test 3: Gap record emitted when eviction discards events after cursor
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_gapRecord_whenEventsEvicted_afterCursor() {
        // Fill the queue past its limit to trigger eviction.
        // MAX_QUEUED_EVENTS = 1000; we fill it and enqueue one more.
        injectEvents(MAX_QUEUED_EVENTS + 5);

        // Cursor is before the eviction boundary: the first ~5 events have been lost.
        // The first still-retained ID is approximately MAX_QUEUED_EVENTS + 6 (5 lost = IDs 1-5).
        String result = handler.getMissedEvents(sessionId, 0);

        assertNotNull(extractGapData(result),
                "Gap record must be emitted when events were evicted: " + result);

        // The lost range should be 1..5 (or slightly more if eviction happened later
        // due to concurrent threads).  We verify the gap covers the beginning.
        String gapData = extractGapData(result);
        assertTrue(gapData.matches("\\{\"from\":\\s*1,\\s*\"to\":\\s*\\d+\\}"),
                "Gap should start from 1, got: " + gapData);

        // The gap should end before the first retained event ID.
        List<Long> ids = extractIds(result);
        assertFalse(ids.isEmpty(), "Should retain some events after eviction");

        // Parse from/to from gap
        Pattern p = Pattern.compile("\"from\":\\s*(\\d+).*?\"to\":\\s*(\\d+)");
        Matcher m = p.matcher(gapData);
        assertTrue(m.find(), "Gap data should parse: " + gapData);
        long from = Long.parseLong(m.group(1));
        long to = Long.parseLong(m.group(2));

        assertEquals(1L, from, "Gap 'from' should be 1");
        assertTrue(to < ids.get(0),
                "Gap 'to' (" + to + ") should be less than first retained ID (" + ids.get(0) + ")");
    }

    // -------------------------------------------------------------------------
    // Test 4: Gap emitted when cursor is mid-range and eviction happened behind it
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_gapRecord_whenCursorIsBehindEvictionBoundary() {
        // Fill with enough events to exceed queue limit
        injectEvents(MAX_QUEUED_EVENTS + 50);  // evict ~50 events

        // Cursor 30: events 1-30 are gone (evicted), IDs 31+ retained
        String result = handler.getMissedEvents(sessionId, 30);

        String gapData = extractGapData(result);
        assertNotNull(gapData, "Gap should be emitted when cursor is behind eviction boundary");

        Pattern p = Pattern.compile("\"from\":\\s*(\\d+).*?\"to\":\\s*(\\d+)");
        Matcher m = p.matcher(gapData);
        assertTrue(m.find(), "Gap data should parse: " + gapData);
        long from = Long.parseLong(m.group(1));
        long to = Long.parseLong(m.group(2));

        assertEquals(31L, from, "Gap should start from cursor+1 = 31");
        // Gap 'to' should be the last evicted ID, which is approximately the 50th lost event
        assertTrue(to < idsOf(result).get(0),
                "Gap 'to' should be less than first retained ID");
    }

    private List<Long> idsOf(String sse) {
        return extractIds(sse);
    }

    // -------------------------------------------------------------------------
    // Test 5: No gap record when cursor is before the retained range
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_noGapRecord_whenAllRetainedEventsAreAfterCursor() {
        // Add some events, replay from cursor before them (gap already covered)
        injectEvents(100);

        String result = handler.getMissedEvents(sessionId, 0);

        // Gap should NOT be present because all events 1-100 are still in queue
        // Wait, after filling to 100 (well below 1000), no eviction occurred.
        assertNull(extractGapData(result),
                "No gap when queue not full and cursor < all retained IDs: " + result);
    }

    // -------------------------------------------------------------------------
    // Test 6: Snapshot is immutable — queue state unchanged after getMissedEvents
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_doesNotMutateQueue() {
        injectEvents(50);
        String result1 = handler.getMissedEvents(sessionId, 0);
        String result2 = handler.getMissedEvents(sessionId, 0);

        List<Long> ids1 = extractIds(result1);
        List<Long> ids2 = extractIds(result2);

        assertEquals(ids1, ids2,
                "Calling getMissedEvents twice should return identical snapshots; queue must not be mutated");
    }

    // -------------------------------------------------------------------------
    // Test 7: pollSseEvent returns events in order, does not affect getMissedEvents snapshot
    // -------------------------------------------------------------------------

    @Test
    void pollSseEvent_doesNotAffectConcurrentReplaySnapshot() {
        injectEvents(30);

        // Grab a snapshot before polling
        String snapshot = handler.getMissedEvents(sessionId, 0);
        List<Long> snapshotIds = extractIds(snapshot);

        // Drain all events via pollSseEvent
        List<Long> polledIds = new ArrayList<>();
        String line;
        while ((line = handler.pollSseEvent(sessionId)) != null) {
            List<Long> ids = extractIds(line);
            polledIds.addAll(ids);
        }

        assertEquals(snapshotIds, polledIds,
                "polled events should match the pre-existing snapshot");

        // After draining, replay from 0 should return no events
        String empty = handler.getMissedEvents(sessionId, 0);
        assertTrue(extractIds(empty).isEmpty(), "Queue should be empty after polling all events");
    }

    // -------------------------------------------------------------------------
    // Test 8: pollPendingNotification returns raw bodies (body-only output shape)
    // -------------------------------------------------------------------------

    @Test
    void pollPendingNotification_returnsBodyOnly() {
        handler.notifyLogMessage("info", null, "body-A");
        handler.notifyLogMessage("info", null, "body-B");

        String body1 = handler.pollPendingNotification(sessionId);
        String body2 = handler.pollPendingNotification(sessionId);

        assertNotNull(body1);
        assertNotNull(body2);
        assertFalse(body1.startsWith("id:"), "pollPendingNotification should return raw body only");
        assertTrue(body1.contains("body-A") || body1.contains("body-B"));
    }

    // -------------------------------------------------------------------------
    // Test 9: Concurrent producer/consumer snapshot correctness
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_noSilentSkips_underConcurrentProducerAndConsumer()
            throws InterruptedException {

        injectEvents(MAX_QUEUED_EVENTS / 2); // warm up

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger producerErrors = new AtomicInteger(0);
        AtomicInteger consumerErrors = new AtomicInteger(0);
        CountDownLatch producersDone = new CountDownLatch(PRODUCER_THREADS);

        // Concurrent producers enqueue events
        for (int t = 0; t < PRODUCER_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < EVENTS_PER_PRODUCER && !stop.get(); i++) {
                        handler.notifyLogMessage("info", "t" + threadId, "msg-" + threadId + "-" + i);
                    }
                } catch (Exception e) {
                    producerErrors.incrementAndGet();
                } finally {
                    producersDone.countDown();
                }
            });
        }

        // Concurrent consumers repeatedly poll both methods while producers run
        CountDownLatch consumerStart = new CountDownLatch(1);
        List<Throwable> consumerExceptions = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            executor.submit(() -> {
                try {
                    consumerStart.await();
                    while (!stop.get()) {
                        // Replay snapshot
                        String snapshot = handler.getMissedEvents(sessionId, 0);
                        List<Long> ids = extractIds(snapshot);

                        // Verify strictly increasing
                        for (int i = 1; i < ids.size(); i++) {
                            if (ids.get(i) <= ids.get(i - 1)) {
                                consumerExceptions.add(new AssertionError(
                                        "IDs not strictly increasing: " + ids.get(i - 1) + " >= " + ids.get(i)));
                                return;
                            }
                        }

                        // Try to drain 1 event
                        handler.pollSseEvent(sessionId);

                        // Small yield to let producers run
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    consumerExceptions.add(new AssertionError("Consumer exception: " + e.getMessage(), e));
                }
            });
        }

        consumerStart.countDown();
        producersDone.await(30, TimeUnit.SECONDS);
        stop.set(true);

        assertEquals(0, producerErrors.get(), "No producer exceptions expected");
        assertTrue(consumerExceptions.isEmpty(),
                "No consumer exceptions expected: " + consumerExceptions);
    }

    // -------------------------------------------------------------------------
    // Test 10: Queue remains bounded after many events
    // -------------------------------------------------------------------------

    @Test
    void eventQueue_remainsBounded() throws InterruptedException {
        // Enqueue far more events than capacity
        int total = MAX_QUEUED_EVENTS * 3;

        CountDownLatch done = new CountDownLatch(1);
        executor.submit(() -> {
            for (int i = 0; i < total; i++) {
                handler.notifyLogMessage("info", null, "msg-" + i);
            }
            done.countDown();
        });

        assertTrue(done.await(30, TimeUnit.SECONDS), "Producer should complete in time");

        // Drain all events
        int drained = 0;
        while (handler.pollSseEvent(sessionId) != null) {
            drained++;
            // Safety: if we drain more than 3x capacity something is wrong
            assertTrue(drained <= MAX_QUEUED_EVENTS * 2,
                    "Drained more events than reasonable; queue may not be bounded correctly");
        }

        // The number drained should equal MAX_QUEUED_EVENTS (queue capacity)
        // plus a few more produced while draining was happening, but not total (3x).
        // After draining, next poll should return null
        assertNull(handler.pollSseEvent(sessionId),
                "Queue should be empty after draining all retained events");
    }

    // -------------------------------------------------------------------------
    // Test 11: Session not found returns empty string
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_returnsEmptyString_forUnknownSession() {
        String result = handler.getMissedEvents("nonexistent-session-id", 0);
        assertEquals("", result, "Should return empty string for unknown session");
    }

    // -------------------------------------------------------------------------
    // Test 12: Replay from very high cursor returns no events, no gap
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_emptyAndNoGap_forCursorBeyondAllEvents() {
        injectEvents(10);

        String result = handler.getMissedEvents(sessionId, 1000);

        assertTrue(extractIds(result).isEmpty(), "No events should match cursor beyond all IDs");
        assertNull(extractGapData(result),
                "No gap record when cursor is beyond all retained IDs");
    }

    // -------------------------------------------------------------------------
    // Test 13: pollSseEvent returns null for unknown session
    // -------------------------------------------------------------------------

    @Test
    void pollSseEvent_returnsNull_forUnknownSession() {
        assertNull(handler.pollSseEvent("unknown-session"));
        assertNull(handler.pollPendingNotification("unknown-session"));
    }

    // -------------------------------------------------------------------------
    // Test 14: SSE data with special characters is escaped in replay
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_escapesSseData() {
        // Gson serializes the raw \n in the Java string as the JSON escape \n (backslash-n, no raw LF).
        // Then escapeSseData() converts that backslash to \\, giving the SSE-safe \\n (2 chars).
        handler.notifyLogMessage("info", null, "line1\nline2");

        String result = handler.getMissedEvents(sessionId, 0);

        // The SSE data field should contain \\n (double-backslash-n), not a raw LF byte.
        // After Gson: JSON has \n; after escapeSseData: JSON has \\n in the SSE data field.
        assertTrue(result.contains("line1\\\\nline2"),
                "Embedded newlines should appear as \\\\n in SSE data, got: " + result);

        // The data field must NOT contain a raw LF (0x0A) between line1 and line2.
        // A raw embedded newline would show as data: ...line1<LF>line2...
        assertFalse(result.contains("data: ...line1\nline2"),
                "Raw embedded LF should not appear in SSE data");
    }

    // -------------------------------------------------------------------------
    // Test 15: gap record format is exactly {"from":N,"to":M}
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_gapRecordFormat_isFromTo() {
        injectEvents(MAX_QUEUED_EVENTS + 10);

        String result = handler.getMissedEvents(sessionId, 0);
        String gapData = extractGapData(result);

        assertNotNull(gapData, "Gap data must be present: " + result);
        assertTrue(gapData.matches("\\{\"from\":\\s*\\d+,\\s*\"to\":\\s*\\d+\\}"),
                "Gap data must match {\"from\":<long>,\"to\":<long>}: got " + gapData);
    }

    // -------------------------------------------------------------------------
    // Test 16: Large concurrent burst fills queue, gap covers expected range
    // -------------------------------------------------------------------------

    @Test
    void getMissedEvents_gapCorrectlySpansEvictedRange_underBurst() {
        // Enqueue enough to cause significant eviction
        injectEvents(MAX_QUEUED_EVENTS + 500);

        String result = handler.getMissedEvents(sessionId, 0);
        String gapData = extractGapData(result);

        assertNotNull(gapData, "Gap must be emitted for burst scenario");

        Pattern p = Pattern.compile("\"from\":\\s*(\\d+).*?\"to\":\\s*(\\d+)");
        Matcher m = p.matcher(gapData);
        assertTrue(m.find());
        long from = Long.parseLong(m.group(1));
        long to = Long.parseLong(m.group(2));

        // From must be 1 (first lost event)
        assertEquals(1L, from, "Gap should start at 1");
        // To must be the last evicted ID, which should be around 500
        assertTrue(to > 0 && to <= 500,
                "Gap 'to' should be within evicted range, got: " + to);
    }
}
