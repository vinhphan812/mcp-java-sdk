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

import com.google.gson.Gson;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.McpSecurityDefaults;
import io.github.vinhphan812.mcp.api.logging.McpLogger;
import io.github.vinhphan812.mcp.api.spi.McpResourceUpdateListener;
import io.github.vinhphan812.mcp.api.utils.McpGson;
import io.github.vinhphan812.mcp.api.utils.McpJsonRpc;
import io.github.vinhphan812.mcp.api.utils.McpMethodNames;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Isolated 2025-era {@code resources/subscribe} wrapper.
 *
 * <p>This class implements the legacy MCP 2025-era resource subscription behaviour
 * in a fully isolated namespace. It maintains its own per-session subscription map,
 * its own bounded SSE event queues, and its own update-delivery thread — completely
 * separate from the 2026-era listener state machine in {@link io.github.vinhphan812.mcp.core.McpProtocolHandler}.
 *
 * <h2>Era isolation boundary</h2>
 *
 * <p>The isolation boundary between 2025 and 2026 eras is enforced at three levels:
 *
 * <ol>
 *   <li><strong>Namespace isolation</strong> — 2025 subscription state lives entirely in
 *       {@code io.github.vinhphan812.mcp.legacy}, never in the 2026 core package.</li>
 *   <li><strong>Queue isolation</strong> — 2025 SSE events are stored in
 *       {@link LegacySubscription#pendingEvents} queues. 2026 notification events are
 *       stored in {@code McpProtocolHandler.SessionState.pendingNotifications}. The two
 *       queue types never share objects or references.</li>
 *   <li><strong>Delivery channel isolation</strong> — 2025 events are delivered via
 *       {@link #pollSseEvent(String)} (raw SSE text). 2026 events are delivered via
 *       {@code McpProtocolHandler.pollResourceNotification(sessionId)} (SSE-formatted
 *       {@code notifications/resources/updated} messages).</li>
 * </ol>
 *
 * <p>As a result, a 2025 client calling {@code resources/subscribe} and a 2026 client
 * calling {@code resources/listChanged} on the same server will never interfere with
 * each other's event delivery.
 *
 * <h2>Usage</h2>
 *
 * <p>Register this wrapper with the {@link McpResourceUpdateListener} SPI to receive
 * resource update notifications, then connect it to the transport layer:
 *
 * <pre>{@code
 * LegacyResourceSubscribeWrapper legacy = new LegacyResourceSubscribeWrapper();
 * registry.setNotificationTarget(legacy);           // receives update notifications
 *
 * // In transport layer, for GET /mcp SSE streams serving 2025 clients:
 * String event = legacy.pollSseEvent(sessionId);   // non-null → write to SSE
 * }</pre>
 *
 * <h2>Bounded queues</h2>
 *
 * <p>Each subscription has an independent bounded event queue. When the queue reaches
 * capacity, the oldest event is silently dropped (FIFO eviction) to prevent unbounded
 * memory growth on slow or disconnected clients.
 *
 * @see LegacySubscription
 */
public final class LegacyResourceSubscribeWrapper implements McpResourceUpdateListener {

    private static final Logger LOGGER = Logger.getLogger(
            LegacyResourceSubscribeWrapper.class.getName());

    /** Default maximum events per subscription queue. */
    public static final int DEFAULT_MAX_QUEUED_EVENTS = McpSecurityDefaults.MAX_QUEUED_EVENTS;

    /** Default polling interval for the delivery thread, in milliseconds. */
    private static final long DEFAULT_POLL_INTERVAL_MS = 500L;

    // ── Configuration ────────────────────────────────────────────────────────

    private final int maxQueuedEvents;
    private final long pollIntervalMs;
    private final Gson mapper;

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Maps (sessionId, uri) → LegacySubscription.
     * Keyed by composite to avoid O(n) session scans for targeted delivery.
     */
    private final ConcurrentHashMap<String, LegacySubscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * Session-scoped subscription sets for efficient session-teardown cleanup.
     * Maps sessionId → set of URIs subscribed by that session.
     */
    private final ConcurrentHashMap<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();

    /** Background delivery thread. */
    private volatile boolean deliveryRunning = false;
    private Thread deliveryThread = null;

    // ── Constructors ─────────────────────────────────────────────────────────

    /**
     * Creates a wrapper with defaults: {@link #DEFAULT_MAX_QUEUED_EVENTS} queue depth
     * and {@link #DEFAULT_POLL_INTERVAL_MS} delivery interval.
     */
    public LegacyResourceSubscribeWrapper() {
        this(DEFAULT_MAX_QUEUED_EVENTS, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Creates a wrapper with custom queue capacity and delivery interval.
     *
     * @param maxQueuedEvents   maximum events per subscription queue (FIFO eviction)
     * @param pollIntervalMs    polling interval for the background delivery thread
     * @throws IllegalArgumentException if either argument is non-positive
     */
    public LegacyResourceSubscribeWrapper(int maxQueuedEvents, long pollIntervalMs) {
        if (maxQueuedEvents <= 0)
            throw new IllegalArgumentException("maxQueuedEvents must be positive");
        if (pollIntervalMs <= 0)
            throw new IllegalArgumentException("pollIntervalMs must be positive");
        this.maxQueuedEvents = maxQueuedEvents;
        this.pollIntervalMs = pollIntervalMs;
        this.mapper = McpGson.get();
    }

    // ── McpResourceUpdateListener ───────────────────────────────────────────

    /**
     * Called by the registry when a resource is updated.
     * Finds all sessions subscribed to the URI and enqueues a
     * {@code notifications/resources/updated} event into each subscription's
     * isolated SSE queue.
     *
     * <p>This method is called from application code or the registry, not from
     * the protocol handler. It is entirely independent of the 2026-era listener
     * state machine.
     *
     * @param uri updated resource URI
     */
    @Override
    public void notifyResourceUpdated(String uri) {
        if (uri == null) return;

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);
        String body = mapper.toJson(mapAsRpcNotification(
                McpMethodNames.NOTIF_RESOURCES_UPDATED, params));

        // Fan out to every session subscribed to this URI.
        // Composite key: sessionId + "::" + uri
        for (String sessionId : sessionSubscriptions.keySet()) {
            String key = compositeKey(sessionId, uri);
            LegacySubscription sub = subscriptions.get(key);
            if (sub != null) {
                enqueueWithBound(sub, body);
            }
        }
    }

    // ── Subscription management ───────────────────────────────────────────────

    /**
     * Creates a 2025-era subscription for the given session and URI.
     *
     * @param sessionId session that is subscribing
     * @param uri       resource URI to subscribe to
     * @return the newly created subscription, never null
     */
    public LegacySubscription subscribe(String sessionId, String uri) {
        String key = compositeKey(sessionId, uri);
        return subscriptions.computeIfAbsent(key, k -> {
            LegacySubscription sub = new LegacySubscription(uri, sessionId);
            sessionSubscriptions
                    .computeIfAbsent(sessionId, s -> ConcurrentHashMap.newKeySet())
                    .add(uri);
            LOGGER.fine("2025-era subscribe: session=" + sessionId + " uri=" + uri);
            return sub;
        });
    }

    /**
     * Removes the subscription for the given session and URI.
     *
     * @param sessionId session that is unsubscribing
     * @param uri       resource URI to unsubscribe from
     */
    public void unsubscribe(String sessionId, String uri) {
        String key = compositeKey(sessionId, uri);
        LegacySubscription removed = subscriptions.remove(key);
        if (removed != null) {
            Set<String> uris = sessionSubscriptions.get(sessionId);
            if (uris != null) {
                uris.remove(uri);
                if (uris.isEmpty()) {
                    sessionSubscriptions.remove(sessionId, uris);
                }
            }
            LOGGER.fine("2025-era unsubscribe: session=" + sessionId + " uri=" + uri);
        }
    }

    /**
     * Removes all subscriptions for the given session.
     *
     * @param sessionId session whose subscriptions should be removed
     */
    public void unsubscribeAll(String sessionId) {
        Set<String> uris = sessionSubscriptions.remove(sessionId);
        if (uris != null) {
            for (String uri : uris) {
                subscriptions.remove(compositeKey(sessionId, uri));
            }
            LOGGER.fine("2025-era unsubscribeAll: session=" + sessionId
                    + " (" + uris.size() + " subscriptions removed)");
        }
    }

    // ── SSE delivery ────────────────────────────────────────────────────────

    /**
     * Polls the next SSE event for the given session, from the 2025-era event queue.
     *
     * <p>Returns a formatted SSE block ready to be written to the SSE stream.
     * Returns {@code null} when the queue is empty — the transport should send
     * a ping or wait before polling again.
     *
     * <p>This method never touches the 2026-era queue
     * ({@code McpProtocolHandler.pollResourceNotification}), and vice versa.
     *
     * @param sessionId session whose SSE stream to poll
     * @return formatted SSE block, or {@code null} when no events are pending
     */
    public String pollSseEvent(String sessionId) {
        // Poll across all subscriptions owned by this session.
        // Since ConcurrentHashMap iteration is weakly consistent and concurrent
        // with subscribe/unsubscribe, we optimistically poll without locking.
        for (String uri : sessionSubscriptions.getOrDefault(sessionId, Collections.emptySet())) {
            LegacySubscription sub = subscriptions.get(compositeKey(sessionId, uri));
            if (sub != null) {
                String event = sub.pollSseEvent();
                if (event != null) return event;
            }
        }
        return null;
    }

    /**
     * Returns whether the given session has any pending 2025-era SSE events.
     *
     * @param sessionId session to check
     * @return {@code true} when at least one subscription has pending events
     */
    public boolean hasPendingEvents(String sessionId) {
        for (String uri : sessionSubscriptions.getOrDefault(sessionId, Collections.emptySet())) {
            LegacySubscription sub = subscriptions.get(compositeKey(sessionId, uri));
            if (sub != null && sub.hasPendingEvents()) return true;
        }
        return false;
    }

    // ── Delivery thread ─────────────────────────────────────────────────────

    /**
     * Starts the background delivery thread.
     * The thread periodically checks all sessions for pending events and
     * emits a log message when a session has queued events (for observability).
     *
     * <p>Calling this method multiple times is safe (no-op if already running).
     */
    public synchronized void start() {
        if (deliveryRunning) return;
        deliveryRunning = true;
        deliveryThread = new Thread(this::runDeliveryLoop, "legacy-subscribe-delivery");
        deliveryThread.setDaemon(true);
        deliveryThread.start();
        LOGGER.info("2025-era resource subscription delivery thread started");
    }

    /**
     * Stops the delivery thread and clears all subscription state.
     */
    public synchronized void shutdown() {
        deliveryRunning = false;
        if (deliveryThread != null) {
            deliveryThread.interrupt();
            deliveryThread = null;
        }
        subscriptions.clear();
        sessionSubscriptions.clear();
        LOGGER.info("2025-era resource subscription delivery thread stopped");
    }

    private void runDeliveryLoop() {
        while (deliveryRunning) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            // Passive observation pass: log sessions with pending events.
            // The actual delivery is driven by the transport layer via pollSseEvent().
            for (String sessionId : sessionSubscriptions.keySet()) {
                if (hasPendingEvents(sessionId)) {
                    LOGGER.fine("2025-era session has pending events: " + sessionId);
                }
            }
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    /**
     * Enqueues a formatted SSE event into a subscription's queue with FIFO eviction
     * when the queue reaches its capacity limit.
     */
    private void enqueueWithBound(LegacySubscription sub, String jsonBody) {
        String sseEvent = formatSseEvent(jsonBody);
        synchronized (sub.pendingEvents) {
            // Bounded queue: evict oldest event when at capacity.
            while (sub.pendingEvents.size() >= maxQueuedEvents) {
                sub.pendingEvents.poll();
            }
            sub.enqueueEvent(sseEvent);
        }
    }

    private static String compositeKey(String sessionId, String uri) {
        return sessionId + "\0" + uri;
    }

    private static final AtomicLong SSE_COUNTER = new AtomicLong(0L);

    /**
     * Formats a JSON-RPC notification body as an SSE event block with embedded ID.
     */
    private static String formatSseEvent(String body) {
        return "id: " + SSE_COUNTER.incrementAndGet() + "\nevent: message\ndata: " + body + "\n\n";
    }

    private Map<String, Object> mapAsRpcNotification(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", McpJsonRpc.VERSION);
        message.put("method", method);
        if (params != null) message.put("params", params);
        return message;
    }

    // ── Introspection ───────────────────────────────────────────────────────

    /**
     * Returns the number of active subscriptions across all sessions.
     *
     * @return total subscription count
     */
    public int getSubscriptionCount() {
        return subscriptions.size();
    }

    /**
     * Returns the number of subscriptions for the given session.
     *
     * @param sessionId session to query
     * @return subscription count for this session, or 0 if none
     */
    public int getSessionSubscriptionCount(String sessionId) {
        Set<String> uris = sessionSubscriptions.get(sessionId);
        return uris == null ? 0 : uris.size();
    }

    /**
     * Returns an unmodifiable view of the session IDs with active subscriptions.
     *
     * @return set of active session IDs
     */
    public Set<String> getActiveSessions() {
        return Collections.unmodifiableSet(sessionSubscriptions.keySet());
    }
}
