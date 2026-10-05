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

package io.github.vinhphan812.mcp.api.dto;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed DTO for the MCP 2026-07-28 {@code listens/subscribe} request parameters.
 *
 * <p>Structure:
 * <pre>{@code
 * {
 *   "topics": ["tools", "resources", "prompts", "resources/updated"],
 *   "_meta": { "subscriptionId": "...", "progressToken": "..." }
 * }
 * }</pre>
 *
 * <p>Topics are the registry-change categories plus {@code resources/updated}
 * for resource-content change events. Unknown topics are ignored.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @see io.github.vinhphan812.mcp.core.McpProtocolHandler
 */
public final class ListenRequest {

    /**
     * Immutable set of requested topic names.
     * Never null; empty when absent from the params.
     */
    public final Set<String> topics;

    /**
     * Client-supplied subscription identifier, stored in outbound notifications
     * so the client can correlate which subscription an event belongs to.
     * Null when absent.
     */
    public final String subscriptionId;

    /**
     * Client-supplied progress token for the {@code notifications/progress} flow.
     * May be a String or Number. Null when absent.
     */
    public final Object progressToken;

    /**
     * Bounded buffer size hint requested by the client.
     * The server may honour this or clamp it to its configured maximum.
     * Non-positive values are treated as absent.
     */
    public final int bufferSizeHint;

    /**
     * Any additional _meta keys not recognised by this SDK.
     * Always non-null (empty when no extras).
     */
    public final Map<String, Object> extras;

    private ListenRequest(Set<String> topics, String subscriptionId,
                          Object progressToken, int bufferSizeHint,
                          Map<String, Object> extras) {
        this.topics = topics;
        this.subscriptionId = subscriptionId;
        this.progressToken = progressToken;
        this.bufferSizeHint = bufferSizeHint;
        this.extras = extras;
    }

    /**
     * Returns the subscription identifier, or null when absent.
     */
    public String getSubscriptionId() {
        return subscriptionId;
    }

    /**
     * Whether a progress token is present.
     */
    public boolean hasProgressToken() {
        return progressToken != null;
    }

    /**
     * Whether the given topic name is included in this subscription.
     *
     * @param topic one of the canonical topic names
     * @return true when the topic is subscribed
     */
    public boolean hasTopic(String topic) {
        return topics.contains(topic);
    }

    /**
     * Parses a {@code listens/subscribe} params map into a {@link ListenRequest}.
     *
     * <p>Canonical topics that are always valid:
     * {@value io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener#TOPIC_TOOLS},
     * {@value io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener#TOPIC_RESOURCES},
     * {@value io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener#TOPIC_PROMPTS},
     * {@value io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener#TOPIC_RESOURCES_UPDATED}.
     * Any unknown or null topic names are silently dropped.
     *
     * @param params raw request params map (may be null)
     * @return parsed request, never null
     */
    @SuppressWarnings("unchecked")
    public static ListenRequest fromParams(Map<String, Object> params) {
        if (params == null) {
            return EMPTY;
        }

        // Parse topics array
        Set<String> topics = Collections.emptySet();
        Object topicsObj = params.get("topics");
        if (topicsObj instanceof java.util.Collection) {
            java.util.Collection<?> raw = (java.util.Collection<?>) topicsObj;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object item : raw) {
                if (item instanceof String && !((String) item).isEmpty()) {
                    seen.add((String) item);
                }
            }
            if (!seen.isEmpty()) {
                topics = Collections.unmodifiableSet(seen);
            }
        }

        // Parse _meta
        Object metaObj = params.get("_meta");
        String subId = null;
        Object pt = null;
        int bufferHint = 0;
        Map<String, Object> extras = null;

        if (metaObj instanceof Map) {
            Map<String, Object> meta = (Map<String, Object>) metaObj;
            Object sid = meta.get("subscriptionId");
            if (sid instanceof String && !((String) sid).isEmpty()) {
                subId = (String) sid;
            }
            pt = meta.get("progressToken");
            Object hint = meta.get("bufferSize");
            if (hint instanceof Number) {
                int v = ((Number) hint).intValue();
                if (v > 0) bufferHint = v;
            }
            // Collect unknown _meta keys as extras
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String k = e.getKey();
                if (!"subscriptionId".equals(k) && !"progressToken".equals(k)
                        && !"bufferSize".equals(k)) {
                    if (extras == null) extras = new java.util.LinkedHashMap<>();
                    extras.put(k, e.getValue());
                }
            }
        }

        if (topics.isEmpty() && subId == null && pt == null) {
            return EMPTY;
        }

        return new ListenRequest(
                topics,
                subId,
                pt,
                bufferHint,
                extras != null ? Collections.unmodifiableMap(extras) : Collections.emptyMap()
        );
    }

    /**
     * Empty request used when no params are present.
     */
    public static final ListenRequest EMPTY = new ListenRequest(
            Collections.emptySet(), null, null, 0, Collections.emptyMap()
    );

    @Override
    public String toString() {
        return "ListenRequest{topics=" + topics
                + ", subscriptionId=" + subscriptionId
                + ", progressToken=" + progressToken
                + ", bufferSizeHint=" + bufferSizeHint
                + ", extras=" + extras + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ListenRequest that = (ListenRequest) o;
        return bufferSizeHint == that.bufferSizeHint
                && Objects.equals(topics, that.topics)
                && Objects.equals(subscriptionId, that.subscriptionId)
                && Objects.equals(progressToken, that.progressToken)
                && Objects.equals(extras, that.extras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topics, subscriptionId, progressToken, bufferSizeHint, extras);
    }
}
