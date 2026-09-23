# Rate-Limit / JSON-RPC Error Triage Specification

**Document:** RATE-LIMIT-TRIAGE-SPEC
**Date:** 2026-09-20
**Author:** dev-backend
**Status:** Complete
**Task:** t_4c57602f
**Files Reviewed:** `McpProtocolHandler.java` (working-tree diff), `docs/MCP-RateLimit-Repair-Spec.md`,
`src/main/java/.../api/config/RateLimits.java`, `src/test/java/.../McpRateLimitTest.java`

**Source Validation (2026-09-21):**

- `McpProtocolHandler.java` lines 617/631: `checkMethodRateLimit` BEFORE `incrementMethodConcurrentCount` — race
  condition confirmed NOT fixed (check-before-increment order, no decrement-on-denial)
- `McpProtocolHandler.java` line 1524: authorization denial throws `-32029` not `-32001` — confirmed unfixed
- `McpProtocolHandler.java` line 1544: `handleHandlerException("Tool", ...)` returns error result, does NOT throw
  `McpErrorException` — confirmed unfixed; `errorToolResult` path active
- `McpRateLimitTest.java` line 37: class-level `@Disabled` confirmed — all 15 tests vacuous when disabled
    - No `testSustainedLimitTest`, `testAbuseScoreAccumulates`, `testAbuseScoreBlocksSession` methods exist (deleted in
      prior triage per ACT-02/ACT-03/ACT-04)
- `RateLimits.java`: builder seeds defaults from `McpProtocolHandler` constants; fully compatible with
  `RateLimits.builder()` low-threshold test pattern
- `McpRegistry`: getters return defensive copies via `copyDefinitions`/`copyMap`; listeners backed by
  `CopyOnWriteArrayList` — matches spec's safe-notification pattern

**Residual Verification Gap:**

- Class-level `@Disabled` on `McpRateLimitTest` makes all 15 tests vacuous. Concurrent-cap tests (
  `testReadConcurrentCap`, `testWriteConcurrentCap`, `testAdminConcurrentCap`) are additionally unreliable due to the
  unfixed race condition. Until both the class is enabled and the race condition is fixed, no rate-limit enforcement
  claim is verified by the test suite.

---

## 1. Audit: Working-Tree Diff vs. Spec

### 1.1 Race Condition (Section 1 of Spec)

| Item                                | Spec Requirement                                                 | Implementation                                                | Status            |
|-------------------------------------|------------------------------------------------------------------|---------------------------------------------------------------|-------------------|
| **Order**                           | Option A recommended: increment THEN check, decrement on failure | **Check at line 617, increment at line 631** — OPPOSITE order | ❌ NOT IMPLEMENTED |
| **CAS pattern**                     | Option B alternative: `compareAndSet` for atomic reservation     | Not implemented                                               | ❌ NOT IMPLEMENTED |
| **Concurrent decrement on failure** | If check fails, decrement the slot back                          | Decrement does not happen when check fails (early return)     | ❌ NOT IMPLEMENTED |

**Race scenario that still exists:**

```
T1 checks readConcurrent.get() == 1 (limit == 1)  → passes
T2 checks readConcurrent.get() == 1 (limit == 1)  → passes (same instant)
T1 increments → readConcurrent = 2
T2 increments → readConcurrent = 3
Both proceed past concurrent cap
```

**Deviation detail:** The spec's Option A was not applied. The implementation kept check-before-increment order but
removed the duplicate per-category checks from `checkToolRateLimit` (see 1.3 below).

### 1.2 Authorization Error Code (Section 3 of Spec)

| Item                      | Spec Requirement | Implementation | Status      |
|---------------------------|------------------|----------------|-------------|
| Authorization denial code | -32001           | -32029         | ❌ NOT FIXED |
| Rate limit exceeded code  | -32029           | -32029         | ✅ Correct   |

Both auth denial and rate-limit denial currently use `-32029`. The implementation correctly throws
`McpErrorException(-32029, ...)` for both, matching the spec's rate-limit code but not the spec's auth code.

### 1.3 Tool Exception Contract (Section 4 of Spec)

| Item                                            | Spec Requirement     | Implementation                                       | Status        |
|-------------------------------------------------|----------------------|------------------------------------------------------|---------------|
| Tool exceptions → `McpErrorException`           | Throw error envelope | Still returns `errorToolResult()` wrapped in success | ❌ NOT FIXED   |
| Missing params → `McpErrorException(-32602)`    | Throw error envelope | ✅ Fixed (line 1499)                                  | ✅ IMPLEMENTED |
| Rate-limit denial → `McpErrorException(-32029)` | Throw error envelope | ✅ Fixed (line 1509)                                  | ✅ IMPLEMENTED |
| Auth denial → `McpErrorException(-32001)`       | Throw error envelope | Throws -32029 not -32001                             | ❌ Wrong code  |
| Error marker `isError: true` in result          | Should not exist     | Still returned in success envelope                   | ❌ NOT FIXED   |

### 1.4 Cooldown Policy (Section 2 of Spec)

| Item                                            | Spec Requirement                                  | Implementation                    | Status                                   |
|-------------------------------------------------|---------------------------------------------------|-----------------------------------|------------------------------------------|
| State machine: IDLE → AT_CAP → COOLDOWN → RESET | Documented in spec                                | ✅ Matches spec logic              | ✅ IMPLEMENTED                            |
| Abuse score on cap exceed                       | `shutdown=5, delete=3, upload=2`                  | `addAbuseScore` calls **removed** | ⚠️ CHANGED (intentional simplification?) |
| Increment + timestamp on success                | `count++` and `lastMs = now` after cooldown reset | ✅ Correct                         | ✅ IMPLEMENTED                            |
| Reset to 0 then increment                       | Resets `count.set(0)` then `incrementAndGet()`    | ✅ Correct                         | ✅ IMPLEMENTED                            |

**Note:** Removing `addAbuseScore` from destructive cap violations changes the abuse scoring contract. This was not
discussed in the spec.

### 1.5 Architectural Separation

| Item                                           | Spec Expectation                                  | Implementation                                                      | Status        |
|------------------------------------------------|---------------------------------------------------|---------------------------------------------------------------------|---------------|
| `checkToolRateLimit` handles destructive only  | Category checks removed from tools                | ✅ Category limits removed, javadoc updated                          | ✅ IMPLEMENTED |
| `checkMethodRateLimit` handles category limits | Per-method category check                         | ✅ No structural change needed                                       | ✅ CORRECT     |
| Increment at method level, not tool level      | Remove duplicate increment from `handleToolsCall` | ✅ Removed from `handleToolsCall`, placed at `handleRequestResponse` | ✅ IMPLEMENTED |

### 1.6 Test Infrastructure Additions (Not in Original Spec)

| Addition                                              | Purpose                                             |
|-------------------------------------------------------|-----------------------------------------------------|
| `SessionState.setAbuseScore(int)` / `getAbuseScore()` | Package-visible for test access                     |
| `getSessionCategoryLimits(String)`                    | Returns `categoryLimits` object for test assertions |

---

## 2. Atomic Reserve/Release Semantics

### 2.1 Current (Non-Atomic) Pattern

```
Thread A: checkMethodRateLimit()  → reads counter
Thread B: checkMethodRateLimit()  → reads same counter
          [check passes for both]
Thread A: incrementMethodConcurrentCount() → counter++
Thread B: incrementMethodConcurrentCount() → counter++
          [both proceed; cap violated]
```

### 2.2 Specified Atomic Pattern (Option A — Recommended)

```
1. incrementMethodConcurrentCount()  → reserves slot atomically
2. checkMethodRateLimit()            → validates reservation
3. If check fails:
     decrementMethodConcurrentCount() → releases reservation
     return denial
4. [request proceeds]
5. [finally] decrementMethodConcurrentCount() → releases on completion
```

### 2.3 Alternative Atomic Pattern (Option B — CAS)

```java
// In CategoryRateLimitState
public boolean tryReserveConcurrent(AtomicInteger counter, int limit) {
    while (true) {
        int current = counter.get();
        if (current >= limit) return false;
        if (counter.compareAndSet(current, current + 1)) return true;
    }
}

public void releaseConcurrent(AtomicInteger counter) {
    counter.decrementAndGet();
}
```

### 2.4 Edge Cases

| Scenario                                 | Expected Behavior                                        |
|------------------------------------------|----------------------------------------------------------|
| Request throws exception after increment | `decrementMethodConcurrentCount` in `catch` block        |
| Request completes successfully           | `decrementMethodConcurrentCount` in success path         |
| Rate-limit check fails after increment   | `decrementMethodConcurrentCount` before returning denial |
| Session doesn't exist                    | `incrementMethodConcurrentCount` is no-op (null check)   |
| Method is session-optional               | `incrementMethodConcurrentCount` not called              |

### 2.5 Deterministic Concurrency Testing Strategy

Use `CountDownLatch` for simultaneous thread release:

```java
@Test
void testConcurrentCapEnforcement() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);  // Gates all threads
    CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger deniedCount = new AtomicInteger(0);

    for (int i = 0; i < NUM_THREADS; i++) {
        new Thread(() -> {
            try {
                startLatch.await();  // Wait for release signal
                String response = handler.handleRequest(requestJson, sessionId);
                if (response.contains("-32029") || response.contains("concurrent")) {
                    deniedCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        }).start();
    }

    startLatch.countDown();  // Release all threads simultaneously
    assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

    // Verify: exactly CONCURRENT_CAP requests succeeded
    assertEquals(CONCURRENT_CAP, successCount.get(),
        "Expected exactly " + CONCURRENT_CAP + " successes");
}
```

**Time-based tests:** Use `TimeUnit.SECONDS.sleep()` with a tolerance window (e.g., ±200ms) instead of exact
`Thread.sleep()`. For cooldown tests, use `AtomicInteger` counters to verify state transitions without real-time delays.

### 2.6 Java 8 Compatibility

- `CountDownLatch`: Java 5+ ✅
- `AtomicInteger.compareAndSet()`: Java 5+ ✅
- `ExecutorService`: Java 5+ ✅
- `java.time`: NOT used — use `System.currentTimeMillis()` ✅

---

## 3. Authorization vs. Rate-Limit Error Envelope

### 3.1 Specified Error Codes

| Error Type               | JSON-RPC Code | Current Code | Status      |
|--------------------------|---------------|--------------|-------------|
| Invalid Request          | -32600        | -32600       | ✅ Correct   |
| Method Not Found         | -32601        | -32601       | ✅ Correct   |
| Invalid Params           | -32602        | -32602       | ✅ Correct   |
| Internal Error           | -32603        | -32603       | ✅ Correct   |
| **Authorization Denied** | **-32001**    | **-32029**   | ❌ Needs fix |
| **Rate Limit Exceeded**  | **-32029**    | **-32029**   | ✅ Correct   |
| Parse Error              | -32700        | N/A (Gson)   | N/A         |

### 3.2 Specified Envelope Structure

```json
// Authorization denial
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32001,
    "message": "Authorization denied: missing required scope: admin:write",
    "data": null
  }
}

// Rate limit exceeded
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32029,
    "message": "Too Many Requests: admin method concurrent cap exceeded: tools/call",
    "data": null
  }
}
```

### 3.3 Implementation Requirements

1. Change authorization denial from `-32029` to `-32001` in `handleToolsCall` line 1521
2. Keep rate-limit denial at `-32029`
3. Keep internal error at `-32603` for tool handler exceptions
4. Ensure `McpErrorException` constructor signature matches: `McpErrorException(int code, String message)`

### 3.4 Breaking Change Warning

Changing auth error code from `-32029` to `-32001` is a **breaking change**:

- Any client parsing error codes programmatically must be updated
- Clients relying on `-32029` for authorization denial must migrate to `-32001`
- Document in release notes with migration guidance

### 3.5 Tool Handler Exception Wrapping

| Exception Type                                        | Envelope Type     | Code          |
|-------------------------------------------------------|-------------------|---------------|
| `McpErrorException`                                   | Error             | `e.getCode()` |
| `IllegalArgumentException` (missing params)           | Error             | -32602 ✅      |
| `RuntimeException` / other `Throwable` (tool failure) | Error (SHOULD BE) | -32603 ❌      |
| Resource handler exception                            | Error             | -32603 ✅      |

**Current behavior for tool failures:** Returns `Map` from `errorToolResult()` wrapped in success envelope (
`"isError": true` marker).

**Required behavior:** Throw `McpErrorException(-32603, "Error calling <tool>: <message>")` instead of returning
`errorToolResult()`.

---

## 4. Destructive Cooldown State Machine

### 4.1 State Diagram

```
                         ┌──────────────────────────────────────────────┐
                         │                                              │
                         ▼                                              │
┌─────────────────────┐  │    ┌─────────────────────────────────────┐  │
│        IDLE         │  │    │           CAP_EXCEEDED               │  │
│  (count < cap)      │──┘    │  (count >= cap, lastMs == 0)         │  │
│                     │       │  Action: set lastMs = now             │  │
│  Normal operation   │       └──────────────┬──────────────────────────┘  │
│  count++ each call  │                      │ lastMs now set              │
└─────────┬───────────┘                      │                             │
          │ count >= cap                     ▼                             │
          │                         ┌─────────────────────────────────────┐
          └────────────────────────►│           COOLDOWN                 │
                                    │  (lastMs > 0, elapsed < cooldown)   │
                                    │                                     │
                                    │  Block all calls with retry-msg    │
                                    │  remaining = (cooldown - elapsed)   │
                                    └──────────────┬──────────────────────┘
                                                   │ elapsed >= cooldown
                                                   ▼
                                    ┌─────────────────────────────────────┐
                                    │             RESET                   │
                                    │  Action: count.set(0), lastMs=now   │
                                    │  count++ (current call counted)     │
                                    └──────────────┬──────────────────────┘
                                                   │
                                                   ▼
                                    ┌─────────────────────────────────────┐
                                    │        IDLE (count = 1)              │
                                    │  Normal operation resumes            │
                                    └─────────────────────────────────────┘
```

### 4.2 Observable Transitions

| From         | To           | Trigger                  | Side Effects                   |
|--------------|--------------|--------------------------|--------------------------------|
| IDLE         | CAP_EXCEEDED | `count >= cap`           | `lastMs = now`                 |
| CAP_EXCEEDED | COOLDOWN     | (implicit, same call)    | None                           |
| COOLDOWN     | RESET        | `elapsed >= cooldown`    | `count.set(0)`, `lastMs = now` |
| RESET        | IDLE         | (same call, after reset) | `count++`                      |
| IDLE         | IDLE         | `count < cap`            | `count++`                      |

### 4.3 Tool-Specific Parameters

| Tool                              | Cap | Cooldown   | Abuse Score (spec) | Abuse Score (impl) |
|-----------------------------------|-----|------------|--------------------|--------------------|
| `shutdown`                        | 3   | 10 minutes | +5 per violation   | **Not applied** ❌  |
| `delete_action` / `delete_prompt` | 10  | 2 minutes  | +3 per violation   | **Not applied** ❌  |
| `upload_file`                     | 5   | 1 minute   | +2 per violation   | **Not applied** ❌  |

### 4.4 Key Behavioral Notes

1. **Cooldown timer starts on FIRST cap exceed** (when `lastMs == 0`). Subsequent calls within cooldown just check
   elapsed time — they do NOT restart the timer.

2. **Timer persists across requests**: Once `lastMs` is set, it stays set until cooldown fully elapses. Multiple calls
   during cooldown all share the same timer.

3. **Reset is per-tool, not global**: `shutdown` counter/timer is independent of `delete` counter/timer.

4. **After cooldown, count resets to 0 then increments**: The call that triggers the reset ALSO counts toward the new
   cycle. After a 10-minute shutdown cooldown, one shutdown call is allowed (count=1).

5. **Abuse scoring removed**: The implementation no longer calls `addAbuseScore()` for destructive cap violations. This
   diverges from the spec and the original behavior. This is a **behavioral change** that should be documented.

---

## 5. Minimal Implementation Tasks (Dependency Order)

### Phase 1: Fix Race Condition (Highest Priority)

1. **Move `incrementMethodConcurrentCount` before `checkMethodRateLimit`**
    - File: `McpProtocolHandler.java`
    - Location: `handleRequestResponse()` around lines 617-631
    - Order change:
      ```
      BEFORE: checkMethodRateLimit() → [increment BEFORE check is wrong]
      AFTER:  incrementMethodConcurrentCount() → checkMethodRateLimit() → decrement on failure
      ```
    - Dependency: None

2. **Add decrement-on-failure path**
    - After `checkMethodRateLimit` returns non-null at line 617-621
    - Call `decrementMethodConcurrentCount(sessionId, method, toolScopes)` before returning denial
    - Dependency: Task 1

### Phase 2: Fix Error Codes

3. **Change authorization denial from -32029 to -32001**
    - File: `McpProtocolHandler.java`
    - Location: `handleToolsCall()` line 1521
    - Change: `throw new McpErrorException(-32029, "Authorization denied: " + denial)` → `-32001`
    - Dependency: None

4. **Add test asserting distinct error codes**
    - File: New or existing rate-limit test
    - Verify auth denial returns -32001
    - Verify rate-limit denial returns -32029
    - Dependency: Task 3

### Phase 3: Fix Tool Exception Contract

5. **Change tool handler exception from `errorToolResult` to `McpErrorException`**
    - File: `McpProtocolHandler.java`
    - Location: `handleToolsCall()` catch block for `Throwable` (around line 1542)
    - Change: `return handleHandlerException("Tool", name, t)` →
      `throw new McpErrorException(-32603, "Error calling " + name + ": " + t.getMessage())`
    - Dependency: None

6. **Remove or deprecate `errorToolResult` method**
    - File: `McpProtocolHandler.java`
    - Check if any other caller exists
    - Dependency: Task 5

### Phase 4: Cooldown Policy Clarification

7. **Document abuse score removal decision**
    - File: `docs/adr/ADR-0011-security-rate-limiting.md`
    - Note: destructive cap violations no longer add abuse score (behavioral change)
    - Dependency: None

### Phase 5: Testing (All Java 8 Compatible)

8. **Enable `McpRateLimitTest` with CountDownLatch-based tests**
    - Remove `@Disabled`
    - Replace `Thread.sleep` with `CountDownLatch` coordination
    - Dependency: Tasks 1, 2

9. **Add race condition regression test**
    - Launch N threads simultaneously
    - Verify exactly CONCURRENT_CAP requests succeed
    - Dependency: Tasks 1, 2

10. **Add cooldown state machine test**
    - Exceed cap, verify cooldown message
    - Wait for cooldown (with tolerance), verify reset
    - Dependency: Task 7

### Task Dependency Graph

```
[Task 1: Move increment] ──→ [Task 2: Decrement on failure]
                                    │
[Task 3: Fix auth code] ──→ [Task 4: Test error codes]
                                    │
[Task 5: Fix tool exceptions] ──→ [Task 6: Remove errorToolResult]
                                          │
[Task 7: Document abuse score change] ──→ [Task 10: Cooldown test]
                                    │
[Task 1] + [Task 2] ──→ [Task 8: Enable rate-limit tests]
                                    │
                  [Task 9: Race condition test] ──→ [Task 8]
```

---

## Summary of Gaps

| Gap                                                          | Severity     | Spec Section |
|--------------------------------------------------------------|--------------|--------------|
| Check-before-increment order (race condition)                | **CRITICAL** | 1            |
| Missing decrement on rate-limit denial                       | **HIGH**     | 1            |
| Authorization error code -32029 instead of -32001            | **MEDIUM**   | 3            |
| Tool exceptions still return success envelope with `isError` | **MEDIUM**   | 4            |
| Abuse score removal undocumented                             | **LOW**      | 2            |

---

## Files to Modify

| File                                 | Tasks                                        |
|--------------------------------------|----------------------------------------------|
| `McpProtocolHandler.java`            | 1, 2, 3, 5                                   |
| `McpRateLimitTest.java`              | 8, 9, 10                                     |
| `ADR-0011-security-rate-limiting.md` | 7                                            |
| `docs/MCP-RateLimit-Repair-Spec.md`  | This document supersedes it for action items |

---

## References

- Spec (archived): `docs/audits/historical/MCP-RateLimit-Repair-Spec-2026-09-20.md`
- Test triage (archived): `docs/audits/historical/SECURITY-RATE-LIMIT-TEST-TRIAGE-t_c2753596-2026-09-20.md`
- Implementation: `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`
- Configuration: `src/main/java/io/github/vinhphan812/mcp/api/config/RateLimits.java`
- Tests: `src/test/java/io/github/vinhphan812/mcp/McpRateLimitTest.java`
- ADR: `docs/adr/ADR-0011-security-rate-limiting.md`




