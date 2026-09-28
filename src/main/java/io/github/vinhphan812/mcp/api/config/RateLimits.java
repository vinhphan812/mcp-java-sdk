package io.github.vinhphan812.mcp.api.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable server security and rate-limit configuration. */
public final class RateLimits {
    /** Configured maxConcurrentSessions. */
    public final int maxConcurrentSessions;
    /** Configured sessionTimeoutMs. */
    public final long sessionTimeoutMs;
    /** Configured sessionCleanupIntervalMs. */
    public final long sessionCleanupIntervalMs;
    /** Configured maxPendingNotificationsPerSession. */
    public final int maxPendingNotificationsPerSession;
    /** Configured maxRequestsPerIpPerMinute. */
    public final int maxRequestsPerIpPerMinute;
    /** Configured maxRequestsPerSessionPerMinute. */
    public final int maxRequestsPerSessionPerMinute;
    /** Configured readConcurrent. */
    public final int readBurst, readSustained, readConcurrent;
    /** Configured writeConcurrent. */
    public final int writeBurst, writeSustained, writeConcurrent;
    /** Configured adminConcurrent. */
    public final int adminBurst, adminSustained, adminConcurrent;
    /** Configured uploadCap. */
    public final int shutdownCap, deleteCap, uploadCap;
    /** Configured uploadCooldownMs. */
    public final long shutdownCooldownMs, deleteCooldownMs, uploadCooldownMs;
    /** Configured abuseScoreBlockThreshold. */
    public final int abuseScoreBlockThreshold;
    /** Configured rateLimitSustainedWindowMs. */
    public final long rateLimitWindowMs, rateLimitSustainedWindowMs;
    /** Configured destructiveTools. */
    public final Set<String> destructiveTools;

    private RateLimits(Builder b) {
        maxConcurrentSessions = b.maxConcurrentSessions;
        sessionTimeoutMs = b.sessionTimeoutMs;
        sessionCleanupIntervalMs = b.sessionCleanupIntervalMs;
        maxPendingNotificationsPerSession = b.maxPendingNotificationsPerSession;
        maxRequestsPerIpPerMinute = b.maxRequestsPerIpPerMinute;
        maxRequestsPerSessionPerMinute = b.maxRequestsPerSessionPerMinute;
        readBurst = b.readBurst;
        readSustained = b.readSustained;
        readConcurrent = b.readConcurrent;
        writeBurst = b.writeBurst;
        writeSustained = b.writeSustained;
        writeConcurrent = b.writeConcurrent;
        adminBurst = b.adminBurst;
        adminSustained = b.adminSustained;
        adminConcurrent = b.adminConcurrent;
        shutdownCap = b.shutdownCap;
        deleteCap = b.deleteCap;
        uploadCap = b.uploadCap;
        shutdownCooldownMs = b.shutdownCooldownMs;
        deleteCooldownMs = b.deleteCooldownMs;
        uploadCooldownMs = b.uploadCooldownMs;
        abuseScoreBlockThreshold = b.abuseScoreBlockThreshold;
        rateLimitWindowMs = b.rateLimitWindowMs;
        rateLimitSustainedWindowMs = b.rateLimitSustainedWindowMs;
        destructiveTools = Collections.unmodifiableSet(new LinkedHashSet<>(b.destructiveTools));
    }

    /**
     * Returns the default security and rate-limit configuration.
     * Values match {@link io.github.vinhphan812.mcp.api.config.McpSecurityDefaults}.
     */
    public static RateLimits defaults() {
        return builder().build();
    }

    /** Returns a builder with SDK defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder initialised from this configuration. */
    public Builder toBuilder() {
        return builder().maxConcurrentSessions(maxConcurrentSessions).sessionTimeoutMs(sessionTimeoutMs)
                .sessionCleanupIntervalMs(sessionCleanupIntervalMs)
                .maxPendingNotificationsPerSession(maxPendingNotificationsPerSession)
                .maxRequestsPerIpPerMinute(maxRequestsPerIpPerMinute)
                .maxRequestsPerSessionPerMinute(maxRequestsPerSessionPerMinute)
                .read(readBurst, readSustained, readConcurrent)
                .write(writeBurst, writeSustained, writeConcurrent)
                .admin(adminBurst, adminSustained, adminConcurrent)
                .shutdown(shutdownCap, shutdownCooldownMs).delete(deleteCap, deleteCooldownMs)
                .upload(uploadCap, uploadCooldownMs)
                .abuseScoreBlockThreshold(abuseScoreBlockThreshold)
                .rateLimitWindows(rateLimitWindowMs, rateLimitSustainedWindowMs)
                .destructiveTools(destructiveTools);
    }

    public static final class Builder {
        private int maxConcurrentSessions = McpSecurityDefaults.MAX_CONCURRENT_SESSIONS;
        private long sessionTimeoutMs = McpSecurityDefaults.SESSION_TIMEOUT_MS;
        private long sessionCleanupIntervalMs = McpSecurityDefaults.SESSION_CLEANUP_INTERVAL_MS_DEFAULT;
        private int maxPendingNotificationsPerSession = McpSecurityDefaults.MAX_PENDING_NOTIFICATIONS_PER_SESSION;
        private int maxRequestsPerIpPerMinute = McpSecurityDefaults.MAX_REQUESTS_PER_IP_PER_MINUTE;
        private int maxRequestsPerSessionPerMinute = McpSecurityDefaults.MAX_REQUESTS_PER_SESSION_PER_MINUTE;
        private int readBurst = McpSecurityDefaults.CATEGORY_READ_BURST_LIMIT;
        private int readSustained = McpSecurityDefaults.CATEGORY_READ_SUSTAINED_LIMIT;
        private int readConcurrent = McpSecurityDefaults.CATEGORY_READ_CONCURRENT_CAP;
        private int writeBurst = McpSecurityDefaults.CATEGORY_WRITE_BURST_LIMIT;
        private int writeSustained = McpSecurityDefaults.CATEGORY_WRITE_SUSTAINED_LIMIT;
        private int writeConcurrent = McpSecurityDefaults.CATEGORY_WRITE_CONCURRENT_CAP;
        private int adminBurst = McpSecurityDefaults.CATEGORY_ADMIN_BURST_LIMIT;
        private int adminSustained = McpSecurityDefaults.CATEGORY_ADMIN_SUSTAINED_LIMIT;
        private int adminConcurrent = McpSecurityDefaults.CATEGORY_ADMIN_CONCURRENT_CAP;
        private int shutdownCap = McpSecurityDefaults.DESTRUCTIVE_CAP_SHUTDOWN;
        private long shutdownCooldownMs = McpSecurityDefaults.DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS;
        private int deleteCap = McpSecurityDefaults.DESTRUCTIVE_CAP_DELETE;
        private long deleteCooldownMs = McpSecurityDefaults.DESTRUCTIVE_COOLDOWN_DELETE_MS;
        private int uploadCap = McpSecurityDefaults.DESTRUCTIVE_CAP_UPLOAD;
        private long uploadCooldownMs = McpSecurityDefaults.DESTRUCTIVE_COOLDOWN_UPLOAD_MS;
        private int abuseScoreBlockThreshold = McpSecurityDefaults.ABUSE_SCORE_BLOCK_THRESHOLD;
        private long rateLimitWindowMs = McpSecurityDefaults.RATE_LIMIT_WINDOW_MS_DEFAULT;
        private long rateLimitSustainedWindowMs = McpSecurityDefaults.RATE_LIMIT_SUSTAINED_WINDOW_MS_DEFAULT;
        private Set<String> destructiveTools = new LinkedHashSet<>(Arrays.asList(
                "shutdown", "delete_action", "delete_prompt", "set_mcp_api_key",
                "revoke_mcp_api_key", "upload_file"));

        public Builder maxConcurrentSessions(int v) {
            maxConcurrentSessions = positive(v, "maxConcurrentSessions");
            return this;
        }

        public Builder sessionTimeoutMs(long v) {
            sessionTimeoutMs = positive(v, "sessionTimeoutMs");
            return this;
        }

        public Builder sessionCleanupIntervalMs(long v) {
            sessionCleanupIntervalMs = positive(v, "sessionCleanupIntervalMs");
            return this;
        }

        public Builder maxPendingNotificationsPerSession(int v) {
            maxPendingNotificationsPerSession = positive(v, "maxPendingNotificationsPerSession");
            return this;
        }

        public Builder maxRequestsPerIpPerMinute(int v) {
            maxRequestsPerIpPerMinute = positive(v, "maxRequestsPerIpPerMinute");
            return this;
        }

        public Builder maxRequestsPerSessionPerMinute(int v) {
            maxRequestsPerSessionPerMinute = positive(v, "maxRequestsPerSessionPerMinute");
            return this;
        }

        public Builder read(int burst, int sustained, int concurrent) {
            readBurst = positive(burst, "readBurst");
            readSustained = positive(sustained, "readSustained");
            readConcurrent = positive(concurrent, "readConcurrent");
            return this;
        }

        public Builder write(int burst, int sustained, int concurrent) {
            writeBurst = positive(burst, "writeBurst");
            writeSustained = positive(sustained, "writeSustained");
            writeConcurrent = positive(concurrent, "writeConcurrent");
            return this;
        }

        public Builder admin(int burst, int sustained, int concurrent) {
            adminBurst = positive(burst, "adminBurst");
            adminSustained = positive(sustained, "adminSustained");
            adminConcurrent = positive(concurrent, "adminConcurrent");
            return this;
        }

        public Builder shutdown(int cap, long cooldown) {
            shutdownCap = positive(cap, "shutdownCap");
            shutdownCooldownMs = positive(cooldown, "shutdownCooldownMs");
            return this;
        }

        public Builder delete(int cap, long cooldown) {
            deleteCap = positive(cap, "deleteCap");
            deleteCooldownMs = positive(cooldown, "deleteCooldownMs");
            return this;
        }

        public Builder upload(int cap, long cooldown) {
            uploadCap = positive(cap, "uploadCap");
            uploadCooldownMs = positive(cooldown, "uploadCooldownMs");
            return this;
        }

        public Builder abuseScoreBlockThreshold(int v) {
            abuseScoreBlockThreshold = positive(v, "abuseScoreBlockThreshold");
            return this;
        }

        public Builder rateLimitWindows(long window, long sustained) {
            rateLimitWindowMs = positive(window, "rateLimitWindowMs");
            rateLimitSustainedWindowMs = positive(sustained, "rateLimitSustainedWindowMs");
            return this;
        }

        public Builder destructiveTools(Set<String> tools) {
            destructiveTools = tools == null ? new LinkedHashSet<String>() : new LinkedHashSet<>(tools);
            return this;
        }

        public RateLimits build() {
            return new RateLimits(this);
        }

        private static int positive(int v, String name) {
            if (v <= 0) throw new IllegalArgumentException(name + " must be positive");
            return v;
        }

        private static long positive(long v, String name) {
            if (v <= 0) throw new IllegalArgumentException(name + " must be positive");
            return v;
        }
    }
}
