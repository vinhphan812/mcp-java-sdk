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

package io.github.vinhphan812.mcp.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.api.dto.McpBlobContent;
import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.api.handler.*;
import io.github.vinhphan812.mcp.api.logging.McpLogger;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.api.spi.McpRegistrar;
import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Lightweight MCP protocol handler that implements JSON-RPC 2.0 for MCP
 * without depending on the MCP SDK's server-side code (which uses Stream.toList(),
 * incompatible with Android API 22).
 * <p>
 * This handler replaces McpAsyncServer/McpSyncServer while keeping the schema types.
 * Definitions are supplied by an application-owned {@link McpRegistry}; the
 * protocol layer does not construct project-specific tools or resources.
 * <p>
 * Security features (ADR-0011): owner-based sessions, per-category rate limiting,
 * destructive tool caps, abuse scoring, queue overflow handling, and authorization SPI.
 */
@SuppressWarnings("unused")
public class McpProtocolHandler implements McpRegistrar, McpRegistryChangeListener {

    private static final String JSONRPC_VERSION = "2.0";
    private static final String SERVER_NAME = "mcp-java-sdk";
    private static final String SERVER_VERSION = "1.0.0";
    private static final Logger LOGGER = Logger.getLogger(McpProtocolHandler.class.getName());

    private final Gson mapper;
    private final McpRegistry registry;
    private final McpServerConfig config;
    private final McpLogger applicationLogger;
    private final RateLimits rateLimits;

    public McpServerConfig getConfig() {
        return config;
    }

    // ==================== Security (ADR-0011) ====================

    private final McpAuthorization authorization;
    private final QueueOverflowListener overflowListener;

    /** Maximum concurrent sessions per server. */
    /** Maximum concurrent MCP sessions allowed on this server. */
    public static final int MAX_CONCURRENT_SESSIONS = 10;

    /** Session idle timeout in milliseconds (5 minutes). */
    /** Session idle timeout in milliseconds before cleanup. */
    public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

    /** Maximum pending notifications per session before triggering overflow. */
    /** Maximum pending notifications per session before overflow is triggered. */
    public static final int MAX_PENDING_NOTIFICATIONS_PER_SESSION = 100;

    /** Max requests per IP per minute. */
    /** Maximum requests per client IP per minute. */
    public static final int MAX_REQUESTS_PER_IP_PER_MINUTE = 60;

    /** Max requests per session per minute. */
    /** Maximum requests per session per minute. */
    public static final int MAX_REQUESTS_PER_SESSION_PER_MINUTE = 120;

    /** Session cleanup interval in milliseconds. */
    public static final long SESSION_CLEANUP_INTERVAL_MS_DEFAULT = 60 * 1000L;
    /** Sliding request rate-limit window in milliseconds. */
    public static final long RATE_LIMIT_WINDOW_MS_DEFAULT = 60 * 1000L;
    /** Sustained rate-limit window in milliseconds. */
    public static final long RATE_LIMIT_SUSTAINED_WINDOW_MS_DEFAULT = 5 * 60 * 1000L;

    private static final long RATE_LIMIT_WINDOW_MS = RATE_LIMIT_WINDOW_MS_DEFAULT;
    private static final long RATE_LIMIT_SUSTAINED_WINDOW_MS = RATE_LIMIT_SUSTAINED_WINDOW_MS_DEFAULT;

    // Per-category limits
    /** Burst limit for read operations per session per minute. */
    public static final int CATEGORY_READ_BURST_LIMIT = 60;
    /** Sustained limit for read operations per session per 5 minutes. */
    public static final int CATEGORY_READ_SUSTAINED_LIMIT = 200;
    /** Concurrent in-flight cap for read operations. */
    public static final int CATEGORY_READ_CONCURRENT_CAP = 5;

    /** Burst limit for write operations per session per minute. */
    public static final int CATEGORY_WRITE_BURST_LIMIT = 30;
    /** Sustained limit for write operations per session per 5 minutes. */
    public static final int CATEGORY_WRITE_SUSTAINED_LIMIT = 100;
    /** Concurrent in-flight cap for write operations. */
    public static final int CATEGORY_WRITE_CONCURRENT_CAP = 3;

    /** Burst limit for admin operations per session per minute. */
    public static final int CATEGORY_ADMIN_BURST_LIMIT = 5;
    /** Sustained limit for admin operations per session per 5 minutes. */
    public static final int CATEGORY_ADMIN_SUSTAINED_LIMIT = 15;
    /** Concurrent in-flight cap for admin operations. */
    public static final int CATEGORY_ADMIN_CONCURRENT_CAP = 1;

    // Destructive tool caps
    /** Maximum lifetime calls to "shutdown" tool per session. */
    public static final int DESTRUCTIVE_CAP_SHUTDOWN = 3;
    /** Cool-down period for "shutdown" tool in milliseconds. */
    public static final long DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS = 10 * 60 * 1000L;
    /** Maximum lifetime calls to "delete_action" / "delete_prompt" tools per session. */
    public static final int DESTRUCTIVE_CAP_DELETE = 10;
    /** Cool-down period for delete tools in milliseconds. */
    public static final long DESTRUCTIVE_COOLDOWN_DELETE_MS = 2 * 60 * 1000L;
    /** Maximum lifetime calls to "upload_file" tool per session. */
    public static final int DESTRUCTIVE_CAP_UPLOAD = 5;
    /** Cool-down period for "upload_file" tool in milliseconds. */
    public static final long DESTRUCTIVE_COOLDOWN_UPLOAD_MS = 60 * 1000L;

    /** Abuse score threshold for session blocking. */
    /** Abuse score at or above which the session is blocked. */
    public static final int ABUSE_SCORE_BLOCK_THRESHOLD = 10;

    private static final Set<String> DESTRUCTIVE_TOOLS = new HashSet<>(Arrays.asList(
            "shutdown", "delete_action", "delete_prompt",
            "set_mcp_api_key", "revoke_mcp_api_key", "upload_file"));

    private static final String CATEGORY_READ = "read";
    private static final String CATEGORY_WRITE = "write";
    private static final String CATEGORY_ADMIN = "admin";

    private static final long SESSION_CLEANUP_INTERVAL_MS = 60 * 1000L;

    // Category rate-limit state (per session)
    static final class CategoryRateLimitState {
        final RateLimitRecord readBurst = new RateLimitRecord();
        final RateLimitRecord readSustained = new RateLimitRecord();
        final RateLimitRecord writeBurst = new RateLimitRecord();
        final RateLimitRecord writeSustained = new RateLimitRecord();
        final RateLimitRecord adminBurst = new RateLimitRecord();
        final RateLimitRecord adminSustained = new RateLimitRecord();

        final AtomicInteger readConcurrent = new AtomicInteger(0);
        final AtomicInteger writeConcurrent = new AtomicInteger(0);
        final AtomicInteger adminConcurrent = new AtomicInteger(0);

        final AtomicInteger shutdownCount = new AtomicInteger(0);
        volatile long shutdownLastMs = 0L;
        final AtomicInteger deleteCount = new AtomicInteger(0);
        volatile long deleteLastMs = 0L;
        final AtomicInteger uploadCount = new AtomicInteger(0);
        volatile long uploadLastMs = 0L;

        final AtomicInteger abuseScore = new AtomicInteger(0);
        volatile boolean blocked = false;

        /** Package-visible for test access. */
        void setAbuseScore(int score) {
            abuseScore.set(score);
        }

        int getAbuseScore() {
            return abuseScore.get();
        }

        /** Package-visible for test access. */
        void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }
    }

    // Sliding-window rate limit tracker
    private static final class RateLimitRecord {
        private final ConcurrentLinkedQueue<Long> requestTimestamps = new ConcurrentLinkedQueue<>();

        synchronized boolean allowRequest(int maxRequests, long windowMs) {
            long now = System.currentTimeMillis();
            while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() > windowMs) {
                requestTimestamps.poll();
            }
            if (requestTimestamps.size() >= maxRequests) {
                return false;
            }
            requestTimestamps.offer(now);
            return true;
        }

        synchronized int getRequestCount(long windowMs) {
            long now = System.currentTimeMillis();
            while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() > windowMs) {
                requestTimestamps.poll();
            }
            return requestTimestamps.size();
        }

        synchronized long getResetTime(long windowMs) {
            long now = System.currentTimeMillis();
            while (!requestTimestamps.isEmpty() && now - requestTimestamps.peek() > windowMs) {
                requestTimestamps.poll();
            }
            if (requestTimestamps.isEmpty()) return now + windowMs;
            return requestTimestamps.peek() + windowMs;
        }
    }

    /** Thrown when notification queue overflows and no listener is configured. */
    public static class QueueOverflowException extends RuntimeException {
        /**
         * @param sessionId session identifier where overflow occurred
         */
        public QueueOverflowException(String sessionId) {
            super("MCP notification queue is full for session: " + sessionId);
        }
    }

    /**
     * Listener for notification queue overflow events.
     * Register via {@code McpServerConfig.Builder.overflowListener(listener)}.
     */
    public interface QueueOverflowListener {
        /**
         * Called when the notification queue of a session exceeds the configured limit.
         * @param sessionId session identifier where overflow occurred
         */
        void onOverflow(String sessionId);
    }

    private final ConcurrentHashMap<String, RateLimitRecord> ipRateLimits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimitRecord> sessionRateLimits = new ConcurrentHashMap<>();
    /** Maps owner identity to session ID. One active session per owner. */
    private final ConcurrentHashMap<String, String> sessionOwners = new ConcurrentHashMap<>();

    // ==================== SSE Event ====================

    private static final int MAX_QUEUED_EVENTS = 1000;

    private static final class SseEvent {
        final long id;
        final String body;

        SseEvent(long id, String body) {
            this.id = id;
            this.body = body;
        }
    }

    // ==================== Session State ====================

    private static class SessionState {
        final String sessionId;
        final long createdAt;
        volatile long lastActivity;
        final String clientIp;   // Client IP for rate limiting
        final String ownerId;    // Stable owner identity
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<SseEvent> pendingEvents = new ConcurrentLinkedQueue<>();
        final AtomicLong nextEventId = new AtomicLong(1L);
        /** Bounded notification queue for overflow tracking. */
        final ConcurrentLinkedQueue<String> pendingNotifications = new ConcurrentLinkedQueue<>();
        /** Per-category rate-limit and abuse state. */
        final CategoryRateLimitState categoryLimits = new CategoryRateLimitState();

        SessionState(String sessionId, String clientIp, String ownerId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.lastActivity = this.createdAt;
            this.clientIp = clientIp;
            this.ownerId = ownerId;
        }

        void enqueueEvent(String body) {
            synchronized(this) {
                if (pendingEvents.size() >= MAX_QUEUED_EVENTS) pendingEvents.poll();
                pendingEvents.offer(new SseEvent(nextEventId.getAndIncrement(), body));
            }
        }
    }

    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    // ==================== Response ====================

    /**
     * Request-local protocol output used by transports to set response headers safely.
     */
    public static final class McpResponse {
        private final String body;
        private final String sessionId;

        /**
         * Creates response metadata.
         * @param body JSON response body
         * @param sessionId response session ID, possibly null
         */
        public McpResponse(String body, String sessionId) {
            this.body = body;
            this.sessionId = sessionId;
        }

        /**
         * Returns response body.
         * @return JSON response body
         */
        public String getBody() {
            return body;
        }

        /**
         * Returns response session ID.
         * @return session ID, possibly null
         */
        public String getSessionId() {
            return sessionId;
        }
    }

    // ==================== Error ====================

    /**
     * Thrown to signal a JSON-RPC error from within a handler method without a full stack trace.
     */
    private static final class McpErrorException extends RuntimeException {
        private final int code;

        McpErrorException(int code, String message) {
            super(message);
            this.code = code;
        }

        int getCode() {
            return code;
        }
    }

    // ==================== Constructors ====================

    /**
     * Creates a protocol handler from application-owned MCP definitions.
     * No overflow listener and no authorization (all tools allowed).
     *
     * @param registry application-owned MCP registry
     */
    public McpProtocolHandler(McpRegistry registry) {
        this(registry, defaultConfig(), null, null);
    }

    /**
     * Creates a protocol handler with application-owned definitions and metadata.
     * No overflow listener and no authorization (all tools allowed).
     *
     * @param registry application-owned MCP registry
     * @param config   protocol metadata and capability configuration
     */
    public McpProtocolHandler(McpRegistry registry, McpServerConfig config) {
        this(registry, config,
                config == null ? null : config.getOverflowListener(),
                config == null ? null : config.getAuthorization());
    }

    /**
     * Creates a protocol handler with overflow listener and authorization.
     *
     * @param registry        application-owned MCP registry
     * @param config          protocol metadata and capability configuration
     * @param overflowListener listener for queue overflow events (may be null)
     * @param authorization    tool authorization handler (may be null — all tools allowed)
     */
    public McpProtocolHandler(McpRegistry registry, McpServerConfig config,
                              QueueOverflowListener overflowListener,
                              McpAuthorization authorization) {
        if (registry == null) throw new IllegalArgumentException("registry cannot be null");
        if (config == null) throw new IllegalArgumentException("config cannot be null");
        this.mapper = new Gson();
        this.registry = registry;
        this.config = config;
        this.applicationLogger = config.logger;
        this.rateLimits = config.rateLimits == null ? RateLimits.defaults() : config.rateLimits;
        this.overflowListener = overflowListener;
        this.authorization = authorization;
        registry.setNotificationTarget(this);
        registry.addRegistryChangeListener(this);
        startCleanupThread();
    }

    private static McpServerConfig defaultConfig() {
        return McpServerConfig.builder()
                .serverName(SERVER_NAME)
                .serverVersion(SERVER_VERSION)
                .build();
    }

    // ==================== Cleanup Thread ====================

    private volatile boolean cleanupRunning = false;
    private Thread cleanupThread = null;

    private synchronized void startCleanupThread() {
        if (cleanupRunning) return;
        cleanupRunning = true;
        cleanupThread = new Thread(() -> {
            while (cleanupRunning) {
                try {
                    Thread.sleep(rateLimits.sessionCleanupIntervalMs);
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
                        if (now - entry.getValue().lastActivity > rateLimits.sessionTimeoutMs
                                && sessions.remove(entry.getKey(), entry.getValue())) {
                            clearOwnerBinding(entry.getValue());
                            sessionRateLimits.remove(entry.getKey());
                            LOGGER.warning("Expired inactive MCP session: " + entry.getKey());
                        }
                    }
                    // Prune idle IP rate limit records
                    ipRateLimits.entrySet().removeIf(entry ->
                            entry.getValue().getRequestCount(rateLimits.rateLimitWindowMs) == 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "mcp-session-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Shuts down the cleanup thread and clears all rate limit records.
     */
    public synchronized void shutdown() {
        cleanupRunning = false;
        if (cleanupThread != null) cleanupThread.interrupt();
        cleanupThread = null;
        ipRateLimits.clear();
        sessionRateLimits.clear();
    }

    // ==================== Public API ====================

    /**
     * Returns whether server accepts a protocol version during HTTP negotiation.
     *
     * @param version requested protocol version
     * @return true when version is supported
     */
    public boolean supportsProtocolVersion(String version) {
        return version == null || config.protocolVersion.equals(version)
                || "2025-06-18".equals(version) || "2025-03-26".equals(version);
    }

    /**
     * Returns whether a session is currently active.
     *
     * @param sessionId session identifier
     * @return true when session is active
     */
    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * Returns the current rate-limit status for a session.
     * Used by transports to add rate-limit headers to 429 responses.
     *
     * @param sessionId session identifier
     * @return rate-limit status or null if no rate limit record exists
     */
    public RateLimitStatus getSessionRateLimitStatus(String sessionId) {
        if (sessionId == null) return null;
        RateLimitRecord record = sessionRateLimits.get(sessionId);
        if (record == null) return null;

        int limit = rateLimits.maxRequestsPerSessionPerMinute;
        long windowMs = rateLimits.rateLimitWindowMs;

        // Clean up and get current count
        int currentCount = record.getRequestCount(windowMs);
        long resetTime = record.getResetTime(windowMs);

        int remaining = Math.max(0, limit - currentCount);
        // Convert to seconds for Unix timestamp
        long resetSeconds = resetTime / 1000;

        return new RateLimitStatus(limit, remaining, resetSeconds);
    }

    /**
     * Returns the category rate limit state for a session.
     * Used by tests to verify concurrent slot counts.
     *
     * @param sessionId session identifier
     * @return category state or null if session not found
     */
    public CategoryRateLimitState getSessionCategoryLimits(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? null : state.categoryLimits;
    }

    /**
     * Rate-limit status information for HTTP headers.
     */
    public static final class RateLimitStatus {
        public final int limit;
        public final int remaining;
        public final long resetTime; // Unix timestamp in seconds

        public RateLimitStatus(int limit, int remaining, long resetTime) {
            this.limit = limit;
            this.remaining = remaining;
            this.resetTime = resetTime;
        }
    }

    /**
     * Handle a JSON-RPC request and return only its response body.
     * The sessionId parameter is the Mcp-Session-Id from the request header (maybe null).
     *
     * @param requestBody JSON-RPC request body.
     * @param sessionId   request session ID, possibly null.
     * @return response body.
     */
    public String handleRequest(String requestBody, String sessionId) {
        return handleRequestResponse(requestBody, sessionId, null).getBody();
    }

    /**
     * Handle a JSON-RPC request with client IP for rate limiting.
     *
     * @param requestBody JSON-RPC request body.
     * @param sessionId   request session ID, possibly null.
     * @param clientIp    client IP address (may be null — rate limiting skipped for null IP).
     * @return response body.
     */
    public String handleRequest(String requestBody, String sessionId, String clientIp) {
        return handleRequestResponse(requestBody, sessionId, clientIp).getBody();
    }

    /**
     * Handle a request and return request-local metadata for the transport layer.
     *
     * @param requestBody JSON-RPC request body.
     * @param sessionId   request session ID, possibly null.
     * @return response body and session metadata.
     */
    public McpResponse handleRequestResponse(String requestBody, String sessionId) {
        return handleRequestResponse(requestBody, sessionId, null);
    }

    /**
     * Handle a request with client IP for rate limiting.
     *
     * @param requestBody JSON-RPC request body.
     * @param sessionId   request session ID, possibly null.
     * @param clientIp    client IP address (may be null — rate limiting skipped).
     * @return response body and session metadata.
     */
    @SuppressWarnings("unchecked")
    public McpResponse handleRequestResponse(String requestBody, String sessionId, String clientIp) {
        Object id = null;
        String method = null;
        List<String> toolScopes = null;
        String reservedCategory = null;
        try {
            Map<String, Object> request = mapper.fromJson(requestBody, new TypeToken<Map<String, Object>>() {
            }.getType());
            if (request == null || !JSONRPC_VERSION.equals(request.get("jsonrpc"))) {
                return new McpResponse(errorResponse(null, -32600, "Invalid Request: jsonrpc must be 2.0"), sessionId);
            }
            Object methodValue = request.get("method");
            method = methodValue instanceof String ? (String) methodValue : null;
            id = request.get("id");
            Object params = request.get("params");
            boolean notification = !request.containsKey("id");

            if (method == null) {
                return new McpResponse(errorResponse(id, -32600,
                        "Invalid Request: missing method"), sessionId);
            }

            applicationLogger.debug("Handling MCP request: " + method);

            // Check max concurrent sessions for initialize
            if ("initialize".equals(method) && sessions.size() >= rateLimits.maxConcurrentSessions) {
                return new McpResponse(errorResponse(id, -32029,
                        "Too Many Requests: max concurrent sessions reached"), sessionId);
            }

            if (!isSessionOptional(method) && !hasSession(sessionId)) {
                return new McpResponse(errorResponse(id, -32001,
                        "Missing or invalid MCP session"), sessionId);
            }

            // Check rate limits (IP + session)
            String rateLimitError = checkRateLimit(clientIp, sessionId, method);
            if (rateLimitError != null) {
                return new McpResponse(errorResponse(id, -32029,
                        "Too Many Requests: " + rateLimitError), sessionId);
            }

            // Check category rate limits for all methods
            toolScopes = null;
            if (!isSessionOptional(method) && hasSession(sessionId)) {
                // Extract tool scopes for tools/call
                if ("tools/call".equals(method) && params instanceof Map) {
                    Map<String, Object> toolParams = (Map<String, Object>) params;
                    String toolName = toolParams.get("name") instanceof String
                            ? (String) toolParams.get("name") : null;
                    if (toolName != null) {
                        Map<String, Object> toolDef = registry.getToolDefinition(toolName);
                        if (toolDef != null) {
                            toolScopes = (List<String>) toolDef.get("requiredScopes");
                        }
                    }
                }
                String categoryRateLimitError = checkMethodRateLimit(sessionId, method, toolScopes);
                if (categoryRateLimitError != null) {
                    return new McpResponse(errorResponse(id, -32029,
                            "Too Many Requests: " + categoryRateLimitError), sessionId);
                }
            }

            // Refresh activity for active sessions
            if (hasSession(sessionId)) {
                refreshActivity(sessionId);
            }

            // Track concurrent requests for all methods
            if (!isSessionOptional(method) && hasSession(sessionId)) {
                String cat = methodCategory(method);
                if ("tools/call".equals(method) && toolScopes != null) {
                    cat = toolCategory(toolScopes);
                }
                if (reserveCategory(sessions.get(sessionId).categoryLimits, cat, getCategoryCap(cat))) {
                    reservedCategory = cat;
                } else {
                    return new McpResponse(errorResponse(id, -32029, "Too Many Requests: " + cat + " concurrent limit exceeded"), sessionId);
                }
            }

            String responseSessionId = sessionId;
            Map<String, Object> result;

            // Intentional: each MCP list handler calls a different registry method.
            //noinspection DuplicateBranchesInSwitch
            switch (method) {
                case "initialize":
                    Map<String, Object> initializeParams = params instanceof Map
                            ? (Map<String, Object>) params : null;
                    Object requestedVersion = initializeParams == null
                            ? null : initializeParams.get("protocolVersion");
                    if (requestedVersion != null && !(requestedVersion instanceof String)) {
                        return new McpResponse(errorResponse(id, -32602,
                                "Invalid params: protocolVersion must be a string"), sessionId);
                    }
                    if (requestedVersion != null && !supportsProtocolVersion((String) requestedVersion)) {
                        return new McpResponse(errorResponse(id, -32602,
                                "Unsupported protocol version: " + requestedVersion), sessionId);
                    }
                    String initSessionId = UUID.randomUUID().toString();
                    result = handleInitialize(initializeParams, initSessionId, clientIp);
                    responseSessionId = initSessionId;
                    break;
                case "notifications/initialized":
                case "notifications/message":
                    return new McpResponse(null, sessionId);
                case "tools/list":
                    if (!config.tools) {
                        return new McpResponse(capabilityError(id, "tools"), sessionId);
                    }
                    result = handleToolsList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "tools/call":
                    if (!config.tools) {
                        return new McpResponse(capabilityError(id, "tools"), sessionId);
                    }
                    result = handleToolsCall(
                            params instanceof Map ? (Map<String, Object>) params : null,
                            sessionId, id);
                    break;
                case "resources/list":
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourcesList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "resources/read":
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourcesRead(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "resources/templates/list":
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourceTemplatesList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "resources/templates/get":
                    return new McpResponse(errorResponse(id, -32601,
                            "Method not found: resources/templates/get — use resources/read with resolved URI"), sessionId);
                case "resources/subscribe":
                    if (!config.resources || !config.resourceSubscriptions) {
                        return new McpResponse(capabilityError(id, "resource subscriptions"), sessionId);
                    }
                    result = handleResourceSubscribe(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "resources/unsubscribe":
                    if (!config.resources || !config.resourceSubscriptions) {
                        return new McpResponse(capabilityError(id, "resource subscriptions"), sessionId);
                    }
                    result = handleResourceUnsubscribe(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "prompts/list":
                    if (!config.prompts) {
                        return new McpResponse(capabilityError(id, "prompts"), sessionId);
                    }
                    result = handlePromptsList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "prompts/get":
                    if (!config.prompts) {
                        return new McpResponse(capabilityError(id, "prompts"), sessionId);
                    }
                    result = handlePromptsGet(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "tasks/get":
                    if (!config.tasks) return new McpResponse(capabilityError(id, "tasks"), sessionId);
                    result = handleTasksGet(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "tasks/result":
                    if (!config.tasks) return new McpResponse(capabilityError(id, "tasks"), sessionId);
                    result = handleTasksResult(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "tasks/cancel":
                    if (!config.tasks) return new McpResponse(capabilityError(id, "tasks"), sessionId);
                    result = handleTasksCancel(params instanceof Map ? (Map<String, Object>) params : null, sessionId, id);
                    break;
                case "tasks/create":
                    if (!config.tasks) return new McpResponse(capabilityError(id, "tasks"), sessionId);
                    result = handleTasksCreate(params instanceof Map ? (Map<String, Object>) params : null, sessionId, id);
                    break;
                case "completion/complete":
                    if (!config.completions) return new McpResponse(capabilityError(id, "completions"), sessionId);
                    result = handleCompletion(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "logging/setLevel":
                    if (!config.logging) return new McpResponse(capabilityError(id, "logging"), sessionId);
                    result = handleSetLogLevel(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "notifications/cancelled":
                    handleNotificationCancelled(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    return new McpResponse(null, sessionId);
                case "ping":
                    result = new LinkedHashMap<>();
                    break;
                default:
                    return new McpResponse(errorResponse(id, -32601,
                            "Method not found: " + method), sessionId);
            }

            if (result == null || notification) {
                return new McpResponse(null, responseSessionId);
            }
            return new McpResponse(successResponse(id, result), responseSessionId);
        } catch (McpErrorException e) {
            return new McpResponse(errorResponse(id, e.getCode(), e.getMessage()), sessionId);
        } catch (Throwable t) {
            applicationLogger.error("Error handling MCP request: " + t.getMessage());
            if (t instanceof Error) throw (Error) t;
            Exception e = (Exception) t;
            try {
                Map<String, Object> req = mapper.fromJson(requestBody, new TypeToken<Map<String, Object>>() {
                }.getType());
                if (req != null) id = req.get("id");
            } catch (Exception ignored) {
                // Keep the JSON-RPC error id null when the request cannot be parsed.
            }
            return new McpResponse(errorResponse(id, -32603, "Internal server error"), sessionId);
        } finally {
            if (reservedCategory != null) {
                releaseCategory(sessions.get(sessionId).categoryLimits, reservedCategory);
            }
        }
    }

    private boolean isSessionOptional(String method) {
        return "initialize".equals(method)
                || "notifications/initialized".equals(method)
                || "ping".equals(method);
    }

    // Backward-compatible overload

    /**
     * Creates or performs the requested operation.
     *
     * @param requestBody parameter used by this operation.
     * @return operation result.
     */
    public String handleRequest(String requestBody) {
        return handleRequest(requestBody, null, null);
    }

    /**
     * Terminates a session.
     *
     * @param sessionId session identifier
     */
    public void terminateSession(String sessionId) {
        if (sessionId != null) {
            SessionState state = sessions.remove(sessionId);
            if (state != null) {
                clearOwnerBinding(state);
                sessionRateLimits.remove(sessionId);
                LOGGER.info("Session terminated: " + sessionId);
            }
        }
    }

    /**
     * Terminate every active session and discard subscriptions and queued notifications.
     */
    public void closeAllSessions() {
        int count = sessions.size();
        for (SessionState state : sessions.values()) clearOwnerBinding(state);
        sessions.clear();
        sessionOwners.clear();
        sessionRateLimits.clear();
        if (count > 0) {
            LOGGER.info("Closed " + count + " MCP sessions");
        }
    }

    /**
     * Returns the session ID bound to an owner identity, or null if no binding exists.
     * Test helper for verifying owner-based session semantics.
     *
     * @param ownerId owner identity
     * @return session ID, or null if unbound
     */
    /**
     * @param ownerId owner identity
     * @return session ID bound to this owner, or null
     */
    public String getSessionIdForOwner(String ownerId) {
        return ownerId == null ? null : sessionOwners.get(ownerId);
    }

    /**
     * Returns the owner identity bound to a session, or null if no binding exists.
     *
     * @param sessionId session identifier
     * @return owner identity, or null if unbound
     */
    /**
     * @param sessionId session identifier
     * @return owner identity bound to this session, or null
     */
    public String getOwnerIdForSession(String sessionId) {
        if (sessionId == null) return null;
        SessionState state = sessions.get(sessionId);
        return state == null ? null : state.ownerId;
    }

    /**
     * Returns true if the session has been blocked due to abuse scoring.
     *
     * @param sessionId session identifier
     * @return true if blocked
     */
    /**
     * @param sessionId session identifier
     * @return true if the session has been blocked due to abuse scoring
     */
    public boolean isSessionBlocked(String sessionId) {
        if (sessionId == null) return false;
        SessionState state = sessions.get(sessionId);
        return state != null && state.categoryLimits.blocked;
    }

    // ==================== Rate Limiting (ADR-0011) ====================

    /**
     * Normalise an owner ID to a trimmed non-empty string, or null if absent/blank.
     */
    private static String normalizeOwnerId(String ownerId) {
        if (ownerId == null) return null;
        String normalized = ownerId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void clearOwnerBinding(SessionState state) {
        if (state != null && state.ownerId != null) {
            sessionOwners.remove(state.ownerId, state.sessionId);
        }
    }

    private SessionState handleSessionCreate(String sessionId, String clientIp, String ownerId) {
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        if (normalizedOwnerId != null) {
            String existingSession = sessionOwners.putIfAbsent(normalizedOwnerId, sessionId);
            if (existingSession != null) {
                // Owner already has a session — replace it
                SessionState old = sessions.remove(existingSession);
                if (old != null) clearOwnerBinding(old);
            }
        }
        SessionState state = new SessionState(sessionId, clientIp, normalizedOwnerId);
        sessions.put(sessionId, state);
        return state;
    }

    private void refreshActivity(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.lastActivity = System.currentTimeMillis();
        }
    }

    /**
     * Determine the rate-limit category from required scopes.
     * admin > write > read.
     */
    private boolean reserveCategory(CategoryRateLimitState cl, String category, int cap) {
        AtomicInteger counter;
        switch (category) {
            case CATEGORY_ADMIN:
                counter = cl.adminConcurrent;
                break;
            case CATEGORY_WRITE:
                counter = cl.writeConcurrent;
                break;
            default:
                counter = cl.readConcurrent;
                break;
        }
        while (true) {
            int current = counter.get();
            if (current >= cap) return false;
            if (counter.compareAndSet(current, current + 1)) {
                LOGGER.info("RESERVED " + category + " from=" + current + " to=" + (current + 1));
                return true;
            }
        }
    }

    private void releaseCategory(CategoryRateLimitState cl, String category) {
        AtomicInteger counter;
        switch (category) {
            case CATEGORY_ADMIN:
                counter = cl.adminConcurrent;
                break;
            case CATEGORY_WRITE:
                counter = cl.writeConcurrent;
                break;
            default:
                counter = cl.readConcurrent;
                break;
        }
        int current = counter.decrementAndGet();
        LOGGER.info("RELEASED " + category + " to=" + current);
    }

    private int getCategoryCap(String category) {
        switch (category) {
            case CATEGORY_ADMIN:
                return rateLimits.adminConcurrent;
            case CATEGORY_WRITE:
                return rateLimits.writeConcurrent;
            default:
                return rateLimits.readConcurrent;
        }
    }

    private final class Reserve implements AutoCloseable {
        private final CategoryRateLimitState cl;
        private final String category;

        public Reserve(CategoryRateLimitState cl, String category) {
            if (!reserveCategory(cl, category, getCategoryCap(category))) {
                throw new McpErrorException(-32029, "Too Many Requests: " + category + " concurrent limit exceeded");
            }
            this.cl = cl;
            this.category = category;
        }

        @Override
        public void close() {
            releaseCategory(cl, category);
        }
    }


    private String toolCategory(List<String> requiredScopes) {
        if (requiredScopes == null || requiredScopes.isEmpty()) return CATEGORY_READ;
        for (String s : requiredScopes) {
            if (CATEGORY_ADMIN.equals(s)) return CATEGORY_ADMIN;
        }
        for (String s : requiredScopes) {
            if (CATEGORY_WRITE.equals(s)) return CATEGORY_WRITE;
        }
        return CATEGORY_READ;
    }

    /**
     * Determine the rate-limit category for an MCP method.
     * Read operations: tools/list, resources/read, resources/list, prompts/list, prompts/get,
     *                  tasks/get, tasks/result, completion/complete, logging/setLevel, ping
     * Write operations: resources/subscribe, resources/unsubscribe, tasks/cancel
     * Admin operations: tasks/create (creates new server-side resources)
     *
     * @param method the MCP method name
     * @return the category (read/write/admin)
     */
    private String methodCategory(String method) {
        if (method == null) return CATEGORY_READ;

        // Read operations - methods that only retrieve data
        switch (method) {
            case "tools/list":
            case "tools/call":
            case "resources/list":
            case "resources/read":
            case "resources/templates/list":
            case "resources/templates/get":
            case "prompts/list":
            case "prompts/get":
            case "tasks/get":
            case "tasks/result":
            case "completion/complete":
            case "logging/setLevel":
            case "ping":
                return CATEGORY_READ;

            // Write operations - modify client state or cancel operations
            case "resources/subscribe":
            case "resources/unsubscribe":
            case "tasks/cancel":
                return CATEGORY_WRITE;

            // Admin operations - create server-side resources/tasks
            case "tasks/create":
                return CATEGORY_ADMIN;

            default:
                return CATEGORY_READ;
        }
    }

    /**
     * Check per-category rate limits for any MCP method.
     * This extends category rate limiting beyond tools to all protocol methods.
     *
     * @return null if allowed, or a denial message.
     */
    String checkMethodRateLimit(String sessionId, String method, List<String> toolScopes) {
        if (sessionId == null) return null;
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;

        CategoryRateLimitState cl = state.categoryLimits;

        // Check blocked session
        if (cl.blocked) {
            return "session blocked due to abuse: " + method;
        }

        // For tools/call, use tool-specific scopes if provided
        String category;
        if ("tools/call".equals(method) && toolScopes != null) {
            category = toolCategory(toolScopes);
        } else {
            category = methodCategory(method);
        }

        long now = System.currentTimeMillis();

        // Per-category limits
        switch (category) {
            case CATEGORY_ADMIN:
                if (!cl.adminBurst.allowRequest(rateLimits.adminBurst, rateLimits.rateLimitWindowMs)) {
                    addAbuseScore(cl, sessionId, method, 2, "admin burst limit exceeded");
                    return "admin method burst limit exceeded: " + method;
                }
                if (!cl.adminSustained.allowRequest(rateLimits.adminSustained, rateLimits.rateLimitSustainedWindowMs)) {
                    addAbuseScore(cl, sessionId, method, 2, "admin sustained limit exceeded");
                    return "admin method sustained limit exceeded: " + method;
                }
                break;
            case CATEGORY_WRITE:
                if (!cl.writeBurst.allowRequest(rateLimits.writeBurst, rateLimits.rateLimitWindowMs)) {
                    addAbuseScore(cl, sessionId, method, 1, "write burst limit exceeded");
                    return "write method burst limit exceeded: " + method;
                }
                if (!cl.writeSustained.allowRequest(rateLimits.writeSustained, rateLimits.rateLimitSustainedWindowMs)) {
                    addAbuseScore(cl, sessionId, method, 1, "write sustained limit exceeded");
                    return "write method sustained limit exceeded: " + method;
                }
                break;
            default: // READ
                if (!cl.readBurst.allowRequest(rateLimits.readBurst, rateLimits.rateLimitWindowMs)) {
                    addAbuseScore(cl, sessionId, method, 1, "read burst limit exceeded");
                    return "read method burst limit exceeded: " + method;
                }
                if (!cl.readSustained.allowRequest(rateLimits.readSustained, rateLimits.rateLimitSustainedWindowMs)) {
                    addAbuseScore(cl, sessionId, method, 1, "read sustained limit exceeded");
                    return "read method sustained limit exceeded: " + method;
                }
                break;
        }
        return null;
    }

    /**
     * Increment the in-flight concurrent counter for a method category.
     * Must pair with {@link #decrementMethodConcurrentCount} in a finally block.
     */
    void incrementMethodConcurrentCount(String sessionId, String method, List<String> toolScopes) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;

        String category;
        if ("tools/call".equals(method) && toolScopes != null) {
            category = toolCategory(toolScopes);
        } else {
            category = methodCategory(method);
        }

        switch (category) {
            case CATEGORY_ADMIN:
                state.categoryLimits.adminConcurrent.incrementAndGet();
                break;
            case CATEGORY_WRITE:
                state.categoryLimits.writeConcurrent.incrementAndGet();
                break;
            default:
                state.categoryLimits.readConcurrent.incrementAndGet();
                break;
        }
    }

    /**
     * Decrement the in-flight concurrent counter for a method category.
     */
    void decrementMethodConcurrentCount(String sessionId, String method, List<String> toolScopes) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;

        String category;
        if ("tools/call".equals(method) && toolScopes != null) {
            category = toolCategory(toolScopes);
        } else {
            category = methodCategory(method);
        }

        switch (category) {
            case CATEGORY_ADMIN:
                state.categoryLimits.adminConcurrent.decrementAndGet();
                break;
            case CATEGORY_WRITE:
                state.categoryLimits.writeConcurrent.decrementAndGet();
                break;
            default:
                state.categoryLimits.readConcurrent.decrementAndGet();
                break;
        }
    }

    /**
     * Check global (IP + session) rate limits for a request method.
     *
     * @return null if allowed, or a denial message.
     */
    private String checkRateLimit(String clientIp, String sessionId, String method) {
        // Skip for session-optional methods
        if (isSessionOptional(method)) return null;

        if (clientIp != null && !clientIp.trim().isEmpty()) {
            RateLimitRecord ipRecord = ipRateLimits.computeIfAbsent(
                    clientIp, k -> new RateLimitRecord());
            if (!ipRecord.allowRequest(rateLimits.maxRequestsPerIpPerMinute, rateLimits.rateLimitWindowMs)) {
                return "client IP request limit exceeded";
            }
        }
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            RateLimitRecord sessionRecord = sessionRateLimits.computeIfAbsent(
                    sessionId, k -> new RateLimitRecord());
            if (!sessionRecord.allowRequest(rateLimits.maxRequestsPerSessionPerMinute, rateLimits.rateLimitWindowMs)) {
                return "session request limit exceeded";
            }
        }
        return null;
    }

    /**
     * Check per-category and destructive-tool rate limits for a tool call.
     *
     * @return null if allowed, or a denial message.
     */
    @SuppressWarnings("unchecked")
    String checkToolRateLimit(String sessionId, String toolName,
                              List<String> requiredScopes) {
        if (sessionId == null) return null;
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;

        CategoryRateLimitState cl = state.categoryLimits;

        // Check blocked session
        if (cl.blocked) {
            return "session blocked due to abuse: " + toolName;
        }

        String category = toolCategory(requiredScopes);
        long now = System.currentTimeMillis();

        // Destructive tool caps
        if (rateLimits.destructiveTools.contains(toolName)) {
            String destructiveError = checkDestructiveCap(cl, toolName, now, sessionId);
            if (destructiveError != null) return destructiveError;
        }

        // Per-category limits
        switch (category) {
            case CATEGORY_ADMIN:
                if (!cl.adminBurst.allowRequest(rateLimits.adminBurst, rateLimits.rateLimitWindowMs)) {
                    addAbuseScore(cl, sessionId, toolName, 2, "admin burst limit exceeded");
                    return "admin tool burst limit exceeded for tool: " + toolName;
                }
                if (!cl.adminSustained.allowRequest(rateLimits.adminSustained, rateLimits.rateLimitSustainedWindowMs)) {
                    addAbuseScore(cl, sessionId, toolName, 2, "admin sustained limit exceeded");
                    return "admin tool sustained limit exceeded for tool: " + toolName;
                }
                break;
            case CATEGORY_WRITE:
                if (!cl.writeBurst.allowRequest(rateLimits.writeBurst, rateLimits.rateLimitWindowMs)) {
                    addAbuseScore(cl, sessionId, toolName, 1, "write burst limit exceeded");
                    return "write tool burst limit exceeded for tool: " + toolName;
                }
                if (!cl.writeSustained.allowRequest(rateLimits.writeSustained, rateLimits.rateLimitSustainedWindowMs)) {
                    addAbuseScore(cl, sessionId, toolName, 1, "write sustained limit exceeded");
                    return "write tool sustained limit exceeded for tool: " + toolName;
                }
                break;
            default: // READ
                if (!cl.readBurst.allowRequest(rateLimits.readBurst, rateLimits.rateLimitWindowMs)) {
                    addAbuseScore(cl, sessionId, toolName, 1, "read burst limit exceeded");
                    return "read tool burst limit exceeded for tool: " + toolName;
                }
                if (!cl.readSustained.allowRequest(rateLimits.readSustained, rateLimits.rateLimitSustainedWindowMs)) {
                    addAbuseScore(cl, sessionId, toolName, 1, "read sustained limit exceeded");
                    return "read tool sustained limit exceeded for tool: " + toolName;
                }
                break;
        }
        return null;
    }

    private String checkDestructiveCap(CategoryRateLimitState cl, String toolName,
                                       long now, String sessionId) {
        long elapsed;
        switch (toolName) {
            case "shutdown":
                if (cl.shutdownCount.get() >= rateLimits.shutdownCap) {
                    addAbuseScore(cl, sessionId, toolName, 5, "shutdown cap exceeded");
                    return "shutdown tool lifetime cap exceeded for session";
                }
                elapsed = now - cl.shutdownLastMs;
                if (cl.shutdownLastMs > 0 && elapsed < rateLimits.shutdownCooldownMs) {
                    long remaining = (rateLimits.shutdownCooldownMs - elapsed) / 1000;
                    return "shutdown tool on cool-down, retry in " + remaining + "s";
                }
                cl.shutdownCount.incrementAndGet();
                cl.shutdownLastMs = now;
                break;
            case "delete_action":
            case "delete_prompt":
                if (cl.deleteCount.get() >= rateLimits.deleteCap) {
                    addAbuseScore(cl, sessionId, toolName, 3, "delete cap exceeded");
                    return toolName + " lifetime cap exceeded for session";
                }
                elapsed = now - cl.deleteLastMs;
                if (cl.deleteLastMs > 0 && elapsed < rateLimits.deleteCooldownMs) {
                    long remaining = (rateLimits.deleteCooldownMs - elapsed) / 1000;
                    return toolName + " on cool-down, retry in " + remaining + "s";
                }
                cl.deleteCount.incrementAndGet();
                cl.deleteLastMs = now;
                break;
            case "upload_file":
                if (cl.uploadCount.get() >= rateLimits.uploadCap) {
                    addAbuseScore(cl, sessionId, toolName, 2, "upload cap exceeded");
                    return "upload_file lifetime cap exceeded for session";
                }
                elapsed = now - cl.uploadLastMs;
                if (cl.uploadLastMs > 0 && elapsed < rateLimits.uploadCooldownMs) {
                    long remaining = (rateLimits.uploadCooldownMs - elapsed) / 1000;
                    return "upload_file on cool-down, retry in " + remaining + "s";
                }
                cl.uploadCount.incrementAndGet();
                cl.uploadLastMs = now;
                break;
        }
        return null;
    }

    private void addAbuseScore(CategoryRateLimitState cl, String sessionId,
                               String toolName, int weight, String reason) {
        int score = cl.abuseScore.addAndGet(weight);
        String level = score >= rateLimits.abuseScoreBlockThreshold ? "SEVERE"
                : score >= rateLimits.abuseScoreBlockThreshold / 2 ? "CRITICAL"
                : "WARN";
        LOGGER.warning("[" + level + "] session=" + sessionId
                + " tool=" + toolName + " abuseScore=" + score + " reason=" + reason);
        if (score >= rateLimits.abuseScoreBlockThreshold) {
            cl.blocked = true;
            LOGGER.severe("[SEVERE] Session blocked for abuse: " + sessionId);
        }
    }

    /**
     * Increment the in-flight concurrent counter for a session.
     * Must pair with {@link #decrementConcurrentCount} in a finally block.
     */
    void incrementConcurrentCount(String sessionId, List<String> requiredScopes) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;
        switch (toolCategory(requiredScopes)) {
            case CATEGORY_ADMIN:
                state.categoryLimits.adminConcurrent.incrementAndGet();
                break;
            case CATEGORY_WRITE:
                state.categoryLimits.writeConcurrent.incrementAndGet();
                break;
            default:
                state.categoryLimits.readConcurrent.incrementAndGet();
                break;
        }
    }

    /**
     * Decrement the in-flight concurrent counter for a session.
     */
    void decrementConcurrentCount(String sessionId, List<String> requiredScopes) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;
        switch (toolCategory(requiredScopes)) {
            case CATEGORY_ADMIN:
                state.categoryLimits.adminConcurrent.decrementAndGet();
                break;
            case CATEGORY_WRITE:
                state.categoryLimits.writeConcurrent.decrementAndGet();
                break;
            default:
                state.categoryLimits.readConcurrent.decrementAndGet();
                break;
        }
    }

    // ==================== Initialize ====================

    private Map<String, Object> handleInitialize(Map<String, Object> params,
                                                 String newSessionId, String clientIp) {
        String ownerId = null;
        if (params != null && params.get("ownerId") instanceof String) {
            ownerId = (String) params.get("ownerId");
        }
        SessionState state = handleSessionCreate(newSessionId, clientIp, ownerId);

        LOGGER.info("Initialized session: " + newSessionId
                + (state.ownerId != null ? " owner=" + state.ownerId : ""));

        String negotiatedVersion = config.protocolVersion;
        if (params != null) {
            String requestedVersion = (String) params.get("protocolVersion");
            if (requestedVersion != null && supportsProtocolVersion(requestedVersion)) {
                negotiatedVersion = requestedVersion;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", negotiatedVersion);

        Map<String, Object> capabilities = new LinkedHashMap<>();

        if (config.tools) {
            Map<String, Object> toolsCap = new LinkedHashMap<>();
            toolsCap.put("listChanged", true);
            capabilities.put("tools", toolsCap);
        }

        if (config.resources) {
            Map<String, Object> resourcesCap = new LinkedHashMap<>();
            resourcesCap.put("listChanged", true);
            if (config.resourceSubscriptions) resourcesCap.put("subscribe", true);
            capabilities.put("resources", resourcesCap);
        }

        if (config.prompts) {
            Map<String, Object> promptsCap = new LinkedHashMap<>();
            promptsCap.put("listChanged", true);
            capabilities.put("prompts", promptsCap);
        }

        if (config.completions) capabilities.put("completions", new LinkedHashMap<String, Object>());
        if (config.logging) capabilities.put("logging", new LinkedHashMap<String, Object>());
        if (config.tasks) capabilities.put("tasks", new LinkedHashMap<String, Object>());
        if (!config.experimental.isEmpty()) capabilities.put("experimental", config.experimental);

        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", config.serverName);
        serverInfo.put("version", config.serverVersion);
        result.put("serverInfo", serverInfo);

        return result;
    }

    private Map<String, Object> handleTasksGet(Map<String, Object> params) {
        return findTask(params).toMap();
    }

    private Map<String, Object> handleTasksResult(Map<String, Object> params) {
        McpTask task = findTask(params);
        if (task.getStatus() == McpTask.Status.WORKING)
            throw new McpErrorException(-32001, "Task is not complete: " + task.getTaskId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getTaskId());
        if (task.getStatus() == McpTask.Status.FAILED || task.getStatus() == McpTask.Status.CANCELLED)
            out.put("error", task.getError());
        else if (task.getResult() != null) out.put("result", task.getResult());
        return out;
    }

    private Map<String, Object> handleTasksCancel(Map<String, Object> params, String sessionId, Object requestId) {
        McpTask task = findTask(params);
        if (task.getStatus() != McpTask.Status.WORKING)
            throw new McpErrorException(-32002, "Task is already terminal: " + task.getStatus().name().toLowerCase());
        registry.cancelRequest(sessionId, requestId == null ? null : requestId.toString());
        Map<String, Object> result = registry.cancelTask(task.getTaskId()).toMap();
        // Emit server-initiated notifications/cancelled to client
        Map<String, Object> notifyParams = new LinkedHashMap<>();
        notifyParams.put("requestId", requestId);
        notifyParams.put("reason", "Task cancelled by server");
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.enqueueEvent(mapper.toJson(mapAsRpcNotification("notifications/cancelled", notifyParams)));
        }
        return result;
    }

    /**
     * Creates a task for deferred execution.  The returned task is queued and a
     * {@code task} notification is emitted so the client can begin tracking progress.
     *
     * @param params must contain "name" (String) and optionally "input" (Map) and
     *               "inputSchema" (Map — JSON Schema for the input)
     * @param sessionId session to emit the notification to
     * @param requestId request id for the task token
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleTasksCreate(Map<String, Object> params, String sessionId, Object requestId) {
        if (params == null) throw new McpErrorException(-32602, "Invalid params: params required");
        String name = params.get("name") instanceof String ? (String) params.get("name") : null;
        if (name == null || name.trim().isEmpty())
            throw new McpErrorException(-32602, "Invalid params: name is required");
        Map<String, Object> input = params.get("input") instanceof Map
                ? (Map<String, Object>) params.get("input") : new LinkedHashMap<>();
        Map<String, Object> inputSchema = params.get("inputSchema") instanceof Map
                ? (Map<String, Object>) params.get("inputSchema") : null;
        McpTask task = registry.createTask(name, sessionId, requestId, input, inputSchema);
        // Emit task notification so client knows the task token
        Map<String, Object> notifParams = new LinkedHashMap<>();
        notifParams.put("task", task.toMap());
        notifParams.put("token", task.getTaskId());
        sessions.get(sessionId).enqueueEvent(mapper.toJson(mapAsRpcNotification("tasks/task", notifParams)));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("task", task.toMap());
        resp.put("token", task.getTaskId());
        return resp;
    }

    private McpTask findTask(Map<String, Object> params) {
        if (params == null || !(params.get("taskId") instanceof String) || ((String) params.get("taskId")).trim().isEmpty())
            throw new McpErrorException(-32602, "Invalid params: taskId is required");
        McpTask t = registry.getTask((String) params.get("taskId"));
        if (t == null) throw new McpErrorException(-32602, "Unknown task: " + params.get("taskId"));
        return t;
    }

    private Map<String, Object> handleCompletion(Map<String, Object> params) {
        if (params == null || !(params.get("ref") instanceof Map) || !(params.get("argument") instanceof Map))
            throw new McpErrorException(-32602, "Invalid params: ref and argument are required");
        Map<?, ?> rawRef = (Map<?, ?>) params.get("ref");
        Map<?, ?> rawArgument = (Map<?, ?>) params.get("argument");
        Map<String, Object> ref = stringObjectMap(rawRef, "ref");
        Map<String, Object> argument = stringObjectMap(rawArgument, "argument");
        Object type = ref.get("type");
        McpCompletionProvider provider = registry.getCompletionProvider(type instanceof String ? (String) type : null);
        if (provider == null) throw new McpErrorException(-32602, "No completion provider for reference type: " + type);
        Map<String, Object> result = provider.complete(ref, argument);
        return result == null ? new LinkedHashMap<>() : result;
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> source, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String))
                throw new McpErrorException(-32602, "Invalid params: " + field + " keys must be strings");
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Map<String, Object> handleSetLogLevel(Map<String, Object> params) {
        if (params == null || !(params.get("level") instanceof String))
            throw new McpErrorException(-32602, "Invalid params: level is required");
        try {
            Level level = Level.parse((String) params.get("level"));
            LOGGER.setLevel(level);
            return new LinkedHashMap<>();
        } catch (IllegalArgumentException e) {
            throw new McpErrorException(-32602, "Invalid log level: " + params.get("level"));
        }
    }

    // ==================== Tools ====================

    private Map<String, Object> handleToolsList(Map<String, Object> params) {
        return paginate("tools", registry.getRegisteredTools(), params);
    }

    private Map<String, Object> paginate(String key, List<Map<String, Object>> definitions,
                                         Map<String, Object> params) {
        int offset = decodeCursor(params == null ? null : params.get("cursor"), definitions.size());
        if (offset > definitions.size()) offset = definitions.size();
        long rawEnd = (long) offset + (long) config.pageSize;
        int end = (int) Math.min(rawEnd, (long) definitions.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, new ArrayList<>(definitions.subList(offset, end)));
        if (end < definitions.size()) result.put("nextCursor", encodeCursor(end));
        return result;
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(Object cursor, int size) {
        if (cursor == null) return 0;
        if (!(cursor instanceof String) || ((String) cursor).isEmpty()) {
            throw new McpErrorException(-32602, "Invalid params: cursor is invalid");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode((String) cursor), StandardCharsets.UTF_8);
            if (!decoded.matches("[0-9]+")) throw new IllegalArgumentException();
            long offset = Long.parseLong(decoded);
            if (offset < 0 || offset >= size) throw new IllegalArgumentException();
            return (int) offset;
        } catch (Exception e) {
            throw new McpErrorException(-32602, "Invalid params: cursor is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params, String sessionId, Object requestId) {
        if (params == null) throw new McpErrorException(-32602, "Invalid params: missing params");

        String name = requireName(params, "tool");
        Map<String, Object> arguments = optionalArguments(params);
        Object progressToken = params.get("_meta") instanceof Map
                ? ((Map<String, Object>) params.get("_meta")).get("progressToken")
                : null;

        McpToolHandler handler = registry.getToolHandler(name);
        if (handler == null) throw new McpErrorException(-32602, "Unknown tool: " + name);

        // Lookup tool definition for schema and scopes
        Map<String, Object> definition = registry.getToolDefinition(name);
        List<String> requiredScopes = null;
        if (definition != null) {
            // Schema validation — check required params
            Map<String, Object> inputSchema = (Map<String, Object>) definition.get("inputSchema");
            if (inputSchema != null) {
                List<String> requiredParams = (List<String>) inputSchema.get("required");
                if (requiredParams != null && !requiredParams.isEmpty()) {
                    List<String> missing = new ArrayList<>();
                    for (String required : requiredParams) {
                        if (!arguments.containsKey(required) || arguments.get(required) == null) {
                            missing.add(required);
                        }
                    }
                    if (!missing.isEmpty()) {
                        return errorToolResult("Missing required parameter(s): " + missing);
                    }
                }
            }
            requiredScopes = (List<String>) definition.get("requiredScopes");
        }

        // Rate limit check
        String rateLimitError = checkToolRateLimit(sessionId, name, requiredScopes);
        if (rateLimitError != null) {
            return errorToolResult(rateLimitError);
        }

        // Authorization check
        if (authorization != null) {
            String[] scopes = requiredScopes == null ? new String[0]
                    : requiredScopes.toArray(new String[0]);
            String denial = authorization.denial(
                    scopes,
                    definition != null && Boolean.TRUE.equals(definition.get("confirmationRequired")),
                    arguments);
            if (denial != null) {
                return errorToolResult("Authorization denied: " + denial);
            }
        }

        try {
            if (progressToken != null) {
                notifyToolProgress(sessionId, progressToken, 0d, 1d, "Tool " + name + " started");
            }
            Map<String, Object> result = handler.call(arguments);
            if (progressToken != null) {
                notifyToolProgress(sessionId, progressToken, 1d, 1d, "Tool " + name + " completed");
            }
            return result;
        } catch (McpErrorException e) {
            throw e;
        } catch (Throwable t) {
            return handleHandlerException("Tool", name, t);
        }
    }

    /**
     * Handles {@code notifications/cancelled} by recording the cancellation flag
     * so subsequent in-flight tool invocations on the same session can observe it.
     * Does not abort running tool code; tool authors must cooperatively poll
     * {@link McpRegistry#isCancelled(String, String)} from long-running logic.
     */
    private void handleNotificationCancelled(String sessionId, Map<String, Object> params) {
        if (sessionId == null || params == null) return;
        Object requestId = params.get("requestId");
        if (requestId == null) requestId = params.get("request_id");
        String idString = requestId == null ? null : requestId.toString();
        if (idString != null) registry.cancelRequest(sessionId, idString);
        Object reason = params.get("reason");
        applicationLogger.info("Cancellation received for session=" + sessionId
                + " requestId=" + idString + " reason=" + reason);
    }

    /**
     * Emits a {@code notifications/progress} message to the session's SSE queue.
     *
     * @param sessionId     target session
     * @param progressToken client-supplied progress token echoed in params
     * @param current       progress value so far
     * @param total         expected total progress
     * @param message       optional human-readable message
     */
    public void notifyToolProgress(String sessionId, Object progressToken,
                                   double current, double total, String message) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("progressToken", progressToken);
        params.put("progress", current);
        params.put("total", total);
        if (message != null) params.put("message", message);
        String body = mapper.toJson(mapAsRpcNotification("notifications/progress", params));
        state.enqueueEvent(body);
    }

    /**
     * Log and handle a handler-level exception, returning the appropriate error result.
     * Separates tool exceptions (return error result) from resource exceptions (throw).
     *
     * @param kind        identifier kind label for logging
     * @param identifier  resource/tool name or URI
     * @param t           the caught exception
     * @return error result for tool handlers; throws McpErrorException for resources
     */
    private Map<String, Object> handleHandlerException(String kind, String identifier, Throwable t) {
        LOGGER.log(Level.SEVERE, kind + " error (" + identifier + "): " + t.getMessage(), t);
        if (t instanceof Error) {
            throw (Error) t;
        }
        Exception e = (Exception) t;
        if ("Tool".equals(kind)) {
            return errorToolResult("Error calling " + identifier + ": " + e.getMessage());
        }
        throw new McpErrorException(-32603, "Error reading " + identifier + ": " + e.getMessage());
    }

    private String requireName(Map<String, Object> params, String kind) {
        Object value = params.get("name");
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new McpErrorException(-32602,
                    "Invalid params: " + kind + " name must be a non-empty string");
        }
        return (String) value;
    }

    private Map<String, Object> optionalArguments(Map<String, Object> params) {
        Object value = params.get("arguments");
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map)) {
            throw new McpErrorException(-32602, "Invalid params: arguments must be an object");
        }
        return stringObjectMap((Map<?, ?>) value, "arguments");
    }

    private Map<String, Object> resourceContents(String uri, String text) {
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("uri", uri);
        contentItem.put("mimeType", mimeTypeForUri(uri));
        contentItem.put("text", text);
        return wrapContents(contentItem);
    }

    /**
     * Wraps a blob resource result as MCP blob content shape:
     * {@code {{"uri":"...","mimeType":"...","blob":"<base64>"}}}.
     */
    private Map<String, Object> blobContents(String uri, McpBlobContent blob) {
        return wrapContents(blob);
    }

    /** Wraps a single content item as the MCP {@code contents} envelope. */
    private static Map<String, Object> wrapContents(Map<String, Object> contentItem) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        contents.add(contentItem);
        result.put("contents", contents);
        return result;
    }

    // ==================== Resources ====================

    private Map<String, Object> handleResourcesList(Map<String, Object> params) {
        return paginate("resources", registry.getRegisteredResources(), params);
    }

    private Map<String, Object> handleResourcesRead(Map<String, Object> params) {
        if (params == null) throw new McpErrorException(-32602, "Invalid params: missing params");
        Object uriValue = params.get("uri");
        if (!(uriValue instanceof String) || ((String) uriValue).trim().isEmpty()) {
            throw new McpErrorException(-32602, "Invalid params: uri must be a non-empty string");
        }
        String uri = (String) uriValue;

        McpResourceHandler handler = registry.getResourceHandler(uri);
        if (handler == null) handler = findTemplateHandler(uri);
        if (handler == null) throw new McpErrorException(-32602, "Unknown resource: " + uri);

        try {
            if (handler instanceof McpBlobResourceHandler) {
                return blobContents(uri, ((McpBlobResourceHandler) handler).readBlob(uri));
            }
            return resourceContents(uri, handler.read(uri));
        } catch (McpErrorException e) {
            throw e;
        } catch (Throwable t) {
            return handleHandlerException("Resource", uri, t);
        }
    }

    // ==================== Resource Templates ====================

    private Map<String, Object> handleResourceTemplatesList(Map<String, Object> params) {
        return paginate("resourceTemplates", registry.getRegisteredResourceTemplates(), params);
    }

    private Map<String, Object> handleResourceTemplatesGet(Map<String, Object> params) {
        if (params == null) return errorResourceResult("Missing params");
        String uri = (String) params.get("uri");
        if (uri == null) return errorResourceResult("Missing resource URI");

        McpResourceHandler handler = registry.getResourceHandler(uri);
        if (handler == null) handler = findTemplateHandler(uri);
        if (handler == null) return errorResourceResult("Unknown resource template URI: " + uri);

        try {
            if (handler instanceof McpBlobResourceHandler) {
                return blobContents(uri, ((McpBlobResourceHandler) handler).readBlob(uri));
            }
            return resourceContents(uri, handler.read(uri));
        } catch (Throwable t) {
            return handleHandlerException("Resource", uri, t);
        }
    }

    private McpResourceHandler findTemplateHandler(String uri) {
        if (uri == null) return null;
        for (Map.Entry<String, McpResourceHandler> entry : registry.getResourceTemplateHandlers().entrySet()) {
            if (templateMatches(entry.getKey(), uri)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, McpBlobResourceHandler> entry : registry.getBlobResourceTemplateHandlers().entrySet()) {
            if (templateMatches(entry.getKey(), uri)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String mimeTypeForUri(String uri) {
        String mimeType = registry.getResourceMimeType(uri);
        if (mimeType != null) return mimeType;
        if (uri != null) {
            for (String template : registry.getResourceTemplateHandlers().keySet()) {
                if (templateMatches(template, uri)) {
                    String templateMimeType = registry.getResourceTemplateMimeType(template);
                    return templateMimeType == null ? "application/json" : templateMimeType;
                }
            }
        }
        return "application/json";
    }

    /**
     * Match URI templates using exact path boundaries and every placeholder.
     */
    private boolean templateMatches(String template, String uri) {
        if (template == null || uri == null) return false;
        StringBuilder regex = new StringBuilder("^");
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                regex.append(Pattern.quote(template.substring(cursor)));
                break;
            }
            int close = template.indexOf('}', open + 1);
            if (close < 0 || close == open + 1) return false;
            regex.append(Pattern.quote(template.substring(cursor, open)));
            regex.append("[^/]+");
            cursor = close + 1;
        }
        regex.append('$');
        return Pattern.matches(regex.toString(), uri);
    }

    private Map<String, Object> handleResourceSubscribe(String sessionId, Map<String, Object> params) {
        SessionState state = requireSession(sessionId);
        String uri = requireResourceUri(params);
        if (registry.getResourceHandler(uri) == null && findTemplateHandler(uri) == null) {
            return errorResourceResult("Unknown resource URI: " + uri);
        }
        state.subscriptions.add(uri);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> handleResourceUnsubscribe(String sessionId, Map<String, Object> params) {
        SessionState state = requireSession(sessionId);
        state.subscriptions.remove(requireResourceUri(params));
        return new LinkedHashMap<>();
    }

    private SessionState requireSession(String sessionId) {
        if (sessionId == null) throw new McpErrorException(-32602, "Missing session ID");
        SessionState state = sessions.get(sessionId);
        if (state == null) throw new McpErrorException(-32602, "Unknown session: " + sessionId);
        return state;
    }

    private String requireResourceUri(Map<String, Object> params) {
        if (params == null || !(params.get("uri") instanceof String)
                || ((String) params.get("uri")).trim().isEmpty()) {
            throw new McpErrorException(-32602, "Missing resource URI");
        }
        return (String) params.get("uri");
    }

    // ==================== Prompts ====================

    private Map<String, Object> handlePromptsList(Map<String, Object> params) {
        return paginate("prompts", registry.getRegisteredPrompts(), params);
    }

    private Map<String, Object> handlePromptsGet(Map<String, Object> params) {
        if (params == null) return errorPromptResult("Missing params");

        String name = requireName(params, "prompt");
        Map<String, Object> arguments = optionalArguments(params);

        McpPromptHandler handler = registry.getPromptHandler(name);
        if (handler == null) return errorPromptResult("Unknown prompt: " + name);

        try {
            return handler.get(arguments);
        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "Prompt get error: " + name, t);
            if (t instanceof Error) throw (Error) t;
            return errorPromptResult("Error getting prompt " + name + ": " + t.getMessage());
        }
    }

    // ==================== Registration (called by Managers) ====================

    /**
     * registers an externally managed task with the server registry.
     *
     * @param task task to register
     */
    public void registerTask(McpTask task) {
        registry.registerTask(task);
    }

    /**
     * Creates a bounded server task in working state.
     *
     * @return newly created task
     */
    public McpTask createTask() {
        return registry.createTask();
    }

    /**
     * Completes a task created through this handler.
     *
     * @param taskId task identifier
     * @param result task result value
     * @return updated task
     */
    @SuppressWarnings("unused, UnusedReturnValue")
    public McpTask completeTask(String taskId, Object result) {
        return registry.completeTask(taskId, result);
    }

    /**
     * Fails a task created through this handler.
     *
     * @param taskId task identifier
     * @param error  failure description
     * @return updated task
     */
    @SuppressWarnings("unused, UnusedReturnValue")
    public McpTask failTask(String taskId, String error) {
        return registry.failTask(taskId, error);
    }

    /**
     * Register a tool schema and handler.
     * Called by ToolManager.registerToolDefinitions() during initialization.
     */
    public void registerTool(String name, String description, Map<String, Object> inputSchema,
                             List<String> required, McpToolHandler handler) {
        registry.registerTool(name, description, inputSchema, required, handler);
    }

    @Override
    public void registerTool(String name, String description, Map<String, Object> inputSchema,
                             List<String> required, Map<String, Object> outputSchema,
                             McpToolHandler handler) {
        registry.registerTool(name, description, inputSchema, required, outputSchema, handler);
    }

    /**
     * Register a resource schema and handler using the default JSON MIME type.
     */
    @Override
    public void registerResource(String uri, String name, String description, McpResourceHandler handler) {
        registerResource(uri, name, description, "application/json", handler);
    }

    /**
     * Register a resource schema, MIME type, and handler.
     */
    @Override
    public void registerResource(String uri, String name, String description, String mimeType,
                                 McpResourceHandler handler) {
        registry.registerResource(uri, name, description, mimeType, handler);
    }

    /**
     * Register a URI template using the default JSON MIME type.
     */
    @Override
    public void registerResourceTemplate(String uriTemplate, String name, String description,
                                         McpResourceHandler handler) {
        registerResourceTemplate(uriTemplate, name, description, "application/json", handler);
    }

    /**
     * Register a URI template, MIME type, and dynamic resource handler.
     */
    @Override
    public void registerResourceTemplate(String uriTemplate, String name, String description,
                                         String mimeType, McpResourceHandler handler) {
        registry.registerResourceTemplate(uriTemplate, name, description, mimeType, handler);
    }

    /**
     * Queues a list-changed notification for every active session.
     */
    @Override
    public void onRegistryChanged(String listType) {
        String method = "notifications/" + listType + "/list_changed";
        for (SessionState state : sessions.values()) {
            state.enqueueEvent(mapper.toJson(mapAsRpcNotification(method, null)));
        }
    }

    /**
     * Queue a resource update for every session subscribed to the URI.
     * Uses bounded queue with overflow handling.
     */
    public void notifyResourceUpdated(String uri) {
        if (uri == null) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);
        String body = mapper.toJson(mapAsRpcNotification("notifications/resources/updated", params));
        for (SessionState state : sessions.values()) {
            if (state.subscriptions.contains(uri)) {
                enqueue(state, body);
            }
        }
    }

    /**
     * Enqueue notification with bounded capacity.
     * Synchronized to prevent race between size check and offer.
     */
    private void enqueue(SessionState state, String notification) {
        synchronized (state.pendingNotifications) {
            if (state.pendingNotifications.size() >= rateLimits.maxPendingNotificationsPerSession) {
                if (overflowListener != null) {
                    overflowListener.onOverflow(state.sessionId);
                    return;
                }
                throw new QueueOverflowException(state.sessionId);
            }
            state.pendingNotifications.offer(notification);
        }
    }

    /**
     * Returns next queued resource notification as formatted SSE line, or null when queue is empty.
     * @param sessionId session identifier
     * @return next resource notification as SSE-formatted string, or null
     */
    public String pollResourceNotification(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;
        String notification = state.pendingNotifications.poll();
        if (notification == null) return null;
        return "event: message\ndata: " + notification + "\n\n";
    }

    /**
     * Returns next queued SSE event as raw body, or null when queue is empty.
     * Backward-compatible alias for the original pollPendingNotification.
     * @param sessionId session identifier
     * @return next SSE event body, or null if none available
     */
    public String pollPendingNotification(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;
        SseEvent event = state.pendingEvents.poll();
        if (event == null) return null;
        return event.body;
    }

    /**
     * Queues a server logging notification for every active session.
     * @param level protocol logging level
     * @param logger optional logger name
     * @param data notification payload
     */
    public void notifyLogMessage(String level, String logger, Object data) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("level", level);
        if (logger != null) params.put("logger", logger);
        params.put("data", data);
        String body = mapper.toJson(mapAsRpcNotification("notifications/message", params));
        for (SessionState state : sessions.values()) state.enqueueEvent(body);
    }

    /**
     * Returns next queued event as a formatted SSE line with event ID.
     *
     * @param sessionId session identifier
     * @return formatted SSE line (e.g. {@code id:42<newline>data:{...}<newline><newline>}), or null when none is available
     */
    public String pollSseEvent(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return null;
        SseEvent event = state.pendingEvents.poll();
        if (event == null) return null;
        return "id: " + event.id + "\nevent: message\ndata: " + event.body + "\n\n";
    }

    /**
     * Returns all queued events with IDs strictly greater than {@code afterEventId}.
     * Used for SSE replay when a client reconnects with {@code Last-Event-ID}.
     *
     * @param sessionId    session identifier
     * @param afterEventId return events with ID {@literal >} afterEventId
     * @return concatenated SSE blocks for all missed events, or empty string if none
     */
    public String getMissedEvents(String sessionId, long afterEventId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return "";
        StringBuilder sb = new StringBuilder();
        SseEvent firstEvent = state.pendingEvents.peek();
        if (firstEvent != null && firstEvent.id > afterEventId + 1) {
            long gapFrom = afterEventId + 1;
            long gapTo = firstEvent.id - 1;
            sb.append("event: gap\ndata: {\"from\":").append(gapFrom)
              .append(",\"to\":").append(gapTo).append("}\n\n");
        }
        for (SseEvent event : state.pendingEvents) {
            if (event.id > afterEventId) {
                sb.append("id: ").append(event.id)
                        .append("\nevent: message\ndata: ")
                        .append(escapeSseData(event.body))
                        .append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String escapeSseData(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Registers a completion provider by reference type.
     */
    @Override
    public void registerCompletionProvider(String referenceType, McpCompletionProvider provider) {
        registry.registerCompletionProvider(referenceType, provider);
    }

    /**
     * Register a prompt schema and handler.
     * Called by PromptManager.registerPromptDefinitions() during initialization.
     */
    public void registerPrompt(String name, String description, List<Map<String, Object>> arguments,
                               McpPromptHandler handler) {
        registry.registerPrompt(name, description, arguments, handler);
    }

    // ==================== JSON-RPC Response Helpers ====================

    private Map<String, Object> mapAsRpcNotification(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", JSONRPC_VERSION);
        message.put("method", method);
        if (params != null) message.put("params", params);
        return message;
    }

    private String capabilityError(Object id, String capability) {
        return errorResponse(id, -32601, "MCP capability is disabled: " + capability);
    }

    private String successResponse(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", JSONRPC_VERSION);
        resp.put("id", id);
        resp.put("result", result);
        return mapper.toJson(resp);
    }

    private String errorResponse(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", JSONRPC_VERSION);
        resp.put("id", id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "Unknown MCP error" : message);
        resp.put("error", error);
        try {
            return mapper.toJson(resp);
        } catch (Exception ignored) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal server error\"}}";
        }
    }

    private Map<String, Object> errorToolResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> content = new ArrayList<>();
        Map<String, String> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", "Error: " + message);
        content.add(textContent);
        result.put("content", content);
        result.put("isError", true);
        return result;
    }

    private Map<String, Object> errorResourceResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        Map<String, Object> contentItem = new LinkedHashMap<>();
        contentItem.put("uri", "error");
        contentItem.put("mimeType", "application/json");

        Map<String, String> errorPayload = new LinkedHashMap<>();
        errorPayload.put("error", message == null ? "Unknown resource error" : message);
        try {
            contentItem.put("text", mapper.toJson(errorPayload));
        } catch (Exception ignored) {
            contentItem.put("text", "{\"error\":\"Unable to serialize resource error\"}");
        }
        contents.add(contentItem);
        result.put("contents", contents);
        return result;
    }

    private Map<String, Object> errorPromptResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "Error: " + message);
        result.put("messages", new ArrayList<>());
        return result;
    }
}
