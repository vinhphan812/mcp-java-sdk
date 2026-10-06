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
import io.github.vinhphan812.mcp.api.config.McpSecurityDefaults;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.config.RateLimits;
import io.github.vinhphan812.mcp.api.dto.ElicitAction;
import io.github.vinhphan812.mcp.api.dto.ElicitRequest;
import io.github.vinhphan812.mcp.api.dto.ElicitationResult;
import io.github.vinhphan812.mcp.api.dto.ListenRequest;
import io.github.vinhphan812.mcp.api.dto.McpBlobContent;
import io.github.vinhphan812.mcp.api.dto.McpTask;
import io.github.vinhphan812.mcp.api.dto.Mcp2026RequestContext;
import io.github.vinhphan812.mcp.api.handler.*;
import io.github.vinhphan812.mcp.api.logging.McpLogger;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.api.spi.McpRegistrar;
import io.github.vinhphan812.mcp.api.spi.McpRegistryChangeListener;
import io.github.vinhphan812.mcp.api.spi.McpTaskExtension;
import io.github.vinhphan812.mcp.api.utils.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static io.github.vinhphan812.mcp.api.spi.McpAuthorization.*;

/**
 * Lightweight MCP protocol handler that implements JSON-RPC 2.0 for MCP
 * without depending on the MCP SDK's server-side code (which uses Stream.toList(),
 * incompatible with Android API 22).
 * <p>
 * This handler replaces McpAsyncServer/McpSyncServer while keeping the schema types.
 * Definitions are supplied by an application-owned {@link McpRegistry}; the
 * protocol layer does not construct project-specific tools or resources.
 * <p>
 * <p>Version strategy: the default {@code 2025-11-25} mode keeps session-scoped
 * HTTP behavior. Configure {@code McpServerConfig.ProtocolMode.STATELESS} (or
 * select {@code 2026-07-28} per request) to use self-describing requests without
 * initialization or {@code Mcp-Session-Id}; unsupported versions are rejected.
 * Stateless mode intentionally omits session-only subscriptions.
 */
public class McpProtocolHandler implements McpRegistrar, McpRegistryChangeListener {

    private static final String SERVER_NAME = "mcp-java-sdk";
    // JSON-RPC version and server version are in McpJsonRpc.
    private static final Logger LOGGER = Logger.getLogger(McpProtocolHandler.class.getName());

    private final Gson mapper;
    private final McpRegistry registry;
    private final McpServerConfig config;
    private final McpLogger applicationLogger;
    private final RateLimits rateLimits;
    private final CategoryRateLimitController categoryRateLimitController;
    private final McpTaskExtension tasksExtension;
    private final McpTaskExtension.TaskRegistry taskRegistry;

    public McpServerConfig getConfig() {
        return config;
    }

    // ==================== Server-Initiated Request Infrastructure (ADR-0022) ====================
    // Tracks in-flight server-initiated requests (e.g. elicitation/create) by their
    // server-generated UUID token for correlation with client responses.

    private final ConcurrentHashMap<String, ServerInitiatedRequest> serverInitiatedRequests =
            new ConcurrentHashMap<>();

    /**
     * Single-threaded daemon executor for scheduling server-initiated request timeouts.
     * Lazily started on first use; shut down during {@link #shutdown()}.
     */
    private ScheduledExecutorService scheduledExecutor;

    private final Object executorLock = new Object();

    private ScheduledExecutorService getScheduledExecutor() {
        if (scheduledExecutor == null) {
            synchronized (executorLock) {
                if (scheduledExecutor == null) {
                    scheduledExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "mcp-server-request-timeout");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return scheduledExecutor;
    }

    // Server-initiated request transport; SSE implementation is the default.
    private volatile ServerRequestTransport serverRequestTransport;

    /**
     * Sets the pluggable transport for server-initiated requests.
     * Defaults to {@link SseServerRequestTransport} on first use.
     *
     * @param transport the transport, or null to use the SSE default
     */
    public void setServerRequestTransport(ServerRequestTransport transport) {
        this.serverRequestTransport = transport != null ? transport
                : new SseServerRequestTransport(this);
    }

    private ServerRequestTransport getServerRequestTransport() {
        ServerRequestTransport t = this.serverRequestTransport;
        if (t == null) {
            t = new SseServerRequestTransport(this);
            this.serverRequestTransport = t;
        }
        return t;
    }

    // ==================== Security (ADR-0011) ====================
    // Rate-limit and security defaults are defined in McpSecurityDefaults.
    // Runtime values come from RateLimits (injected via McpServerConfig).
    // McpProtocolHandler does NOT declare security defaults — it consumes them.

    private final McpAuthorization authorization;
    private final QueueOverflowListener overflowListener;
    private final int maxQueuedEvents;
    private final SubscriptionManager listenerSubscriptions;

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
        private static int maxQueuedEvents = McpSecurityDefaults.MAX_QUEUED_EVENTS;

        static void setMaxQueuedEvents(int limit) {
            maxQueuedEvents = limit;
        }

        final String sessionId;
        final long createdAt;
        volatile long lastActivity;
        final String clientIp;   // Client IP for rate limiting
        final String ownerId;    // Stable owner identity
        /** Protocol version negotiated for this session (never null after init). */
        volatile String negotiatedVersion;
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        final ConcurrentLinkedQueue<SseEvent> pendingEvents = new ConcurrentLinkedQueue<>();
        final AtomicLong nextEventId = new AtomicLong(1L);
        /** Bounded notification queue for overflow tracking. */
        final ConcurrentLinkedQueue<String> pendingNotifications = new ConcurrentLinkedQueue<>();

        SessionState(String sessionId, String clientIp, String ownerId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.lastActivity = this.createdAt;
            this.clientIp = clientIp;
            this.ownerId = ownerId;
            this.negotiatedVersion = null;
        }

        void enqueueEvent(String body) {
            synchronized (this) {
                if (pendingEvents.size() >= maxQueuedEvents) pendingEvents.poll();
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
    // McpErrorException was moved to io.github.vinhphan812.mcp.api.utils.McpException

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
        this.mapper = McpGson.get();
        this.registry = registry;
        this.config = config;
        this.applicationLogger = config.logger;
        this.rateLimits = config.rateLimits == null ? RateLimits.defaults() : config.rateLimits;
        this.categoryRateLimitController = new CategoryRateLimitController(this.rateLimits, LOGGER);
        this.overflowListener = overflowListener;
        this.maxQueuedEvents = config.maxQueuedEvents;
        SessionState.setMaxQueuedEvents(config.maxQueuedEvents);
        this.authorization = authorization;
        this.tasksExtension = config.tasksExtension;
        this.taskRegistry = new TaskRegistryImpl(registry);
        if (this.tasksExtension != null) {
            this.tasksExtension.register(this.taskRegistry);
        }
        this.listenerSubscriptions = new SubscriptionManager(config.maxListenerBufferSize);
        registry.setNotificationTarget(this);
        registry.addRegistryChangeListener(this);
        startCleanupThread();
    }

    private static McpServerConfig defaultConfig() {
        return McpServerConfig.builder()
                .serverName(SERVER_NAME)
                .serverVersion(McpJsonRpc.SERVER_VERSION)
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
                            categoryRateLimitController.removeSession(entry.getKey());
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
        listenerSubscriptions.shutdown();
        ipRateLimits.clear();
        sessionRateLimits.clear();
        // ADR-0022 §4: complete all in-flight server-initiated requests with shutdown error.
        for (ServerInitiatedRequest req : serverInitiatedRequests.values()) {
            req.responseFuture.completeExceptionally(
                    new McpElicitationException(McpErrorCodes.INTERNAL, "Server shutting down"));
        }
        serverInitiatedRequests.clear();
        // Shut down the scheduled executor (reject new tasks, let in-flight run).
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
            scheduledExecutor = null;
        }
    }

    // ==================== Public API ====================

    /**
     * Returns whether server accepts a protocol version during HTTP negotiation.
     *
     * @param version requested protocol version
     * @return true when version is supported
     */
    public boolean supportsProtocolVersion(String version) {
        return version == null || McpJsonRpc.PROTOCOL_VERSION_STATELESS.equals(version)
                || config.protocolVersion.equals(version)
                || McpJsonRpc.PROTOCOL_VERSION_LEGACY.equals(version)
                || McpJsonRpc.PROTOCOL_VERSION.equals(version);
    }

    /** Returns whether this handler is configured for stateless protocol operation. */
    public boolean isStatelessMode() {
        return config.protocolMode == McpServerConfig.ProtocolMode.STATELESS
                || McpJsonRpc.PROTOCOL_VERSION_STATELESS.equals(config.protocolVersion);
    }

    /** Returns whether the request explicitly selects the stateless 2026 mode. */
    private boolean isStatelessProtocol(String requestedVersion) {
        return McpJsonRpc.PROTOCOL_VERSION_STATELESS.equals(requestedVersion)
                || isStatelessMode();
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

    /** Package-visible test seam for abuse score setup. */
    void setSessionCategoryAbuseScore(String sessionId, int score) {
        categoryRateLimitController.setAbuseScore(sessionId, score);
    }

    /** Package-visible test seam for blocked-session setup. */
    void setSessionCategoryBlocked(String sessionId, boolean blocked) {
        categoryRateLimitController.setBlocked(sessionId, blocked);
    }

    int getSessionCategoryReadConcurrent(String sessionId) {
        return categoryRateLimitController.concurrent(sessionId, READ);
    }

    int getSessionCategoryWriteConcurrent(String sessionId) {
        return categoryRateLimitController.concurrent(sessionId, WRITE);
    }

    int getSessionCategoryAdminConcurrent(String sessionId) {
        return categoryRateLimitController.concurrent(sessionId, ADMIN);
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
     * Minimal task-registry bridge used by {@link McpTaskExtension} implementations.
     * Exposes only the lifecycle operations needed by extensions, hiding the full
     * {@link McpRegistry} surface.
     */
    private static final class TaskRegistryImpl implements McpTaskExtension.TaskRegistry {
        private final McpRegistry registry;

        TaskRegistryImpl(McpRegistry registry) {
            this.registry = registry;
        }

        @Override
        public void registerTask(String taskId, String status) {
            if (taskId == null || taskId.trim().isEmpty())
                throw new IllegalArgumentException("taskId cannot be blank");
            if (status == null || status.trim().isEmpty())
                throw new IllegalArgumentException("status cannot be blank");
            long now = System.currentTimeMillis();
            if ("working".equalsIgnoreCase(status)) {
                registry.registerTask(
                        new McpTask(taskId, McpTask.Status.WORKING, now, now, null, null));
            } else if ("completed".equalsIgnoreCase(status)) {
                registry.registerTask(
                        new McpTask(taskId, McpTask.Status.COMPLETED, now, now, "OK", null));
            } else if ("failed".equalsIgnoreCase(status)) {
                registry.registerTask(
                        new McpTask(taskId, McpTask.Status.FAILED, now, now, null, "Extension failure"));
            } else if ("cancelled".equalsIgnoreCase(status)) {
                registry.registerTask(
                        new McpTask(taskId, McpTask.Status.CANCELLED, now, now, null, "Extension cancelled"));
            } else {
                throw new IllegalArgumentException("Unknown status: " + status);
            }
        }

        @Override
        public String getTaskStatus(String taskId) {
            if (taskId == null) return null;
            McpTask t = registry.getTask(taskId);
            return t == null ? null : t.getStatus().name().toLowerCase();
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
        String method;
        List<String> toolScopes;
        String reservedCategory = null;
        try {
            Map<String, Object> request = mapper.fromJson(requestBody, new TypeToken<Map<String, Object>>() {
            }.getType());
            if (request == null || !McpJsonRpc.VERSION.equals(request.get("jsonrpc"))) {
                return new McpResponse(errorResponse(null, McpError.invalidRequestPrefix("jsonrpc must be 2.0")), sessionId);
            }
            Object methodValue = request.get("method");
            method = methodValue instanceof String ? (String) methodValue : null;
            id = request.get("id");
            Object params = request.get("params");
            boolean notification = !request.containsKey("id");
            String requestedProtocolVersion = null;
            Object topLevelVersion = request.get("protocolVersion");
            if (topLevelVersion != null && !(topLevelVersion instanceof String)) {
                return new McpResponse(errorResponse(id, McpError.invalidParamsPrefix("protocolVersion must be a string")), sessionId);
            }
            if (topLevelVersion instanceof String) {
                requestedProtocolVersion = (String) topLevelVersion;
            } else if (params instanceof Map && ((Map<?, ?>) params).get("protocolVersion") != null) {
                Object paramVersion = ((Map<?, ?>) params).get("protocolVersion");
                if (!(paramVersion instanceof String)) {
                    return new McpResponse(errorResponse(id, McpError.invalidParamsPrefix("protocolVersion must be a string")), sessionId);
                }
                requestedProtocolVersion = (String) paramVersion;
            }
            if (requestedProtocolVersion != null && !supportsProtocolVersion(requestedProtocolVersion)) {
                return new McpResponse(errorResponse(id, McpError.invalidParams("Unsupported protocol version: " + requestedProtocolVersion)), sessionId);
            }
            boolean stateless = isStatelessProtocol(requestedProtocolVersion);

            // ── 2026-07-28 wire contract enforcement ──────────────────────────
            // In per-request 2026 stateless mode, initialize MUST be rejected with methodNotFound
            // (the spec requires using server/discover instead). notifications/initialized is silently
            // accepted for forward-compatibility (no session or side-effects).
            if (stateless) {
                if (McpMethodNames.INITIALIZE.equals(method)) {
                    return new McpResponse(errorResponse(id, McpError.methodNotFound(
                            "initialize is not valid in " + McpJsonRpc.PROTOCOL_VERSION_STATELESS
                                    + " mode — use server/discover for capability discovery")),
                            null);
                }
                if (McpMethodNames.NOTIF_INITIALIZED.equals(method)) {
                    return new McpResponse(null, null);
                }
            }
            // ── End 2026 enforcement ─────────────────────────────────────────

            if (method == null) {
                return new McpResponse(errorResponse(id, McpError.invalidRequestPrefix("missing method")), sessionId);
            }

            applicationLogger.debug("Handling MCP request: " + method);

            // Check max concurrent sessions for initialize
            if (McpMethodNames.INITIALIZE.equals(method) && sessions.size() >= rateLimits.maxConcurrentSessions) {
                return new McpResponse(errorResponse(id, McpError.rateLimitExceeded("max concurrent sessions reached")), sessionId);
            }

            if (!stateless && !isSessionOptional(method) && !hasSession(sessionId)) {
                return new McpResponse(errorResponse(id, McpError.of(McpErrorCodes.RESULT_NOT_COMPLETE,
                        "Missing or invalid MCP session")), sessionId);
            }

            // Check rate limits (IP + session)
            String rateLimitError = checkRateLimit(clientIp, sessionId, method);
            if (rateLimitError != null) {
                return new McpResponse(errorResponse(id, McpError.rateLimitExceeded(rateLimitError)), sessionId);
            }

            // Check category rate limits for all methods
            toolScopes = null;
            if (!stateless && !isSessionOptional(method) && hasSession(sessionId)) {
                // Extract tool scopes for tools/call
                if (McpMethodNames.TOOLS_CALL.equals(method) && params instanceof Map) {
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
                String categoryRateLimitError = categoryRateLimitController.checkMethod(sessionId, method, toolScopes);
                if (categoryRateLimitError != null) {
                    return new McpResponse(errorResponse(id, McpError.rateLimitExceeded(categoryRateLimitError)), sessionId);
                }
            }

            // Refresh activity for active sessions
            if (hasSession(sessionId)) {
                refreshActivity(sessionId);
            }

            // Track concurrent requests for all methods
            if (!stateless && !isSessionOptional(method) && hasSession(sessionId)) {
                String cat = categoryRateLimitController.categoryForMethod(method, toolScopes);
                if (McpMethodNames.TOOLS_CALL.equals(method) && toolScopes != null) {
                    cat = categoryRateLimitController.toolCategory(toolScopes);
                }
                if (categoryRateLimitController.reserve(sessionId, cat)) {
                    reservedCategory = cat;
                } else {
                    return new McpResponse(errorResponse(id, McpError.rateLimitExceeded(cat + " concurrent limit exceeded")), sessionId);
                }
            }

            String responseSessionId = sessionId;
            Map<String, Object> result;
            final Object requestId = id;  // effectively-final copy for lambda capture

            // Intentional: each MCP list handler calls a different registry method.
            //noinspection DuplicateBranchesInSwitch
            switch (method) {
                case McpMethodNames.SERVER_DISCOVER:
                    // In stateless 2026 mode, server/discover MUST NOT create or echo a session.
                    // Null sessionId honours the wire contract regardless of incoming session value.
                    responseSessionId = stateless ? null : sessionId;
                    result = handleServerDiscover();
                    break;
                case McpMethodNames.INITIALIZE:
                    Map<String, Object> initializeParams = params instanceof Map
                            ? (Map<String, Object>) params : null;
                    Object requestedVersion = initializeParams == null
                            ? null : initializeParams.get("protocolVersion");
                    if (requestedVersion != null && !(requestedVersion instanceof String)) {
                        return new McpResponse(errorResponse(id, McpError.invalidParamsPrefix("protocolVersion must be a string")), sessionId);
                    }
                    if (requestedVersion != null && !supportsProtocolVersion((String) requestedVersion)) {
                        return new McpResponse(errorResponse(id, McpError.invalidParams("Unsupported protocol version: " + requestedVersion)), sessionId);
                    }
                    String initSessionId = stateless ? null : UUID.randomUUID().toString();
                    result = handleInitialize(initializeParams, initSessionId, clientIp, stateless);
                    responseSessionId = initSessionId;
                    break;
                case McpMethodNames.NOTIF_INITIALIZED:
                case McpMethodNames.NOTIF_MESSAGE:
                    return new McpResponse(null, sessionId);
                case McpMethodNames.TOOLS_LIST:
                    responseSessionId = null;
                    if (!config.tools) {
                        return new McpResponse(capabilityError(id, "tools"), sessionId);
                    }
                    result = handleToolsList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.TOOLS_CALL:
                    responseSessionId = null;
                    if (!config.tools) {
                        return new McpResponse(capabilityError(id, "tools"), sessionId);
                    }
                    result = handleToolsCall(
                            params instanceof Map ? (Map<String, Object>) params : null,
                            sessionId, id, stateless);
                    break;
                case McpMethodNames.RESOURCES_LIST:
                    responseSessionId = null;
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourcesList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.RESOURCES_READ:
                    responseSessionId = null;
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourcesRead(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case "resources/templates/list":
                    responseSessionId = null;
                    if (!config.resources) {
                        return new McpResponse(capabilityError(id, "resources"), sessionId);
                    }
                    result = handleResourceTemplatesList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case "resources/templates/get":
                    return new McpResponse(errorResponse(id, McpError.methodNotFound("Method not found: resources/templates/get — use resources/read with resolved URI")), sessionId);
                case McpMethodNames.RESOURCES_SUBSCRIBE:
                    if (!config.resources || !config.resourceSubscriptions) {
                        return new McpResponse(capabilityError(id, "resource subscriptions"), sessionId);
                    }
                    result = handleResourceSubscribe(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.RESOURCES_UNSUBSCRIBE:
                    if (!config.resources || !config.resourceSubscriptions) {
                        return new McpResponse(capabilityError(id, "resource subscriptions"), sessionId);
                    }
                    result = handleResourceUnsubscribe(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.PROMPTS_LIST:
                    responseSessionId = null;
                    if (!config.prompts) {
                        return new McpResponse(capabilityError(id, "prompts"), sessionId);
                    }
                    result = handlePromptsList(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.PROMPTS_GET:
                    responseSessionId = null;
                    if (!config.prompts) {
                        return new McpResponse(capabilityError(id, "prompts"), sessionId);
                    }
                    result = handlePromptsGet(params instanceof Map
                            ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.TASKS_GET: {
                    Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : null;
                    result = dispatchTaskRequest(method, id, sessionId, null,
                            pp -> handleTasksGet(pp), p);
                    break;
                }
                case McpMethodNames.TASKS_RESULT: {
                    Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : null;
                    result = dispatchTaskRequest(method, id, sessionId, null,
                            pp -> handleTasksResult(pp), p);
                    break;
                }
                case McpMethodNames.TASKS_CANCEL: {
                    Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : null;
                    result = dispatchTaskRequest(method, id, sessionId, requestId,
                            pp -> handleTasksCancel(pp, sessionId, requestId), p);
                    break;
                }
                case McpMethodNames.TASKS_CREATE: {
                    Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : null;
                    result = dispatchTaskRequest(method, id, sessionId, requestId,
                            pp -> handleTasksCreate(pp, sessionId, requestId), p);
                    break;
                }
                case McpMethodNames.TASKS_UPDATE: {
                    Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : null;
                    result = dispatchTaskRequest(method, id, sessionId, requestId,
                            pp -> handleTasksUpdate(pp, sessionId), p);
                    break;
                }
                case McpMethodNames.COMPLETION_COMPLETE:
                    responseSessionId = null;
                    if (!config.completions) return new McpResponse(capabilityError(id, "completions"), sessionId);
                    result = handleCompletion(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.LOGGING_SET_LEVEL:
                    responseSessionId = null;
                    if (!config.logging) return new McpResponse(capabilityError(id, "logging"), sessionId);
                    result = handleSetLogLevel(params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.ELICITATION_CREATE:
                    // Elicitation is a 2026 capability and is only dispatched for
                    // a request negotiated as the stateless 2026 protocol.
                    if (!config.elicitation || !stateless) {
                        return new McpResponse(capabilityError(id, "elicitation"), sessionId);
                    }
                    result = handleElicitationRequest(id, sessionId, params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.SAMPLING_CREATE_MESSAGE:
                    // ADR-0022 §9a: sampling is stub-only — return method-not-found.
                    return new McpResponse(errorResponse(id, McpErrorCodes.METHOD_NOT_FOUND,
                            "Sampling not implemented in this release"), sessionId);
                case McpMethodNames.NOTIF_CANCELLED:
                    handleNotificationCancelled(sessionId, params instanceof Map
                            ? (Map<String, Object>) params : null);
                    return new McpResponse(null, sessionId);
                case McpMethodNames.PING:
                    result = new LinkedHashMap<>();
                    break;
                case McpMethodNames.LISTEN_SUBSCRIBE:
                    responseSessionId = null;
                    result = handleListenSubscribe(
                            params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                case McpMethodNames.LISTEN_UNSUBSCRIBE:
                    responseSessionId = null;
                    result = handleListenUnsubscribe(
                            params instanceof Map ? (Map<String, Object>) params : null);
                    break;
                default:
                    return new McpResponse(errorResponse(id, McpError.methodNotFound("Method not found: " + method)), sessionId);
            }

            if (result == null || notification) {
                return new McpResponse(null, responseSessionId);
            }
            return new McpResponse(successResponse(id, result), responseSessionId);
        } catch (McpException e) {
            return new McpResponse(errorResponse(id, e.getCode(), e.getMessage()), sessionId);
        } catch (Throwable t) {
            applicationLogger.error("Error handling MCP request: " + t.getMessage());
            if (t instanceof Error) throw (Error) t;
            Exception e = (Exception) t;
            try {
                Map<String, Object> req = mapper.fromJson(requestBody, new TypeToken<Map<String, Object>>() {
                }.getType());
                if (req != null) id = req.get("id");
            } catch (Exception parseFailure) {
                // Keep the JSON-RPC error id null when the request cannot be parsed.
            }
            return new McpResponse(errorResponse(id, McpErrorCodes.INTERNAL_ERROR, "Internal server error"), sessionId);
        } finally {
            if (reservedCategory != null) {
                categoryRateLimitController.release(sessionId, reservedCategory);
            }
        }
    }

    private boolean isSessionOptional(String method) {
        return McpMethodNames.SERVER_DISCOVER.equals(method)
                || McpMethodNames.INITIALIZE.equals(method)
                || McpMethodNames.NOTIF_INITIALIZED.equals(method)
                || McpMethodNames.PING.equals(method)
                || McpMethodNames.LISTEN_SUBSCRIBE.equals(method)
                || McpMethodNames.LISTEN_UNSUBSCRIBE.equals(method);
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
                categoryRateLimitController.removeSession(sessionId);
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
        categoryRateLimitController.clearSessions();
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
    public String getSessionIdForOwner(String ownerId) {
        return ownerId == null ? null : sessionOwners.get(ownerId);
    }

    /**
     * Returns the owner identity bound to a session, or null if no binding exists.
     *
     * @param sessionId session identifier
     * @return owner identity, or null if unbound
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
    public boolean isSessionBlocked(String sessionId) {
        if (sessionId == null) return false;
        SessionState state = sessions.get(sessionId);
        return categoryRateLimitController.isBlocked(sessionId);
    }

    /**
     * Returns true if there are pending notifications for the given session.
     * @param sessionId session identifier
     * @return true when pending notifications exist
     */
    public boolean hasPendingNotifications(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null && !state.pendingNotifications.isEmpty();
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

    private SessionState handleSessionCreate(String sessionId, String clientIp, String ownerId, String negotiatedVersion) {
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        if (normalizedOwnerId != null) {
            String existingSession = sessionOwners.putIfAbsent(normalizedOwnerId, sessionId);
            if (existingSession != null) {
                // Owner already has a session — replace it
                SessionState old = sessions.remove(existingSession);
                if (old != null) {
                    categoryRateLimitController.removeSession(existingSession);
                    clearOwnerBinding(old);
                }
            }
        }
        SessionState state = new SessionState(sessionId, clientIp, normalizedOwnerId);
        state.negotiatedVersion = negotiatedVersion;
        sessions.put(sessionId, state);
        categoryRateLimitController.registerSession(sessionId);
        return state;
    }

    private void refreshActivity(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.lastActivity = System.currentTimeMillis();
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

    // ==================== Initialize ====================

    private Map<String, Object> handleInitialize(Map<String, Object> params,
                                                 String newSessionId, String clientIp,
                                                 boolean stateless) {
        String ownerId = (params != null && params.get("ownerId") instanceof String)
                ? (String) params.get("ownerId") : null;
        String negotiatedVersion = config.protocolVersion;
        if (params != null) {
            String requestedVersion = (String) params.get("protocolVersion");
            if (requestedVersion != null && supportsProtocolVersion(requestedVersion)) {
                negotiatedVersion = requestedVersion;
            }
        }
        SessionState state = newSessionId == null ? null : handleSessionCreate(newSessionId, clientIp, ownerId, negotiatedVersion);

        if (state != null) LOGGER.info("Initialized session: " + newSessionId
                + (state.ownerId != null ? " owner=" + state.ownerId : ""));

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
            if (config.resourceSubscriptions && !stateless) resourcesCap.put("subscribe", true);
            capabilities.put("resources", resourcesCap);
        }

        if (config.prompts) {
            Map<String, Object> promptsCap = new LinkedHashMap<>();
            promptsCap.put("listChanged", true);
            capabilities.put("prompts", promptsCap);
        }

        if (config.completions) capabilities.put("completions", new LinkedHashMap<String, Object>());
        if (config.logging) capabilities.put("logging", new LinkedHashMap<String, Object>());
        if (config.elicitation && McpJsonRpc.PROTOCOL_VERSION_STATELESS.equals(negotiatedVersion)) {
            capabilities.put("elicitation", new LinkedHashMap<String, Object>());
        }
        if (config.tasks) {
            if (tasksExtension != null && tasksExtension.supports(negotiatedVersion)) {
                capabilities.put("tasks", tasksExtension.advertiseCapabilities(negotiatedVersion));
            } else if (tasksExtension == null) {
                capabilities.put("tasks", new LinkedHashMap<String, Object>());
            }
        }
        if (!config.experimental.isEmpty()) capabilities.put("experimental", config.experimental);

        result.put("capabilities", capabilities);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", config.serverName);
        serverInfo.put("version", config.serverVersion);
        result.put("serverInfo", serverInfo);

        return result;
    }

    // ==================== Server Discover ====================

    /**
     * Handles the sessionless server/discover request.
     * Returns server capability metadata without requiring a session.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleServerDiscover() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");

        List<String> supportedVersions = new ArrayList<>();
        supportedVersions.add(McpJsonRpc.PROTOCOL_VERSION_STATELESS);
        if (!supportedVersions.contains(config.protocolVersion)) {
            supportedVersions.add(config.protocolVersion);
        }
        if (!supportedVersions.contains(McpJsonRpc.PROTOCOL_VERSION)) {
            supportedVersions.add(McpJsonRpc.PROTOCOL_VERSION);
        }
        result.put("supportedVersions", supportedVersions);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (config.tools) {
            Map<String, Object> toolsCap = new LinkedHashMap<>();
            toolsCap.put("listChanged", true);
            capabilities.put("tools", toolsCap);
        }
        if (config.resources) {
            Map<String, Object> resourcesCap = new LinkedHashMap<>();
            resourcesCap.put("listChanged", true);
            capabilities.put("resources", resourcesCap);
        }
        if (config.prompts) {
            Map<String, Object> promptsCap = new LinkedHashMap<>();
            promptsCap.put("listChanged", true);
            capabilities.put("prompts", promptsCap);
        }
        if (config.completions) capabilities.put("completions", new LinkedHashMap<String, Object>());
        if (config.logging) capabilities.put("logging", new LinkedHashMap<String, Object>());
        if (config.elicitation) capabilities.put("elicitation", new LinkedHashMap<String, Object>());
        if (config.tasks) {
            // Use the configured protocolVersion for extension capability checks.
            // server/discover reflects the server's configured version, not a hardcoded one.
            if (tasksExtension != null && tasksExtension.supports(config.protocolVersion)) {
                capabilities.put("tasks", tasksExtension.advertiseCapabilities(config.protocolVersion));
            } else if (tasksExtension == null) {
                capabilities.put("tasks", new LinkedHashMap<String, Object>());
            }
        }
        result.put("capabilities", capabilities);

        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", config.serverName);
        serverInfo.put("version", config.serverVersion);
        meta.put("io.modelcontextprotocol/serverInfo", serverInfo);
        result.put("_meta", meta);

        result.put("instructions", "");
        result.put("ttlMs", 3600000L);
        result.put("cacheScope", "public");

        return result;
    }

    /**
     * Dispatches a task-related method with version gating and extension callback.
     *
     * <ol>
     *   <li>If {@code tasks} capability is disabled → {@code -32601 capability disabled}</li>
     *   <li>If an extension is registered and does not {@code support(version)} →
     *       {@code -32601 method not found}</li>
     *   <li>If an extension is registered → call {@code extension.onRequest}; non-null result is used</li>
     *   <li>Otherwise → delegate to the built-in handler</li>
     * </ol>
     *
     * <p>When the built-in handler throws {@link McpException}, the extension's
     * {@code onError} hook is called; if it returns a replacement the server
     * uses that, otherwise the original error is returned.
     *
     * @param method       the MCP method name
     * @param id           JSON-RPC request id
     * @param sessionId    MCP session id (may be null in stateless mode)
     * @param requestId    the request id for cancellation tracking (may be null)
     * @param builtIn      the built-in handler
     * @return the request result (never null)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchTaskRequest(
            String method, Object id, String sessionId, Object requestId,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> builtIn,
            Map<String, Object> rawParams) {

        if (!config.tasks) {
            throw McpException.methodNotFound("MCP capability is disabled: tasks");
        }

        String version = sessionId != null
                ? sessions.get(sessionId).negotiatedVersion
                : (isStatelessMode() ? McpJsonRpc.PROTOCOL_VERSION_STATELESS : config.protocolVersion);

        // Extension version gating: if extension exists but doesn't support this version,
        // treat as unknown method (safe unknown extension behaviour).
        if (tasksExtension != null && !tasksExtension.supports(version)) {
            throw McpException.methodNotFound("Method not found: " + method);
        }

        // Delegate to extension first if present
        if (tasksExtension != null) {
            McpTaskExtension.RequestResult extResult =
                    tasksExtension.onRequest(method, rawParams, sessionId);
            if (extResult != null) {
                if (extResult.isSuccess()) {
                    return extResult.result;
                } else {
                    throw new McpException(extResult.error.code, extResult.error.message);
                }
            }
        }

        // Built-in handling
        try {
            return builtIn.apply(rawParams);
        } catch (McpException e) {
            // Extension error hook: let extension transform or suppress the error
            if (tasksExtension != null) {
                McpTaskExtension.RequestResult extResult =
                        tasksExtension.onError(method, rawParams, sessionId, e.getCode(), e.getMessage());
                if (extResult != null) {
                    if (extResult.isSuccess()) {
                        return extResult.result;
                    } else {
                        throw new McpException(extResult.error.code, extResult.error.message);
                    }
                }
            }
            throw e;
        }
    }

    private Map<String, Object> handleTasksGet(Map<String, Object> params) {
        McpTask task = findTask(params);
        Map<String, Object> out = new LinkedHashMap<>();
        // SEP-2663 §Task Polling: resultType MUST be "complete"
        out.put("resultType", "complete");
        // Copy the full task metadata from toMap()
        for (Map.Entry<String, Object> e : task.toMap().entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private Map<String, Object> handleTasksResult(Map<String, Object> params) {
        McpTask task = findTask(params);
        // SEP-2663: tasks/result only applies to terminal states.
        // INPUT_REQUIRED is not terminal → throw.
        if (task.getStatus() == McpTask.Status.WORKING
                || task.getStatus() == McpTask.Status.INPUT_REQUIRED)
            throw McpException.taskNotComplete(task.getTaskId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getTaskId());
        if (task.getStatus() == McpTask.Status.FAILED || task.getStatus() == McpTask.Status.CANCELLED) {
            out.put("error", task.getError());
        } else {
            // COMPLETED: always include result (null is valid — empty-result task)
            out.put("result", task.getResult());
        }
        return out;
    }

    private Map<String, Object> handleTasksCancel(Map<String, Object> params, String sessionId, Object requestId) {
        McpTask task = findTask(params);
        // Allow cancelling WORKING or INPUT_REQUIRED tasks (both are non-terminal)
        if (task.getStatus() != McpTask.Status.WORKING
                && task.getStatus() != McpTask.Status.INPUT_REQUIRED)
            throw McpException.taskAlreadyTerminal(task.getStatus().name());
        registry.cancelRequest(sessionId, requestId == null ? null : requestId.toString());
        Map<String, Object> result = registry.cancelTask(task.getTaskId()).toMap();
        // Emit server-initiated notifications/cancelled to client
        Map<String, Object> notifyParams = new LinkedHashMap<>();
        notifyParams.put("requestId", requestId);
        notifyParams.put("reason", "Task cancelled by server");
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.enqueueEvent(mapper.toJson(mapAsRpcNotification(McpMethodNames.NOTIF_CANCELLED, notifyParams)));
        }
        return result;
    }

    /**
     * Handles SEP-2663 tasks/update: client provides inputResponses for an INPUT_REQUIRED task.
     * Returns an empty acknowledgement on success (SEP-2663 §Task Update).
     *
     * @param params must contain "taskId" (String) and "inputResponses" (Map)
     * @param sessionId MCP session id
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleTasksUpdate(Map<String, Object> params, String sessionId) {
        if (params == null)
            throw McpException.invalidParams("params required");
        String taskId = params.get("taskId") instanceof String
                ? (String) params.get("taskId") : null;
        if (taskId == null || taskId.trim().isEmpty())
            throw McpException.invalidParams("taskId is required");
        Map<String, Object> inputResponses = params.get("inputResponses") instanceof Map
                ? (Map<String, Object>) params.get("inputResponses") : null;
        // Find task — throws INVALID_PARAMS if unknown
        McpTask task = registry.getTask(taskId);
        if (task == null)
            throw McpException.invalidParams("Unknown task: " + taskId);
        if (task.getStatus() != McpTask.Status.INPUT_REQUIRED)
            throw McpException.invalidParams("tasks/update only valid for INPUT_REQUIRED tasks");
        // Delegate to extension for input-response processing if present.
        // The extension can choose to transition the task, store responses, etc.
        if (tasksExtension != null) {
            McpTaskExtension.RequestResult extResult =
                    tasksExtension.onRequest(McpMethodNames.TASKS_UPDATE,
                            params, sessionId);
            if (extResult != null) {
                if (extResult.isSuccess()) {
                    return extResult.result != null ? extResult.result : new LinkedHashMap<>();
                } else {
                    throw new McpException(extResult.error.code, extResult.error.message);
                }
            }
        }
        // Default: acknowledge the update. The application is responsible for
        // calling registry.transitionToInputRequired() or registry.completeTask()
        // after processing the inputResponses out-of-band.
        return new LinkedHashMap<>();
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
        if (params == null) throw McpException.invalidParams("params required");
        String name = params.get("name") instanceof String ? (String) params.get("name") : null;
        if (name == null || name.trim().isEmpty())
            throw McpException.invalidParams("name is required");
        Map<String, Object> input = params.get("input") instanceof Map
                ? (Map<String, Object>) params.get("input") : new LinkedHashMap<>();
        Map<String, Object> inputSchema = params.get("inputSchema") instanceof Map
                ? (Map<String, Object>) params.get("inputSchema") : null;
        McpTask task = registry.createTask(name, sessionId, requestId, input, inputSchema);
        // Emit task notification so client knows the task token
        Map<String, Object> notifParams = new LinkedHashMap<>();
        notifParams.put("task", task.toMap());
        notifParams.put("token", task.getTaskId());
        sessions.get(sessionId).enqueueEvent(mapper.toJson(mapAsRpcNotification(McpMethodNames.NOTIF_TASK, notifParams)));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("task", task.toMap());
        resp.put("token", task.getTaskId());
        return resp;
    }

    private McpTask findTask(Map<String, Object> params) {
        if (params == null || !(params.get("taskId") instanceof String) || ((String) params.get("taskId")).trim().isEmpty())
            throw McpException.invalidParams("taskId is required");
        McpTask t = registry.getTask((String) params.get("taskId"));
        if (t == null) throw McpException.invalidParams("Unknown task: " + params.get("taskId"));
        return t;
    }

    private Map<String, Object> handleCompletion(Map<String, Object> params) {
        if (params == null || !(params.get("ref") instanceof Map) || !(params.get("argument") instanceof Map))
            throw McpException.invalidParams("ref and argument are required");
        Map<?, ?> rawRef = (Map<?, ?>) params.get("ref");
        Map<?, ?> rawArgument = (Map<?, ?>) params.get("argument");
        Map<String, Object> ref = stringObjectMap(rawRef, "ref");
        Map<String, Object> argument = stringObjectMap(rawArgument, "argument");
        Object type = ref.get("type");
        McpCompletionProvider provider = registry.getCompletionProvider(type instanceof String ? (String) type : null);
        if (provider == null) throw McpException.invalidParams("No completion provider for reference type: " + type);
        Map<String, Object> result = provider.complete(ref, argument);
        return result == null ? new LinkedHashMap<>() : result;
    }

    private Map<String, Object> stringObjectMap(Map<?, ?> source, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String))
                throw McpException.invalidParams(field + " keys must be strings");
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Map<String, Object> handleSetLogLevel(Map<String, Object> params) {
        if (params == null || !(params.get("level") instanceof String))
            throw McpException.invalidParams("level is required");
        try {
            Level level = Level.parse((String) params.get("level"));
            LOGGER.setLevel(level);
            return new LinkedHashMap<>();
        } catch (IllegalArgumentException e) {
            throw McpException.invalidParams("Invalid log level: " + params.get("level"));
        }
    }

    // ==================== 2026 subscriptions / listen ====================

    /**
     * Handles {@code listens/subscribe} (2026-07-28).
     * Opens a sessionless listener subscription and returns the server-assigned token.
     *
     * <p>Response shape:
     * <pre>{@code
     * {
     *   "subscription": { "token": "...", "topics": [...], "bufferSize": 100, "maxCapacity": 100 }
     * }
     * }</pre>
     */
    private Map<String, Object> handleListenSubscribe(Map<String, Object> params) {
        ListenRequest req = ListenRequest.fromParams(params);
        if (req.topics.isEmpty()) {
            throw McpException.invalidParams("at least one topic is required in the topics array");
        }
        SubscriptionManager.ListenerOpenResult open =
                listenerSubscriptions.subscribe(req.topics, req.subscriptionId, req.bufferSizeHint);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("token", open.token());
        subscription.put("topics", new java.util.ArrayList<>(req.topics));
        subscription.put("bufferSize", open.bufferSize());
        subscription.put("maxCapacity", open.maxCapacity());
        result.put("subscription", subscription);
        return result;
    }

    /**
     * Handles {@code listens/unsubscribe} (2026-07-28).
     * Closes an active listener subscription and releases its resources.
     *
     * <p>Response shape:
     * <pre>{@code
     * { "unsubscribed": true }
     * }</pre>
     */
    private Map<String, Object> handleListenUnsubscribe(Map<String, Object> params) {
        if (params == null) throw McpException.invalidParams("params required");
        Object tokenObj = params.get("token");
        if (!(tokenObj instanceof String) || ((String) tokenObj).isEmpty()) {
            throw McpException.invalidParams("token is required and must be a non-empty string");
        }
        String token = (String) tokenObj;
        listenerSubscriptions.unsubscribe(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unsubscribed", true);
        return result;
    }

    // ==================== Server-initiated Requests / Elicitation (ADR-0022) ====================

    /**
     * Handles an incoming {@code elicitation/create} response from the client.
     * The client sends this as a JSON-RPC response to a server-initiated elicitation request.
     *
     * @param id        JSON-RPC id from the request
     * @param sessionId target session
     * @param params    request params
     * @return empty result map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> handleElicitationRequest(Object id, String sessionId, Map<String, Object> params) {
        if (params == null) throw McpException.invalidParams("params required");
        Object message = params.get("message");
        if (!(message instanceof String) || ((String) message).isEmpty())
            throw McpException.invalidParams("message is required");
        // Extract progressToken for correlation
        Object meta = params.get("_meta");
        Object progressToken = null;
        if (meta instanceof Map) {
            progressToken = ((Map<?, ?>) meta).get("progressToken");
        }
        String token = progressToken instanceof String ? (String) progressToken : null;
        if (token == null || !token.startsWith("uuid:")) {
            throw McpException.invalidParams("elicitation request must include _meta.progressToken");
        }
        applicationLogger.info("Elicitation request received: " + message + " [token=" + token + "]");
        // Return empty result; the elicitation is handled by the application asynchronously.
        return new LinkedHashMap<>();
    }

    /**
     * Sends a server-initiated request (e.g. {@code elicitation/create}) and returns a
     * CompletableFuture that resolves with the client's JSON-RPC response body.
     *
     * @param sessionId  target MCP session
     * @param method     JSON-RPC method name
     * @param params     request params, or null
     * @param timeoutMs  per-request timeout in milliseconds; defaults to config value if non-positive
     * @return CompletableFuture with the JSON-RPC response body; completes exceptionally on timeout/cancel/error
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> sendServerRequest(
            String sessionId, String method, Map<String, Object> params, long timeoutMs) {

        if (!getServerRequestTransport().supportsServerRequests()) {
            return CompletableFuture.failedFuture(
                    new McpElicitationException(McpErrorCodes.METHOD_NOT_FOUND,
                            "Server requests not supported by this transport"));
        }

        if (!hasSession(sessionId)) {
            return CompletableFuture.failedFuture(
                    new McpElicitationException(McpErrorCodes.INVALID_PARAMS,
                            "Unknown session: " + sessionId));
        }

        long timeout = timeoutMs > 0 ? timeoutMs : config.serverRequestTimeoutMs;
        long now = System.currentTimeMillis();
        String requestId = "uuid:" + UUID.randomUUID().toString();

        // Build JSON-RPC request
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", McpJsonRpc.VERSION);
        request.put("id", requestId);
        request.put("method", method);
        if (params != null) {
            Map<String, Object> enrichedParams = new LinkedHashMap<>(params);
            // Add _meta.progressToken
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("progressToken", requestId);
            enrichedParams.put("_meta", meta);
            request.put("params", enrichedParams);
        }
        String requestBody = mapper.toJson(request);

        // Create the CompletableFuture to be completed by handleServerInitiatedResponse
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        // Schedule timeout
        ServerInitiatedRequest record = new ServerInitiatedRequest(
                requestId, method,
                params != null ? params : new LinkedHashMap<>(),
                sessionId, now, now + timeout, future);

        serverInitiatedRequests.put(requestId, record);

        try {
            CompletableFuture<Map<String, Object>> result =
                    getServerRequestTransport().sendRequest(sessionId, requestBody, timeout, future);

            getScheduledExecutor().schedule(() -> {
                ServerInitiatedRequest req = serverInitiatedRequests.remove(requestId);
                if (req != null && !future.isDone()) {
                    future.completeExceptionally(
                            new McpElicitationException(McpErrorCodes.SERVER_REQUEST_TIMEOUT,
                                    "Server request timed out after " + timeout + "ms"));
                }
            }, timeout, java.util.concurrent.TimeUnit.MILLISECONDS);

            return result;
        } catch (Exception e) {
            serverInitiatedRequests.remove(requestId);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Handles a JSON-RPC response from the client for a server-initiated request.
     * Called by the HTTP handler when a POST arrives with a matching request ID.
     *
     * <p>ADR-0022 §5: malformed replies (missing id) and duplicate replies (already-resolved)
     * are logged and discarded.
     *
     * @param requestId  the JSON-RPC id from the client's response
     * @param response   parsed response body
     * @param isError    true when the response is a JSON-RPC error
     */
    @SuppressWarnings("unchecked")
    public void handleServerInitiatedResponse(String requestId, Map<String, Object> response, boolean isError) {
        if (requestId == null) {
            LOGGER.warning("Received server-initiated response without request id — discarded");
            return;
        }
        ServerInitiatedRequest req = serverInitiatedRequests.remove(requestId);
        if (req == null) {
            LOGGER.warning("Duplicate or unknown server-initiated response for id=" + requestId + " — discarded");
            return;
        }
        if (isError) {
            Object errCode = response.get("code");
            int code = errCode instanceof Number ? ((Number) errCode).intValue() : McpErrorCodes.INTERNAL_ERROR;
            Object errMsg = response.get("message");
            String msg = errMsg instanceof String ? (String) errMsg : "Unknown error";
            req.responseFuture.completeExceptionally(new McpElicitationException(code, msg));
        } else {
            req.responseFuture.complete(response);
        }
    }

    /**
     * Cancels an in-flight server-initiated request (ADR-0022 §3).
     *
     * @param requestId the server-generated request token (uuid:...)
     */
    public void cancelServerRequest(String requestId) {
        ServerInitiatedRequest req = serverInitiatedRequests.remove(requestId);
        if (req != null && !req.responseFuture.isDone()) {
            req.responseFuture.completeExceptionally(new java.util.concurrent.CancellationException(
                    "Server-initiated request cancelled: " + requestId));
            LOGGER.info("Server-initiated request cancelled: " + requestId);
        }
    }

    /**
     * Cancels an in-flight server-initiated request by its token (with or without {@code uuid:} prefix).
     *
     * @param token the request token
     */
    public void cancelServerRequestByToken(String token) {
        String id = token.startsWith("uuid:") ? token : ("uuid:" + token);
        cancelServerRequest(id);
    }

    /**
     * Returns the number of in-flight server-initiated requests (for testing/monitoring).
     */
    public int getPendingServerRequestCount() {
        return serverInitiatedRequests.size();
    }

    /**
     * Returns true when a server-initiated request has been cancelled or timed out.
     *
     * @param requestId the server-generated request token
     */
    public boolean isServerRequestCancelled(String requestId) {
        ServerInitiatedRequest req = serverInitiatedRequests.get(requestId);
        if (req == null) return true;
        return req.responseFuture.isDone() && req.responseFuture.isCancelled();
    }

    /**
     * Enqueues a server-initiated JSON-RPC request into the session's SSE event queue.
     * Called by {@link SseServerRequestTransport} to put the request on the wire.
     *
     * @param sessionId   target session
     * @param requestBody serialised JSON-RPC request
     */
    void enqueueServerEvent(String sessionId, String requestBody) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.enqueueEvent(requestBody);
        }
    }

    // ==================== Elicitation Convenience API ====================

    /**
     * Sends a generic elicitation request and returns a CompletableFuture for the response.
     *
     * @param sessionId target session
     * @param request   the elicitation request
     * @param timeoutMs per-request timeout; defaults to config if non-positive
     * @return CompletableFuture resolving to the JSON-RPC response body
     */
    public CompletableFuture<Map<String, Object>> elicit(
            String sessionId, ElicitRequest request, long timeoutMs) {
        Map<String, Object> params = request.toParams(null);
        return sendServerRequest(sessionId, McpMethodNames.ELICITATION_CREATE, params, timeoutMs);
    }

    /**
     * Sends an elicitation confirmation request and returns the selected action label.
     *
     * @param sessionId target session
     * @param message   prompt message
     * @param actions   labelled actions the client can select
     * @param timeoutMs per-request timeout; defaults to config if non-positive
     * @return CompletableFuture resolving to the selected action label
     */
    public CompletableFuture<String> elicitConfirmation(
            String sessionId, String message, List<ElicitAction> actions, long timeoutMs) {
        ElicitRequest req = ElicitRequest.builder()
                .message(message)
                .actions(actions)
                .build();
        return elicit(sessionId, req, timeoutMs).thenApply(result -> {
            ElicitationResult er = ElicitationResult.fromResponse(result);
            if (er.isDeclined()) {
                throw new McpElicitationException(McpErrorCodes.ELICITATION_REJECTED, "Elicitation rejected by client");
            }
            return er.getAction() != null ? er.getAction() : er.getValue();
        });
    }

    /**
     * Sends an elicitation text-input request and returns the entered value.
     *
     * @param sessionId    target session
     * @param message      prompt message
     * @param defaultValue optional default value; returned if client declines
     * @param timeoutMs    per-request timeout; defaults to config if non-positive
     * @return CompletableFuture resolving to the client's text input
     */
    public CompletableFuture<String> elicitInput(
            String sessionId, String message, String defaultValue, long timeoutMs) {
        ElicitRequest req = ElicitRequest.builder()
                .message(message)
                .defaultValue(defaultValue)
                .build();
        return elicit(sessionId, req, timeoutMs).thenApply(result -> {
            ElicitationResult er = ElicitationResult.fromResponse(result);
            if (er.isDeclined()) return defaultValue;
            return er.getValue() != null ? er.getValue()
                    : (er.getAction() != null ? er.getAction() : defaultValue);
        });
    }

    // ==================== Tools ====================

    private Map<String, Object> handleToolsList(Map<String, Object> params) {
        return paginate("tools", registry.getRegisteredTools(), params, "tools");
    }

    /**
     * Builds a paginated list response with optional 2026-07-28 cache metadata.
     * The definitions snapshot is captured at the start of the call so that concurrent
     * registrations cannot alter the page mid-flight (stable snapshot guarantee).
     *
     * @param key          JSON key for the items array (e.g. "tools", "resources")
     * @param definitions  live definitions list (read-once snapshot)
     * @param params       request params containing optional cursor
     * @param listType     the list type string passed to getCacheMetadata, or null to skip metadata
     * @return paginated result map
     */
    private Map<String, Object> paginate(String key, List<Map<String, Object>> definitions,
                                         Map<String, Object> params, String listType) {
        int total = definitions.size();
        int offset = decodeCursor(params == null ? null : params.get("cursor"), total);
        if (offset > total) offset = total;
        int pageEnd = Math.min(offset + config.pageSize, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, new ArrayList<>(definitions.subList(offset, pageEnd)));
        if (pageEnd < total) result.put("nextCursor", encodeCursor(pageEnd));
        // Attach version-gated cache metadata for 2026-07-28+ sessions
        if (isStatelessMode() && listType != null) {
            Map<String, Object> cacheMeta = registry.getCacheMetadata(McpJsonRpc.PROTOCOL_VERSION_STATELESS);
            result.put("catalogVersion", cacheMeta.get("catalogVersion"));
            result.put("ttlMs", cacheMeta.get("ttlMs"));
            result.put("cacheScope", cacheMeta.get("cacheScope"));
            result.put("totalCount", total);
            result.put("pageStart", offset);
            result.put("pageEnd", pageEnd);
            result.put("registrationTimestamp", cacheMeta.get("registrationTimestamp"));
        }
        return result;
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(Object cursor, int size) {
        if (cursor == null) return 0;
        if (!(cursor instanceof String) || ((String) cursor).isEmpty()) {
            throw McpException.invalidParams("cursor is invalid");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode((String) cursor), StandardCharsets.UTF_8);
            if (!decoded.matches("[0-9]+")) throw new IllegalArgumentException();
            long offset = Long.parseLong(decoded);
            if (offset < 0 || offset >= size) throw new IllegalArgumentException();
            return (int) offset;
        } catch (Exception e) {
            throw McpException.invalidParams("cursor is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleToolsCall(Map<String, Object> params, String sessionId, Object requestId,
                                               boolean stateless) {
        if (params == null) throw McpException.invalidParams("missing params");

        String name = requireName(params, "tool");
        Map<String, Object> arguments = optionalArguments(params);

        // Parse 2026 request context from _meta (only progressToken is used today).
        Mcp2026RequestContext ctx2026 = Mcp2026RequestContext.fromParams(params);
        Object progressToken = ctx2026.hasProgressToken() ? ctx2026.progressToken : null;

        McpToolHandler handler = registry.getToolHandler(name);
        if (handler == null) throw McpException.invalidParams("Unknown tool: " + name);

        // Lookup tool definition for schema and scopes
        Map<String, Object> definition = registry.getToolDefinition(name);
        List<String> requiredScopes = (definition != null)
                ? (List<String>) definition.get("requiredScopes") : null;
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
        String rateLimitError = stateless ? null : categoryRateLimitController.checkTool(sessionId, name, requiredScopes);
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

        if (stateless) sessionId = null;
        try {
            if (progressToken != null) {
                notifyToolProgress(sessionId, progressToken, 0d, 1d, "Tool " + name + " started");
            }
            Map<String, Object> result = handler.call(arguments);
            if (progressToken != null) {
                notifyToolProgress(sessionId, progressToken, 1d, 1d, "Tool " + name + " completed");
            }
            return result;
        } catch (McpException e) {
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
        String body = mapper.toJson(mapAsRpcNotification(McpMethodNames.NOTIF_PROGRESS, params));
        state.enqueueEvent(body);
    }

    /**
     * Log and handle a handler-level exception, returning the appropriate error result.
     * Separates tool exceptions (return error result) from resource exceptions (throw).
     *
     * @param kind        identifier kind label for logging
     * @param identifier  resource/tool name or URI
     * @param t           the caught exception
     * @return error result for tool handlers; throws McpException for resources
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
        throw McpException.internalError("Error reading " + identifier + ": " + e.getMessage());
    }

    private String requireName(Map<String, Object> params, String kind) {
        Object value = params.get("name");
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw McpException.invalidParams(kind + " name must be a non-empty string");
        }
        return (String) value;
    }

    private Map<String, Object> optionalArguments(Map<String, Object> params) {
        Object value = params.get("arguments");
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map)) {
            throw McpException.invalidParams("arguments must be an object");
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
        return paginate("resources", registry.getRegisteredResources(), params, "resources");
    }

    private Map<String, Object> handleResourcesRead(Map<String, Object> params) {
        if (params == null) throw McpException.invalidParams("missing params");
        Object uriValue = params.get("uri");
        if (!(uriValue instanceof String) || ((String) uriValue).trim().isEmpty()) {
            throw McpException.invalidParams("uri must be a non-empty string");
        }
        String uri = (String) uriValue;

        McpResourceHandler handler = registry.getResourceHandler(uri);
        if (handler == null) handler = findTemplateHandler(uri);
        if (handler == null) throw McpException.invalidParams("Unknown resource: " + uri);

        try {
            if (handler instanceof McpBlobResourceHandler) {
                return blobContents(uri, ((McpBlobResourceHandler) handler).readBlob(uri));
            }
            return resourceContents(uri, handler.read(uri));
        } catch (McpException e) {
            throw e;
        } catch (Throwable t) {
            return handleHandlerException("Resource", uri, t);
        }
    }

    // ==================== Resource Templates ====================

    private Map<String, Object> handleResourceTemplatesList(Map<String, Object> params) {
        return paginate("resourceTemplates", registry.getRegisteredResourceTemplates(), params, "resourceTemplates");
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
        if (sessionId == null) throw McpException.invalidParams("Missing session ID");
        SessionState state = sessions.get(sessionId);
        if (state == null) throw McpException.invalidParams("Unknown session: " + sessionId);
        return state;
    }

    private String requireResourceUri(Map<String, Object> params) {
        if (params == null || !(params.get("uri") instanceof String)
                || ((String) params.get("uri")).trim().isEmpty()) {
            throw McpException.invalidParams("Missing resource URI");
        }
        return (String) params.get("uri");
    }

    // ==================== Prompts ====================

    private Map<String, Object> handlePromptsList(Map<String, Object> params) {
        return paginate("prompts", registry.getRegisteredPrompts(), params, "prompts");
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
     * Transitions a working task to the INPUT_REQUIRED state with SEP-2663 metadata.
     * Use this when a task needs client input before it can proceed.
     *
     * @param taskId task identifier
     * @param statusMessage human-readable status message
     * @param ttlMs time-to-live in ms, or null for unlimited
     * @param pollIntervalMs suggested polling interval in ms, or null
     * @param inputRequests pending MRTR input requests (SEP-2663 shape), or null
     * @return updated INPUT_REQUIRED task snapshot
     */
    @SuppressWarnings("unused, UnusedReturnValue")
    public McpTask transitionTaskToInputRequired(String taskId, String statusMessage,
                                                  Long ttlMs, Integer pollIntervalMs,
                                                  Map<String, Object> inputRequests) {
        return registry.transitionToInputRequired(taskId, statusMessage, ttlMs, pollIntervalMs, inputRequests);
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
     * Queues a list-changed notification for every active session
     * AND for every active 2026 listener subscription.
     */
    @Override
    public void onRegistryChanged(String listType) {
        String method;
        switch (listType) {
            case "tools":
                method = McpMethodNames.NOTIF_TOOLS_LIST_CHANGED;
                break;
            case "resources":
                method = McpMethodNames.NOTIF_RESOURCES_LIST_CHANGED;
                break;
            case "prompts":
                method = McpMethodNames.NOTIF_PROMPTS_LIST_CHANGED;
                break;
            default:
                method = McpMethodNames.NOTIF_LIST_CHANGED;
        }
        // Session-based notifications (legacy 2025 behaviour)
        String body = mapper.toJson(mapAsRpcNotification(method, null));
        for (SessionState state : sessions.values()) {
            state.enqueueEvent(body);
        }
        // 2026 listener subscriptions
        listenerSubscriptions.notifyRegistryChanged(listType, method, null, mapper);
    }

    /**
     * Queue a resource update for every session subscribed to the URI
     * AND for every active 2026 listener subscription subscribed to {@code resources/updated}.
     * Uses bounded queue with overflow handling.
     */
    public void notifyResourceUpdated(String uri) {
        if (uri == null) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", uri);
        String body = mapper.toJson(mapAsRpcNotification(McpMethodNames.NOTIF_RESOURCES_UPDATED, params));
        // Session-based resource updates (legacy 2025 behaviour)
        for (SessionState state : sessions.values()) {
            if (state.subscriptions.contains(uri)) {
                enqueue(state, body);
            }
        }
        // 2026 listener subscriptions
        listenerSubscriptions.notifyResourceUpdated(uri, mapper);
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
        String body = mapper.toJson(mapAsRpcNotification(McpMethodNames.NOTIF_MESSAGE, params));
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

    // ── 2026 listener subscription polling ───────────────────────────────────────

    /**
     * Polls the next queued notification for a 2026 listener subscription.
     * Returns the notification body as an SSE-formatted line, or null when the queue is empty.
     *
     * <p>This method is used by the transport layer to drain the listener's event queue
     * and stream notifications over the HTTP response.
     *
     * @param subscriptionToken the server-assigned subscription token from {@code listens/subscribe}
     * @return SSE-formatted notification line, or null
     */
    public String pollListenerNotification(String subscriptionToken) {
        if (subscriptionToken == null) return null;
        String body = listenerSubscriptions.poll(subscriptionToken);
        if (body == null) return null;
        return "event: message\ndata: " + body + "\n\n";
    }

    /**
     * Drains and returns all queued notifications for a 2026 listener subscription.
     * Used for replay when a client reconnects with {@code Last-Event-ID}.
     *
     * @param subscriptionToken the server-assigned subscription token
     * @return concatenated SSE blocks for all queued notifications, or empty string
     */
    public String drainListenerNotifications(String subscriptionToken) {
        if (subscriptionToken == null) return "";
        java.util.List<String> bodies = listenerSubscriptions.drain(subscriptionToken);
        if (bodies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String body : bodies) {
            sb.append("event: message\ndata: ")
                    .append(escapeSseData(body))
                    .append("\n\n");
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
        message.put("jsonrpc", McpJsonRpc.VERSION);
        message.put("method", method);
        if (params != null) message.put("params", params);
        return message;
    }

    private String capabilityError(Object id, String capability) {
        return errorResponse(id, McpError.capabilityDisabled(capability));
    }

    private String successResponse(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", McpJsonRpc.VERSION);
        resp.put("id", id);
        resp.put("result", result);
        return mapper.toJson(resp);
    }

    @SuppressWarnings("unchecked")
    private String errorResponse(Object id, Map<String, Object> error) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", McpJsonRpc.VERSION);
        resp.put("id", id);
        Map<String, Object> errorClone = new LinkedHashMap<>(2);
        errorClone.put("code", error.get("code"));
        errorClone.put("message", error.get("message") == null ? "Unknown MCP error" : error.get("message"));
        resp.put("error", errorClone);
        try {
            return mapper.toJson(resp);
        } catch (Exception serializationFailure) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal server error\"}}";
        }
    }

    @SuppressWarnings("unchecked")
    private String errorResponse(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return errorResponse(id, error);
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
        return McpError.resourceResult(message);
    }

    private Map<String, Object> errorPromptResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", "Error: " + message);
        result.put("messages", new ArrayList<>());
        return result;
    }
}
