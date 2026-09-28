# Category Reservation Test Evidence Report

**Date:** 2026-09-23  
**Test Class:** `io.github.vinhphan812.mcp.core.McpCategoryReservationTest`  
**Workspace:** D:/android/mcp-java-sdk  
**Status:** 6 tests run, 5 FAILED

---

## Test Results Summary

| Test Name                                           | Result | Expected             | Actual               |
|-----------------------------------------------------|--------|----------------------|----------------------|
| `categoryCap_N_plus_1_denied_then_release_unblocks` | FAILED | 2 admitted, 1 denied | 1 admitted, 2 denied |
| `mixed_read_write_admin_capsIndependent`            | FAILED | writeCap=2 admitted  | write exceeded cap   |
| `handlerThrow_releasesSlot`                         | FAILED | error returned       | N/A                  |
| `methodNotFound_earlyReturn_releasesSlot`           | FAILED | isAdmitted(r2)       | N/A                  |
| `blockedSession_deniesWithoutConsumingSlot`         | PASSED | -                    | -                    |
| `stress_fiftyConcurrent_noOverAdmission`            | FAILED | readCap=5 admitted   | read exceeded cap    |

---

## Root Cause Analysis

### Bug 1: Double Increment for `tools/call`

**Location:** `McpProtocolHandler.java`

The concurrent counter is incremented TWICE for `tools/call` requests:

1. **Line 638-640** (outer handler):
   ```java
   // Track concurrent requests for all methods
   if (!isSessionOptional(method) && hasSession(sessionId)) {
       incrementMethodConcurrentCount(sessionId, method, toolScopes);
   }
   ```

2. **Line 1571** (inside `handleToolsCall`):
   ```java
   // Increment concurrent counter
   final List<String> scopesForCounter = requiredScopes;
   if (sessionId != null) incrementConcurrentCount(sessionId, scopesForCounter);
   ```

**Effect:** For a single `tools/call`, the counter goes 0 → 1 → 2. With cap=2, only 1 call is admitted instead of 2.

### Bug 2: Missing Decrement on Early Return Paths

Several early return paths in the switch statement bypass the decrement logic at lines 767-778:

1. **Line 663** (`initialize`): Returns directly with new sessionId, but counter already incremented at line 639
2. **Line 667** (`notifications/initialized`, `notifications/message`): Returns directly, but these are session-optional
   so no increment
3. **Line 763-764** (`default` - method not found): Returns directly with error - counter incremented but never
   decremented!

**Evidence from test log:**

```
WARNING: [WARN] session=0abba242... tool=tools/call abuseScore=1 reason=read method concurrent cap exceeded
WARNING: [WARN] session=0abba242... tool=tools/call abuseScore=2 reason=read method concurrent cap exceeded
```

Two abuse score increments occur for ONE denied call, indicating the counter exceeded cap by 2.

---

## Test Input/Output Details

### Test 1: `categoryCap_N_plus_1_denied_then_release_unblocks`

**Command:**

```bash
./gradlew test --tests "io.github.vinhphan812.mcp.core.McpCategoryReservationTest.categoryCap_N_plus_1_denied_then_release_unblocks"
```

**Setup:** cap=2, total=3 threads (2+1 overflow)

**Expected:** 2 admitted, 1 denied  
**Actual:** 1 admitted, 2 denied

**Root Cause:** Double increment means counter reaches 2 after just 1 call, so 2nd call is denied.

### Test 2: `mixed_read_write_admin_capsIndependent`

**Setup:** readCap=3, writeCap=2, adminCap=1  
**Expected per category:** exactly cap admitted, 1 denied  
**Actual:** Write exceeded cap

**Root Cause:** Same double-increment bug affects write category tools.

### Test 3: `handlerThrow_releasesSlot`

**Setup:** cap=1, handler throws Error  
**Expected:** First call returns error, second call also returns error (slot released)

**Root Cause:** Double increment prevents second call from being admitted.

### Test 4: `methodNotFound_earlyReturn_releasesSlot`

**Setup:** First call `unknown/method`, then `tools/list`  
**Expected:** First returns -32601, second is admitted

**Root Cause:** The `default` case (line 763) returns directly without decrementing the counter incremented at line 639.
This is a LEAK.

---

## Defect Classification

| Defect                              | Type                       | Severity |
|-------------------------------------|----------------------------|----------|
| Double increment for `tools/call`   | Slot leak / over-admission | HIGH     |
| Missing decrement on `default` case | Slot leak                  | HIGH     |
| Missing decrement on `initialize`   | Slot leak                  | MEDIUM   |

---

## Minimal Deterministic Reproduction

### Case 1: Double Increment

```java
// With cap=1, single tools/call increments counter to 2
String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"testTool\"}}";
handler.handleRequestResponse(req, sessionId, null);
// Counter is 2, not 1
```

### Case 2: Method Not Found Leak

```java
// With cap=1, unknown method increments but never decrements
String req1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"unknown/method\"}";
handler.handleRequestResponse(req1, sessionId, null);
// Counter is 1 (leaked), next call denied
String req2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
// Will be denied incorrectly
```

---

## Acceptance Criteria for Fix

1. **Single Increment Point:** Remove either line 639 OR line 1571 increment. Recommended: Remove line 639 (outer
   handler) since line 1571 already has proper scope tracking.

2. **Fix Early Returns:** Add decrement before early returns in switch:
    - Before `return new McpResponse(..., sessionId)` at line 663 (`initialize`)
    - Before `return new McpResponse(errorResponse(...), sessionId)` at line 763 (`default`)

3. **Verification:** All 6 tests must pass:
    - `categoryCap_N_plus_1_denied_then_release_unblocks`: 2 admitted, 1 denied
    - `mixed_read_write_admin_capsIndependent`: Each category admits exactly cap
    - `handlerThrow_releasesSlot`: Second call admitted after Error
    - `methodNotFound_earlyReturn_releasesSlot`: Second call admitted after -32601
    - `blockedSession_deniesWithoutConsumingSlot`: (already passes)
    - `stress_fiftyConcurrent_noOverAdmission`: No category exceeds cap

---

## Notes

- **No test semantics altered** - failures are genuine production bugs
- **Test harness is correct** - CyclicBarrier provides deterministic concurrency
- **Burst/sustained limits set high** in tests so only concurrent cap is limiting factor
