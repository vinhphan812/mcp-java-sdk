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

/**
 * Typed internal request context for MCP 2026-07-28 protocol requests.
 *
 * <p>This class captures the subset of the {@code _meta} object that the SDK
 * actually uses at runtime. Source-supported keys:
 * <ul>
 *   <li>{@code progressToken} — client-supplied token for progress/cancellation signalling</li>
 * </ul>
 *
 * <p>Any additional keys present in {@code _meta} are silently ignored. Null values
 * are normalized to absent.
 *
 * <p>This class is immutable and thread-safe.
 *
 * @see io.github.vinhphan812.mcp.core.McpProtocolHandler
 */
public final class Mcp2026RequestContext {

    /**
     * Progress token for the {@code notifications/progress} flow.
     * May be a String or Number. Null when absent.
     */
    public final Object progressToken;

    /**
     * Immutable map of any additional _meta keys not recognised by this SDK.
     * Always non-null (empty when no extras).
     */
    public final Map<String, Object> extras;

    private Mcp2026RequestContext(Object progressToken, Map<String, Object> extras) {
        this.progressToken = progressToken;
        this.extras = extras;
    }

    /**
     * Returns the progress token as a String, or null when absent or non-string.
     */
    public String progressTokenAsString() {
        return progressToken instanceof String ? (String) progressToken : null;
    }

    /**
     * Returns the progress token as a Number, or null when absent or non-numeric.
     */
    public Number progressTokenAsNumber() {
        return progressToken instanceof Number ? (Number) progressToken : null;
    }

    /**
     * Whether a progress token is present.
     */
    public boolean hasProgressToken() {
        return progressToken != null;
    }

    /**
     * Parses a {@code _meta} object from a request params map.
     *
     * <p>Only the {@code progressToken} key is extracted. All other keys
     * are collected into {@link #extras} for forward-compatibility.
     *
     * @param params raw request params map (may be null)
     * @return parsed context, never null
     */
    @SuppressWarnings("unchecked")
    public static Mcp2026RequestContext fromParams(Map<String, Object> params) {
        if (params == null) {
            return EMPTY;
        }
        Object metaObj = params.get("_meta");
        if (!(metaObj instanceof Map)) {
            return EMPTY;
        }
        Map<String, Object> meta = (Map<String, Object>) metaObj;

        // Extract known keys
        Object pt = meta.get("progressToken");
        if (pt == null) {
            return EMPTY;
        }

        // Collect unknown keys as extras
        Map<String, Object> extras = null;
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            if (!"progressToken".equals(entry.getKey())) {
                if (extras == null) extras = new java.util.LinkedHashMap<>();
                extras.put(entry.getKey(), entry.getValue());
            }
        }

        return new Mcp2026RequestContext(
                pt,
                extras != null ? Collections.unmodifiableMap(extras) : Collections.emptyMap()
        );
    }

    /**
     * Empty context used when no _meta is present.
     */
    public static final Mcp2026RequestContext EMPTY = new Mcp2026RequestContext(null, Collections.emptyMap());

    @Override
    public String toString() {
        return "Mcp2026RequestContext{" +
                "progressToken=" + progressToken +
                ", extras=" + extras +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Mcp2026RequestContext that = (Mcp2026RequestContext) o;
        return Objects.equals(progressToken, that.progressToken) &&
                Objects.equals(extras, that.extras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(progressToken, extras);
    }
}
