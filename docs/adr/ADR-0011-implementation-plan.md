# ADR-0011 Implementation Plan — Security and Rate Limiting

This document tracks the phased implementation of ADR-0011. Each phase is self-contained, testable, and leaves the codebase in a buildable state.

---

## Phase 1 — Rate limit data structures

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to add

```java
// ==================== Constants ====================

/** Maximum pending notifications per session before overflow. */
public static final int MAX_PENDING_NOTIFICATIONS_PER_SESSION = 100;

/** Maximum concurrent sessions. */
public static final int MAX_CONCURRENT_SESSIONS = 10;

/** Session idle timeout in milliseconds (5 minutes). */
public static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000L;

/** Session cleanup interval in milliseconds (1 minute). */
private static final long SESSION_CLEANUP_INTERVAL_MS = 60 * 1000L;

// Rate limiting
/** Max requests per IP per minute. */
public static final int MAX_REQUESTS_PER_IP_PER_MINUTE = 60;
/** Max requests per session per minute. */
public static final int MAX_REQUESTS_PER_SESSION_PER_MINUTE = 120;
/** Rate limit window in milliseconds (1 minute). */
private static final long RATE_LIMIT_WINDOW_MS = 60 * 1000L;

// Per-category limits
/** Read burst limit per session per minute. */
public static final int CATEGORY_READ_BURST_LIMIT = 60;
/** Read sustained limit per session per 5 minutes. */
public static final int CATEGORY_READ_SUSTAINED_LIMIT = 200;
/** Read concurrent in-flight cap. */
public static final int CATEGORY_READ_CONCURRENT_CAP = 5;

public static final int CATEGORY_WRITE_BURST_LIMIT = 30;
public static final int CATEGORY_WRITE_SUSTAINED_LIMIT = 100;
public static final int CATEGORY_WRITE_CONCURRENT_CAP = 3;

public static final int CATEGORY_ADMIN_BURST_LIMIT = 5;
public static final int CATEGORY_ADMIN_SUSTAINED_LIMIT = 15;
public static final int CATEGORY_ADMIN_CONCURRENT_CAP = 1;

/** Sustained rate limit window (5 minutes). */
private static final long RATE_LIMIT_SUSTAINED_WINDOW_MS = 5 * 60 * 1000L;

// Destructive tool caps
public static final int DESTRUCTIVE_CAP_SHUTDOWN = 3;
public static final long DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS = 10 * 60 * 1000L;
public static final int DESTRUCTIVE_CAP_DELETE = 10;
public static final long DESTRUCTIVE_COOLDOWN_DELETE_MS = 2 * 60 * 1000L;
public static final int DESTRUCTIVE_CAP_UPLOAD = 5;
public static final long DESTRUCTIVE_COOLDOWN_UPLOAD_MS = 60 * 1000L;

// Abuse scoring
/** Abuse score threshold for SEVERE block. */
public static final int ABUSE_SCORE_BLOCK_THRESHOLD = 10;

/** Set of destructive tool names. */
private static final Set<String> DESTRUCTIVE_TOOLS = ConcurrentHashMap.newKeySet();
static {
    DESTRUCTIVE_TOOLS.addAll(Arrays.asList(
            "shutdown", "delete_action", "delete_prompt",
            "set_mcp_api_key", "revoke_mcp_api_key", "upload_file"
    ));
}

// ==================== RateLimitRecord ====================

/**
 * Sliding-window rate limit tracker.
 * Thread-safe: timestamps are added/removed atomically.
 */
private static class RateLimitRecord {
    private final ConcurrentLinkedQueue<Long> requestTimestamps = new ConcurrentLinkedQueue<>();

    /**
     * Returns true if the request is allowed under this limit.
     * Removes timestamps outside the window before checking.
     */
    synchronized boolean allowRequest(int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        // Remove old timestamps
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
}

// ==================== CategoryRateLimitState ====================

/**
 * Per-session, per-category rate-limit state.
 * All fields are thread-safe via AtomicInteger or volatile.
 */
static final class CategoryRateLimitState {
    // 6 sliding-window records
    final RateLimitRecord readBurst = new RateLimitRecord();
    final RateLimitRecord readSustained = new RateLimitRecord();
    final RateLimitRecord writeBurst = new RateLimitRecord();
    final RateLimitRecord writeSustained = new RateLimitRecord();
    final RateLimitRecord adminBurst = new RateLimitRecord();
    final RateLimitRecord adminSustained = new RateLimitRecord();

    // In-flight counters
    final AtomicInteger readConcurrent = new AtomicInteger(0);
    final AtomicInteger writeConcurrent = new AtomicInteger(0);
    final AtomicInteger adminConcurrent = new AtomicInteger(0);

    // Destructive counters
    final AtomicInteger shutdownCount = new AtomicInteger(0);
    volatile long shutdownLastMs = 0L;
    final AtomicInteger deleteCount = new AtomicInteger(0);
    volatile long deleteLastMs = 0L;
    final AtomicInteger uploadCount = new AtomicInteger(0);
    volatile long uploadLastMs = 0L;

    // Abuse
    final AtomicInteger abuseScore = new AtomicInteger(0);
    volatile boolean blocked = false;
}
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- No new javadoc warnings

---

## Phase 2 — Session state extensions

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to add to `SessionState`

```java
private static class SessionState {
    final String sessionId;
    final long createdAt;
    volatile long lastActivity;
    final String clientIp;  // NEW: client IP for rate limiting
    final String ownerId;   // NEW: stable owner identity
    Map<String, Object> clientInfo;
    Map<String, Object> clientCapabilities;
    String protocolVersion;
    final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    // NEW: bounded notification queue
    final ConcurrentLinkedQueue<String> pendingNotifications = new ConcurrentLinkedQueue<>();
    // NEW: per-category rate limits
    final CategoryRateLimitState categoryLimits = new CategoryRateLimitState();

    SessionState(String sessionId, String clientIp, String ownerId) {
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.lastActivity = this.createdAt;
        this.clientIp = clientIp;
        this.ownerId = ownerId;
    }
}

// NEW global maps
private final ConcurrentHashMap<String, RateLimitRecord> ipRateLimits = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, RateLimitRecord> sessionRateLimits = new ConcurrentHashMap<>();
/** Owner identity to session binding. One session per owner. */
private final ConcurrentHashMap<String, String> sessionOwners = new ConcurrentHashMap<>();

// NEW helper methods
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
```

### What to update

**`handleInitialize`** — extract `ownerId` from params, call `handleSessionCreate`:

```java
private Map<String, Object> handleInitialize(Object id, Map<String, Object> params,
                                              String newSessionId, String clientIp) {
    String ownerId = null;
    if (params != null) {
        Object o = params.get("ownerId");
        if (o instanceof String) ownerId = (String) o;
        // ... existing clientInfo, capabilities, protocolVersion extraction
    }
    SessionState state = handleSessionCreate(newSessionId, clientIp, ownerId);
    // ... rest of existing implementation
}
```

**`handleRequestResponse`** — add `clientIp` param to overload, add max sessions check:

```java
public McpResponse handleRequestResponse(String requestBody, String sessionId, String clientIp) {
    // ... existing parse ...

    // Before "initialize" case:
    if (sessions.size() >= MAX_CONCURRENT_SESSIONS) {
        return new McpResponse(errorResponse(id, -32029,
                "Too Many Requests: max concurrent sessions reached"), sessionId);
    }

    // Refresh activity for valid sessions
    if (hasSession(sessionId)) {
        refreshActivity(sessionId);
    }
}

public McpResponse handleRequestResponse(String requestBody, String sessionId) {
    return handleRequestResponse(requestBody, sessionId, null);
}
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- `McpProtocolHandlerTest` — all pass
- New test: `testMaxConcurrentSessions` — `initialize` returns error when limit reached

---

## Phase 3 — Rate limiting methods

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to add

```java
// Category strings (mirrors McpAuthorization constants)
private static final String CATEGORY_READ = "read";
private static final String CATEGORY_WRITE = "write";
private static final String CATEGORY_ADMIN = "admin";

/**
 * Determine abuse category from required scopes.
 * admin > write > read.
 */
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
 * Check global (IP + session) rate limits for a request method.
 * @return null if allowed, or a denial message.
 */
private String checkRateLimit(String clientIp, String sessionId, String method) {
    // Skip for session-optional methods
    if (isSessionOptional(method)) return null;

    if (clientIp != null && !clientIp.trim().isEmpty()) {
        RateLimitRecord ipRecord = ipRateLimits.computeIfAbsent(
                clientIp, k -> new RateLimitRecord());
        if (!ipRecord.allowRequest(MAX_REQUESTS_PER_IP_PER_MINUTE, RATE_LIMIT_WINDOW_MS)) {
            return "client IP request limit exceeded";
        }
    }
    if (sessionId != null && !sessionId.trim().isEmpty()) {
        RateLimitRecord sessionRecord = sessionRateLimits.computeIfAbsent(
                sessionId, k -> new RateLimitRecord());
        if (!sessionRecord.allowRequest(MAX_REQUESTS_PER_SESSION_PER_MINUTE, RATE_LIMIT_WINDOW_MS)) {
            return "session request limit exceeded";
        }
    }
    return null;
}

/**
 * Check per-category and destructive-tool rate limits.
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
    if (DESTRUCTIVE_TOOLS.contains(toolName)) {
        String destructiveError = checkDestructiveCap(cl, toolName, now, sessionId);
        if (destructiveError != null) return destructiveError;
    }

    // Per-category limits
    switch (category) {
        case CATEGORY_ADMIN:
            if (cl.adminConcurrent.get() >= CATEGORY_ADMIN_CONCURRENT_CAP) {
                addAbuseScore(cl, sessionId, toolName, 1, "admin concurrent cap exceeded");
                return "admin tool concurrent cap exceeded for tool: " + toolName;
            }
            if (!cl.adminBurst.allowRequest(CATEGORY_ADMIN_BURST_LIMIT, RATE_LIMIT_WINDOW_MS)) {
                addAbuseScore(cl, sessionId, toolName, 2, "admin burst limit exceeded");
                return "admin tool burst limit exceeded for tool: " + toolName;
            }
            if (!cl.adminSustained.allowRequest(CATEGORY_ADMIN_SUSTAINED_LIMIT, RATE_LIMIT_SUSTAINED_WINDOW_MS)) {
                addAbuseScore(cl, sessionId, toolName, 2, "admin sustained limit exceeded");
                return "admin tool sustained limit exceeded for tool: " + toolName;
            }
            break;
        case CATEGORY_WRITE:
            if (cl.writeConcurrent.get() >= CATEGORY_WRITE_CONCURRENT_CAP) {
                addAbuseScore(cl, sessionId, toolName, 1, "write concurrent cap exceeded");
                return "write tool concurrent cap exceeded for tool: " + toolName;
            }
            if (!cl.writeBurst.allowRequest(CATEGORY_WRITE_BURST_LIMIT, RATE_LIMIT_WINDOW_MS)) {
                addAbuseScore(cl, sessionId, toolName, 1, "write burst limit exceeded");
                return "write tool burst limit exceeded for tool: " + toolName;
            }
            if (!cl.writeSustained.allowRequest(CATEGORY_WRITE_SUSTAINED_LIMIT, RATE_LIMIT_SUSTAINED_WINDOW_MS)) {
                addAbuseScore(cl, sessionId, toolName, 1, "write sustained limit exceeded");
                return "write tool sustained limit exceeded for tool: " + toolName;
            }
            break;
        default: // READ
            if (cl.readConcurrent.get() >= CATEGORY_READ_CONCURRENT_CAP) {
                addAbuseScore(cl, sessionId, toolName, 1, "read concurrent cap exceeded");
                return "read tool concurrent cap exceeded for tool: " + toolName;
            }
            if (!cl.readBurst.allowRequest(CATEGORY_READ_BURST_LIMIT, RATE_LIMIT_WINDOW_MS)) {
                addAbuseScore(cl, sessionId, toolName, 1, "read burst limit exceeded");
                return "read tool burst limit exceeded for tool: " + toolName;
            }
            if (!cl.readSustained.allowRequest(CATEGORY_READ_SUSTAINED_LIMIT, RATE_LIMIT_SUSTAINED_WINDOW_MS)) {
                addAbuseScore(cl, sessionId, toolName, 1, "read sustained limit exceeded");
                return "read tool sustained limit exceeded for tool: " + toolName;
            }
            break;
    }
    return null;
}

private String checkDestructiveCap(CategoryRateLimitState cl, String toolName,
                                    long now, String sessionId) {
    switch (toolName) {
        case "shutdown":
            if (cl.shutdownCount.get() >= DESTRUCTIVE_CAP_SHUTDOWN) {
                addAbuseScore(cl, sessionId, toolName, 5, "shutdown cap exceeded");
                return "shutdown tool lifetime cap exceeded for session";
            }
            long elapsed = now - cl.shutdownLastMs;
            if (cl.shutdownLastMs > 0 && elapsed < DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS) {
                long remaining = (DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS - elapsed) / 1000;
                return "shutdown tool on cool-down, retry in " + remaining + "s";
            }
            cl.shutdownCount.incrementAndGet();
            cl.shutdownLastMs = now;
            break;
        case "delete_action":
        case "delete_prompt":
            if (cl.deleteCount.get() >= DESTRUCTIVE_CAP_DELETE) {
                addAbuseScore(cl, sessionId, toolName, 3, "delete cap exceeded");
                return toolName + " lifetime cap exceeded for session";
            }
            elapsed = now - cl.deleteLastMs;
            if (cl.deleteLastMs > 0 && elapsed < DESTRUCTIVE_COOLDOWN_DELETE_MS) {
                long remaining = (DESTRUCTIVE_COOLDOWN_DELETE_MS - elapsed) / 1000;
                return toolName + " on cool-down, retry in " + remaining + "s";
            }
            cl.deleteCount.incrementAndGet();
            cl.deleteLastMs = now;
            break;
        case "upload_file":
            if (cl.uploadCount.get() >= DESTRUCTIVE_CAP_UPLOAD) {
                addAbuseScore(cl, sessionId, toolName, 2, "upload cap exceeded");
                return "upload_file lifetime cap exceeded for session";
            }
            elapsed = now - cl.uploadLastMs;
            if (cl.uploadLastMs > 0 && elapsed < DESTRUCTIVE_COOLDOWN_UPLOAD_MS) {
                long remaining = (DESTRUCTIVE_COOLDOWN_UPLOAD_MS - elapsed) / 1000;
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
    String level = score >= ABUSE_SCORE_BLOCK_THRESHOLD ? "SEVERE"
            : score >= ABUSE_SCORE_BLOCK_THRESHOLD / 2 ? "CRITICAL"
            : "WARN";
    LOGGER.warning("[" + level + "] session=" + sessionId
            + " tool=" + toolName + " abuseScore=" + score + " reason=" + reason);
    if (score >= ABUSE_SCORE_BLOCK_THRESHOLD) {
        cl.blocked = true;
        LOGGER.severe("[SEVERE] Session blocked for abuse: " + sessionId);
    }
}

/** Increment in-flight concurrent counter. Must pair with decrementConcurrentCount. */
void incrementConcurrentCount(String sessionId, List<String> requiredScopes) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return;
    switch (toolCategory(requiredScopes)) {
        case CATEGORY_ADMIN: state.categoryLimits.adminConcurrent.incrementAndGet(); break;
        case CATEGORY_WRITE: state.categoryLimits.writeConcurrent.incrementAndGet(); break;
        default:             state.categoryLimits.readConcurrent.incrementAndGet();  break;
    }
}

/** Decrement in-flight concurrent counter. */
void decrementConcurrentCount(String sessionId, List<String> requiredScopes) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return;
    switch (toolCategory(requiredScopes)) {
        case CATEGORY_ADMIN: state.categoryLimits.adminConcurrent.decrementAndGet(); break;
        case CATEGORY_WRITE: state.categoryLimits.writeConcurrent.decrementAndGet(); break;
        default:             state.categoryLimits.readConcurrent.decrementAndGet();  break;
    }
}
```

### What to update in `handleRequestResponse`

Add rate limit check after parsing and before dispatch:

```java
// Check rate limits before processing
String rateLimitError = checkRateLimit(clientIp, sessionId, method);
if (rateLimitError != null) {
    return new McpResponse(errorResponse(id, -32029,
            "Too Many Requests: " + rateLimitError), sessionId);
}
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- New test file: `McpRateLimitTest.java` with tests for:
  - IP rate limit exceeded
  - Session rate limit exceeded
  - Read/write/admin category burst limits
  - Read/write/admin sustained limits
  - Concurrent cap exceeded
  - Destructive tool lifetime caps
  - Destructive tool cooldown
  - Abuse score accumulates
  - Abuse score blocks session

---

## Phase 4 — Queue overflow handling

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to add

```java
/** Listener for notification queue overflow events. */
public interface QueueOverflowListener {
    void onOverflow(String sessionId);
}

/** Thrown when notification queue overflows and no listener is configured. */
public static class QueueOverflowException extends RuntimeException {
    public QueueOverflowException(String sessionId) {
        super("MCP notification queue is full for session: " + sessionId);
    }
}

// New constructor parameter
private final QueueOverflowListener overflowListener;

// New constructor overload
public McpProtocolHandler(McpRegistry registry, McpServerConfig config,
                            QueueOverflowListener overflowListener) {
    // ... existing ...
    this.overflowListener = overflowListener;
    // ... existing ...
}

/**
 * Enqueue notification with bounded capacity.
 * Synchronized to prevent race between size check and offer.
 */
private void enqueue(SessionState state, String notification) {
    synchronized (state.pendingNotifications) {
        if (state.pendingNotifications.size() >= MAX_PENDING_NOTIFICATIONS_PER_SESSION) {
            if (overflowListener != null) {
                overflowListener.onOverflow(state.sessionId);
                return;
            }
            throw new QueueOverflowException(state.sessionId);
        }
        state.pendingNotifications.offer(notification);
    }
}

/** Return next queued notification as JSON-RPC message, or null if queue empty. */
public String pollPendingNotification(String sessionId) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return null;
    String notification = state.pendingNotifications.poll();
    if (notification == null) return null;
    try {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", JSONRPC_VERSION);
        message.put("method", "notifications/resources/updated");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uri", notification);
        message.put("params", params);
        return GSON.toJson(message);
    } catch (Exception e) {
        LOGGER.severe("Unable to serialise resource update notification");
        return null;
    }
}
```

### What to update

`notifyResourceUpdated` — use `enqueue` instead of direct offer:

```java
public void notifyResourceUpdated(String uri) {
    if (uri == null) return;
    for (SessionState state : sessions.values()) {
        if (!state.subscriptions.contains(uri)) continue;
        enqueue(state, uri);  // was: state.pendingNotifications.offer(uri)
    }
}
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- New test: `testQueueOverflowWithListener` — overflow calls listener
- New test: `testQueueOverflowWithoutListener` — throws `QueueOverflowException`

---

## Phase 5 — Authorization SPI

**Owner:** `dev-backend`
**Files:**
- `src/main/java/io/github/vinhphan812/mcp/api/spi/McpAuthorization.java` (NEW)
- `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to create

```java
// src/main/java/io/github/vinhphan812/mcp/api/spi/McpAuthorization.java

package io.github.vinhphan812.mcp.api.spi;

import java.util.Map;

/**
 * SPI for tool authorization.
 * Implement this to enforce scope-based or confirmation-based tool access control.
 *
 * <p>The server calls {@link #denial(String[], boolean, Map)} before invoking a tool.
 * Return null to allow the call; return a denial message string to reject it.
 *
 * <p>Example: require "admin" scope for destructive tools.
 *
 * <pre>{@code
 * public class MyAuth implements McpAuthorization {
 *     public String denial(String[] requiredScopes, boolean confirmationRequired,
 *                          Map<String, Object> arguments) {
 *         for (String scope : requiredScopes) {
 *             if (!currentUser.hasScope(scope)) {
 *                 return "Missing required scope: " + scope;
 *             }
 *         }
 *         if (confirmationRequired && !currentUser.confirmed()) {
 *             return "Tool requires user confirmation";
 *         }
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * <p>To enable, set via {@code McpServerConfig.Builder.authorization(myAuth)}.
 * If no authorization is configured, all tools are allowed.
 */
public interface McpAuthorization {

    /** Category constant for read operations. */
    String READ = "read";

    /** Category constant for write operations. */
    String WRITE = "write";

    /** Category constant for admin/destructive operations. */
    String ADMIN = "admin";

    /**
     * Check whether the current context is authorised to call a tool.
     *
     * @param requiredScopes array of required scope names (e.g. "admin", "write", "read")
     *                        — may be empty
     * @param confirmationRequired true if the tool requires explicit user confirmation
     * @param arguments the tool's input arguments — may be null
     * @return null if authorised; a denial message string if not authorised
     */
    String denial(String[] requiredScopes, boolean confirmationRequired,
                 Map<String, Object> arguments);
}
```

### What to update in `McpProtocolHandler`

**New constructor param:**

```java
private final McpAuthorization authorization;

public McpProtocolHandler(McpRegistry registry, McpServerConfig config) {
    this(registry, config, null, null);
}

public McpProtocolHandler(McpRegistry registry, McpServerConfig config,
                            QueueOverflowListener overflowListener,
                            McpAuthorization authorization) {
    // ... existing ...
    this.authorization = authorization != null ? authorization
            : (scopes, conf, args) -> null; // default: allow all
}
```

**`handleToolsCall`** — add authorization check:

```java
// After finding definition and extracting requiredScopes...
String denial = null;
if (authorization != null) {
    String[] scopes = requiredScopes == null ? new String[0]
            : requiredScopes.toArray(new String[0]);
    denial = authorization.denial(scopes, Boolean.TRUE.equals(definition.get("confirmationRequired")), arguments);
}
if (denial != null) return errorToolResult("Authorization denied: " + denial);
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- New test: `McpAuthorizationTest.java` — authorization called, denial returned

---

## Phase 6 — Input schema validation

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to update in `handleToolsCall`

Add before authorization check:

```java
// Validate required input parameters from registered schema
if (definition != null) {
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
    // Extract requiredScopes for authorization
    requiredScopes = (List<String>) definition.get("requiredScopes");
}
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- Existing `McpReflectionRegistrarDirectBindingTest` covers this via schema

---

## Phase 7 — Concurrent counter integration

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to update in `handleToolsCall`

Wrap tool invocation in concurrent counter:

```java
final List<String> scopesForCounter = requiredScopes;
incrementConcurrentCount(sessionId, scopesForCounter);
try {
    Map<String, Object> toolResult = handler.call(arguments);
    return toolResult;
} catch (Exception e) {
    LOGGER.severe("Tool call error: " + toolName);
    return errorToolResult("Error calling " + toolName + ": " + e.getMessage());
} finally {
    decrementConcurrentCount(sessionId, scopesForCounter);
}
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- `McpRateLimitTest.testConcurrentCapExceeded` — concurrent counter blocks

---

## Phase 8 — Session cleanup thread

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

### What to add

```java
private volatile boolean cleanupRunning = false;
private Thread cleanupThread = null;

private synchronized void startCleanupThread() {
    if (cleanupRunning) return;
    cleanupRunning = true;
    cleanupThread = new Thread(() -> {
        while (cleanupRunning) {
            try {
                Thread.sleep(SESSION_CLEANUP_INTERVAL_MS);
                long now = System.currentTimeMillis();
                for (Map.Entry<String, SessionState> entry : sessions.entrySet()) {
                    if (now - entry.getValue().lastActivity > SESSION_TIMEOUT_MS
                            && sessions.remove(entry.getKey(), entry.getValue())) {
                        clearOwnerBinding(entry.getValue());
                        sessionRateLimits.remove(entry.getKey());
                        LOGGER.warning("Expired inactive MCP session: " + entry.getKey());
                    }
                }
                // Prune idle IP rate limit records
                ipRateLimits.entrySet().removeIf(entry ->
                        entry.getValue().getRequestCount(RATE_LIMIT_WINDOW_MS) == 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }, "mcp-session-cleanup");
    cleanupThread.setDaemon(true);
    cleanupThread.start();
}

public synchronized void shutdown() {
    cleanupRunning = false;
    if (cleanupThread != null) cleanupThread.interrupt();
    cleanupThread = null;
    ipRateLimits.clear();
    sessionRateLimits.clear();
}
```

Call `startCleanupThread()` in constructor (existing pattern already has it).

Call `shutdown()` in `McpServer.stop()`.

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- `McpProtocolHandlerTest` — sessions expire after timeout

---

## Phase 9 — McpServerConfig extensions

**Owner:** `dev-backend`
**File:** `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java`

### What to add

```java
private QueueOverflowListener overflowListener;
private McpAuthorization authorization;

/** Builder method to set the queue overflow listener. */
public Builder overflowListener(QueueOverflowListener listener) {
    this.overflowListener = listener;
    return this;
}

/** Builder method to set the authorization handler. */
public Builder authorization(McpAuthorization authorization) {
    this.authorization = authorization;
    return this;
}

public QueueOverflowListener getOverflowListener() {
    return overflowListener;
}

public McpAuthorization getAuthorization() {
    return authorization;
}
```

### What to update in `McpServer`

```java
// McpServer constructor passes overflow listener and authorization to handler
protocolHandler = new McpProtocolHandler(registry, config,
        config.getOverflowListener(), config.getAuthorization());
```

### Validation
- `./gradlew clean test` — BUILD SUCCESSFUL
- `McpServerConfigTest` — overflow listener and authorization set and retrieved

---

## Phase 10 — Documentation

**Owner:** `dev-backend`
**Files:**
- `docs/adr/ADR-0011-security-rate-limiting.md` — update status to Accepted
- `docs/adr/README.md` — update ADR-0011 status
- `docs/API-REFERENCE.md` — add `McpAuthorization` SPI section
- `docs/PROJECT-GUIDE.md` — add security section
- `docs/IMPLEMENTATION-STATUS.md` — mark security features as Implemented

### Update ADR status

```diff
-**Status:** Proposed
+**Status:** Accepted
 **Date:** 2026-09-14
+**Accepted:** 2026-09-14 — all 10 phases implemented and tested.
```

### Add `McpAuthorization` to API-REFERENCE.md

```markdown
### `McpAuthorization` SPI

Security interface for tool authorization. Set via `McpServerConfig.Builder.authorization(...)`.

```java
public interface McpAuthorization {
    String denial(String[] requiredScopes, boolean confirmationRequired,
                 Map<String, Object> arguments);
    // Constants: READ = "read", WRITE = "write", ADMIN = "admin"
}
```

Return `null` to allow; return a denial message to reject.

If not configured, all tools are allowed. The authorization handler is consulted after
schema validation and before the tool handler is invoked.
```

### Add security section to PROJECT-GUIDE.md

```markdown
## Security

The SDK provides built-in security controls (ADR-0011):

- **Owner-based sessions:** one active session per owner identity
- **Per-category rate limiting:** read/write/admin burst + sustained + concurrent caps
- **Destructive tool caps:** lifetime limits + cooldown for shutdown, delete, upload
- **Abuse scoring:** weighted signals accumulate; session blocked at threshold
- **Queue overflow handling:** bounded notification queue; overflow reported via listener
- **IP-based rate limiting:** per-client-IP request limits
- **Max concurrent sessions:** configurable cap on active sessions
- **Authorization SPI:** `McpAuthorization` for scope-based tool access control

See `docs/adr/ADR-0011-security-rate-limiting.md` for the full design.
```

### Update IMPLEMENTATION-STATUS.md

Mark `McpAuthorization` SPI and all rate-limiting features as Implemented.

### Validation
- `./gradlew clean build javadoc` — BUILD SUCCESSFUL, 0 warnings
- `gh run list` — CI passed

---

## Test summary

| Test file | Coverage |
|---|---|
| `McpProtocolHandlerTest` | Session lifecycle, initialize, terminate, closeAll |
| `McpRateLimitTest` | IP/session limits, category burst/sustained/concurrent, destructive caps, cooldown, abuse scoring, blocking |
| `McpQueueOverflowTest` | Bounded queue, overflow with listener, overflow without listener (exception) |
| `McpAuthorizationTest` | Authorization called, denial returned, null allows |
| `McpSecurityConfigTest` | Overflow listener and authorization set via config |
| `McpSessionTimeoutTest` | Session expires after idle timeout |
| `McpOwnerSessionTest` | One session per owner, replace existing |

---

## Files summary

| File | Action |
|---|---|
| `core/McpProtocolHandler.java` | Rewrite ~1800 lines |
| `api/spi/McpAuthorization.java` | New interface |
| `api/config/McpServerConfig.java` | Add overflowListener + authorization fields |
| `core/McpServer.java` | Pass overflowListener + authorization to handler |
| `McpRateLimitTest.java` | New test |
| `McpQueueOverflowTest.java` | New test |
| `McpAuthorizationTest.java` | New test |
| `McpSecurityConfigTest.java` | New test |
| `McpSessionTimeoutTest.java` | New test |
| `McpOwnerSessionTest.java` | New test |
| `docs/adr/ADR-0011-security-rate-limiting.md` | Update status → Accepted |
| `docs/adr/ADR-0011-implementation-plan.md` | This document |
| `docs/API-REFERENCE.md` | Add McpAuthorization section |
| `docs/PROJECT-GUIDE.md` | Add security section |
| `docs/IMPLEMENTATION-STATUS.md` | Mark features implemented |
| `docs/adr/README.md` | Update ADR-0011 status |

---

## Acceptance criteria

1. `./gradlew clean test` — BUILD SUCCESSFUL, all tests pass
2. `./gradlew clean build javadoc` — BUILD SUCCESSFUL, 0 javadoc warnings
3. `examples/compileJava` — BUILD SUCCESSFUL against built JAR
4. GitHub Actions CI — PASSED
5. ADR-0011 status — Accepted
6. All documentation updated and cross-linked
