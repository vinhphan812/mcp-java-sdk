# ADR-0024 — Command Authorization, Lease, and Acknowledgement Lifecycle

**Status:** Accepted
**Date:** 2026-10-17
**Authors:** MCP Java SDK team
**Parent:** [ADR-0006](ADR-0006-security-model.md), [ADR-0011](ADR-0011-security-rate-limiting.md)

---

## Context

This ADR documents three interrelated lifecycle mechanisms in the MCP Java SDK:

1. **Command Authorization** — scope-based and confirmation-based access control for tool execution
2. **Lease (Reservation)** — concurrent request slot management for category-based rate limiting
3. **Acknowledgement** — protocol-level confirmation for streaming subscriptions and task updates

These mechanisms work together: authorization gates whether a command may execute, lease reserves a concurrent slot during execution, and acknowledgement signals completion or progress to the client.

---

## 1. Command Authorization

### 1.1 SPI Contract

Authorization is implemented via the `McpAuthorization` SPI (`api/spi/McpAuthorization.java`):

```java
public interface McpAuthorization {
    String READ = "read";
    String WRITE = "write";
    String ADMIN = "admin";

    /**
     * Check whether the current context is authorised to call a tool.
     *
     * @param requiredScopes         array of required scope names (e.g. "admin", "write", "read")
     * @param confirmationRequired    true if the tool requires explicit user confirmation
     * @param arguments              the tool's input arguments
     * @return null if authorised; a denial message string if not authorised
     */
    String denial(String[] requiredScopes, boolean confirmationRequired,
                  Map<String, Object> arguments);
}
```

### 1.2 Metadata Declaration

Tools declare authorization requirements via the `@McpTool` annotation:

```java
@McpTool(
    name = "delete-user",
    description = "Permanently removes a user account",
    scopes = {"admin:users"},
    confirmationRequired = true
)
public Map<String, Object> deleteUser(
        @McpParam(name = "userId", required = true) String userId) {
    // ...
}
```

### 1.3 Authorization Flow

The authorization check occurs in `McpProtocolHandler.handleToolsCall()` (lines 2218-2229):

```
1. Tool definition loaded from registry
2. Extract requiredScopes and confirmationRequired from definition
3. Call authorization.denial(scopes, confirmationRequired, arguments)
4. If denial != null → return errorToolResult("Authorization denied: " + denial)
5. If denial == null → proceed to handler invocation
```

**Error code:** `-32001` (Authorization Denied, per MCP spec)

### 1.4 Configuration

Enable authorization via `McpServerConfig`:

```java
McpServerConfig config = McpServerConfig.builder()
    .authorization(new MyAuthorizationImpl())
    .build();
```

If no authorization is configured, all tools are allowed by default.

---

## 2. Lease (Concurrent Slot Reservation)

### 2.1 Purpose

Category-based rate limiting reserves concurrent execution slots per session. Each slot represents one in-flight tool execution. Slots are acquired before authorization and released after completion (success, error, or exception).

### 2.2 Categories

| Category | Description |
|----------|-------------|
| `READ` | Tools with no special scope (default) |
| `WRITE` | Tools requiring write scope |
| `ADMIN` | Tools requiring admin/destructive scope |

The category is determined by the tool's `requiredScopes`:
- Empty/null scopes → `READ`
- Any scope → `WRITE` (or highest scope if multiple)

### 2.3 Reservation Flow

Reservation occurs in `McpProtocolHandler.handleRequestResponse()` (lines 864-875):

```
1. Extract tool scopes from tool definition
2. Determine category via categoryRateLimitController.categoryForMethod()
3. Call categoryRateLimitController.reserve(sessionId, category)
4. If reserve returns false → 429 error (concurrent limit exceeded)
5. If reserve returns true → proceed with request
6. In finally block: release the slot via categoryRateLimitController.release()
```

### 2.4 Release Paths

Slots are released in the following scenarios:

| Path | Release Mechanism |
|------|------------------|
| Handler completes successfully | `finally` block calls `release()` |
| Handler throws exception | `finally` block calls `release()` |
| Early return (methodNotFound) | `finally` block calls `release()` |
| Rate limit exceeded | Slot never acquired; no release needed |
| Authorization denied | Slot already acquired; `finally` releases |

### 2.5 Testing

`McpCategoryReservationTest` validates:
- N concurrent requests succeed, N+1 is denied
- Release unblocks waiting requests
- Exception paths release slots
- Early return paths release slots

---

## 3. Acknowledgement

### 3.1 SEP-2575 Subscriptions Acknowledgement

For streaming subscriptions via `subscriptions/listen` (2026-07-28):

**Protocol:**
1. Client sends `subscriptions/listen` request
2. Server responds with HTTP 200 and switches to SSE streaming
3. **First frame:** `notifications/subscriptions/acknowledged` notification
4. Subsequent frames: queued events for the subscription
5. Final frame (on close): `notifications/subscriptions/complete` with `resultType: "complete"`

**Implementation:** `McpHttpHandler.handleSep2575Streaming()` (lines 704-707):

```java
// First frame: acknowledgment notification with the subscription id
String ackBody = buildSep2575Ack(subscriptionId);
response.getWriter().write(sseMessage(ackBody));
response.getWriter().flush();
```

### 3.2 SEP-2663 Tasks Update Acknowledgement

For task input responses via `tasks/update`:

**Protocol:**
1. Client sends `tasks/update` with `taskId` and `inputResponses`
2. Server validates requestState token (replay protection)
3. Server returns empty acknowledgement on success
4. Application processes input out-of-band, then transitions task state

**Implementation:** `McpProtocolHandler.handleTasksUpdate()` (lines 1535-1583):

```java
// SEP-2663 §Task Update: empty acknowledgement on success
return new LinkedHashMap<>();
```

### 3.3 RequestState Token (Replay Protection)

For tasks requiring input (`INPUT_REQUIRED` state), the server issues a `requestState` token:

1. **Issue:** `RequestStateManager.create()` generates a token with TTL and max rounds
2. **Validate:** `RequestStateManager.validateAndGet(token)` checks token validity
3. **Consume:** Token is removed after successful validation (single-use per round)

This prevents replay attacks where a client resubmits stale input for a previous task.

---

## 4. Integration Example

Complete flow showing all three mechanisms:

```java
// 1. Server configuration with authorization
McpAuthorization auth = new ScopeAuthorization();
McpServerConfig config = McpServerConfig.builder()
    .authorization(auth)
    .build();

// 2. Client calls tools/call
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
        "name": "delete-user",
        "arguments": { "userId": "123" }
    }
}

// 3. Server processing:
//    a) Category reservation: reserve("admin") slot
//    b) Authorization: auth.denial(["admin:users"], false, {userId: "123"})
//    c) Handler invocation: execute deleteUser("123")
//    d) Release: finally { release("admin") }

// 4. Server response:
//    {
//        "jsonrpc": "2.0",
//        "id": 1,
//        "result": { "resultType": "complete", ... }
//    }
```

---

## 5. Related Decisions

| ADR | Topic |
|-----|-------|
| ADR-0006 | Security Model: Origin, Authentication, Input Validation |
| ADR-0011 | Security Rate Limiting |
| ADR-0022 | MRTR Server-to-Client Foundation and Elicitation |

---

## 6. Test Coverage

| Mechanism | Test File |
|-----------|-----------|
| Authorization | `McpAuthorizationTest.java` |
| Category Reservation | `McpCategoryReservationTest.java` |
| Subscriptions Acknowledgement | `Mcp2026SubscriptionsTest.java` |
| Tasks Update | `McpTasksTest.java`, `McpTaskExtensionTest.java` |

---

## 7. Validation Checklist

- [ ] `./gradlew test --console=plain` — all tests pass
- [ ] Authorization denial returns error code `-32001`
- [ ] Rate-limit denial returns error code `-32029`
- [ ] `notifications/subscriptions/acknowledged` sent as first SSE frame
- [ ] `tasks/update` returns empty acknowledgement
- [ ] RequestState token prevents replay attacks
