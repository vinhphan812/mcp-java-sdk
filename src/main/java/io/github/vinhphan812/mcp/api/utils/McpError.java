package io.github.vinhphan812.mcp.api.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical JSON-RPC error-object factory for the MCP protocol layer.
 * <p>
 * Every error map built here follows the JSON-RPC 2.0 error envelope:
 * {@code {"code": <int>, "message": <string>}}.
 * Named factory methods replace magic strings scattered through
 * {@link io.github.vinhphan812.mcp.core.McpProtocolHandler} and
 * {@link io.github.vinhphan812.mcp.transport.McpHttpHandler}.
 * <p>
 * Call sites should pass the returned {@code Map<String, Object>} to
 * {@code errorResponse(id, error)} rather than building the map inline.
 *
 * @see McpErrorCodes for the canonical numeric codes
 */
public final class McpError {

    // ── factory ────────────────────────────────────────────────────────────────

    /**
     * Builds a generic error envelope.
     *
     * @param code    a {@link McpErrorCodes} value
     * @param message human-readable description
     * @return immutable-style error map
     */
    public static Map<String, Object> of(int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>(2);
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    /**
     * Builds a JSON-RPC response envelope without any transport concerns.
     * The returned map is data only; callers decide how it is serialized and
     * where it is written.
     *
     * @param id request identifier, possibly null for transport-generated errors
     * @param error JSON-RPC error object
     * @return JSON-RPC response envelope
     */
    public static Map<String, Object> jsonRpcEnvelope(Object id, Map<String, Object> error) {
        Map<String, Object> response = new LinkedHashMap<>(3);
        response.put("jsonrpc", McpJsonRpc.VERSION);
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    // ── JSON-RPC reserved codes ────────────────────────────────────────────────

    /**
     * {@code -32600} — The request is not a valid JSON-RPC Request object.
     * @param message human-readable error detail
     * @return JSON-RPC error object
     */
    public static Map<String, Object> invalidRequest(String message) {
        return of(McpErrorCodes.INVALID_REQUEST, message);
    }

    /**
     * {@code -32600} — Convenience overload: "Invalid Request: " + detail.
     * @param detail request validation detail
     * @return JSON-RPC error object
     */
    public static Map<String, Object> invalidRequestPrefix(String detail) {
        return invalidRequest("Invalid Request: " + detail);
    }

    /**
     * {@code -32601} — The method does not exist or is not available.
     * @param method method name that was requested
     * @return JSON-RPC error object
     */
    public static Map<String, Object> methodNotFound(String method) {
        return of(McpErrorCodes.METHOD_NOT_FOUND, method);
    }

    /**
     * {@code -32601} — MCP capability is disabled.
     * @param capability capability name that was requested
     * @return JSON-RPC error object
     */
    public static Map<String, Object> capabilityDisabled(String capability) {
        return of(McpErrorCodes.METHOD_NOT_FOUND, "MCP capability is disabled: " + capability);
    }

    /**
     * {@code -32602} — The params are invalid.
     * @param message human-readable error detail
     * @return JSON-RPC error object
     */
    public static Map<String, Object> invalidParams(String message) {
        return of(McpErrorCodes.INVALID_PARAMS, message);
    }

    /**
     * {@code -32602} — Convenience overload: "Invalid params: " + detail.
     * @param detail parameter validation detail
     * @return JSON-RPC error object
     */
    public static Map<String, Object> invalidParamsPrefix(String detail) {
        return invalidParams("Invalid params: " + detail);
    }

    /**
     * {@code -32603} — Internal server error (JSON-RPC catch-all).
     * @param message human-readable error detail
     * @return JSON-RPC error object
     */
    public static Map<String, Object> internalError(String message) {
        return of(McpErrorCodes.INTERNAL_ERROR, message);
    }

    // ── MCP server-defined codes ──────────────────────────────────────────────

    /**
     * {@code -32001} — Task result is not yet available.
     * @param taskId task identifier
     * @return JSON-RPC error object
     */
    public static Map<String, Object> taskNotComplete(String taskId) {
        return of(McpErrorCodes.RESULT_NOT_COMPLETE, "Task is not complete: " + taskId);
    }

    /**
     * {@code -32002} — Task is already in a terminal state.
     * @param status task status
     * @return JSON-RPC error object
     */
    public static Map<String, Object> taskAlreadyTerminal(String status) {
        return of(McpErrorCodes.RESULT_ALREADY_TERMINAL,
                "Task is already terminal: " + status.toLowerCase());
    }

    /**
     * {@code -32029} — Rate limit exceeded (concurrent / burst / sustained).
     * @param message rate limit error detail
     * @return JSON-RPC error object
     */
    public static Map<String, Object> rateLimitExceeded(String message) {
        return of(McpErrorCodes.RATE_LIMIT_EXCEEDED, "Too Many Requests: " + message);
    }

    // ── HTTP transport ────────────────────────────────────────────────────────

    /**
     * HTTP 429 error body for SSE permit exhaustion.
     * Not a JSON-RPC envelope — used directly by {@link io.github.vinhphan812.mcp.transport.McpHttpHandler}.
     * @param message human-readable error detail
     * @return HTTP error object
     */
    public static Map<String, Object> httpTooManyRequests(String message) {
        return of(429, message);
    }

    // ── error-tool / error-resource helpers ────────────────────────────────────
    // These are NOT JSON-RPC error objects; they are MCP tool/resource result maps
    // with isError=true.  Kept here for discoverability alongside the error codes.

    /**
     * Builds a tool-call error result: {@code {"content": [{"type":"text","text":"Error: ..."}], "isError": true}}.
     * @param message error message text
     * @return tool error result map
     */
    public static Map<String, Object> toolResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", "Error: " + message);
        result.put("content", java.util.Collections.singletonList(textContent));
        result.put("isError", true);
        return result;
    }

    /**
     * Builds a resource error result: {@code {"contents": [{"uri":"error","mimeType":"application/json","text":"{\"error\":\"...\"}"}]}}.
     * @param message error message text
     * @return resource error result map
     */
    public static Map<String, Object> resourceResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("uri", "error");
        contentItem.put("mimeType", "application/json");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("error", message == null ? "Unknown resource error" : message);
        try {
            contentItem.put("text", McpGson.get().toJson(payload));
        } catch (Exception ignored) {
            // Serialisation should never fail for a LinkedHashMap of String values,
            // but a hard-coded fallback preserves the response envelope.
            contentItem.put("text", "{\"error\":\"Unable to serialize resource error\"}");
        }
        result.put("contents", java.util.Collections.singletonList(contentItem));
        return result;
    }

    /**
     * Builds a prompt error result: {@code {"description":"Error: ...","messages":[]}}.
     * @param message error message text
     * @return prompt error result map
     */
    public static Map<String, Object> promptResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "Error: " + message);
        result.put("messages", java.util.Collections.emptyList());
        return result;
    }

    private McpError() {
        // utility class — prevent instantiation
    }
}
