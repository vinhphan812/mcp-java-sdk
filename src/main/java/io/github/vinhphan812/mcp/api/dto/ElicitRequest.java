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

/**
 * Represents a server-initiated elicitation request sent to the client.
 *
 * <p>This type is used both as a DTO for the public API and as the internal
 * state stored in the pending-requests map. Callers use {@link #builder()} to
 * construct instances; the protocol handler serialises instances to JSON-RPC
 * and enqueues them as SSE events.
 *
 * <p>See ADR-0022 for the full design.
 *
 * @see ElicitationMessage
 * @see io.github.vinhphan812.mcp.core.McpProtocolHandler#sendElicitRequest(String, ElicitRequest)
 */
public class ElicitRequest {

    /**
     * Unique correlation ID for this request.  The client MUST echo this value
     * in the corresponding {@link ElicitationMessage#requestId} when responding.
     * Defaults to a random UUID when not supplied.
     */
    private final String requestId;

    /**
     * Human-readable message/prompt for the client to display to the user.
     * May be plain text or an empty string.
     */
    private final String message;

    /**
     * Optional structured context passed to the client.
     * Never {@code null} — exposed as an empty map when not set.
     */
    private final Map<String, Object> metadata;

    /**
     * Response timeout in milliseconds.
     * If the client does not respond within this window the request is automatically
     * cancelled and an error propagated to the registered {@link ElicitationCallback}.
     * Must be positive; defaults to the value configured in {@code McpServerConfig}.
     */
    private final long timeoutMs;

    private ElicitRequest(Builder builder) {
        this.requestId = builder.requestId;
        this.message = builder.message;
        this.metadata = builder.metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(builder.metadata);
        this.timeoutMs = builder.timeoutMs;
    }

    /** Returns the correlation ID. Never null. */
    public String getRequestId() {
        return requestId;
    }

    /** Returns the message text. May be empty. */
    public String getMessage() {
        return message;
    }

    /** Returns the metadata map. Never null. */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** Returns the timeout in milliseconds. Always positive. */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Creates a new builder pre-populated with this request's values.
     * Useful for copying and modifying.
     */
    public Builder toBuilder() {
        return new Builder()
                .requestId(requestId)
                .message(message)
                .metadata(metadata)
                .timeoutMs(timeoutMs);
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ElicitRequest{"
                + "requestId='" + requestId + '\''
                + ", message='" + message + '\''
                + ", metadata=" + metadata
                + ", timeoutMs=" + timeoutMs
                + '}';
    }

    /**
     * Builder for {@link ElicitRequest}.
     */
    public static final class Builder {
        private String requestId;
        private String message = "";
        private Map<String, Object> metadata;
        private long timeoutMs;

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder message(String message) {
            this.message = message == null ? "" : message;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets the response timeout in milliseconds. Must be positive.
         *
         * @param timeoutMs positive duration in milliseconds
         * @return this builder
         */
        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * Builds an {@link ElicitRequest}.  If {@code requestId} was not set,
         * a random UUID is generated.
         *
         * @return a new ElicitRequest
         */
        public ElicitRequest build() {
            if (requestId == null || requestId.isEmpty()) {
                this.requestId = java.util.UUID.randomUUID().toString();
            }
            if (timeoutMs <= 0) {
                this.timeoutMs = 60_000L;
            }
            return new ElicitRequest(this);
        }
    }
}
