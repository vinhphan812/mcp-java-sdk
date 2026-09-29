package io.github.vinhphan812.mcp.api.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Canonical SDK security and rate-limit defaults (ADR-0011).
 * <p>
 * This class is the single source of truth for all security defaults.
 * Both {@link io.github.vinhphan812.mcp.core.McpProtocolHandler} and
 * {@link RateLimits.Builder} delegate to these values to avoid duplication.
 */
public final class McpSecurityDefaults {

    // ---- Session limits ----

    /** Maximum concurrent MCP sessions allowed on this server. */
    public static final int MAX_CONCURRENT_SESSIONS = 10;

    /**
     * Session idle timeout in milliseconds.
     * Sessions idle longer than this are eligible for cleanup.
     */
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    /** Default interval between session cleanup passes in milliseconds. */
    public static final long SESSION_CLEANUP_INTERVAL_MS_DEFAULT = 60 * 1000L;

    /**
     * Maximum pending notifications queued per session before triggering overflow.
     * See {@link io.github.vinhphan812.mcp.core.McpProtocolHandler.QueueOverflowListener}.
     */
    public static final int MAX_PENDING_NOTIFICATIONS_PER_SESSION = 100;

    // ---- Global rate limits ----

    /** Maximum requests per client IP per minute (global/IP-level throttle). */
    public static final int MAX_REQUESTS_PER_IP_PER_MINUTE = 60;

    /** Maximum requests per session per minute. */
    public static final int MAX_REQUESTS_PER_SESSION_PER_MINUTE = 120;

    // ---- Sliding window configuration ----

    /** Sliding rate-limit window in milliseconds (burst bucket). */
    public static final long RATE_LIMIT_WINDOW_MS_DEFAULT = 60 * 1000L;

    /** Sustained-rate sliding window in milliseconds (slower bucket). */
    public static final long RATE_LIMIT_SUSTAINED_WINDOW_MS_DEFAULT = 5 * 60 * 1000L;

    // ---- Per-category limits ----

    /** Burst limit for read operations per session per minute. */
    public static final int CATEGORY_READ_BURST_LIMIT = 60;
    /** Sustained limit for read operations per session per 5 minutes. */
    public static final int CATEGORY_READ_SUSTAINED_LIMIT = 200;
    /** Maximum in-flight read operations per session. */
    public static final int CATEGORY_READ_CONCURRENT_CAP = 5;

    /** Burst limit for write operations per session per minute. */
    public static final int CATEGORY_WRITE_BURST_LIMIT = 30;
    /** Sustained limit for write operations per session per 5 minutes. */
    public static final int CATEGORY_WRITE_SUSTAINED_LIMIT = 100;
    /** Maximum in-flight write operations per session. */
    public static final int CATEGORY_WRITE_CONCURRENT_CAP = 3;

    /** Burst limit for admin operations per session per minute. */
    public static final int CATEGORY_ADMIN_BURST_LIMIT = 5;
    /** Sustained limit for admin operations per session per 5 minutes. */
    public static final int CATEGORY_ADMIN_SUSTAINED_LIMIT = 15;
    /** Maximum in-flight admin operations per session. */
    public static final int CATEGORY_ADMIN_CONCURRENT_CAP = 1;

    /** Canonical immutable names of tools subject to destructive limits. */
    public static final Set<String> DESTRUCTIVE_TOOLS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "shutdown", "delete_action", "delete_prompt", "set_mcp_api_key",
                    "revoke_mcp_api_key", "upload_file")));


    /** Maximum lifetime calls to the "shutdown" tool per session. */
    public static final int DESTRUCTIVE_CAP_SHUTDOWN = 3;
    /** Cool-down period for the "shutdown" tool in milliseconds. */
    public static final long DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS = 10 * 60 * 1000L;

    /** Maximum lifetime calls to "delete_action" / "delete_prompt" tools per session. */
    public static final int DESTRUCTIVE_CAP_DELETE = 10;
    /** Cool-down period for delete tools in milliseconds. */
    public static final long DESTRUCTIVE_COOLDOWN_DELETE_MS = 2 * 60 * 1000L;

    /** Maximum lifetime calls to the "upload_file" tool per session. */
    public static final int DESTRUCTIVE_CAP_UPLOAD = 5;
    /** Cool-down period for the "upload_file" tool in milliseconds. */
    public static final long DESTRUCTIVE_COOLDOWN_UPLOAD_MS = 60 * 1000L;

    // ---- Abuse scoring ----

    /**
     * Abuse score threshold: a session is blocked when its score reaches or exceeds this value.
     * Score accumulates from repeated rate-limit violations and decays at
     * {@link #ABUSE_SCORE_DECAY_PER_MINUTE} per minute.
     */
    public static final int ABUSE_SCORE_BLOCK_THRESHOLD = 10;

    /** Points deducted from abuse score per minute of clean behaviour. */
    public static final int ABUSE_SCORE_DECAY_PER_MINUTE = 1;

    private McpSecurityDefaults() {
    }
}
