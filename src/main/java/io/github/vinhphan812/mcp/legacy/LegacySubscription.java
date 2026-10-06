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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Represents a single 2025-era {@code resources/subscribe} subscription.
 *
 * <p>Each subscription maintains its own isolated SSE event channel: a bounded
 * {@link #pendingEvents} queue and a monotonically-increasing {@link #nextEventId}.
 * This state is <strong>entirely separate</strong> from the 2026-era listener state
 * machine in {@code McpProtocolHandler.SessionState} — the two eras never share
 * event queues or SSE channels.
 *
 * <h2>Era isolation boundary</h2>
 *
 * <ul>
 *   <li>2025-era: client calls {@code resources/subscribe} → events land in
 *       {@link #pendingEvents} → delivered via {@link #pollSseEvent()}</li>
 *   <li>2026-era: client registers a listener via the listener SPI → events land
 *       in {@code McpProtocolHandler.SessionState.pendingNotifications} →
 *       delivered via {@code pollResourceNotification()}</li>
 * </ul>
 *
 * <p>The two queues are disjoint: a 2025 subscription never writes to the 2026
 * queue, and vice versa. Both can coexist on the same server without interference.
 *
 * @see LegacyResourceSubscribeWrapper
 */
public final class LegacySubscription {

    /** URI that this subscription covers. */
    public final String uri;

    /** Session ID that owns this subscription. */
    public final String sessionId;

    /** Millisecond timestamp when this subscription was created. */
    public final long createdAt;

    /**
     * 2025-era SSE event queue. Stores pre-formatted SSE event strings
     * (with embedded IDs) to ensure poll order is deterministic.
     * Bounded via the wrapper's {@code enqueueWithBound} method.
     *
     * <p>Events are stored as pre-formatted SSE blocks ready for direct
     * write to the SSE stream. This eliminates the need for the poll method
     * to compute or mutate any event ID state.
     */
    final ConcurrentLinkedQueue<String> pendingEvents = new ConcurrentLinkedQueue<>();

    LegacySubscription(String uri, String sessionId) {
        this.uri = uri;
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Enqueues a pre-formatted SSE event into this subscription's queue.
     *
     * <p>The event must already include the SSE {@code id:} field.
     * The wrapper calls this via {@link LegacyResourceSubscribeWrapper#enqueueWithBound}
     * which handles the capacity ceiling (FIFO eviction).
     *
     * @param sseEvent pre-formatted SSE block, e.g. {@code "id: 1\nevent: message\ndata: {...}\n\n"}
     */
    void enqueueEvent(String sseEvent) {
        pendingEvents.offer(sseEvent);
    }

    /**
     * Dequeues the next pre-formatted SSE event, or returns {@code null} when
     * the queue is empty.
     *
     * @return pre-formatted SSE block, or {@code null} when no events are pending
     */
    String pollSseEvent() {
        return pendingEvents.poll();
    }

    /**
     * Returns whether this subscription has any pending SSE events.
     *
     * @return {@code true} when the queue is non-empty
     */
    boolean hasPendingEvents() {
        return !pendingEvents.isEmpty();
    }
}
