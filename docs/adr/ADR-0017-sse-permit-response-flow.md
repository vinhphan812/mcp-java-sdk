# ADR-0017 — SSE Permit Acquisition and Response Flow

**Status:** Draft
**Date:** 2026-09-20
**Reference:** `McpGrizzlyHandler.handleGet()`

## Context

The MCP Java SDK uses Server-Sent Events (SSE) for server-to-client notification streaming. The `McpGrizzlyHandler`
class manages SSE connections using a `Semaphore` to limit concurrent connections (`DEFAULT_MAX_SSE_CONNECTIONS = 4`).
The current implementation has an ordering problem that causes resource leaks and incorrect behavior.

## Current Implementation (INCORRECT)

```java
// Lines 301-372 in McpGrizzlyHandler.java
private void handleGet(Request request, Response response) throws IOException {
    // 1. Validate request (origin, auth, session)
    if (isInvalidOrigin(request)) { ...}
    if (isUnauthorized(request)) { ...}
    if (sessionId == null || !handler.hasSession(sessionId)) { ...}

    // 2. Parse Last-Event-ID and REPLAY EVENTS (PROBLEM HERE)
    Long lastEventId = parseLastEventId(request.getHeader(LAST_EVENT_ID_HEADER));
    if (lastEventId != null) {
        String missed = handler.getMissedEvents(sessionId, lastEventId);
        response.getWriter().write(missed);  // Writes BEFORE headers!
        response.getWriter().flush();
    }

    // 3. Acquire permit AFTER replay
    if (!sseConnections.tryAcquire()) {
        writeError(response, 429, "Too many active SSE connections");
        return;
    }

    // 4. Set headers AFTER replay and AFTER permit
    response.setContentType("text/event-stream");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("Connection", "keep-alive");
    response.setStatus(200);

    // 5. Send connected event + enter polling loop
    // ...
}
```

## Problems with Current Order

### Problem 1: Headers Written After Body Content

When `lastEventId` is non-null, the code writes missed events **before** setting the `Content-Type: text/event-stream`
header and status code. This causes:

- HTTP response to be committed with wrong default content-type (`text/html` typically)
- Client may reject the response as malformed
- Inconsistent state if client expects SSE but receives plain text

### Problem 2: Resource Leak on Permit Acquisition Failure

If `sseConnections.tryAcquire()` fails after already writing missed events to the response:

1. `writeError()` is called (lines 329-330) which overwrites the response
2. But the response stream may already be committed or in an inconsistent state
3. The client receives a confusing response (mix of SSE data + JSON error)

### Problem 3: Race Condition in Connection Counting

The permit should represent an **active SSE connection** from the moment the client establishes the connection. Current
order:

1. Replay happens (client gets events)
2. Then permit is acquired (connection is "counted")

This means:

- A client requesting replay can consume server resources without holding a permit
- The permit limit can be exceeded during the replay phase
- Another client might be rejected while the first client is still receiving replayed events

### Problem 4: State Transition Ambiguity

The SSE connection state is ambiguous during replay:

- Is the client "connecting" or "connected"?
- The `connected` event is sent AFTER replay (line 340-342)
- Client cannot distinguish "replay phase" from "live phase"

## Correct Operation Ordering

The correct order MUST be:

```
┌─────────────────────────────────────────────────────────────────────┐
│  handleGet(Request request, Response response)                      │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 1: Validate Request                                           │
│  ─────────────────────────────                                       │
│  • Origin validation (isInvalidOrigin)                              │
│  • Authorization check (isUnauthorized)                             │
│  • Session validation (hasSession)                                  │
│  • Last-Event-ID parsing                                            │
│  • If any validation fails → writeError, return                    │
│                                                                     │
│  ERROR HANDLING: Early return with JSON error response              │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 2: Acquire SSE Permit                                         │
│  ───────────────────────────────                                    │
│  • sseConnections.tryAcquire() BEFORE any response output         │
│  • If acquisition fails → writeError(429), return                 │
│  • Permit is now held → connection is "active"                    │
│                                                                     │
│  ERROR HANDLING: Return 429 "Too many active SSE connections"      │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 3: Set Response Headers and Status                            │
│  ───────────────────────────────────────────                        │
│  • response.setContentType("text/event-stream")                    │
│  • response.setCharacterEncoding("UTF-8")                          │
│  • response.setHeader("Cache-Control", "no-cache, no-transform")   │
│  • response.setHeader("Connection", "keep-alive")                  │
│  • response.setStatus(200)                                         │
│  • Response is now committed as SSE stream                        │
│                                                                     │
│  ERROR HANDLING: N/A (headers cannot fail after validation)         │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 4: Replay Missed Events (if Last-Event-ID provided)          │
│  ─────────────────────────────────────────────────────              │
│  • Only if lastEventId != null                                     │
│  • handler.getMissedEvents(sessionId, lastEventId)                │
│  • Write SSE events to response                                    │
│  • Flush to ensure client receives replay data                     │
│  • Client sees events with incrementing IDs                        │
│                                                                     │
│  ERROR HANDLING: Log error, continue with live events              │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 5: Send Connected Event & Transition to Connected State      │
│  ─────────────────────────────────────────────────────────────      │
│  • Write: id: <nextId> event: connected data: {"sessionId":"..."}  │
│  • Flush                                                            │
│  • Connection state → CONNECTED (or POLLING)                        │
│  • Now enters the notification polling loop                        │
│                                                                     │
│  ERROR HANDLING: N/A                                                │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 6: Enter Notification Polling Loop                           │
│  ───────────────────────────────────────                            │
│  • while (!interrupted && session valid && !timeout)               │
│  • Sleep 1 second                                                  │
│  • Poll pending notifications                                      │
│  • Write SSE message events                                        │
│  • Write ping if no notifications                                  │
│  • Flush                                                           │
│                                                                     │
│  ERROR HANDLING: Loop exits on interrupt/timeout/session-end       │
├─────────────────────────────────────────────────────────────────────┤
│  STEP 7: Release Permit on Completion                               │
│  ─────────────────────────────────────                              │
│  • In finally block                                                │
│  • sseConnections.release()                                         │
│  • Connection state → DISCONNECTED                                 │
│  • Response stream closes                                          │
│                                                                     │
│  ALWAYS EXECUTES: finally block guarantees cleanup                 │
└─────────────────────────────────────────────────────────────────────┘
```

## State Diagram

```
                    ┌─────────────────┐
                    │     START       │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │   VALIDATING    │
                    │  (origin, auth, │
                    │   session, ID)  │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
     ┌────────▼────────┐          ┌───────▼───────┐
     │   VALIDATION     │          │  VALIDATION   │
     │    FAILED       │          │    PASSED     │
     │  (error sent)   │          └───────┬───────┘
     └────────┬────────┘                  │
              │                   ┌────────▼────────┐
              │                   │   ACQUIRING    │
              │                   │    PERMIT      │
              │                   └────────┬────────┘
              │                            │
     ┌────────▼────────┐         ┌────────▼────────┐
     │    TERMINAL      │         │    ACQUIRE      │  ACQUIRE FAIL
     │   (COMPLETE)    │         │    SUCCESS     │  (429 error)
     └─────────────────┘         └────────┬────────┘
                                         │
                                ┌────────▼────────┐
                                │   SET HEADERS   │
                                │  (200, SSE)    │
                                └────────┬────────┘
                                         │
              ┌──────────────────────────┴──────────────────────────┐
              │                                                       │
     ┌────────▼────────┐                                 ┌─────────▼────────┐
     │   REPLAY EVENTS │                                 │   NO REPLAY      │
     │ (if LastEventId)│                                 │   (first conn)   │
     └────────┬────────┘                                 └────────┬─────────┘
              │                                                    │
              │                                           ┌────────▼────────┐
              │                                           │  SEND CONNECTED │
              │                                           │      EVENT      │
              │                                           └────────┬────────┘
              │                                                    │
              └────────────────────┬────────────────────────────────┘
                                   │
                          ┌────────▼────────┐
                          │   CONNECTED /   │
                          │     POLLING     │
                          │  (notification  │
                          │     loop)       │
                          └────────┬────────┘
                                   │
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
     ┌────────▼────────┐  ┌────────▼────────┐  ┌─────────▼────────┐
     │    TIMEOUT      │  │  INTERRUPTED   │  │  SESSION END    │
     │  (5 min idle)  │  │  (client disconnect)│  │ (handler.end) │
     └────────┬────────┘  └────────┬────────┘  └────────┬─────────┘
              │                    │                    │
              └────────────────────┼────────────────────┘
                                   │
                          ┌────────▼────────┐
                          │  RELEASE PERMIT │
                          │   (finally)     │
                          └────────┬────────┘
                                   │
                          ┌────────▼────────┐
                          │   TERMINAL      │
                          │   (COMPLETE)   │
                          └─────────────────┘
```

## Corrected Implementation

```java
private void handleGet(Request request, Response response) throws IOException {
    // STEP 1: Validate request
    if (isInvalidOrigin(request)) {
        writeError(response, 403, "Forbidden Origin");
        return;
    }
    if (isUnauthorized(request)) {
        writeError(response, 401, "Unauthorized");
        return;
    }
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

    // STEP 2: Acquire SSE permit BEFORE any response output
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

        // STEP 4: Replay missed events (if Last-Event-ID provided)
        // This happens AFTER headers are set and permit is acquired
        if (lastEventId != null) {
            String missed = handler.getMissedEvents(sessionId, lastEventId);
            if (missed != null && !missed.isEmpty()) {
                response.getWriter().write(missed);
                response.getWriter().flush();
            }
        }

        // STEP 5: Send connected event and transition to connected state
        long nextEventId = 1L;
        response.getWriter().write(formatSseEvent(nextEventId++, "connected",
                "{\"sessionId\":\"" + escapeSseData(sessionId) + "\"}"));
        response.getWriter().flush();
        // State transition: -> CONNECTED

        // STEP 6: Enter notification polling loop
        long start = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()
                && handler.hasSession(sessionId)
                && System.currentTimeMillis() - start < 300000L) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String notification;
            boolean sent = false;
            while ((notification = handler.pollPendingNotification(sessionId)) != null) {
                response.getWriter().write(formatSseEvent(nextEventId++, "message",
                        escapeSseData(notification)));
                sent = true;
            }
            if (!sent) {
                response.getWriter().write(formatSseEvent(nextEventId++, "ping", "{}"));
            }
            response.getWriter().flush();
        }
    } catch (IOException e) {
        // Client disconnected mid-stream — permit will be released in finally
    } finally {
        // STEP 7: Release permit on completion
        if (permitHeld) {
            sseConnections.release();
            permitHeld = false;
        }
        // State transition: -> DISCONNECTED
    }
}
```

## Why This Order Is Correct

1. **Headers before body**: Setting `Content-Type: text/event-stream` before writing any data ensures the client
   correctly interprets the response as SSE.

2. **Permit before resources**: Acquiring the permit before any I/O ensures the connection limit is enforced from the
   moment the server starts processing the request.

3. **Clean error handling**: If permit acquisition fails, a clean 429 error can be returned because no response body has
   been written yet.

4. **State clarity**: The client receives the `connected` event after all replay events, clearly marking the transition
   from "catch-up" to "live".

5. **Resource accountability**: Every active SSE connection holds exactly one permit, with no gaps or race conditions.

## Relationship to Modern Streamable HTTP

This ADR specifies the SSE permit ordering for **Legacy HTTP+SSE mode** only. With the adoption of Modern Streamable HTTP (see ADR-0018), the GET handler behavior changes:

### Modern Transport Mode Differences

| Aspect | Legacy HTTP+SSE | Modern Streamable HTTP |
|--------|-----------------|----------------------|
| GET endpoint | Long-lived SSE stream | Returns 405 (not used) |
| Permit ordering | Critical (see this ADR) | N/A (no GET endpoint) |
| Session model | Stateful | Stateless per-request |
| Connection limits | 4 concurrent SSE | Server-driven per-request |

### Implementation Notes

When implementing the hybrid mode (ADR-0018):

1. **Legacy mode**: Apply this ADR's permit ordering rules to `handleLegacyGet()`
2. **Modern mode**: GET requests return 405; no permit acquisition needed
3. **POST streaming**: Server-initiated SSE via POST uses different flow (see STREAMABLE-HTTP-MIGRATION-SPEC.md)

The `isModernClient()` detection (see `TransportMode.AUTO`) determines which handler path is used.

## Child Tasks

The following tasks were created to implement this specification:

- `t_58c3758b` — Refactor handleGet to correct SSE permit ordering
- `t_a1765ce9` — Add unit tests for permit acquisition ordering
- `t_bedfc4a5` — Verify error responses during replay phase
- `t_d9dd0e89` — Test concurrent connection limit enforcement
- `t_e9a917e2` — Update integration tests for SSE state transitions
- `t_ec0e9fbb` — Review and validate ADR
