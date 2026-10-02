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
 * Represents a client's response to a server {@link ElicitRequest}.
 *
 * <p>The client MUST echo the {@code requestId} from the corresponding
 * {@link ElicitRequest#getRequestId()} to enable correlation.
 * If the user cancelled the interaction, set {@link #cancelled} to {@code true}
 * and optionally provide a {@link #reason}.
 *
 * <p>See ADR-0022 for the full design.
 *
 * @see ElicitRequest
 */
public class ElicitationMessage {

    /**
     * The correlation ID echoed from the original elicitation request.
     * Must match the {@link ElicitRequest#getRequestId()}.
     */
    private final String requestId;

    /**
     * The client's response content.  When {@link #cancelled} is {@code true}
     * this field may be {@code null} or empty.
     */
    private final String content;

    /**
     * Whether the user cancelled the elicitation interaction.
     */
    private final boolean cancelled;

    /**
     * Optional cancellation reason.  Never {@code null}.
     */
    private final String reason;

    /**
     * Optional structured result data.
     * Used when the client wants to return structured data instead of plain text.
     * Never {@code null} — exposed as an empty map when not set.
     */
    private final Map<String, Object> data;

    private ElicitationMessage(Builder builder) {
        this.requestId = builder.requestId;
        this.content = builder.content;
        this.cancelled = builder.cancelled;
        this.reason = builder.reason == null ? "" : builder.reason;
        this.data = builder.data == null ? Collections.emptyMap() : Collections.unmodifiableMap(builder.data);
    }

    /** Returns the correlation ID echoed from the request. */
    public String getRequestId() {
        return requestId;
    }

    /** Returns the response content. May be null or empty. */
    public String getContent() {
        return content;
    }

    /** Returns whether the user cancelled the elicitation. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Returns the cancellation reason. Never null. */
    public String getReason() {
        return reason;
    }

    /** Returns structured result data. Never null. */
    public Map<String, Object> getData() {
        return data;
    }

    @Override
    public String toString() {
        return "ElicitationMessage{"
                + "requestId='" + requestId + '\''
                + ", cancelled=" + cancelled
                + ", reason='" + reason + '\''
                + ", content='" + content + '\''
                + '}';
    }

    /**
     * Creates a new builder pre-populated with this message's values.
     */
    public Builder toBuilder() {
        return new Builder()
                .requestId(requestId)
                .content(content)
                .cancelled(cancelled)
                .reason(reason)
                .data(data);
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ElicitationMessage}.
     */
    public static final class Builder {
        private String requestId;
        private String content;
        private boolean cancelled;
        private String reason;
        private Map<String, Object> data;

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder cancelled(boolean cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        /**
         * Builds an {@link ElicitationMessage}.
         *
         * @return a new ElicitationMessage
         * @throws IllegalStateException if {@code requestId} is null or blank
         */
        public ElicitationMessage build() {
            if (requestId == null || requestId.trim().isEmpty()) {
                throw new IllegalStateException("requestId is required");
            }
            return new ElicitationMessage(this);
        }
    }
}
