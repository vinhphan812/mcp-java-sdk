package io.github.vinhphan812.mcp.api.utils;

/**
 * Canonical method-name constants for the MCP protocol.
 * Every MCP method name MUST be declared here — no magic strings elsewhere.
 *
 * <p>Excludes SSE event types ({@code ping}, {@code connected}, {@code message})
 * which are defined in {@link io.github.vinhphan812.mcp.transport.SseEventFormatter}.
 */
public final class McpMethodNames {

    private McpMethodNames() {}

    // ── Client → Server requests ─────────────────────────────────────────────

    /** {@code initialize} — opens a session and negotiates protocol version. */
    public static final String INITIALIZE = "initialize";

    // ── Tools ────────────────────────────────────────────────────────────────

    /** {@code tools/list} — returns the registry's tool list. */
    public static final String TOOLS_LIST = "tools/list";

    /** {@code tools/call} — invokes a registered tool. */
    public static final String TOOLS_CALL = "tools/call";

    // ── Resources ────────────────────────────────────────────────────────────

    /** {@code resources/list} — returns the registry's resource list. */
    public static final String RESOURCES_LIST = "resources/list";

    /** {@code resources/read} — returns content for a specific resource URI. */
    public static final String RESOURCES_READ = "resources/read";

    /** {@code resources/subscribe} — subscribes to change notifications for a resource. */
    public static final String RESOURCES_SUBSCRIBE = "resources/subscribe";

    /** {@code resources/unsubscribe} — cancels a resource subscription. */
    public static final String RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

    // ── Prompts ──────────────────────────────────────────────────────────────

    /** {@code prompts/list} — returns the registry's prompt list. */
    public static final String PROMPTS_LIST = "prompts/list";

    /** {@code prompts/get} — returns a rendered prompt. */
    public static final String PROMPTS_GET = "prompts/get";

    // ── Tasks ────────────────────────────────────────────────────────────────

    /** {@code tasks/create} — creates a background task. */
    public static final String TASKS_CREATE = "tasks/create";

    /** {@code tasks/get} — returns task status. */
    public static final String TASKS_GET = "tasks/get";

    /** {@code tasks/cancel} — cancels a running task. */
    public static final String TASKS_CANCEL = "tasks/cancel";

    /** {@code tasks/result} — returns the result of a completed task. */
    public static final String TASKS_RESULT = "tasks/result";

    // ── Completion ─────────────────────────────────────────────────────────

    /** {@code completion/complete} — returns completion candidates for input. */
    public static final String COMPLETION_COMPLETE = "completion/complete";

    // ── Logging ─────────────────────────────────────────────────────────────

    /** {@code logging/setLevel} — sets the client-side log level. */
    public static final String LOGGING_SET_LEVEL = "logging/setLevel";

    // ── Server → Client notifications ──────────────────────────────────────

    /** {@code notifications/message} — sends a log/trace message to the client. */
    public static final String NOTIF_MESSAGE = "notifications/message";

    /** {@code notifications/cancelled} — notifies that a requested task was cancelled. */
    public static final String NOTIF_CANCELLED = "notifications/cancelled";

    /** {@code notifications/initialized} — sent after {@link #INITIALIZE} completes. */
    public static final String NOTIF_INITIALIZED = "notifications/initialized";

    /** {@code notifications/progress} — sends progress notifications for long-running tasks. */
    public static final String NOTIF_PROGRESS = "notifications/progress";

    /**
     * {@code notifications/list_changed} — notifies that a registry list changed.
     */
    public static final String NOTIF_LIST_CHANGED = "notifications/list_changed";

    /**
     * {@code notifications/tools/list_changed} — tools registry changed.
     */
    public static final String NOTIF_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

    /**
     * {@code notifications/resources/list_changed} — resources registry changed.
     */
    public static final String NOTIF_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

    /**
     * {@code notifications/prompts/list_changed} — prompts registry changed.
     */
    public static final String NOTIF_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

    // ── Server-side notifications (MCP SDK extensions) ─────────────────────

    /**
     * {@code tasks/task} — emitted by the server when a background task is created.
     * This is a server-to-client notification not defined in the base MCP spec.
     */
    public static final String NOTIF_TASK = "tasks/task";

    /**
     * {@code notifications/resources/updated} — emitted when a subscribed resource changes.
     */
    public static final String NOTIF_RESOURCES_UPDATED = "notifications/resources/updated";

    // ── Ping ────────────────────────────────────────────────────────────────

    /**
     * {@code ping} — lightweight liveness probe accepted without a session.
     * Defined in the MCP spec as a valid request on any transport.
     */
    public static final String PING = "ping";
}
