# SSE Connection State Machine — McpGrizzlyHandler + McpProtocolHandler

> Spec source: task body requirement
> Code under analysis:
> - `McpGrizzlyHandler.java` lines 322–393 (`handleGet`)
> - `McpProtocolHandler.java` lines 2018–2031 (`getMissedEvents`), lines 249–287 (`SessionState`)

---

## 1. Authoritative State Machine

```
                              HTTP GET /endpoint
                                    │
                                    ▼
                    ┌─────────────────────────────┐
                    │        VALIDATING            │
                    │  (gate checks, no I/O or    │
                    │   state mutation on handler) │
                    └────────────┬────────────────┘
                                 │ all gates pass
                                 ▼
                    ┌─────────────────────────────┐
                    │    PERMIT_ACQUIRING          │
                    │  sseConnections.tryAcquire()  │
                    │  (may reject with 429)        │
                    └────────────┬────────────────┘
                                 │ permit held
                                 ▼
                    ┌─────────────────────────────┐
                    │    HEADERS_WRITING           │
                    │  setContentType / setStatus  │
                    │  setCache-Control etc.       │
                    └────────────┬────────────────┘
                                 │ headers committed
                                 ▼
                    ┌─────────────────────────────┐
                    │    REPLAYING (Last-Event-ID)│
                    │  getMissedEvents(sessionId,  │
                    │  lastEventId)               │
                    │  write SSE blocks + flush()  │
                    └────────────┬────────────────┘
                                 │ replay done (or no Last-Event-ID)
                                 ▼
                    ┌─────────────────────────────┐
                    │    CONNECTED_EMITTING        │
                    │  write SSE "connected" event  │
                    │  + flush()                  │
                    └────────────┬────────────────┘
                                 │ flush confirmed
                                 ▼
                    ┌─────────────────────────────┐
                    │    LIVE_POLLING              │
                    │  while loop (5 min timeout) │
                    │  pollPendingNotification()   │
                    │  write "message" or "ping"    │
                    │  flush() each cycle         │
                    └────────────┬────────────────┘
                                 │ timeout / disconnect /
                                 │ session gone / interrupt
                                 ▼
                    ┌─────────────────────────────┐
                    │       CLEANUP_RELEASING      │
                    │  sseConnections.release()     │
                    │  finally block               │
                    └─────────────────────────────┘
```

---

## 2. State Entry / Exit Contracts

| State                | Entry condition                                             | Entry side-effects                                                                 | Exit trigger                                                                                | Exit side-effects                                 |
|----------------------|-------------------------------------------------------------|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|---------------------------------------------------|
| `VALIDATING`         | TCP connection accepted, routing to `handleGet`             | None                                                                               | All preconditions met                                                                       | Proceed to `PERMIT_ACQUIRING`                     |
| `PERMIT_ACQUIRING`   | Entering `handleGet` try-block                              | None                                                                               | `tryAcquire()` succeeds                                                                     | `permitHeld = true`; proceed to `HEADERS_WRITING` |
| `HEADERS_WRITING`    | Permit acquired                                             | Write 200 status, `Content-Type: text/event-stream`, `Cache-Control`, `Connection` | Headers flushed                                                                             | Proceed to `REPLAYING`                            |
| `REPLAYING`          | Headers written                                             | None                                                                               | `getMissedEvents()` returns; if `lastEventId == null` skip directly to `CONNECTED_EMITTING` | Write replay SSE blocks + `flush()`               |
| `CONNECTED_EMITTING` | Replay complete                                             | Write `connected` SSE event + `flush()`                                            | Flush confirmed                                                                             | Proceed to `LIVE_POLLING`                         |
| `LIVE_POLLING`       | `connected` event sent                                      | Start timeout timer                                                                | Loop condition fails                                                                        | Proceed to `CLEANUP_RELEASING`                    |
| `CLEANUP_RELEASING`  | Exit from `LIVE_POLLING` loop or `IOException` in any state | None                                                                               | `release()` called exactly once                                                             | Semaphore permit returned to pool                 |

---

## 3. Current Implementation Trace

### VALIDATING (lines 323–342)

```java
323:  if (isInvalidOrigin(request))  → 403
327:  if (isUnauthorized(request))   → 401
331:  if (sessionId == null || !handler.hasSession(sessionId)) → 400
336–342: parseLastEventId(header)   → 400 on IAE
```

**Compliant.** No I/O, no state mutation, short-circuits on first failure.

### REPLAYING (lines 343–348) — **VIOLATION #1**

```java
343: if (lastEventId != null) {
344:     String missed = handler.getMissedEvents(sessionId, lastEventId);
345:     response.getWriter().write(missed);
346:     response.getWriter().flush();
347: }
```

Runs BEFORE permit acquisition (line 349) and BEFORE headers/status are written (lines 355–359).

**Problems:**

1. If `getMissedEvents()` writes a large backlog, the response is partially committed before the HTTP 200 status and SSE
   headers are set. Intermediate proxies may reject or corrupt a stream whose status line changes from 200→200 (benign)
   but whose committed headers differ from what the client expects.
2. If replay throws (unchecked exception percolating through `getMissedEvents`), the `finally` block in the outer
   try-finally (lines 387–392) would attempt to release the permit — but the permit was never acquired (line 349),
   causing a **spurious `IllegalReleaseException`** from the Semaphore.

**Spec contract broken:** Replay must happen after headers/status write.

### PERMIT_ACQUIRING (lines 349–352) — **VIOLATION #2**

```java
349: if (!sseConnections.tryAcquire()) {
350:     writeError(response, 429, "Too many active SSE connections");
351:     return;
352: }
```

Runs AFTER replay has already written SSE data and flushed to the client (lines 345–347).

**Problem:** By the time this runs, `response.getWriter()` bytes may already be buffered/committed at the HTTP layer.
Returning a 429 JSON error at this point would produce a mixed-stream response — part SSE replay data, then a JSON error
block. A client parsing SSE would receive a truncated `data:` block followed by non-SSE content.

**Spec contract broken:** Permit must be held before any response bytes are written.

### HEADERS_WRITING (lines 355–359) — **VIOLATION #3**

```java
355: response.setContentType("text/event-stream");
356: response.setCharacterEncoding("UTF-8");
357: response.setHeader("Cache-Control", "no-cache, no-transform");
358: response.setHeader("Connection", "keep-alive");
359: response.setStatus(200);
```

Runs AFTER replay data has already been flushed (line 347). Any subsequent call to `setStatus()` after bytes are written
to the response buffer has no effect in most servlet containers — the status is already committed.

**Spec contract broken:** Headers/status must be committed before any body bytes.

### CONNECTED_EMITTING (lines 361–363)

```java
361: response.getWriter().write(formatSseEvent(nextEventId++, "connected", ...));
363: response.getWriter().flush();
```

**Compliant with spec** (once ordering is fixed). The `connected` event is written after headers/status and after
replay.

### LIVE_POLLING (lines 368–384)

```java
368: while (!Thread.currentThread().isInterrupted()
369:        && handler.hasSession(sessionId)
370:        && System.currentTimeMillis() - start < 300000L) {
```

**Compliant.** Uses `hasSession()` as the session-liveness check — if `terminateSession()` is called from another
thread, the loop exits cleanly.

### CLEANUP_RELEASING (lines 387–392)

```java
387: } finally {
388:     if (permitHeld) {
389:         sseConnections.release();
390:         permitHeld = false;
391:     }
392: }
```

**Partially compliant** — `permitHeld` guard prevents double-release on `IOException` re-entry, but note the ordering
violations above mean this finally runs even when replay/headers already produced partial output.

---

## 4. Gap Analysis Summary

| # | State               | Violation type       | Severity   | Description                                                                                             |
|---|---------------------|----------------------|------------|---------------------------------------------------------------------------------------------------------|
| 1 | `REPLAYING`         | Ordering             | **HIGH**   | `getMissedEvents()` + flush execute before permit acquisition and before headers/status are set.        |
| 2 | `PERMIT_ACQUIRING`  | Ordering             | **HIGH**   | Permit check is the 5th operation; replay and headers already written to network buffer.                |
| 3 | `HEADERS_WRITING`   | Ordering             | **HIGH**   | HTTP status and headers set after replay data is flushed — status may be silently ignored by container. |
| 4 | `CLEANUP_RELEASING` | Crash safety         | **MEDIUM** | If `getMissedEvents()` throws, `finally` releases an unacquired permit → `IllegalReleaseException`.     |
| 5 | `PERMIT_ACQUIRING`  | Response correctness | **MEDIUM** | 429 on exhausted permit arrives as a mixed SSE-replay + JSON block, not a clean error.                  |

---

## 5. Recommended Fix Order

The operations in `handleGet` must be reordered to:

```
1. VALIDATING          (unchanged — all gate checks)
2. PERMIT_ACQUIRING   (sseConnections.tryAcquire() FIRST)
3. HEADERS_WRITING     (setContentType / setStatus / cache headers)
4. REPLAYING           (getMissedEvents() + write + flush)
5. CONNECTED_EMITTING  (write "connected" + flush)
6. LIVE_POLLING        (loop, no changes)
7. CLEANUP_RELEASING   (finally: release, no structural change needed)
```

The `permitHeld` flag can be removed once `tryAcquire()` is moved before any I/O, since the finally can unconditionally
release. However, the `permitHeld` guard is still defensively useful as a belt-and-suspenders check if future code paths
are refactored.

The `getMissedEvents()` call itself is safe from exceptions (it is a pure read from `ConcurrentLinkedQueue` with no
throwing paths), but wrapping it in the outer try-finally alongside the permit-acquire is still required so that the
finally's release is skipped if `tryAcquire()` itself throws (which it does not — it returns boolean).

---

## 6. File Locations

| File                      | Lines     | Concern                                                                                       |
|---------------------------|-----------|-----------------------------------------------------------------------------------------------|
| `McpGrizzlyHandler.java`  | 322–393   | All ordering violations                                                                       |
| `McpProtocolHandler.java` | 2018–2031 | `getMissedEvents()` — called too early by handler                                             |
| `McpProtocolHandler.java` | 261–287   | `SessionState.pendingEvents` — `ConcurrentLinkedQueue` safe for concurrent read during replay |
