# MCP Security Synthesis — Tier 2 Specification

**Task:** t_ee6de940  
**Date:** 2026-09-22  
**Status:** Draft — for review  
**Sources:** t_757e71e4, t_c19a82c6, t_ee9d675b

---

## Table of Contents

1. [Unified Admission/Lifecycle Design](#1-unified-admissionlifecycle-design)
2. [Data-Driven Policy Design](#2-data-driven-policy-design)
3. [Cancellation Cleanup Ownership](#3-cancellation-cleanup-ownership)
4. [Implementation Work Decomposition](#4-implementation-work-decomposition)

---

## 1. Unified Admission/Lifecycle Design

### 1.1 Design Principle: Slot-Reservation Pattern

Session admission and category concurrency share the same three-phase pattern. Both must guard against races between the
check and the reservation. The fix for each is distinct but structurally identical:

| Phase       | Session Admission                        | Category Concurrency                               |
|-------------|------------------------------------------|----------------------------------------------------|
| **Acquire** | `LongAdder.increment()` before check     | CAS loop (`compareAndSet`) on `AtomicInteger`      |
| **Check**   | After increment: reject if `sum() > cap` | Inside CAS loop: reject if `observed >= cap`       |
| **Release** | `finally` block: `LongAdder.decrement()` | `finally` block: `AtomicInteger.decrementAndGet()` |

Both patterns are **Java 8-compatible**: `LongAdder` (Java 8), `AtomicInteger.compareAndSet` (Java 5), and
`ConcurrentHashMap` are the only concurrency primitives used. No `VarHandle`, no `StampedLock`, no `Phaser`.

---

### 1.2 Session Admission — Atomic Slot Counter

**Current problem:** `sessions.size()` at L585 is a stale snapshot. Multiple threads can pass the check before any
inserts, admitting N+1 sessions.

**Solution: `LongAdder` slot counter**

```java
// New field in McpProtocolHandler
private static final LongAdder sessionSlotCount = new LongAdder();
```

**Reservation sequence (in `handleRequestResponse`, before routing to `handleInitialize`):**

```java
if ("initialize".equals(method)) {
    sessionSlotCount.increment();                       // reserve slot
    try {
        if (sessionSlotCount.sum() > rateLimits.maxConcurrentSessions) {
            sessionSlotCount.decrement();                // undo — over cap
            return new McpResponse(errorResponse(id, -32029,
                "Too Many Requests: max concurrent sessions reached"), sessionId);
        }
        // proceed to handleInitialize — finally block handles release on error
    } catch (Throwable t) {
        sessionSlotCount.decrement();                   // rollback on any exception
        throw t;
    }
}
```

**Release in existing `finally` block (lines 762–782):**

```java
} finally {
    // existing: decrementMethodConcurrentCount, handlePendingNotifications
    if ("initialize".equals(method)) {
        sessionSlotCount.decrement();
    }
}
```

**Rollback on `handleInitialize` exception:** See Section 1.4.

**Java 8 compatibility:** `LongAdder` is in `java.util.concurrent.atomic`. It uses a striped cell array for
low-contention increments — appropriate for the session-admission burst case.

---

### 1.3 Category Concurrency — CAS Reserve-then-Increment

**Current problem:** `if (cl.adminConcurrent.get() >= cap)` followed by `incrementAndGet()` is a check-then-act race. Up
to N threads pass the check before any increment.

**Solution: Replace check-then-increment with CAS loop**

```java
/**
 * Atomically reserve a concurrent slot for the given category.
 * Returns true if reserved; false if the category cap is already exhausted.
 * Java 8-compatible.
 *
 * @param cl        the session's category limit state
 * @param category  one of CATEGORY_ADMIN, CATEGORY_WRITE, CATEGORY_READ
 * @return true if the slot was acquired
 */
private boolean tryReserveCategorySlot(CategoryRateLimitState cl, String category) {
    int cap;
    AtomicInteger counter;
    switch (category) {
        case CATEGORY_ADMIN:  cap = rateLimits.adminConcurrent;  counter = cl.adminConcurrent;  break;
        case CATEGORY_WRITE:  cap = rateLimits.writeConcurrent;  counter = cl.writeConcurrent;  break;
        default:              cap = rateLimits.readConcurrent;   counter = cl.readConcurrent;   break;
    }
    do {
        int observed = counter.get();
        if (observed >= cap) return false;                         // at cap — deny
    } while (!counter.compareAndSet(observed, observed + 1));      // CAS failed — retry
    return true;
}

/**
 * Release the category slot reserved by tryReserveCategorySlot.
 * Called unconditionally from the request finally block.
 */
private void releaseCategorySlot(CategoryRateLimitState cl, String category) {
    switch (category) {
        case CATEGORY_ADMIN:  cl.adminConcurrent.decrementAndGet();  break;
        case CATEGORY_WRITE:  cl.writeConcurrent.decrementAndGet();  break;
        default:              cl.readConcurrent.decrementAndGet();    break;
    }
}
```

**Caller change (line ~617, replacing `checkMethodRateLimit` denial logic):**

```java
SlotReservation reservation = tryReserveCategorySlot(state.categoryLimits, category);
if (!reservation) {
    addAbuseScore(state, sessionId, method, WEIGHT_CONCURRENT, category + " concurrent cap exceeded");
    return new McpResponse(errorResponse(id, -32029,
        "Too Many Requests: " + category + " concurrent cap exceeded"), sessionId);
}
// proceed — finally block calls releaseCategorySlot
```

**`finally` block update:** Replace the existing `decrementMethodConcurrentCount` calls (lines 762–782) with a single
`releaseCategorySlot(state.categoryLimits, category)` call. The existing `incrementMethodConcurrentCount` calls (lines
1081+) are removed; reservation is now exclusive of increment.

**Note:** Burst and sustained checks (`cl.adminBurst.allowRequest(...)`) remain check-then-act but are safe because
`allowRequest` is atomic (removes expired timestamps and adds the new one in one pass on `ConcurrentLinkedQueue`). The
concurrent counter is the only slot that requires CAS.

---

### 1.4 Exception/Disconnect Guarantees

#### 1.4.1 `handleInitialize` Exception Rollback

**Problem:** `sessions.put(sessionId, state)` at L918 runs before `handleInitialize`. If `handleInitialize` throws, the
entry is orphaned, inflating `sessions.size()`.

**Fix — add to both catch blocks in `handleRequestResponse`:**

```java
} catch (McpErrorException e) {
    if ("initialize".equals(method)) {
        sessions.remove(initSessionId);                           // undo sessions.put
        sessionSlotCount.decrement();                            // undo slot counter
        if (responseSessionId != null && !responseSessionId.equals(sessionId)) {
            sessionOwners.remove(responseSessionId);              // undo owner binding
        }
    }
    // ... existing: decrementMethodConcurrentCount / releaseCategorySlot ...
    return new McpResponse(errorResponse(id, e.getCode(), e.getMessage()), sessionId);

} catch (Throwable t) {
    if ("initialize".equals(method)) {
        sessions.remove(initSessionId);
        sessionSlotCount.decrement();
        if (responseSessionId != null && !responseSessionId.equals(sessionId)) {
            sessionOwners.remove(responseSessionId);
        }
    }
    // ... existing: decrementMethodConcurrentCount / releaseCategorySlot ...
    return new McpResponse(errorResponse(id, -32000, "Internal error: " + t.getMessage()), sessionId);
}
```

#### 1.4.2 Exception Guarantees Summary

| Scenario                          | Slot counter             | `sessions` map                   | `sessionOwners` map | Rate-limit counters           |
|-----------------------------------|--------------------------|----------------------------------|---------------------|-------------------------------|
| Normal completion                 | Decremented in `finally` | Entry remains (session alive)    | Unchanged           | Decremented in `finally`      |
| `McpErrorException` in initialize | Decremented in catch     | `sessions.remove(initSessionId)` | Removed if bound    | N/A (no counter reserved yet) |
| `Throwable` in initialize         | Decremented in catch     | `sessions.remove(initSessionId)` | Removed if bound    | N/A                           |
| Tool call throws                  | N/A                      | Unchanged                        | Unchanged           | Decremented in `finally`      |
| Category cap hit (denied)         | N/A                      | Unchanged                        | Unchanged           | No counter incremented        |

#### 1.4.3 Disconnect Guarantees (SSE Path)

`McpGrizzlyHandler.handleGet()` SSE disconnect at L385 releases the SSE permit but does not call `terminateSession`. The
session remains alive until idle timeout. This is acceptable: the session is not leaked, but its cancellation state is
not cleaned up until timeout (see Section 3).

**Future improvement (out of scope for this spec):** Route the SSE disconnect `IOException` through
`handler.terminateSession(sessionId)` to achieve immediate cleanup.

---

### 1.5 Policy Error Responses

All rate-limit and security denials return MCP error responses via `errorResponse(id, code, message)`. The HTTP status
code is set by the transport layer (`McpGrizzlyHandler`); the MCP handler sets only the JSON-RPC error code.

| Condition                           | MCP error code       | Message pattern                                           | HTTP status (transport) | Abuse score    |
|-------------------------------------|----------------------|-----------------------------------------------------------|-------------------------|----------------|
| Session cap exceeded (`initialize`) | `-32029`             | `"Too Many Requests: max concurrent sessions reached"`    | 503                     | No             |
| Category concurrent cap exceeded    | `-32029`             | `"Too Many Requests: {category} concurrent cap exceeded"` | 429                     | Yes (weight 2) |
| Category burst/sustained exceeded   | `-32029`             | `"Too Many Requests: {category} rate limit exceeded"`     | 429                     | Yes (weight 1) |
| Destructive cap exceeded (cooldown) | `-32029`             | `"{tool} on cool-down, retry in {N}s"`                    | 429                     | Yes (weight 3) |
| Abuse score threshold hit           | `-32029`             | `"Session blocked due to abuse"`                          | 429                     | N/A            |
| Authorization denial                | `-32000` or `-32001` | From `McpAuthorization.denial()`                          | 403                     | No             |
| Input validation failure            | `-32602`             | `"Missing required parameter(s): [...]"`                  | 400                     | No             |
| Internal error (any Throwable)      | `-32000`             | `"Internal error: {detail}"`                              | 500                     | No             |

**HTTP 429 vs 500:** 429 is returned for policy denials (client can retry after cooldown). 500 is returned for internal
errors (programming bugs, unhandled exceptions). Custom MCP error codes (`-32xxx`) are preferred for transportable SDK
errors; `-32000` is the JSON-RPC `Internal error` sentinel.

---

## 2. Data-Driven Policy Design

### 2.1 `DestructivePolicy` Interface

Replace the hard-coded `switch` in `checkDestructiveCap` with a policy lookup map. This enables runtime configuration of
enforcement behaviour per tool name.

```java
package io.github.vinhphan812.mcp.core;

/**
 * Associates a destructive tool name with its per-session enforcement parameters.
 * Functional interface — implemented as lambdas or concrete classes.
 *
 * <p>Default policies are registered in {@link #DEFAULT_POLICIES}. Custom policies
 * are registered at build time via {@link RateLimits.Builder#destructiveToolPolicy}.
 *
 * <p>Unknown tool names (not in DEFAULT_POLICIES and no custom policy registered)
 * use {@link #NO_OP}, which silently allows the call without logging or scoring.
 * This preserves backward compatibility; see Section 2.3 for the migration plan.
 */
@FunctionalInterface
public interface DestructivePolicy {

    /**
     * Checks and records a destructive tool call.
     *
     * @param cl         the session's rate-limit state (read/write)
     * @param toolName   the tool name (used for error messages and shared-counter tools)
     * @param now        current epoch milliseconds
     * @param sessionId  session id (used for error messages)
     * @param rateLimits the server's rate-limit configuration
     * @return null if the call is allowed; an error string if the call should be blocked
     */
    String check(CategoryRateLimitState cl, String toolName, long now,
                 String sessionId, RateLimits rateLimits);

    /** Sentinel policy that performs no action. */
    DestructivePolicy NO_OP = (cl, tool, now, sid, rl) -> null;
}
```

### 2.2 Default Policy Registry

```java
private static final Map<String, DestructivePolicy> DEFAULT_POLICIES;

static {
    Map<String, DestructivePolicy> m = new LinkedHashMap<>();

    m.put("shutdown", (cl, tool, now, sid, rl) -> {
        if (cl.shutdownCount.get() >= rl.shutdownCap) {
            if (cl.shutdownLastMs == 0L) cl.shutdownLastMs = now;
            long elapsedSec = (now - cl.shutdownLastMs) / 1000;
            long cooldownSec = rl.shutdownCooldownMs / 1000;
            if (elapsedSec < cooldownSec) {
                return "shutdown on cool-down, retry in " + (cooldownSec - elapsedSec) + "s";
            }
            cl.shutdownCount.set(0);
            cl.shutdownLastMs = now;
        }
        cl.shutdownCount.incrementAndGet();
        return null;
    });

    // delete_action and delete_prompt share cl.deleteCount
    DestructivePolicy deletePolicy = (cl, tool, now, sid, rl) -> {
        if (cl.deleteCount.get() >= rl.deleteCap) {
            if (cl.deleteLastMs == 0L) cl.deleteLastMs = now;
            long elapsedSec = (now - cl.deleteLastMs) / 1000;
            long cooldownSec = rl.deleteCooldownMs / 1000;
            if (elapsedSec < cooldownSec) {
                return tool + " on cool-down, retry in " + (cooldownSec - elapsedSec) + "s";
            }
            cl.deleteCount.set(0);
            cl.deleteLastMs = now;
        }
        cl.deleteCount.incrementAndGet();
        return null;
    };
    m.put("delete_action", deletePolicy);
    m.put("delete_prompt", deletePolicy);    // documented: shares counter with delete_action

    m.put("upload_file", (cl, tool, now, sid, rl) -> {
        if (cl.uploadCount.get() >= rl.uploadCap) {
            if (cl.uploadLastMs == 0L) cl.uploadLastMs = now;
            long elapsedSec = (now - cl.uploadLastMs) / 1000;
            long cooldownSec = rl.uploadCooldownMs / 1000;
            if (elapsedSec < cooldownSec) {
                return "upload_file on cool-down, retry in " + (cooldownSec - elapsedSec) + "s";
            }
            cl.uploadCount.set(0);
            cl.uploadLastMs = now;
        }
        cl.uploadCount.incrementAndGet();
        return null;
    });

    DEFAULT_POLICIES = Collections.unmodifiableMap(m);
}
```

### 2.3 Backward-Compatibility Rules

#### 2.3.1 Unknown Tool Names in Existing Configs

When `checkDestructiveCap` is called for a tool name that is in `rateLimits.destructiveTools` but has no entry in
`DEFAULT_POLICIES` and no custom policy registered:

| Behaviour                                               | Rationale                                                                                                                                              |
|---------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Silent no-op** (no exception, no log, no abuse score) | Matches current behaviour — introducing a hard failure or warning log would break existing deployments that list tool names without enforcement intent |

**Migration path:** Operators who want strict enforcement should call `.destructiveToolPolicy(name, customPolicy)` or
use a custom policy builder. Operators who want warnings for misconfigured tool names should set
`RateLimits.Builder#warnOnUnknownDestructiveTool(true)` (new flag, default `false`). When `true`, an unknown tool in
`destructiveTools` logs at `WARN` level.

#### 2.3.2 `set_mcp_api_key` and `revoke_mcp_api_key`

These two names remain in the default `destructiveTools` set (Section 1.2 of the audit). They have no enforcement
because they are not in `DEFAULT_POLICIES`. This is a **known gap** tracked as a follow-up task (see Section 4, task F).

**Decision for this spec:** Do not remove these names from the default set. Operators who need enforcement should add
custom `DestructivePolicy` entries via the builder. A separate follow-up task will address whether to implement
enforcement for these names or remove them from the default set.

#### 2.3.3 Behavioral Parity for Known Tools

The three tools with existing enforcement (`shutdown`, `delete_action`/`delete_prompt`, `upload_file`) must produce
identical behavior after this refactor. The `DEFAULT_POLICIES` map re-implements the same counter/cooldown logic. Any
deviation constitutes a regression.

---

### 2.4 Config Schema Extensions

```java
public static final class Builder {
    // ... existing fields ...

    /** Custom destructive tool policies, keyed by tool name. */
    private Map<String, DestructivePolicy> destructivePolicies = new LinkedHashMap<>();

    /** If true, log WARN when a name in destructiveTools has no matching policy. */
    private boolean warnOnUnknownDestructiveTool = false;

    /**
     * Registers a custom enforcement policy for a destructive tool name.
     * Overrides any default policy for the same name.
     *
     * @param toolName the tool name to enforce
     * @param policy   the enforcement policy (must not be null)
     * @return this builder
     */
    public Builder destructiveToolPolicy(String toolName, DestructivePolicy policy) {
        if (toolName == null || policy == null) {
            throw new IllegalArgumentException("toolName and policy must not be null");
        }
        destructivePolicies.put(toolName, policy);
        return this;
    }

    /**
     * If true, logs a WARN message when a tool name is in destructiveTools
     * but has no matching policy (neither custom nor default).
     * Default: false (silent no-op for backward compatibility).
     */
    public Builder warnOnUnknownDestructiveTool(boolean warn) {
        this.warnOnUnknownDestructiveTool = warn;
        return this;
    }

    public RateLimits build() {
        // validate: if warnOnUnknownDestructiveTool, check for orphaned names
        return new RateLimits(this);
    }
}
```

**Note on tool-name validation:** `destructiveTools(Set<String>)` continues to accept any string with no validation.
Adding a strict registry lookup at `build()` time is a **breaking change** for operators who list tool names as comments
or future-proofing placeholders. If validation is desired, add a separate `enforceTools(Set<String>)` method that checks
against registered tool names via the registry.

---

## 3. Cancellation Cleanup Ownership

### 3.1 Single Owner: `McpProtocolHandler`

`McpProtocolHandler` is the sole owner of cancellation cleanup. Rationale:

- Owns all session state maps (`sessions`, `sessionOwners`, `sessionRateLimits`, `cancelledRequests` via registry
  reference).
- Coordinates all four session termination paths.
- Adding cleanup here avoids spreading teardown responsibility across `McpGrizzlyHandler` (transport) and `McpServer` (
  bootstrap).
- The `registry` reference is already held; no new dependency is introduced.

### 3.2 Lifecycle: When to Clear

| Scenario                               | Clear `cancelledRequests`?               | Location                                                                                 |
|----------------------------------------|------------------------------------------|------------------------------------------------------------------------------------------|
| Explicit DELETE → `terminateSession()` | **Yes — add**                            | `McpProtocolHandler.java` `terminateSession()`, after `sessionRateLimits.remove()`       |
| Idle timeout → cleanup thread          | **Yes — add**                            | `McpProtocolHandler.java` cleanup loop, after `sessionRateLimits.remove(entry.getKey())` |
| Server shutdown → `closeAllSessions()` | **Yes — add**                            | `McpProtocolHandler.java` `closeAllSessions()`, before/after `sessions.clear()`          |
| SSE client disconnect (Path D)         | No change                                | Does not call `terminateSession`; session survives until timeout                         |
| `handleInitialize` exception rollback  | No — `clearCancellations` not yet called | No session yet existed in `cancelledRequests`                                            |

### 3.3 Required Test Scenarios and Acceptance Criteria

| ID     | Scenario                                                      | Acceptance criteria                                                                                                                                                                                                                                                                                    |
|--------|---------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **T1** | `terminateSession` clears cancellation state                  | 1. Register tool + send notification/cancelled for `requestId = "99"` on session S. 2. Assert `registry.isCancelled(S, "99") == true`. 3. Call `handler.terminateSession(S)`. 4. Assert `registry.isCancelled(S, "99") == false`.                                                                      |
| **T2** | Idle timeout expires and clears cancellation state            | 1. Init session S with `sessionTimeoutMs = 50ms`. 2. Send notification/cancelled for `requestId = "99"`. 3. Assert `registry.isCancelled(S, "99") == true`. 4. Wait 150ms (3× timeout). 5. Assert `registry.isCancelled(S, "99") == false` AND `handler.hasSession(S) == false`.                       |
| **T3** | `closeAllSessions` clears cancellation state for all sessions | 1. Init two sessions S1, S2. 2. Cancel `requestId = "x"` on each. 3. Assert both `isCancelled` return true. 4. Call `handler.closeAllSessions()`. 5. Assert `isCancelled(S1, "x") == false` AND `isCancelled(S2, "x") == false`.                                                                       |
| **T4** | Concurrent teardown: no `ConcurrentModificationException`     | 1. Init 10 sessions. 2. Cancel `requestId = "x"` on each. 3. Concurrently: call `closeAllSessions()` in one thread; call `terminateSession(sid)` for 5 sessions in another thread. 4. Assert no `ConcurrentModificationException`. 5. Assert all `isCancelled(sid, "x") == false` for all 10 sessions. |

---

## 4. Implementation Work Decomposition

Six concrete child tasks, one per distinct implementation area. Ownership is exclusive per task.

---

### 4.1 Task A: Implement Session Slot Counter (tbd)

**Assignee:** `dev-architect` or `backend-dev`  
**Files affected:** `McpProtocolHandler.java`  
**Parent:** `t_ee6de940`

**Scope:**

- Add `private static final LongAdder sessionSlotCount = new LongAdder();` field.
- In `handleRequestResponse`, wrap the `initialize` branch with slot reservation: `increment()` → check → on deny
  `decrement()` and return.
- Add `sessionSlotCount.decrement()` to the `finally` block for the initialize path.
- In both `McpErrorException` and `Throwable` catch blocks, add `sessionSlotCount.decrement()` when
  `method.equals("initialize")`.
- Add `sessions.remove(initSessionId)` and `sessionOwners.remove(responseSessionId)` rollback in the same catch blocks (
  handleInitialize exception guarantee).

**Backward compatibility:** Preserves all existing public API signatures. `sessionSlotCount` is a private field.

**Tests required:**

- **A1:** Concurrent `initialize` calls equal to `maxConcurrentSessions` — all admitted.
- **A2:** `maxConcurrentSessions + 1` concurrent `initialize` calls — exactly `maxConcurrentSessions` admitted, 1
  rejected with `-32029`.
- **A3:** `initialize` throws — slot counter decremented; subsequent call succeeds.

---

### 4.2 Task B: Implement CAS-based Category Slot Reservation (tbd)

**Assignee:** `backend-dev`  
**Files affected:** `McpProtocolHandler.java`  
**Parent:** `t_ee6de940`

**Scope:**

- Add `tryReserveCategorySlot(CategoryRateLimitState, String)` method using CAS loop.
- Add `releaseCategorySlot(CategoryRateLimitState, String)` method.
- Replace the check-then-increment logic in `checkMethodRateLimit` with `tryReserveCategorySlot` call. Replace the
  `incrementMethodConcurrentCount` calls in the switch body with `// reservation is already done`.
- Replace the `decrementMethodConcurrentCount` calls in the `finally` block with `releaseCategorySlot`.
- Remove `incrementMethodConcurrentCount` method (or make it a no-op, keeping it for source compatibility).

**Tests required:**

- **B1:** N concurrent calls (N = cap) to the same category — all admitted.
- **B2:** N+1 concurrent calls — exactly N admitted, 1 rejected with `-32029`.
- **B3:** Mixed categories: `adminConcurrent = 1`, `writeConcurrent = 3`. Fire 5 admin + 5 write calls simultaneously.
  Assert exactly 1 admin admitted, up to 3 write admitted, rest rejected.
- **B4:** CAS contention stress — 50 threads hammering all three categories simultaneously; verify total admitted per
  category never exceeds cap.

---

### 4.3 Task C: Migrate Destructive Tool Enforcement to `DestructivePolicy` Registry (tbd)

**Assignee:** `backend-dev`  
**Files affected:** `McpProtocolHandler.java`, `RateLimits.java`  
**Parents:** `t_ee6de940`, `t_c19a82c6`

**Scope:**

- Define `DestructivePolicy` functional interface (file: `core/DestructivePolicy.java`).
- Add `DEFAULT_POLICIES` static map to `McpProtocolHandler`.
- Add `destructivePolicies` map and `warnOnUnknownDestructiveTool` flag to `RateLimits.Builder`.
- Add `destructiveToolPolicy(String, DestructivePolicy)` builder method.
- Replace `checkDestructiveCap` switch with policy map lookup:
  `policy = customPolicies.getOrDefault(toolName, DEFAULT_POLICIES.getOrDefault(toolName, NO_OP))`.
- When `warnOnUnknownDestructiveTool` is true and the resolved policy is `NO_OP`, log at `WARN` level.
- Remove dead `DESTRUCTIVE_TOOLS` private static field (`McpProtocolHandler.java:146-148`).

**Backward compatibility:** Default policies produce identical enforcement for `shutdown`, `delete_action`,
`delete_prompt`, `upload_file`. Unknown names silently no-op (unless `warnOnUnknownDestructiveTool = true`).

**Tests required:**

- **C1:** Verify `shutdown`, `delete_action`, `delete_prompt`, `upload_file` produce identical cap/cooldown behaviour as
  before the refactor.
- **C2:** Custom policy via `destructiveToolPolicy` is called and can deny.
- **C3:** `warnOnUnknownDestructiveTool = true` emits one WARN log per unique unknown tool name per session.
- **C4:** Removing `set_mcp_api_key` from `destructiveTools` default set does not affect enforcement of other tools.

---

### 4.4 Task D: Wire Cancellation Cleanup into Session Termination Paths (tbd)

**Assignee:** `backend-dev`  
**Files affected:** `McpProtocolHandler.java`  
**Parent:** `t_ee6de940`

**Scope:**

- In `terminateSession(String)`: add `registry.clearCancellations(sessionId)` after
  `sessionRateLimits.remove(sessionId)`, before the `LOGGER.info` call.
- In the cleanup thread loop: add `registry.clearCancellations(entry.getKey())` after
  `sessionRateLimits.remove(entry.getKey())`.
- In `closeAllSessions()`: add `sessions.keySet().forEach(registry::clearCancellations)` before `sessions.clear()`.

**Tests required:** T1, T2, T3, T4 as specified in Section 3.3.

---

### 4.5 Task E: Add `addAbuseScore` to Destructive Cap Enforcement (tbd)

**Assignee:** `backend-dev`  
**Files affected:** `McpProtocolHandler.java`  
**Parent:** `t_ee6de940`

**Scope:**

- In `checkDestructiveCap` (or the `DestructivePolicy` implementation), call
  `addAbuseScore(state, sessionId, toolName, WEIGHT_DESTRUCTIVE, reason)` when a destructive cap is hit and the call is
  denied.
- Add `WEIGHT_DESTRUCTIVE = 3` constant (higher than concurrent-limit weight of 2, as destructive operations are more
  dangerous).

**Note:** This requires `checkDestructiveCap` to receive the `state` and `sessionId` parameters, which it already does.
The `DestructivePolicy.check` method receives these parameters. Implement `addAbuseScore` call inside the policy lambdas
for each tool that hits its cap.

**Tests required:**

- **E1:** Exhaust `shutdown` cap; assert `cl.abuseScore.get() >= WEIGHT_DESTRUCTIVE * shutdownCap`.
- **E2:** Exhaust all three destructive caps; assert abuse score reaches `abuseScoreBlockThreshold` and session is
  blocked.

---

### 4.6 Task F: Decide and Implement `set_mcp_api_key` / `revoke_mcp_api_key` Policy (tbd)

**Assignee:** `dev-architect`  
**Files affected:** `McpProtocolHandler.java`, `RateLimits.java`  
**Parent:** `t_ee6de940`

**Scope (one of three options, decided by this task):**

| Option                           | Action                                                                                                                                                                                                                                        | Breaking change?                                                         |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| **F-A: Remove from default set** | Delete `"set_mcp_api_key"` and `"revoke_mcp_api_key"` from `RateLimits.Builder.destructiveTools` default. Add a comment explaining these are sensitive but enforcement is the operator's responsibility.                                      | No — these names were never enforced                                     |
| **F-B: Implement enforcement**   | Add entries to `DEFAULT_POLICIES` map with the same pattern as `upload_file`. Cap and cooldown TBD (recommend `cap = 10, cooldown = 5 min`).                                                                                                  | No — new enforcement, operators who don't use these tools are unaffected |
| **F-C: No-op documentation**     | Keep in default set, document as "operator responsibility" with a `WARN` log when called. Requires `warnOnUnknownDestructiveTool = true` behaviour to also apply to names in `DEFAULT_POLICIES` that are in the default set but not enforced. | No                                                                       |

**Recommended:** Option **F-B** — implement a lightweight enforcement for both names. This closes a genuine security gap
with minimal code.

**Tests required:**

- **F1:** Call `set_mcp_api_key`/`revoke_mcp_api_key` more than the cap times — verify denial.
- **F2:** Mixed calls to enforced and unenforced destructive tools — abuse score accumulates correctly.

---

## 5. Concurrency Testing Requirements

All atomic primitives introduced in this spec require deterministic concurrency testing.

### 5.1 jcstress Tests

| Test class                          | What it verifies                                                                                     |
|-------------------------------------|------------------------------------------------------------------------------------------------------|
| `SessionSlotCounterTest`            | `LongAdder` never goes negative; final value equals admitted sessions minus closed sessions          |
| `CategoryCASReservationTest`        | CAS loop never admits more than `cap` threads; every admitted thread gets a release                  |
| `CategoryCASReservationStressTest`  | 50 threads × 1000 iterations: total increments never exceeds `cap * iterations`                      |
| `CancellationCleanupConcurrentTest` | `clearCancellations` called concurrently with `cancelRequest` — no `ConcurrentModificationException` |

### 5.2 Integration Tests

| Test class                           | What it verifies                                                                                                             |
|--------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `McpRateLimitIntegrationTest`        | Full request lifecycle: concurrent sessions admitted up to cap, denied beyond cap, slots released on completion and on error |
| `DestructivePolicyIntegrationTest`   | Full policy lifecycle: cap hit, cooldown, reset; abuse score accumulation; session blocking                                  |
| `CancellationCleanupIntegrationTest` | T1–T4 from Section 3.3                                                                                                       |
| `SessionInitExceptionRollbackTest`   | `handleInitialize` throws → `sessions` has no orphaned entry, slot counter decremented, `sessionOwners` clean                |

### 5.3 Determinism Requirement

All concurrency tests must use `ExecutorService` with a bounded thread pool. Tests that rely on `Thread.yield()` or
sleep for timing are not acceptable — use `CountDownLatch` or `CyclicBarrier` to synchronise thread start, ensuring the
race window is exercised deterministically.

---

## 6. Consolidated Findings and Decisions

### 6.1 Decisions Made (Authority: t_ee6de940)

| ID   | Decision                                                                       | Rationale                                                                             |
|------|--------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| D-01 | Use `LongAdder` for session slot counting                                      | Java 8-compatible; lowest contention for burst admission                              |
| D-02 | Use CAS loop (`compareAndSet`) for category slot reservation                   | No lock, Java 8-compatible, ensures cap is never exceeded                             |
| D-03 | `DestructivePolicy` is a `@FunctionalInterface`                                | Enables inline lambdas for default policies and custom operator policies              |
| D-04 | Unknown tool names silently no-op by default                                   | Backward compatibility; `warnOnUnknownDestructiveTool` opt-in flag for strict configs |
| D-05 | `set_mcp_api_key` / `revoke_mcp_api_key` remain in default set                 | They are sensitive; enforcement is a separate task (F)                                |
| D-06 | `McpProtocolHandler` is sole owner of cancellation cleanup                     | Already coordinates all session teardown paths                                        |
| D-07 | All catch blocks for initialize exception must rollback session + slot counter | Guarantees no orphaned entries regardless of which layer throws                       |
| D-08 | HTTP 429 for policy denials; HTTP 500 for internal errors                      | Client retryable vs. non-retryable distinction                                        |
| D-09 | `addAbuseScore` called on destructive cap hits                                 | Ensures session blocking mechanism covers destructive abuse                           |
| D-10 | `DESTRUCTIVE_TOOLS` dead field removed                                         | Dead code; source of confusion                                                        |

### 6.2 Open Decisions (Not Resolved by This Spec)

| ID   | Question                                                                                        | Owner                             |
|------|-------------------------------------------------------------------------------------------------|-----------------------------------|
| O-01 | Should `set_mcp_api_key` / `revoke_mcp_api_key` have enforcement? What cap/cooldown?            | Task F                            |
| O-02 | Should `destructiveTools(Set)` do registry lookup validation? (Breaking change risk)            | Task C                            |
| O-03 | Should SSE disconnect call `terminateSession` for immediate cleanup?                            | Future task                       |
| O-04 | Should `closeAllSessions` iterate through `terminateSession` per session instead of bulk-clear? | Task D (documented as equivalent) |

---

*Synthesis complete. Child tasks for implementation are listed in Section 4. No code has been produced.*
