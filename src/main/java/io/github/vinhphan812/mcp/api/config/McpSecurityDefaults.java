package io.github.vinhphan812.mcp.api.config;

/** Canonical SDK security and rate-limit defaults. */
public final class McpSecurityDefaults {
    public static final int MAX_CONCURRENT_SESSIONS = 10;
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;
    public static final long SESSION_CLEANUP_INTERVAL_MS_DEFAULT = 60 * 1000L;
    public static final int MAX_PENDING_NOTIFICATIONS_PER_SESSION = 100;
    public static final int MAX_REQUESTS_PER_IP_PER_MINUTE = 60;
    public static final int MAX_REQUESTS_PER_SESSION_PER_MINUTE = 120;
    public static final long RATE_LIMIT_WINDOW_MS_DEFAULT = 60 * 1000L;
    public static final long RATE_LIMIT_SUSTAINED_WINDOW_MS_DEFAULT = 5 * 60 * 1000L;

    public static final int CATEGORY_READ_BURST_LIMIT = 60;
    public static final int CATEGORY_READ_SUSTAINED_LIMIT = 200;
    public static final int CATEGORY_READ_CONCURRENT_CAP = 5;

    public static final int CATEGORY_WRITE_BURST_LIMIT = 30;
    public static final int CATEGORY_WRITE_SUSTAINED_LIMIT = 100;
    public static final int CATEGORY_WRITE_CONCURRENT_CAP = 3;

    public static final int CATEGORY_ADMIN_BURST_LIMIT = 5;
    public static final int CATEGORY_ADMIN_SUSTAINED_LIMIT = 15;
    public static final int CATEGORY_ADMIN_CONCURRENT_CAP = 1;

    public static final int DESTRUCTIVE_CAP_SHUTDOWN = 3;
    public static final long DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS = 10 * 60 * 1000L;
    public static final int DESTRUCTIVE_CAP_DELETE = 10;
    public static final long DESTRUCTIVE_COOLDOWN_DELETE_MS = 2 * 60 * 1000L;
    public static final int DESTRUCTIVE_CAP_UPLOAD = 5;
    public static final long DESTRUCTIVE_COOLDOWN_UPLOAD_MS = 60 * 1000L;

    public static final int ABUSE_SCORE_BLOCK_THRESHOLD = 10;
    public static final int ABUSE_SCORE_DECAY_PER_MINUTE = 1;

    private McpSecurityDefaults() {}
}
