# MCP Rate-Limit Repair Specification

**Document:** MCP-RateLimit-Repair-Spec  
**Date:** 2026-09-20  
**Author:** dev-backend  
**Status:** Specification (not implemented)  
**Files in Scope:**

- `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`
- `src/main/java/io/github/vinhphan812/mcp/api/config/RateLimits.java`
- `src/main/java/io/github/vinhphan812/mcp/api/config/DestructiveToolPolicy.java`
- `docs/adr/ADR-0011-security-rate-limiting.md`
- `src/test/java/io/github/vinhphan812/mcp/McpRateLimitTest.java`

---

## 1. Atomic Counter Reservation/Release Race Condition

### 1.1 Problem Description

In `McpProtocolHandler.java`, the per-category concurrent count check happens BEFORE the counter is incremented. This
creates a time-of-check to time-of-use (TOCTOU) race condition.

**Affected Code (lines 1018-1061, 629-632, 761-769):**

```java
// In checkMethodRateLimit() - concurrent cap CHECK happens here:
case CATEGORY_ADMIN:
        if(cl.adminConcurrent.

get() >=rateLimits.adminConcurrent){  // CHECK

addAbuseScore(cl, sessionId, method, 1,"admin method concurrent cap exceeded");
        return"admin method concurrent cap exceeded: "+method;
    }
            // ... burst and sustained checks ...

// Later in handleRequestResponse() - INCREMENT happens here:
            if(!

isSessionOptional(method) &&

hasSession(sessionId)){

incrementMethodConcurrentCount(sessionId, method, toolScopes);  // INCREMENT
}
```

**Race Condition Scenario:**

1. Thread A checks `adminConcurrent.get()` = 1, limit = 1 → passes check
2. Thread B checks `adminConcurrent.get()` = 1, limit = 1 → passes check (before A increments)
3. Thread A increments → `adminConcurrent` = 2
4. Thread B increments → `adminConcurrent` = 3
5. Both requests proceed, violating the concurrent cap

### 1.2 Specified Fix

**Option A: Move Increment Before Check (Recommended)**

Move the `incrementMethodConcurrentCount()` call to BEFORE the rate-limit checks in `handleRequestResponse()`:

```java
// Current order (racy):
// 1. checkMethodRateLimit() - CHECKS concurrent cap
// 2. incrementMethodConcurrentCount() - INCREMENTS

// Fixed order:
// 1. incrementMethodConcurrentCount() - INCREMENTS first
// 2. checkMethodRateLimit() - CHECKS with reservation semantics
// 3. On failure: decrementMethodConcurrentCount() - RELEASES reservation
```

This ensures atomic reservation semantics: the increment "reserves" a slot, and the check validates the reservation.

**Option B: Atomic Reservation Pattern (Alternative)**

Use `AtomicInteger.compareAndSet()` for atomic reservation:

```java
// In CategoryRateLimitState, add:
public boolean tryReserveConcurrent(AtomicInteger counter, int limit) {
    while (true) {
        int current = counter.get();
        if (current >= limit) {
            return false;  // Slot unavailable
        }
        if (counter.compareAndSet(current, current + 1)) {
            return true;   // Successfully reserved
        }
        // CAS failed, retry
    }
}

public void releaseConcurrent(AtomicInteger counter) {
    counter.decrementAndGet();
}
```

### 1.3 Java 8 Compatibility

Both options use only `java.util.concurrent.atomic.AtomicInteger`, which is fully Java 8 compatible. No changes to
`build.gradle` are required.

### 1.4 Reservation Semantics

| Scenario                 | Behavior                                                     |
|--------------------------|--------------------------------------------------------------|
| Slot available           | Increment succeeds, return null (allowed)                    |
| Slot unavailable         | Increment still happens (reservation), return denial message |
| Request fails validation | Decrement releases the reservation                           |

---

## 2. Cooldown Policy Documentation

### 2.1 Current Implementation

In `McpProtocolHandler.java` (lines 1169-1223), the `checkDestructiveCap()` method implements destructive tool cooldown:

```java
// Simplified flow for shutdown tool:
if(cl.shutdownCount.get() >=rateLimits.shutdownCap){
        if(cl.shutdownLastMs ==0L){
// First time cap exceeded — start cooldown
cl.shutdownLastMs =now;
    }
// Block while cooldown is active
long shutdownElapsedSec = (now - cl.shutdownLastMs) / 1000;
    if(shutdownElapsedSec<rateLimits.shutdownCooldownMs /1000){
long remaining = (rateLimits.shutdownCooldownMs / 1000) - shutdownElapsedSec;
        return"shutdown tool on cool-down, retry in "+remaining +"s";
        }
        // Cooldown elapsed — reset and allow this call
        cl.shutdownCount.

set(0);

cl.shutdownLastMs =now;
}
        cl.shutdownCount.

incrementAndGet();
```

### 2.2 Cooldown State Machine

```
                    ┌─────────────────┐
                    │   IDLE          │
                    │ (count < cap)   │
                    └────────┬────────┘
                             │ increment
                             ▼
                    ┌─────────────────┐
                    │   AT_CAP        │
                    │ (count >= cap)  │
                    └────────┬────────┘
                             │ first time
                             ▼
                    ┌─────────────────┐
                    │  COOLDOWN       │
                    │ (lastMs set)    │
                    └────────┬────────┘
                             │ elapsed >= cooldown
                             ▼
                    ┌─────────────────┐
                    │   RESET         │
                    │ (count = 0)     │
                    └────────┬────────┘
                             │ next call
                             ▼
                    ┌─────────────────┐
                    │   IDLE          │
                    │ (count = 1)     │
                    └─────────────────┘
```

### 2.3 Destructive Cooldown Matrix

| Tool                              | Cap | Cooldown   | Reset Behavior                   |
|-----------------------------------|-----|------------|----------------------------------|
| `shutdown`                        | 3   | 10 minutes | Resets count to 0 after cooldown |
| `delete_action` / `delete_prompt` | 10  | 2 minutes  | Resets count to 0 after cooldown |
| `upload_file`                     | 5   | 1 minute   | Resets count to 0 after cooldown |

### 2.4 Clarification: Reset Scope

**Current behavior:** The reset applies to the specific tool only (e.g., `shutdown` counter resets independently from
`delete` counter).

**Implication:** Each destructive tool has its own counter and cooldown timer - they are NOT global.

---

## 3. Error-Code Mapping

### 3.1 Current Incorrect Mapping

In `McpProtocolHandler.java` line 1524, authorization denial uses the rate-limit error code:

```java
// Authorization denial (line 1524) - WRONG CODE
if(denial !=null){
        throw new

McpErrorException(-32029,"Authorization denied: "+denial);
}
```

### 3.2 Specified Error Code Mapping

| Error Type               | Code       | Description                                 | Current Usage         |
|--------------------------|------------|---------------------------------------------|-----------------------|
| Invalid Request          | -32600     | The JSON sent is not a valid Request object | ✅ Correct             |
| Method Not Found         | -32601     | The method does not exist/is not available  | ✅ Correct             |
| Invalid Params           | -32602     | Invalid method parameter(s)                 | ✅ Correct             |
| Internal Error           | -32603     | Internal JSON-RPC error                     | ✅ Correct             |
| **Authorization Denied** | **-32001** | **Authentication/authorization failure**    | ❌ Currently -32029    |
| **Rate Limit Exceeded**  | **-32029** | **Too Many Requests**                       | ✅ Correct             |
| Parse Error              | -32700     | Invalid JSON                                | N/A (handled by Gson) |

### 3.3 Response Envelope Structure

**Error Response (JSON-RPC 2.0 compliant):**

```json
{
  "jsonrpc": "2.0",
  "id": <request-id
  or
  null>,
  "error": {
    "code": <error
    code
    from
    table
    above>,
    "message": "<human-readable message>",
    "data": <optional
    :
    additional
    error
    details>
  }
}
```

### 3.4 Backward Compatibility Risk

Changing authorization error code from -32029 to -32001 is a **breaking change** for any client that:

- Parses error codes programmatically
- Has custom handling for -32029

**Mitigation:** Document the change in release notes. Clients should handle both codes as "denied" during transition
period.

---

## 4. Tool-Exception Response Contract

### 4.1 Current Problem

In `McpProtocolHandler.java` lines 1598-1608, tool handler exceptions return an error-shaped result wrapped in a success
envelope:

```java
// In handleHandlerException() - lines 1604-1605
if("Tool".equals(kind)){
        return

errorToolResult("Error calling "+identifier +": "+e.getMessage());
        }

// This returns a Map, which gets wrapped in successResponse() at line 770:
// return new McpResponse(successResponse(id, result), responseSessionId);
```

The `errorToolResult()` method (lines 2091-2101) returns:

```java
private Map<String, Object> errorToolResult(String message) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<Map<String, String>> content = new ArrayList<>();
    Map<String, String> textContent = new LinkedHashMap<>();
    textContent.put("type", "text");
    textContent.put("text", "Error: " + message);
    content.add(textContent);
    result.put("content", content);
    result.put("isError", true);  // Marker, but still in "result" field!
    return result;
}
```

**Result:** The client receives a success response with `isError: true` in the result, which is semantically incorrect.

### 4.2 Specified Fix

Tool exceptions should throw `McpErrorException` to trigger the error envelope path:

```java
// In handleToolsCall() - lines 1541-1545, change:
}catch(McpErrorException e){
        throw e;  // Already an error, re-throw
}catch(
Throwable t){
        return

handleHandlerException("Tool",name, t);  // WRONG
}

// To:
        }catch(
McpErrorException e){
        throw e;
}catch(
Throwable t){
        throw new

McpErrorException(-32603,"Error calling "+name +": "+t.getMessage());
        }
```

Or modify `handleHandlerException()` to throw for tools:

```java
private Map<String, Object> handleHandlerException(String kind, String identifier, Throwable t) {
    LOGGER.log(Level.SEVERE, kind + " error (" + identifier + "): " + t.getMessage(), t);
    if (t instanceof Error) {
        throw (Error) t;
    }
    Exception e = (Exception) t;
    if ("Tool".equals(kind)) {
        // Throw error exception instead of returning error result
        throw new McpErrorException(-32603, "Error calling " + identifier + ": " + e.getMessage());
    }
    throw new McpErrorException(-32603, "Error reading " + identifier + ": " + e.getMessage());
}
```

### 4.3 Error Propagation Semantics

| Exception Type                | Current Behavior                           | Correct Behavior           |
|-------------------------------|--------------------------------------------|----------------------------|
| `McpErrorException`           | Wrapped in error envelope                  | ✅ Error envelope           |
| Other `Throwable` (tools)     | Wrapped in success envelope with `isError` | ❌ Should be error envelope |
| Other `Throwable` (resources) | Throws `McpErrorException`                 | ✅ Error envelope           |

---

## 5. Testing Requirements

### 5.1 Current Test Issues

In `McpRateLimitTest.java`:

1. Test class is `@Disabled` (line 37)
2. Uses `Thread.sleep(100)` for timing (non-deterministic, line 501)
3. Tests use arbitrary waits instead of controlled synchronization

### 5.2 Specified Testing Approach

**Use `CountDownLatch` for controlled timing:**

```java

@Test
void testConcurrentCapRaceCondition() throws Exception {
    McpProtocolHandler handler = createHandler();
    String sessionId = initializeSession(handler);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);

    // Launch concurrent requests
    for (int i = 0; i < NUM_THREADS; i++) {
        new Thread(() -> {
            try {
                startLatch.await();  // Wait for signal
                String response = handler.handleRequest(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",
                        \"params\":{\"name\":\"read_data\"}}",
                        sessionId);

                if (response.contains("-32029") || response.contains("concurrent")) {
                    rejectedCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
            } finally {
                doneLatch.countDown();
            }
        }).start();
    }

    // Release all threads simultaneously
    startLatch.countDown();

    // Wait for completion with timeout
    assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

    // Verify: at most CONCURRENT_CAP requests succeeded
    assertTrue(successCount.get() <= CATEGORY_READ_CONCURRENT_CAP,
            "Too many concurrent requests succeeded: " + successCount.get());
}
```

### 5.3 Test Scenarios for Race Conditions

| Scenario              | Approach                                              |
|-----------------------|-------------------------------------------------------|
| Concurrent cap check  | Launch N threads simultaneously, verify ≤ cap succeed |
| Burst limit           | Launch burst requests, verify ≤ limit succeed         |
| Reservation atomicity | Verify increment-then-check vs check-then-increment   |
| Cooldown reset        | Use mock clock or CountDownLatch for time advancement |

### 5.4 Java 8 Test Compatibility

- Use `java.util.concurrent.CountDownLatch` (Java 5+, fully compatible)
- Use `java.util.concurrent.ExecutorService` for thread management
- Avoid `java.time` (Java 8+) - use `System.currentTimeMillis()` for time checks

---

## 6. Java 8/Backward-Compatibility Impact

### 6.1 Current Dependencies

The implementation uses only Java 8-compatible APIs:

| API                                          | Usage                      | Java Version |
|----------------------------------------------|----------------------------|--------------|
| `java.util.concurrent.ConcurrentHashMap`     | Session/rate-limit storage | Java 5+      |
| `java.util.concurrent.ConcurrentLinkedQueue` | Request timestamps         | Java 5+      |
| `java.util.concurrent.atomic.AtomicInteger`  | Counters                   | Java 5+      |
| `java.util.concurrent.atomic.AtomicLong`     | Event IDs                  | Java 5+      |
| `java.util.logging.Logger`                   | Logging                    | Java 1.4+    |
| `com.google.gson.Gson`                       | JSON parsing               | External     |

### 6.2 Assessment

**No Java 8 incompatibilities found.** The current implementation is fully Java 8 compatible.

### 6.3 Gradle Configuration

Current `build.gradle` should remain unchanged. The current Java version configuration:

```groovy
sourceCompatibility = '1.8'
targetCompatibility = '1.8'
```

This is correct and should be preserved.

---

## 7. Files in Scope

| File                         | Issues to Address                                      |
|------------------------------|--------------------------------------------------------|
| `McpProtocolHandler.java`    | Race condition fix, error code fix, tool exception fix |
| `RateLimits.java`            | No changes needed (configuration only)                 |
| `DestructiveToolPolicy.java` | No changes needed (policy definition only)             |
| `ADR-0011`                   | Document cooldown behavior, error codes                |
| `McpRateLimitTest.java`      | Replace disabled tests with deterministic versions     |

---

## 8. Risks and Dependencies

### 8.1 Dependencies on Current Error-Code Semantics

| Component     | Dependency                           | Risk Level |
|---------------|--------------------------------------|------------|
| MCP Clients   | Parse -32029 for rate limiting       | Medium     |
| MCP Clients   | May parse -32029 for auth denial     | Medium     |
| Documentation | References -32029 for various errors | Low        |

### 8.2 Backward-Compatibility Risks

| Change                     | Risk                                            | Mitigation                        |
|----------------------------|-------------------------------------------------|-----------------------------------|
| Auth error -32029 → -32001 | Breaking change                                 | Release notes, deprecation period |
| Tool exception behavior    | Client code expecting `isError: true` in result | Document new behavior             |

### 8.3 Coordination Requirements

- Update MCP protocol documentation if public
- Update any client SDKs that expect -32029 for authorization
- Consider adding a configuration flag for legacy error-code behavior (optional)

---

## 9. Implementation Checklist

- [ ] Fix concurrent cap race condition (move increment before check)
- [ ] Add reservation release on validation failure
- [ ] Change authorization error code from -32029 to -32001
- [ ] Fix tool exception to throw instead of returning error result
- [ ] Enable and fix `McpRateLimitTest` with CountDownLatch-based tests
- [ ] Document cooldown state machine in ADR-0011
- [ ] Update error-code documentation
- [ ] Add release note for error-code change

---

## 10. References

- [ADR-0011 Security and Rate Limiting](adr/ADR-0011-security-rate-limiting.md)
- [JSON-RPC 2.0 Specification](https://www.jsonrpc.org/specification)
- McpProtocolHandler.java: lines 629-632, 761-793, 995-1063, 1169-1223, 1515-1526, 1598-1608
- RateLimits.java: full file (configuration)
- DestructiveToolPolicy.java: full file (policy definition)
