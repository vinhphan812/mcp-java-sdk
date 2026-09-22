# ADR-0016 — SSE Permit Flow Verification and Implementation Plan

**Status:** Active
**Date:** 2026-09-20
**Reference:** `McpHttpHandler.handleGet()` (lines 301-372)
**Supersedes:** ADR-0017 (draft status - this document captures verified findings)

## Context

This ADR documents the findings from verifying `McpHttpHandler.handleGet()` against the SSE permit acquisition and
response flow specification. It captures current code issues, the safe lifecycle specification, and a concrete
implementation/test plan.

---

## 1. Source Verification Results

### Current Implementation Order (McpHttpHandler.java:301-372)

```
Line 301-321:  STEP 1: Validate request (origin, auth, session, Last-Event-ID)
Line 322-327:  STEP 2: Replay missed events ← WRONG ORDER
Line 328-331:  STEP 3: Acquire SSE permit   ← WRONG ORDER  
Line 334-338:  STEP 4: Set headers           ← WRONG ORDER
Line 339-342:  STEP 5: Send connected event
Line 343-363:  STEP 6: Polling loop
Line 366-371:  STEP 7: Release permit (finally)
```

### Critical Code Issues Found

#### Issue 1: Last-Event-ID Replay Happens BEFORE Permit Acquisition

**Location:** Lines 322-327

```java
// CURRENT (INCORRECT) - writes SSE data before permit is acquired
if (lastEventId != null) {
    String missed = handler.getMissedEvents(sessionId, lastEventId);
    response.getWriter().write(missed);  // Writes BEFORE sseConnections.tryAcquire()
    response.getWriter().flush();
}
if (!sseConnections.tryAcquire()) {  // Permit acquired AFTER replay
    writeError(response, 429, "Too many active SSE connections");
    return;
}
```

**Problems:**

- Client receives SSE data without holding a permit (resource accounting violation)
- Connection limit can be exceeded during replay phase
- If `tryAcquire()` fails after replay, client receives corrupted response (mixed SSE + JSON error)

#### Issue 2: Last-Event-ID Replay Happens BEFORE Headers Are Set

**Location:** Lines 322-327 before lines 334-338

```java
// CURRENT: Replay writes BEFORE headers
response.getWriter().write(missed);  // Line 325
response.getWriter().flush();         // Line 326

// Headers set AFTER replay
response.setContentType("text/event-stream");  // Line 334
response.setCharacterEncoding("UTF-8");        // Line 335
response.setStatus(200);                       // Line 338
```

**Problems:**

- HTTP response committed with default Content-Type (typically text/html)
- Client may reject response as malformed
- Inconsistent state between what client expects and receives

---

## 2. Safe Lifecycle Specification

The SSE connection flow MUST follow this exact order:

```
┌─────────────────────────────────────────────────────────────────────┐
│ STEP 1: Validate Request                                             │
│   • isInvalidOrigin(request)                                        │
│   • isUnauthorized(request)                                          │
│   • handler.hasSession(sessionId)                                    │
│   • parseLastEventId()                                              │
│   → On failure: writeError() + return (no permit, no output)        │
├─────────────────────────────────────────────────────────────────────┤
│ STEP 2: Acquire SSE Permit                                          │
│   • sseConnections.tryAcquire()                                     │
│   → On failure: writeError(429) + return (clean JSON error)         │
├─────────────────────────────────────────────────────────────────────┤
│ STEP 3: Set Response Headers and Status                             │
│   • setContentType("text/event-stream")                             │
│   • setCharacterEncoding("UTF-8")                                   │
│   • setHeader("Cache-Control", "no-cache, no-transform")            │
│   • setHeader("Connection", "keep-alive")                          │
│   • setStatus(200)                                                 │
├─────────────────────────────────────────────────────────────────────┤
│ STEP 4: Replay Missed Events (if Last-Event-ID provided)            │
│   • handler.getMissedEvents(sessionId, lastEventId)               │
│   • response.getWriter().write(missed)                             │
│   • response.getWriter().flush()                                   │
├─────────────────────────────────────────────────────────────────────┤
│ STEP 5: Send Connected Event                                         │
│   • formatSseEvent(nextEventId++, "connected", sessionInfo)         │
│   • response.getWriter().write(...); flush()                       │
├─────────────────────────────────────────────────────────────────────┤
│ STEP 6: Enter Polling Loop                                          │
│   • while (!interrupted && session valid && !timeout)              │
│   • pollPendingNotification() → send message events                 │
│   • send ping events when no notifications                          │
│   • flush every iteration                                           │
├─────────────────────────────────────────────────────────────────────┤
│ STEP 7: Release Permit (finally block)                              │
│   • sseConnections.release()                                        │
│   • Always executes regardless of how connection ends               │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Invariants

1. **Permit accountability**: Every active SSE connection holds exactly one permit, with no gaps
2. **Clean errors**: 429 responses are always clean JSON (no SSE stream corruption)
3. **Header order**: Content-Type is set before any body content is written
4. **State clarity**: Client receives `connected` event after all replay events

---

## 3. Implementation Plan

### 3.1 Code Changes Required

**File:** `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java`

**Change:** Move Last-Event-ID replay from lines 322-327 to AFTER permit acquisition (after line 331) and AFTER header
setting (after line 338).

**Corrected Code Structure:**

```java
private void handleGet(Request request, Response response) throws IOException {
    // STEP 1: Validate request (lines 302-321) - unchanged
    if (isInvalidOrigin(request)) { writeError(response, 403, "Forbidden Origin"); return; }
    if (isUnauthorized(request)) { writeError(response, 401, "Unauthorized"); return; }
    String sessionId = request.getHeader(SESSION_HEADER);
    if (sessionId == null || !handler.hasSession(sessionId)) {
        writeError(response, 400, "Missing or invalid Mcp-Session-Id header");
        return;
    }
    Long lastEventId;
    try {
        lastEventId = parseLastEventId(request.getHeader(LAST_EVENT_ID_HEADER));
    } catch (IllegalArgumentException e) {
        writeError(response, 400, e.getMessage());
        return;
    }

    // STEP 2: Acquire SSE permit FIRST (move before replay)
    if (!sseConnections.tryAcquire()) {
        writeError(response, 429, "Too many active SSE connections");
        return;
    }
    boolean permitHeld = true;
    try {
        // STEP 3: Set response headers and status
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setStatus(200);

        // STEP 4: Replay missed events AFTER headers set (moved from lines 322-327)
        if (lastEventId != null) {
            String missed = handler.getMissedEvents(sessionId, lastEventId);
            response.getWriter().write(missed);
            response.getWriter().flush();
        }

        // STEP 5: Send connected event (lines 339-342) - unchanged
        long nextEventId = 1L;
        response.getWriter().write(formatSseEvent(nextEventId++, "connected",
                "{\\\"sessionId\\\":\\\"" + escapeSseData(sessionId) + "\\\"}"));
        response.getWriter().flush();

        // STEP 6: Polling loop (lines 343-363) - unchanged
        // ... (no changes needed)

    } catch (IOException e) {
        // Client disconnected - permit released in finally
    } finally {
        // STEP 7: Release permit (lines 366-371) - unchanged
        if (permitHeld) {
            sseConnections.release();
            permitHeld = false;
        }
    }
}
```

### 3.2 Diff Summary

| Line(s) | Action | Description                                                 |
|---------|--------|-------------------------------------------------------------|
| 322-327 | DELETE | Remove replay block from current position                   |
| 328-331 | KEEP   | Permit acquisition stays in same position                   |
| 334-338 | KEEP   | Header setting stays in same position                       |
| (new)   | INSERT | Replay block moved to AFTER headers, BEFORE connected event |

---

## 4. Test Plan

### 4.1 Unit Tests to Create

**File:** `src/test/java/io/github/vinhphan812/mcp/transport/McpGrizzlySsePermitFlowTest.java`

| Test Case                       | Description                                                | Assertions                                             |
|---------------------------------|------------------------------------------------------------|--------------------------------------------------------|
| `permitAcquiredBeforeReplay`    | Verify `tryAcquire()` is called before `getMissedEvents()` | No SSE data written until permit held                  |
| `headersSetBeforeReplay`        | Verify headers set before any body content                 | Content-Type set before any write                      |
| `replayAfterPermitAcquired`     | Replay events only after permit acquired                   | Event stream contains replay + connected               |
| `cleanErrorWhenPermitExhausted` | 429 response is clean JSON, no SSE corruption              | Response is valid JSON, Content-Type: application/json |
| `permitReleasedOnDisconnect`    | Permit released via finally on IOException                 | New connection succeeds after disconnect               |
| `concurrentConnectionsEnforced` | Semaphore limit enforced correctly                         | Nth+1 connection gets 429                              |
| `connectedEventAfterReplay`     | Connected event sent AFTER replay completes                | Event order: [replayed] → [connected] → [poll]         |

### 4.2 Integration Test Coverage

**Existing Test File:** `src/test/java/io/github/vinhphan812/mcp/McpGrizzlyLiveTest.java`

| Test to Add                          | Coverage                                                            |
|--------------------------------------|---------------------------------------------------------------------|
| `fullSsePermitLifecycle`             | End-to-end: acquire → headers → replay → connected → poll → release |
| `lastEventIdReplayInCorrectPosition` | Verify replay events sent AFTER 200 OK and BEFORE connected         |
| `permitAccountingAcrossConnections`  | Verify permit count is accurate throughout lifecycle                |

### 4.3 Validation Commands

```bash
# Compile and run unit tests
./gradlew test --tests "McpGrizzlySsePermitFlowTest" -i

# Run integration tests
./gradlew test --tests "McpGrizzlyLiveTest" -i

# Run all transport tests
./gradlew test --tests "io.github.vinhphan812.mcp.transport.*" -i

# Verify no regressions in existing tests
./gradlew test --tests "McpGrizzlyResumabilityTest" -i

# Full build verification
./gradlew clean build
```

---

## 5. Dependency Analysis

### Files That May Need Changes

| File                               | Reason                     | Risk           |
|------------------------------------|----------------------------|----------------|
| `McpHttpHandler.java`           | Main fix location          | Medium         |
| `McpGrizzlySsePermitFlowTest.java` | New test file              | Low (additive) |
| `McpGrizzlyLiveTest.java`          | Integration test additions | Low (additive) |

### No Changes Required (verified safe)

| File                              | Status                     |
|-----------------------------------|----------------------------|
| `McpProtocolHandler.java`         | No changes needed          |
| `McpRegistry.java`                | No changes needed          |
| `McpGrizzlyServer.java`           | No changes needed          |
| `McpGrizzlyResumabilityTest.java` | Existing tests still valid |

### Existing Test Files

| File                                | Purpose                                           |
|-------------------------------------|---------------------------------------------------|
| `McpGrizzlyResumabilityTest.java`   | Last-Event-ID parsing + formatSseEvent unit tests |
| `McpGrizzlyLiveTest.java`           | Integration tests (may need additions)            |
| `McpGrizzlySecurityMatrixTest.java` | Security validation tests                         |

---

## 6. Acceptance Criteria

- [ ] **AC-1:** `handleGet()` acquires permit BEFORE any response body written
- [ ] **AC-2:** `handleGet()` sets headers BEFORE any response body written
- [ ] **AC-3:** Last-Event-ID replay happens AFTER permit acquired AND AFTER headers set
- [ ] **AC-4:** 429 error responses are clean JSON (no SSE stream corruption)
- [ ] **AC-5:** Permit is released in all code paths (success, error, timeout, disconnect)
- [ ] **AC-6:** `connected` event sent AFTER replay (if any) completes
- [ ] **AC-7:** All existing tests still pass after the fix
- [ ] **AC-8:** New unit tests verify correct ordering
- [ ] **AC-9:** Integration tests verify end-to-end lifecycle

---

## 7. Child Tasks

The following tasks were created to implement this specification:

| Task ID      | Title                                               | Assignee    |
|--------------|-----------------------------------------------------|-------------|
| `t_60011d87` | Refactor handleGet() to correct SSE permit ordering | dev-backend |
| `t_60011d88` | Add unit tests for permit acquisition ordering      | dev-backend |
| `t_60011d89` | Verify error responses during replay phase          | dev-backend |
| `t_60011d8a` | Test concurrent connection limit enforcement        | dev-backend |
| `t_60011d8b` | Update integration tests for SSE state transitions  | dev-backend |

---

## 8. Related Documents

- `docs/adr/ADR-0017-sse-permit-response-flow.md` - Draft specification (superseded by this ADR)
- `docs/adr/ADR-0005-sse-notifications-event-queue.md` - SSE event queue design
- `docs/TRANSPORT-SSE.md` - Transport layer documentation
- `docs/testing/TEST-0001-sse-connection-release-streaming.md` - Test specifications
