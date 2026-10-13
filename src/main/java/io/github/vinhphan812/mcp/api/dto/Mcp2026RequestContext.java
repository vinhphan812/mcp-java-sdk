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
 * <p>SEP-2322 MRTR same-request retry: top-level {@code inputResponses} and
 * {@code requestState} keys are also parsed from the request params map (not from
 * {@code _meta}) so that clients can retry the same method with client-supplied
 * input without a separate {@code tasks/update} round-trip.
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

    /**
     * SEP-2322 MRTR: client-supplied input responses for same-request retry.
     * Null when absent.
     */
    public final Map<String, Object> inputResponses;

    /**
     * SEP-2322 MRTR: request state token for same-request retry validation.
     * Null when absent.
     */
    public final String requestState;

    private Mcp2026RequestContext(Object progressToken, Map<String, Object> extras,
                                  Map<String, Object> inputResponses, String requestState) {
        this.progressToken = progressToken;
        this.extras = extras;
        this.inputResponses = inputResponses;
        this.requestState = requestState;
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
     * Whether inputResponses are present (SEP-2322 MRTR same-request retry).
     */
    public boolean hasInputResponses() {
        return inputResponses != null && !inputResponses.isEmpty();
    }

    /**
     * Parses a {@code _meta} object from a request params map.
     *
     * <p>Only the {@code progressToken} key is extracted from {@code _meta}. All other keys
     * are collected into {@link #extras} for forward-compatibility.
     *
     * <p>SEP-2322 MRTR: {@code inputResponses} and {@code requestState} are also parsed
     * from the top-level params map (not from {@code _meta}) to support same-request retry.
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
        Map<String, Object> meta = (metaObj instanceof Map) ? (Map<String, Object>) metaObj : null;

        // Extract known keys from _meta
        Object pt = (meta != null) ? meta.get("progressToken") : null;

        // Collect unknown _meta keys as extras
        Map<String, Object> extras = null;
        if (meta != null) {
            for (Map.Entry<String, Object> entry : meta.entrySet()) {
                if (!"progressToken".equals(entry.getKey())) {
                    if (extras == null) extras = new java.util.LinkedHashMap<>();
                    extras.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // SEP-2322 MRTR: extract inputResponses and requestState from top-level params
        Map<String, Object> inputResponses = null;
        Object ir = params.get("inputResponses");
        if (ir instanceof Map) {
            inputResponses = Collections.unmodifiableMap((Map<String, Object>) ir);
        }

        String requestState = null;
        Object rs = params.get("requestState");
        if (rs instanceof String) {
            requestState = (String) rs;
        }

        // Return EMPTY only when nothing is present at all
        if (pt == null && (extras == null || extras.isEmpty())
                && inputResponses == null && requestState == null) {
            return EMPTY;
        }

        return new Mcp2026RequestContext(
                pt,
                extras != null ? Collections.unmodifiableMap(extras) : Collections.emptyMap(),
                inputResponses,
                requestState
        );
    }

    /**
     * Empty context used when no _meta or MRTR fields are present.
     */
    public static final Mcp2026RequestContext EMPTY =
            new Mcp2026RequestContext(null, Collections.emptyMap(), null, null);

    @Override
    public String toString() {
        return "Mcp2026RequestContext{" +
                "progressToken=" + progressToken +
                ", inputResponses=" + inputResponses +
                ", requestState=" + requestState +
                ", extras=" + extras +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Mcp2026RequestContext that = (Mcp2026RequestContext) o;
        return Objects.equals(progressToken, that.progressToken) &&
                Objects.equals(extras, that.extras) &&
                Objects.equals(inputResponses, that.inputResponses) &&
                Objects.equals(requestState, that.requestState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(progressToken, extras, inputResponses, requestState);
    }
}
