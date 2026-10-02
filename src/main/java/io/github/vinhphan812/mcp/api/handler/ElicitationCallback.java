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

package io.github.vinhphan812.mcp.api.handler;

import io.github.vinhphan812.mcp.api.dto.ElicitationMessage;

/**
 * Callback interface for receiving elicitation responses from the client.
 *
 * <p>When the server sends an elicitation request to the client via
 * {@link io.github.vinhphan812.mcp.core.McpProtocolHandler#sendElicitRequest},
 * the client's response (or timeout/cancellation) is delivered through this callback.
 *
 * <p>Implementations MUST be thread-safe — callbacks may be invoked concurrently
 * from different sessions.
 *
 * @see ElicitationMessage
 * @see io.github.vinhphan812.mcp.core.McpProtocolHandler#sendElicitRequest
 */
public interface ElicitationCallback {

    /**
     * Called when the client responds to an elicitation request.
     *
     * <p>This callback is invoked when the server receives a valid
     * {@code elicitation/response} JSON-RPC request from the client.
     *
     * @param requestId the correlation ID matching the original request
     * @param response  the client's response message
     */
    void onResponse(String requestId, ElicitationMessage response);

    /**
     * Called when an elicitation request times out before the client responds.
     *
     * <p>This callback is invoked when the timeout specified in the original
     * {@link io.github.vinhphan812.mcp.api.dto.ElicitRequest} expires
     * before a {@code elicitation/response} is received.
     *
     * @param requestId the correlation ID of the timed-out request
     * @param timeoutMs the configured timeout that was exceeded
     */
    default void onTimeout(String requestId, long timeoutMs) {
    }

    /**
     * Called when an elicitation request is cancelled before the client responds.
     *
     * <p>This callback is invoked when
     * {@link io.github.vinhphan812.mcp.core.McpProtocolHandler#cancelElicitRequest}
     * is called before a response is received.
     *
     * @param requestId the correlation ID of the cancelled request
     * @param reason    the cancellation reason, if any
     */
    default void onCancelled(String requestId, String reason) {
    }

    /**
     * Called when an elicitation response with an unknown requestId is received.
     *
     * <p>This callback is invoked when the client sends a response with a requestId
     * that doesn't match any pending elicitation request (e.g., the request already
     * completed, timed out, or was cancelled).
     *
     * @param requestId the unknown correlation ID from the client's response
     */
    default void onUnknownRequestId(String requestId) {
    }
}
