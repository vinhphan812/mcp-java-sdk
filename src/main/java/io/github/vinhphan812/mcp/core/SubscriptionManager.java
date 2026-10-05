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

import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, per-subscription event buffer for the MCP 2026-07-28 {@code listens/subscribe}
 * protocol.
 *
 * <p>Each subscription is identified by a server-assigned subscription token and holds a
 * fixed-capacity queue of JSON-RPC notification strings. When the queue overflows the
 * oldest event is silently dropped (bounded-buffer / backpressure strategy).
 *
 * <p>Subscriptions are sessionless: they are not tied to a Mcp-Session-Id and are cleaned
 * up only by explicit {@code listens/unsubscribe}, server shutdown, or client disconnect.
 *
 * <p>This class is thread-safe.
 */
class SubscriptionManager {

    /** Default maximum notification buffer size per listener. */
    static final int DEFAULT_MAX_BUFFER_SIZE = 100;

    /** Maximum buffer size that cannot be exceeded even when the client requests more. */
    private final int maxBufferSize;

    /** Server-assigned subscription token counter. */
    private final AtomicLong nextToken = new AtomicLong(1L);

    /**
     * Active subscriptions keyed by subscription token (String).
     * A subscription exists from {@code subscribe()} until {@code unsubscribe()} or shutdown.
     */
    private final ConcurrentHashMap<String, ListenerSubscription> subscriptions =
            new ConcurrentHashMap<>();

    SubscriptionManager(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize > 0 ? maxBufferSize : DEFAULT_MAX_BUFFER_SIZE;
    }

    // ── subscription lifecycle ─────────────────────────────────────────────────

    /**
     * Opens a new listener subscription.
     *
     * @param topics          non-empty set of subscribed topic names
     * @param subscriptionId  client-supplied subscription identifier (may be null)
     * @param bufferSizeHint  client-requested buffer size (clamped to maxBufferSize)
     * @return open result containing the server-assigned subscription token
     */
    ListenerOpenResult subscribe(Set<String> topics, String subscriptionId, int bufferSizeHint) {
        String token = Long.toString(nextToken.getAndIncrement());
        int bufferSize = Math.min(Math.max(1, bufferSizeHint), maxBufferSize);
        ListenerSubscription sub = new ListenerSubscription(token, topics, subscriptionId, bufferSize);
        subscriptions.put(token, sub);
        return new ListenerOpenResult(token, bufferSize, maxBufferSize);
    }

    /**
     * Closes a subscription and frees its resources.
     *
     * @param token the server-assigned subscription token
     * @return true when the subscription existed and was removed
     */
    boolean unsubscribe(String token) {
        return subscriptions.remove(token) != null;
    }

    /**
     * Returns the set of active subscription tokens.
     * Defensive copy.
     */
    Set<String> activeTokens() {
        return Collections.unmodifiableSet(subscriptions.keySet());
    }

    /**
     * Shuts down the manager and clears all subscriptions.
     * Call once during server shutdown.
     */
    void shutdown() {
        subscriptions.clear();
    }

    // ── event delivery ────────────────────────────────────────────────────────

    /**
     * Notifies every active subscription that cares about the given topic.
     *
     * @param topic  canonical topic name from {@link McpRegistryChangeListener}
     * @param method JSON-RPC notification method name (e.g. {@code notifications/tools/list_changed})
     * @param params notification params (null to omit)
     * @param mapper JSON serialiser
     */
    void notifyRegistryChanged(String topic, String method,
                              Map<String, Object> params, com.google.gson.Gson mapper) {
        Map<String, Object> rpc = buildRpcNotification(method, params);
        String body = mapper.toJson(rpc);
        for (ListenerSubscription sub : subscriptions.values()) {
            if (sub.topics.contains(topic)) {
                sub.enqueue(body);
            }
        }
    }

    /**
     * Notifies every active subscription that cares about the {@code resources/updated} topic.
     *
     * @param uri   updated resource URI
     * @param mapper JSON serialiser
     */
    void notifyResourceUpdated(String uri, com.google.gson.Gson mapper) {
        Map<String, Object> params = Collections.singletonMap("uri", uri);
        Map<String, Object> rpc = buildRpcNotification(
                "notifications/resources/updated", params);
        String body = mapper.toJson(rpc);
        for (ListenerSubscription sub : subscriptions.values()) {
            if (sub.topics.contains(McpRegistryChangeListener.TOPIC_RESOURCES_UPDATED)) {
                sub.enqueue(body);
            }
        }
    }

    // ── event retrieval ───────────────────────────────────────────────────────

    /**
     * Polls the next notification from the subscription's queue, or returns null
     * when the queue is empty.
     *
     * @param token  server-assigned subscription token
     * @return next notification body, or null
     */
    String poll(String token) {
        ListenerSubscription sub = subscriptions.get(token);
        return sub == null ? null : sub.poll();
    }

    /**
     * Returns and removes all queued notifications for the subscription, in order.
     * Used when a client reconnects with {@code Last-Event-ID} to replay missed events.
     *
     * @param token server-assigned subscription token
     * @return list of queued notification bodies (never null)
     */
    java.util.List<String> drain(String token) {
        ListenerSubscription sub = subscriptions.get(token);
        if (sub == null) return Collections.emptyList();
        return sub.drain();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static Map<String, Object> buildRpcNotification(String method,
                                                            Map<String, Object> params) {
        Map<String, Object> rpc = new java.util.LinkedHashMap<>();
        rpc.put("jsonrpc", "2.0");
        rpc.put("method", method);
        if (params != null) rpc.put("params", params);
        return rpc;
    }

    // ── value types ──────────────────────────────────────────────────────────

    /**
     * Result of opening a listener subscription.
     *
     * @param token       server-assigned subscription token (used in unsubscribe / poll)
     * @param bufferSize  effective buffer size granted to this subscription
     * @param maxCapacity server's hard maximum buffer size
     */
    static final class ListenerOpenResult {
        private final String token;
        private final int bufferSize;
        private final int maxCapacity;

        ListenerOpenResult(String token, int bufferSize, int maxCapacity) {
            this.token = token;
            this.bufferSize = bufferSize;
            this.maxCapacity = maxCapacity;
        }

        public String token() { return token; }
        public int bufferSize() { return bufferSize; }
        public int maxCapacity() { return maxCapacity; }
    }

    /**
     * Individual listener subscription with its own bounded queue.
     */
    private static final class ListenerSubscription {
        /** Server-assigned token. */
        final String token;
        /** Subscribed topics. */
        final Set<String> topics;
        /** Client-supplied subscription identifier echoed in notifications. */
        final String subscriptionId;
        /** Bounded notification queue. Oldest events dropped on overflow. */
        private final ConcurrentLinkedQueue<String> queue;
        /** Hard capacity for this subscription (clamped to SubscriptionManager.maxBufferSize). */
        private final int capacity;

        ListenerSubscription(String token, Set<String> topics,
                            String subscriptionId, int capacity) {
            this.token = token;
            this.topics = topics;
            this.subscriptionId = subscriptionId;
            this.capacity = capacity;
            this.queue = new ConcurrentLinkedQueue<>();
        }

        /**
         * Enqueues a notification with bounded-buffer semantics.
         * If the queue is at capacity, the oldest event is dropped to make room.
         * Synchronised to prevent concurrent drain + enqueue from losing events.
         */
        synchronized void enqueue(String notification) {
            if (queue.size() >= capacity) {
                queue.poll(); // drop oldest
            }
            queue.offer(notification);
        }

        /**
         * Polls the next notification or returns null.
         */
        String poll() {
            return queue.poll();
        }

        /**
         * Drains and returns all queued notifications in a single atomic operation.
         * Synchronized to prevent concurrent poll() from interleaving.
         */
        synchronized java.util.List<String> drain() {
            java.util.List<String> out = new java.util.ArrayList<>();
            String e;
            while ((e = queue.poll()) != null) {
                out.add(e);
            }
            return out;
        }
    }
}
