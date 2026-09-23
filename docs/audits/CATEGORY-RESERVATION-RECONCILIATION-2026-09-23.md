# Category Reservation Reconciliation - 2026-09-23

## Executive Summary

The task card describes 3 conflicting reports:

- `t_17286d3f`: CAS did not exceed cap, test helper misclassified errors
- `t_63d15a7c`: Real early-return/reservation defects
- `t_0c558d2d`: Non-atomic increment/decrement race

**Reconciliation Finding:** The third report (`t_0c558d2d`) correctly identifies the root cause. The first two reports
are partially correct but incomplete. The race condition between capacity check and increment is the primary failure
mode.

## 1. Problem Identification: Race Condition in Concurrent Slot Reservation

The existing implementation in `McpProtocolHandler.java` separates the capacity check from the concurrent counter
increment.

```java
// Current Flow
String categoryRateLimitError = checkMethodRateLimit(sessionId, method, toolScopes); // Step 1: Check
if (categoryRateLimitError != null) { ... }

// ... (code execution)

incrementMethodConcurrentCount(sessionId, method, toolScopes); // Step 2: Increment
```

This design has a race window between Step 1 and Step 2. Multiple threads can pass Step 1 while the current counter is
below the cap, and then all of them perform the increment in Step 2, leading to the actual counter exceeding the cap.

The `AtomicInteger` usage at Step 2 is individually atomic, but the composite operation `(Check then Increment)` is NOT
atomic.

## 2. Reproduction of Failing Tests

Failure analysis confirms the over-admission due to the race window:

- `McpCategoryReservationTest > mixed_read_write_admin_capsIndependent()`: Admitted count > cap.
- `McpCategoryReservationTest > stress_fiftyConcurrent_noOverAdmission()`: Max observed in-flight > cap.

## 3. Decided Design: AtomicReservation via CAS Loop

To guarantee exactly-once reservation, replace the check-then-increment approach with an atomic `tryReserve` mechanism.

### Proposed Implementation:

```java
// Proposed Atomic Reservation
boolean reserved = tryReserve(sessionId, category);
if (!reserved) {
    return "capacity exceeded";
}
try {
    // ... execute logic ...
} finally {
    release(sessionId, category);
}

// In McpProtocolHandler:
private boolean tryReserve(String sessionId, String category) {
    SessionState state = sessions.get(sessionId);
    AtomicInteger counter = getCounterForCategory(state, category);
    int cap = getCapForCategory(category);
    
    // CAS loop to atomically check and increment
    while (true) {
        int current = counter.get();
        if (current >= cap) return false;
        if (counter.compareAndSet(current, current + 1)) return true;
    }
}
```

This ensures that the check and increment are one atomic operation per requested category slot.

## 4. Call Graph: Acquire/Release Paths

### 4.1 Current Implementation Flow

```
handleRequestResponse()
  ├── checkMethodRateLimit()       ← Line 625: Capacity check (NOT atomic with increment)
  │   └── returns null (allowed) or error string
  ├── if (error != null) return early  ← Line 627: Early return without increment
  │
  ├── incrementMethodConcurrentCount() ← Line 639: Increment AFTER check
  │
  ├── try { ... }
  │     └── handleToolCall() / handleResourceSubscribe() / handleTaskCreate()
  │           └── handler.call() → throws/returns
  │
  └── finally:
        └── decrementMethodConcurrentCount()  ← All exit paths (lines 770, 776, 782, 790)
```

### 4.2 Early-Return Paths That Must Release Slot

| Path                                 | Current Behavior                                                                | Required Fix           |
|--------------------------------------|---------------------------------------------------------------------------------|------------------------|
| `checkMethodRateLimit` returns error | No increment → OK                                                               | No change needed       |
| Unknown method (`-32601`)            | Early return before line 639                                                    | Slot NOT reserved → OK |
| Blocked session                      | Early return before line 639                                                    | Slot NOT reserved → OK |
| Handler throws `Error`               | Propagates to catch(Throwable) → finally runs decrement                         | OK                     |
| Handler throws `RuntimeException`    | Caught by `handleHandlerException` → returns error Map → finally runs decrement | OK                     |

The current implementation has the correct decrement in all catch/finally paths. The ONLY issue is the race between
`checkMethodRateLimit` returning null and `incrementMethodConcurrentCount` executing.

## 5. Contract Decision: Error Handling

### 5.1 Tool Handler `isError: true` vs JSON-RPC Error Envelope

The SDK currently returns tool handler errors in two ways:

1. **Handler returns Map with `isError: true`** (line 2140):
   ```java
   result.put("isError", true);
   ```
   This is converted to a JSON-RPC result with the error message embedded.

2. **Handler throws Exception**:
   Caught by `handleHandlerException` (line 1585), returns error Map.

3. **Handler throws Error**:
   Propagates to outer `catch(Throwable)` in `handleRequestResponse` (line 785), which re-throws after logging.

**Decision:** The `isError` flag in a result Map is a tool-specific convention. The JSON-RPC error envelope is the
standard protocol response. The SDK correctly wraps `isError: true` results into a JSON-RPC error response. This
behavior should be retained.

### 5.2 Early Return Paths - Slot Consumption

| Scenario                        | Slot Reserved?  | Must Release? |
|---------------------------------|-----------------|---------------|
| Rate limit exceeded (-32029)    | No              | No            |
| Unknown method (-32601)         | No              | No            |
| Session blocked                 | No              | No            |
| Handler throws Error            | Yes (pre-throw) | Yes (finally) |
| Handler throws RuntimeException | Yes             | Yes (finally) |
| Handler returns `isError: true` | Yes             | Yes (finally) |
| Normal completion               | Yes             | Yes (finally) |

**Conclusion:** The `finally` block correctly handles all error paths. The atomic reservation fix will make the slot
management correct.

## 6. Decomposed Backend Implementation Task

### 6.1 Exact File and Method Scope

| File                      | Method(s)                                  | Change Type                                        |
|---------------------------|--------------------------------------------|----------------------------------------------------|
| `McpProtocolHandler.java` | `handleRequestResponse()` (lines ~625-640) | Replace check+increment with atomic tryReserve     |
| `McpProtocolHandler.java` | Add `tryReserveCategorySlot()`             | New method for atomic CAS reservation              |
| `McpProtocolHandler.java` | Add `releaseCategorySlot()`                | New method for atomic release (or reuse decrement) |

### 6.2 Validation Command

```bash
./gradlew test --tests "McpCategoryReservationTest"
```

All 6 tests must pass:

- `categoryCap_N_plus_1_denied_then_release_unblocks`
- `mixed_read_write_admin_capsIndependent`
- `handlerThrow_releasesSlot`
- `methodNotFound_earlyReturn_releasesSlot`
- `blockedSession_deniesWithoutConsumingSlot`
- `stress_fiftyConcurrent_noOverAdmission`

### 6.3 Implementation Steps

1. **Add `tryReserveCategorySlot(sessionId, method, toolScopes)`**:
    - Perform atomic check-and-increment using CAS loop on the appropriate `AtomicInteger`.
    - Return `true` if reserved, `false` if cap reached.

2. **Refactor `handleRequestResponse`**:
    - Replace:
      ```java
      String error = checkMethodRateLimit(...);
      if (error != null) return error;
      incrementMethodConcurrentCount(...);
      ```
    - With:
      ```java
      if (!tryReserveCategorySlot(...)) {
          return new McpResponse(errorResponse(id, -32029, "Too Many Requests: ...");
      }
      ```

3. **Ensure `finally` block calls `releaseCategorySlot`** (or existing `decrementMethodConcurrentCount` - should work
   unchanged).

4. **Run tests to verify.**
