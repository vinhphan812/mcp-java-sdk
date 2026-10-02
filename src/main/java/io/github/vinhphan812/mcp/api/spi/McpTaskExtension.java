package io.github.vinhphan812.mcp.api.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pluggable extension point for the MCP Tasks capability.
 *
 * <p>Applications can supply a custom implementation to replace the built-in
 * task-lifecycle behaviour (bounded tasks via {@code tasks/create/get/cancel/result}).
 * When no extension is configured the server uses its built-in task store.
 *
 * <p>The extension's canonical namespace is {@code io.modelcontextprotocol/tasks}.
 * The server advertises this capability in the {@code initialize} response when
 * the extension is present.
 *
 * <h2>Version gating</h2>
 *
 * {@link #supports(String)} is consulted before enabling task-method handling:
 * <ul>
 *   <li>returning {@code true} — task methods are dispatched to this extension</li>
 *   <li>returning {@code false} — task methods return {@code -32601 Method not found}</li>
 * </ul>
 *
 * <h2>Dispatch integration</h2>
 *
 * When {@link #onRequest(String, Map)} returns a non-{@code null} result the
 * protocol handler short-circuits its own built-in handler and returns that
 * result immediately.  Returning {@code null} passes the request to the
 * built-in logic.
 *
 * <h2>Thread safety</h2>
 *
 * Implementations must be thread-safe.  The registry may invoke
 * {@link #registerTask(String, Object)}, {@link #onRequest}, and {@link #onError}
 * concurrently.
 */
public interface McpTaskExtension {

    /**
     * Canonical namespace for the MCP Tasks capability.
     *
     * @see #advertiseCapabilities()
     */
    String NAMESPACE = "io.modelcontextprotocol/tasks";

    /**
     * Returns whether this extension handles task methods for the given protocol version.
     *
     * <p>The server calls this before advertising the {@code tasks} capability
     * and before dispatching any task request.
     *
     * @param protocolVersion protocol version from the {@code initialize} request
     *                       (never {@code null})
     * @return {@code true} when this extension claims the version
     */
    boolean supports(String protocolVersion);

    /**
     * Returns the capability metadata advertised in the {@code initialize} response.
     *
     * <p>The default implementation returns an empty map
     * {@code {"listChanged": false}} — sufficient for clients that only need
     * to know the capability exists.
     *
     * @param protocolVersion the negotiated protocol version (never {@code null})
     * @return capability metadata, never {@code null}
     */
    default Map<String, Object> advertiseCapabilities(String protocolVersion) {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("listChanged", false);
        return cap;
    }

    /**
     * Returns extension metadata to include in the top-level {@code extensions} array
     * of the {@code initialize} / {@code server/discover} response.
     *
     * <p>The default implementation returns {@code null}, meaning no entry is added
     * to the extensions array.  Subclasses may override to supply version, schema,
     * or other extension-specific fields alongside the capability metadata.
     *
     * <p>The returned map is placed directly in the extensions array as-is, so it
     * should at minimum contain {@code "name"} and optionally {@code "version"}
     * and a {@code "capabilities"} sub-object.
     *
     * @param protocolVersion the negotiated protocol version (never {@code null})
     * @return extension metadata map, or {@code null} to skip this extension
     */
    default Map<String, Object> advertiseExtension(String protocolVersion) {
        return null;
    }

    /**
     * Lifecycle hook called when the extension is registered with the server registry.
     *
     * <p>Subclasses can override to perform one-time initialisation (e.g. opening
     * connections, loading persisted state).
     *
     * @param taskRegistry the owning registry; may be used to register task snapshots
     */
    default void register(TaskRegistry taskRegistry) {
    }

    /**
     * Lifecycle hook called when the extension is deregistered.
     *
     * <p>Subclasses can override to release resources.
     */
    default void unregister() {
    }

    /**
     * Called for every MCP request whose method starts with {@code "tasks/"} and
     * is not handled by the built-in logic.
     *
     * <p>Returning a non-{@code null} {@link RequestResult} short-circuits the
     * protocol handler and that value is used as the JSON-RPC result.  Returning
     * {@code null} passes the request onward (typically to the built-in handler).
     *
     * <p>When the result contains an error, the server uses its code and message
     * in the JSON-RPC error response.
     *
     * @param method  the full method name (e.g. {@code "tasks/custom-method"})
     * @param params  the parsed JSON-RPC params, or an empty map
     * @param session the MCP session id, or {@code null} in stateless mode
     * @return request result, or {@code null} to continue to built-in handling
     */
    default RequestResult onRequest(String method, Map<String, Object> params, String session) {
        return null;
    }

    /**
     * Called when the protocol handler encounters an error while processing
     * a task-related request.
     *
     * <p>Implementations can use this to log, metric, or transform the error
     * before it is returned to the client.  Returning a non-{@code null}
     * {@link RequestResult} replaces the original error; returning {@code null}
     * preserves the original.
     *
     * @param method    the method that failed
     * @param params    the request params
     * @param session   the MCP session id, or {@code null} in stateless mode
     * @param errorCode JSON-RPC error code
     * @param message   human-readable error message
     * @return replacement result, or {@code null} to use the original error
     */
    default RequestResult onError(String method, Map<String, Object> params, String session,
                                   int errorCode, String message) {
        return null;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Result returned from {@link McpTaskExtension#onRequest} or
     * {@link McpTaskExtension#onError}.
     *
     * <p>Exactly one of {@code result} or {@code error} is set.
     */
    final class RequestResult {
        /** The JSON-RPC result value, or {@code null} on error. */
        public final Map<String, Object> result;
        /** The JSON-RPC error, or {@code null} on success. */
        public final Error error;

        private RequestResult(Map<String, Object> result, Error error) {
            this.result = result;
            this.error = error;
        }

        /** Creates a successful result. */
        public static RequestResult success(Map<String, Object> result) {
            return new RequestResult(result != null ? result : Collections.emptyMap(), null);
        }

        /** Creates a successful result with no payload. */
        public static RequestResult success() {
            return new RequestResult(Collections.emptyMap(), null);
        }

        /** Creates an error result. */
        public static RequestResult error(int code, String message) {
            return new RequestResult(null, new Error(code, message));
        }

        /** Returns whether this result is a success. */
        public boolean isSuccess() {
            return error == null;
        }

        /** JSON-RPC error descriptor. */
        public static final class Error {
            /** JSON-RPC error code. */
            public final int code;
            /** Human-readable error message. */
            public final String message;

            public Error(int code, String message) {
                this.code = code;
                this.message = message;
            }
        }
    }

    /**
     * Minimal task-registry interface exposed to extensions.
     *
     * <p>Only operations needed for lifecycle management are exposed — the full
     * {@link io.github.vinhphan812.mcp.core.McpRegistry} API is hidden from
     * unknown extensions to prevent arbitrary state interference.
     */
    interface TaskRegistry {
        /**
         * Registers a task snapshot with the server's task store.
         *
         * @param taskId task identifier
         * @param status task status value (one of {@code working}, {@code completed},
         *               {@code failed}, {@code cancelled})
         */
        void registerTask(String taskId, String status);

        /**
         * Returns the current status of a task.
         *
         * @param taskId task identifier
         * @return status string, or {@code null} if the task is unknown
         */
        String getTaskStatus(String taskId);
    }
}
