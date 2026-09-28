# Category Reservation Control Flow Audit

**Date:** 2026-09-23
**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`
**Method Analyzed:** `handleRequestResponse` (lines 569-802)

---

## 1. Line-Level Acquire/Release Call Graph

### Acquisition (incrementMethodConcurrentCount)

**Location:** Line 639

```java
// Track concurrent requests for all methods
if (!isSessionOptional(method) && hasSession(sessionId)) {
    incrementMethodConcurrentCount(sessionId, method, toolScopes);
}
```

**Entry Conditions:**

- Method is NOT session-optional (not "initialize", "notifications/initialized", "ping")
- Session exists (`hasSession(sessionId)` returns true)

**Call Path:**

```
handleRequestResponse:639
  -> incrementMethodConcurrentCount(String, String, List<String>):1077
      -> methodCategory(String):962 OR toolCategory(List<String>):941
      -> CategoryRateLimitState.adminConcurrent.incrementAndGet()
      -> CategoryRateLimitState.writeConcurrent.incrementAndGet()
      -> CategoryRateLimitState.readConcurrent.incrementAndGet()
```

### Release (decrementMethodConcurrentCount)

**Call Sites:**

1. **Line 770** - After switch, when `result == null || notification`
2. **Line 776** - After switch, normal completion path
3. **Line 782** - Catch block for `McpErrorException`
4. **Line 790** - Catch block for `Throwable t`

---

## 2. Enumeration of Every Return, Throw, and Finally After Category Reservation

### Returns in handleRequestResponse (after line 639):

| Line    | Condition                         | Release Called? | Session Exists? |
|---------|-----------------------------------|-----------------|-----------------|
| 577     | Invalid jsonrpc version           | No              | May not exist   |
| 586-587 | Missing method                    | No              | May not exist   |
| 594-595 | Max sessions reached              | No              | N/A             |
| 599-600 | Missing/invalid session           | No              | No              |
| 606-607 | IP/session rate limit exceeded    | No              | Depends         |
| 627-628 | Category rate limit exceeded      | No              | Yes             |
| 654-655 | Invalid protocolVersion type      | No              | Yes             |
| 658-659 | Unsupported protocol version      | No              | Yes             |
| 663     | initialize success                | Yes             | Yes             |
| 667     | notifications/*                   | No              | Yes             |
| 670     | Capability error (tools)          | Yes             | Yes             |
| 672     | tools/list success                | Yes             | Yes             |
| 676     | Capability error (tools)          | Yes             | Yes             |
| 680     | tools/call success                | Yes             | Yes             |
| 684     | Capability error (resources)      | Yes             | Yes             |
| 686     | resources/list success            | Yes             | Yes             |
| 690     | Capability error (resources)      | Yes             | Yes             |
| 692     | resources/read success            | Yes             | Yes             |
| 697     | Capability error (resources)      | Yes             | Yes             |
| 699     | resources/templates/list success  | Yes             | Yes             |
| 702-703 | Method not found                  | Yes             | Yes             |
| 706     | Capability error (subscriptions)  | Yes             | Yes             |
| 708     | resources/subscribe success       | Yes             | Yes             |
| 713     | Capability error (subscriptions)  | Yes             | Yes             |
| 715     | resources/unsubscribe success     | Yes             | Yes             |
| 721     | Capability error (prompts)        | Yes             | Yes             |
| 722     | prompts/list success              | Yes             | Yes             |
| 726     | Capability error (prompts)        | Yes             | Yes             |
| 728     | prompts/get success               | Yes             | Yes             |
| 732     | Capability error (tasks)          | Yes             | Yes             |
| 733     | tasks/get success                 | Yes             | Yes             |
| 736     | Capability error (tasks)          | Yes             | Yes             |
| 737     | tasks/result success              | Yes             | Yes             |
| 740     | Capability error (tasks)          | Yes             | Yes             |
| 741     | tasks/cancel success              | Yes             | Yes             |
| 744     | Capability error (tasks)          | Yes             | Yes             |
| 745     | tasks/create success              | Yes             | Yes             |
| 748     | Capability error (completions)    | Yes             | Yes             |
| 749     | completion/complete success       | Yes             | Yes             |
| 752     | Capability error (logging)        | Yes             | Yes             |
| 753     | logging/setLevel success          | Yes             | Yes             |
| 758     | notifications/cancelled           | **NO**          | Yes             |
| 760-761 | ping success                      | Yes             | Yes             |
| 763-764 | default case (method not found)   | **NO**          | Yes             |
| 772     | result == null or notification    | Yes             | Yes             |
| 778     | Normal success response           | Yes             | Yes             |
| 784     | McpErrorException caught          | Yes             | Yes             |
| 800     | Throwable caught (internal error) | Yes             | Yes             |

### Finally Block Analysis

**No finally block exists.** The decrement is called explicitly at multiple call sites, but there is NO finally block
protecting the release.

---

## 3. Exactly-Once Release Analysis

### Routes and Release Status:

#### Route 1: Early Returns (Before Increment)

**Status: NO SLOT LEAK** - No slot acquired, no release needed

- Lines 577, 586-587, 594-595, 599-600, 606-607, 627-628, 654-655, 658-659
- These return BEFORE line 639 (the increment call)

#### Route 2: Session-Optional Methods

**Status: NO SLOT LEAK** - No increment performed for session-optional methods

- Methods: "initialize", "notifications/initialized", "ping"
- Check at line 638 prevents increment: `if (!isSessionOptional(method) && hasSession(sessionId))`

#### Route 3: Notifications/Cancelled (Line 758)

**Status: SLOT LEAK - PROVEN**

- Increment occurs at line 639
- **No decrement before return at line 758**
- This is a guaranteed leak per notification

#### Route 4: Default Case (Line 763-764)

**Status: SLOT LEAK - PROVEN**

- Increment occurs at line 639
- **No decrement before return at line 763-764**
- This is a guaranteed leak for unknown methods

#### Route 5: Normal Completion (Lines 767-778)

**Status: EXACTLY-ONCE** - Correctly releases

- Decrement at line 770 (notification) or line 776 (success)
- Both paths execute decrement exactly once

#### Route 6: McpErrorException (Line 779-784)

**Status: EXACTLY-ONCE** - Correctly releases

- Decrement at line 782

#### Route 7: Throwable (Line 785-800)

**Status: EXACTLY-ONCE** - Correctly releases

- Decrement at line 790

### Summary Table

| Route                   | Slot Acquired? | Slot Released? | Exactly-Once? |
|-------------------------|----------------|----------------|---------------|
| Early returns           | No             | N/A            | N/A           |
| Session-optional        | No             | N/A            | N/A           |
| notifications/cancelled | Yes            | **NO**         | **LEAK**      |
| default case            | Yes            | **NO**         | **LEAK**      |
| Normal completion       | Yes            | Yes            | YES           |
| McpErrorException       | Yes            | Yes            | YES           |
| Throwable               | Yes            | Yes            | YES           |

---

## 4. Current CAS Reservation vs Configured Caps

### Constants (McpProtocolHandler lines 107-126):

```java
CATEGORY_READ_CONCURRENT_CAP = 5
CATEGORY_WRITE_CONCURRENT_CAP = 3
CATEGORY_ADMIN_CONCURRENT_CAP = 1
```

### Runtime Check (checkMethodRateLimit lines 1026-1068):

```java
// Example for READ category (line 1056)
if (cl.readConcurrent.get() >= rateLimits.readConcurrent) {
    return "read method concurrent cap exceeded: " + method;
}
```

### Gap Analysis:

- The constants (`CATEGORY_*_CONCURRENT_CAP`) are **NOT USED** in the actual check
- The check uses `rateLimits.readConcurrent` which comes from `RateLimits` config
- Default values in `RateLimits.Builder` (lines 97, 100, 103) reference the same constants
- This works correctly BUT creates unnecessary duplication

### AtomicInteger Usage:

- `AtomicInteger` used for concurrent counters (lines 165-167)
- `incrementAndGet()` / `decrementAndGet()` are atomic
- **Race condition exists:** Between the check (line 1056) and increment (line 1089), another thread could increment,
  causing cap to be exceeded by 1

---

## 5. Slot Leak vs Error Response Contract Issue

### Slot Leak (True Resource Leak)

**Two confirmed leaks:**

1. **notifications/cancelled (line 758)**
    - Always leaks when this notification is processed
    - Reproducible: Send any `notifications/cancelled` request

2. **default case (line 763-764)**
    - Leaks when an unknown method is called
    - Reproducible: Send any unrecognized method name

### Error Response Contract Issue

**Not a contract issue.** The error responses are correctly formed JSON-RPC error responses. The issue is resource
management (slot leaks), not error response format.

---

## 6. Recommended Minimal Implementation Patch

### Problem Summary:

- 2 guaranteed leak paths (notifications/cancelled, default case)
- No finally block protection
- Duplication between constant definitions and RateLimits config

### Recommended Fix:

**Option A: Add finally block (Minimal)**

Replace lines 767-801 with try-finally structure:

```java
try {
    // ... existing switch code ...
    
    if (result == null || notification) {
        return new McpResponse(null, responseSessionId);
    }
    return new McpResponse(successResponse(id, result), responseSessionId);
} catch (McpErrorException e) {
    return new McpResponse(errorResponse(id, e.getCode(), e.getMessage()), sessionId);
} catch (Throwable t) {
    applicationLogger.error("Error handling MCP request: " + t.getMessage());
    if (t instanceof Error) throw (Error) t;
    // ... existing error handling ...
    return new McpResponse(errorResponse(id, -32603, "Internal server error"), sessionId);
} finally {
    // ALWAYS release the concurrent slot
    if (!isSessionOptional(method) && hasSession(sessionId)) {
        decrementMethodConcurrentCount(sessionId, method, toolScopes);
    }
}
```

**However, this approach has issues:**

- The increment only happens inside the main try block at line 639
- Early returns (before line 639) won't have slots acquired
- Would need to restructure to acquire slot before try block

**Option B: Acquire slot before try block (Recommended)**

Move increment before the try block and add finally:

```java
public McpResponse handleRequestResponse(String requestBody, String sessionId, String clientIp) {
    Object id = null;
    String method = null;
    List<String> toolScopes = null;
    boolean slotAcquired = false;
    
    try {
        // ... parsing code (lines 574-638) ...
        
        // Track concurrent requests for all methods
        if (!isSessionOptional(method) && hasSession(sessionId)) {
            incrementMethodConcurrentCount(sessionId, method, toolScopes);
            slotAcquired = true;
        }
        
        // ... switch statement ...
        
    } finally {
        // Release slot if acquired
        if (slotAcquired) {
            decrementMethodConcurrentCount(sessionId, method, toolScopes);
        }
    }
}
```

### Files to Modify:

1. `McpProtocolHandler.java` - `handleRequestResponse` method (lines 569-802)

### Testing Requirements:

- Verify `notifications/cancelled` no longer leaks
- Verify unknown method no longer leaks
- Verify normal paths still work correctly
- Verify error paths still work correctly

---

## 7. Additional Findings

### Duplicate Method Pairs:

The codebase contains two similar method pairs:

1. `incrementMethodConcurrentCount` / `decrementMethodConcurrentCount` (lines 1077-1114) - used in handleRequestResponse
2. `incrementConcurrentCount` / `decrementConcurrentCount` (lines 1284-1305) - used in handleToolsCall

This duplication suggests the reservation logic was refactored but the old methods in handleToolsCall were not removed.

### handleToolsCall Reservation (lines 1569-1588):

```java
// Increment concurrent counter
final List<String> scopesForCounter = requiredScopes;
if (sessionId != null) incrementConcurrentCount(sessionId, scopesForCounter);

try {
    // ... tool execution ...
} catch (McpErrorException e) {
    throw e;
} catch (Throwable t) {
    return handleHandlerException("Tool", name, t);
} finally {
    if (sessionId != null) decrementConcurrentCount(sessionId, scopesForCounter);
}
```

This path uses a proper try-finally block and is correct.

---

## 8. Conclusion

**Exactly-Once Release: DISPROVED**

The control flow analysis confirms:

- 2 paths leak slots: `notifications/cancelled` and `default` case
- No finally block protection exists
- The fix requires either adding finally protection or restructuring to acquire slot before try block

The recommended patch is Option B: acquire slot before try with finally block protection.
