# SSE Implementation Slices and Integration Test Plan

**Spec ID:** SSE-IMPL-SLICES-TEST-PLAN
**Date:** 2026-09-21
**Status:** Draft — for implementation handoff
**Sources:** `SSE-CONNECTION-STATE-MACHINE.md` (t_d23b44be), `SSE-LAST-EVENT-ID-REPLAY-CONSISTENCY.md` (t_475ff2c2),
`RATE-LIMIT-429-BEHAVIOR-SPEC.md` (t_59da6f1b)
**Workspace:** `D:\android\mcp-java-sdk`

---

## Scope

This document is a **pure planning artifact** — no code is written here. It decomposes the three upstream specifications
into:

1. **Implementation slices** — the exact method-level changes required, in dependency order.
2. **Integration test plan** — deterministic test cases covering concurrent SSE clients, reconnection with
   `Last-Event-ID`, rate-limit 429 injection, and disconnect/permit release.
3. **Ordering-issue exposure tests** — scenarios designed to prove the current bugs before the fixes land.

---

## Part I — Implementation Slices

### Slice 0: ADR-0016 — SSE Permit Acquisition Ordering (Prerequisite)

**Files:** `McpGrizzlyHandler.java` lines 322–393
**Severity:** HIGH — unblocks all other slices
**Spec:** `docs/SSE-CONNECTION-STATE-MACHINE.md` §5

#### Current broken order (lines 322–363)

```
VALIDATING → REPLAYING → PERMIT_ACQUIRING → HEADERS_WRITING → CONNECTED_EMITTING
```

#### Target correct order

```
VALIDATING → PERMIT_ACQUIRING → HEADERS_WRITING → REPLAYING → CONNECTED_EMITTING → LIVE_POLLING
```

#### Method-level changes

**`McpGrizzlyHandler.handleGet()` (lines 322–393)**

| Step | Action                                                                                                                                                   | Lines (approx)          |
|------|----------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------|
| 0    | Move `parseLastEventId()` call up before `tryAcquire()` (it is pure, no I/O)                                                                             | ~336–342                |
| 1    | Move `sseConnections.tryAcquire()` to line 343, before `getMissedEvents()` call                                                                          | ~343–352                |
| 2    | Set `permitHeld = true` after successful `tryAcquire()`                                                                                                  | ~353                    |
| 3    | Move `response.setContentType(...)`, `setCharacterEncoding(...)`, `setHeader(...)`, `setStatus(200)` to after `tryAcquire()`, before any `write()` calls | ~355–359                |
| 4    | Move `getMissedEvents()` + `write()` + `flush()` to after headers/status                                                                                 | ~343–348 → new ~360–364 |
| 5    | Move `connected` event write + flush to after replay flush                                                                                               | ~361–363                |
| 6    | Remove `permitHeld = true` assignment that currently precedes `tryAcquire()`                                                                             | —                       |
| 7    | Update `finally` block: `permitHeld` guard still needed (belt-and-suspenders)                                                                            | ~387–392                |

**Verification:** After change, no `response.getWriter().write()` or `response.getWriter().flush()` call occurs before
`response.setStatus(200)` in `handleGet()`.

**Side effect fixed:** Gap #4 from state-machine doc (crash in `getMissedEvents()` → spurious `IllegalReleaseException`)
is also fixed because replay now executes inside the `try { permitHeld }` block.

---

### Slice 1: Defensive Copy in `getMissedEvents()`

**File:** `McpProtocolHandler.java` lines 2018–2031
**Severity:** MEDIUM (addresses Scenario A in t_475ff2c2 §3)
**Spec:** `SSE-LAST-EVENT-ID-REPLAY-CONSISTENCY.md` §4, Option A

#### Current code (L2018–2030)

```java
public String getMissedEvents(String sessionId, long afterEventId) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return "";
    StringBuilder sb = new StringBuilder();
    for (SseEvent event : state.pendingEvents) {  // ← weakly-consistent iterator
        if (event.id > afterEventId) {
            sb.append("id: ").append(event.id)
                    .append("\nevent: message\ndata: ")
                    .append(escapeSseData(event.body))
                    .append("\n\n");
        }
    }
    return sb.toString();
}
```

#### Method-level change

Add one line to take a snapshot copy before iterating:

```java
public String getMissedEvents(String sessionId, long afterEventId) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return "";
    StringBuilder sb = new StringBuilder();
    // Snapshot: copy prevents concurrent poll() from skipping unvisited events (Scenario A)
    Object[] snapshot = state.pendingEvents.toArray();
    for (Object o : snapshot) {
        SseEvent event = (SseEvent) o;
        if (event.id > afterEventId) {
            sb.append("id: ").append(event.id)
                    .append("\nevent: message\ndata: ")
                    .append(escapeSseData(event.body))
                    .append("\n\n");
        }
    }
    return sb.toString();
}
```

**Effect:** Eliminates Scenario A (concurrent `pollSseEvent()` skipping unvisited events during iteration). Does NOT fix
Scenario C (overflow eviction during `enqueueEvent`).

**Note:** `toArray()` is O(n) but `pendingEvents` is bounded to `MAX_QUEUED_EVENTS` (100), so this is a small fixed
cost.

---

### Slice 2: Structured `McpResponse.isRateLimited()` — Replace Fragile String Match

**Files:** `McpProtocolHandler.java`, `McpGrizzlyHandler.java` line 282–294
**Severity:** MEDIUM (O1 from RATE-LIMIT-429-BEHAVIOR-SPEC.md §7)
**Spec:** `RATE-LIMIT-429-BEHAVIOR-SPEC.md` §2.2

#### Current fragile code (McpGrizzlyHandler.java L282–294)

```java
// String-match detection — fragile but functional
boolean isRateLimited = result.getBody() != null &&
        result.getBody().contains("\"code\":-32029");
```

This breaks if GSON serializes differently (e.g., no spaces, different key ordering).

#### Method-level changes

**1. Add field to `McpResponse` inner class (McpProtocolHandler.java)**

```java
public static class McpResponse {
    private final String body;
    private final String sessionId;
    private final boolean rateLimited;   // ← new field
    // existing constructor + getters
    public boolean isRateLimited() { return rateLimited; }
}
```

**2. Set `rateLimited = true` in the three rate-limit denial paths (McpProtocolHandler.java L586, 598, 619)**

```java
// In each denial path, instead of returning null or a string body directly:
return new McpResponse(/*body=...*/, /*sessionId=...*/, true);
```

**3. Replace string match in `McpGrizzlyHandler.handlePost()` (L282–284)**

```java
// Replace:
boolean isRateLimited = result.getBody() != null &&
        result.getBody().contains("\"code\":-32029");

// With:
boolean isRateLimited = result != null && result.isRateLimited();
```

**Verification:** Any future GSON serialization change will not break rate-limit detection.

---

### Slice 3: SSE 429 Body Code Consistency

**File:** `McpGrizzlyHandler.java` lines 442–470
**Severity:** LOW (cosmetic — decision documented in RATE-LIMIT-429-BEHAVIOR-SPEC.md §3)
**Spec:** `RATE-LIMIT-429-BEHAVIOR-SPEC.md` §3, Decision Q2

#### Current inconsistency

| Path                              | HTTP Status | Body `code` |
|-----------------------------------|-------------|-------------|
| POST rate limit (handlePost)      | 429         | -32029      |
| SSE permit exhaustion (handleGet) | 429         | 429         |

#### Method-level change

Adopt the documented decision: **do not change**. Transport-layer `writeError` continues using `code = status`. Document
in-code that this is intentional: transport codes are HTTP-level. The authoritative signal is HTTP status, not body
code.

**Action:** Add a code comment above `writeError` documenting this convention.

```java
/**
 * Writes a JSON-RPC 2.0 error response.
 * The body error.code equals the HTTP status (transport-layer convention).
 * For rate-limit 429 specifically, the authoritative signal is the HTTP status;
 * the body code (429 vs -32029) is informational only.
 */
private static void writeError(Response response, int status, String message) { ... }
```

---

### Slice 4: Gap Event on Queue Overflow (Optional Future Enhancement)

**File:** `McpProtocolHandler.java` — `enqueueEvent()` + `getMissedEvents()`
**Severity:** MEDIUM (addresses Scenario C — permanent silent event loss)
**Spec:** `SSE-LAST-EVENT-ID-REPLAY-CONSISTENCY.md` §4, Option D

This is NOT required for initial correctness but is recommended for production soundness.

**Concept:** When `MAX_QUEUED_EVENTS` overflow causes eviction, emit a synthetic gap event that the client can detect:

```java
// In enqueueEvent(), detect overflow:
void enqueueEvent(String body) {
    if (pendingEvents.size() >= MAX_QUEUED_EVENTS) {
        SseEvent evicted = pendingEvents.poll();
        if (evicted != null) {
            // Store lastEvictedId to report in getMissedEvents
            lastEvictedId = evicted.id;  // needs new field on SessionState
        }
    }
    pendingEvents.offer(new SseEvent(nextEventId.getAndIncrement(), body));
}

// In getMissedEvents(), after iteration:
if (lastEvictedId != null && lastEvictedId > afterEventId) {
    sb.append("id: ").append(lastEvictedId)
            .append("\nevent: gap\ndata: {\"lost\":true}\n\n");
}
```

**Trade-off:** Requires new `lastEvictedId` field on `SessionState` and logic in `getMissedEvents`. Client must handle
`event: gap` events.

---

### Slice Dependency Graph

```
Slice 0 (ADR-0016) ──────────────────────────────────────────┐
  [McpGrizzlyHandler.handleGet() reorder]                      │
                                                             │
Slice 1 (getMissedEvents defensive copy)                      │
  [McpProtocolHandler.getMissedEvents() toArray]              │
                                                             │ (independent)
Slice 2 (isRateLimited structured field) ───────────────────┐ │
  [McpResponse.rateLimited field + setter]                    │ │
  [McpGrizzlyHandler string-match removal]                    │ │
                                                             │ │
Slice 3 (code comment only)                                  │ │
  [No code change — documentation only]                      │ │
                                                             │ │
Slice 4 (gap events — optional)                              │ │
  [SessionState.lastEvictedId + enqueueEvent + getMissedEvents]
```

---

## Part II — Integration Test Plan

### Test Infrastructure

All tests use `GrizzlyStreamableServerTransportProvider` (from `McpGrizzlyLiveTest.java`) spun up per-test with
`port(0)` (ephemeral). SSE reads use `HttpURLConnection` with a background thread draining the stream.

**Key test utilities needed:**

```java
// SSE stream reader helper — runs in background thread
static class SseReader implements Callable<List<String>> {
    private final HttpURLConnection conn;
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public List<String> call() throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder event = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    events.add(event.toString());
                    event.setLength(0);
                } else {
                    event.append(line).append("\n");
                }
            }
        } finally {
            latch.countDown();
        }
        return events;
    }
}

// Parse SSE event ID from raw SSE block
static long parseEventId(String block) {
    return Long.parseLong(block.split("\n")[0].substring(3).trim());
}
```

---

### Category A: Concurrent SSE Clients

#### A1: Two Clients Simultaneously Open SSE Connections

**Setup:**

1. Start server with `maxSseConnections = 2`
2. Initialize session via POST
3. Open SSE GET client-1
4. Open SSE GET client-2

**Expected (current):** Both succeed (2 permits available)
**Expected (after fix):** Same
**Deterministic assertion:**

```java
assertEquals(200, client1Status);
assertEquals(200, client2Status);
assertTrue(client1Events.stream().anyMatch(e -> e.contains("id:1\nevent:connected")));
assertTrue(client2Events.stream().anyMatch(e -> e.contains("id:1\nevent:connected")));
```

#### A2: Third Concurrent Client Gets 429

**Setup:** From A1 state, open SSE GET client-3

**Expected:** HTTP 429 with valid JSON-RPC error body

```java
assertEquals(429, client3Status);
assertTrue(client3ErrorBody.contains("\"code\":429"));
assertTrue(client3ErrorBody.contains("Too many active SSE connections"));
```

#### A3: SSE 429 Response Has No SSE Data (CURRENTLY BROKEN — Gap #5)

**Setup:** From A1 state, client-3 reconnects with `Last-Event-ID: 0` (to trigger replay)

**Before fix:** Client-3 gets mixed response — replay SSE data followed by JSON error (violates AC-4 of
RATE-LIMIT-429-BEHAVIOR-SPEC)
**After fix:** Client-3 gets clean 429 with no SSE data in body

```java
// Before fix — this assertion FAILS:
String raw = readRawResponse(client3Conn);
assertFalse(raw.contains("id:"), "429 body must not contain SSE id fields");
assertFalse(raw.contains("event:"), "429 body must not contain SSE event fields");
assertTrue(raw.contains("\"code\":429"), "429 body must be valid JSON-RPC");
```

---

### Category B: Reconnection with Last-Event-ID

#### B1: Replay Returns Events in Strict Monotonic ID Order

**Setup:**

1. Initialize session
2. Send 3 notifications via `notifyResourceUpdated()` (each increments event ID)
3. Open SSE GET with `Last-Event-ID: 0`

**Expected:** Replay contains events with IDs [1, 2, 3] in that order

```java
List<Long> ids = replayEvents.stream()
    .map(SseReader::parseEventId)
    .collect(Collectors.toList());
assertEquals(List.of(1L, 2L, 3L), ids);
```

#### B2: Reconnect with Last-Event-ID Skips Already-Delivered Events

**Setup:**

1. From B1 state: client reads and closes after receiving ID 1
2. Reconnect with `Last-Event-ID: 1`

**Expected:** Replay contains only IDs [2, 3]

```java
assertEquals(List.of(2L, 3L), replayEventIds);
```

#### B3: Reconnect with Last-Event-ID Equal to Max ID — Empty Replay

**Setup:** After B2, reconnect with `Last-Event-ID: 3`

**Expected:** Replay is empty (no SSE events)

```java
assertTrue(replayEvents.isEmpty());
assertTrue(reconnectEvents.stream().anyMatch(e -> e.contains("event:connected")));
```

#### B4: Reconnect with `Last-Event-ID` Higher Than Any Known ID — Empty Replay

**Setup:** Reconnect with `Last-Event-ID: 999`

**Expected:** Empty replay, then `connected` event

```java
assertTrue(replayEvents.isEmpty());
assertFalse(connectedEvent.isEmpty());
```

#### B5: Invalid `Last-Event-ID` (non-integer) Returns 400

**Setup:** SSE GET with `Last-Event-ID: abc`

**Expected:** HTTP 400

```java
assertEquals(400, status);
assertTrue(errorBody.contains("Last-Event-ID must be a non-negative integer"));
```

#### B6: Negative `Last-Event-ID` Returns 400

**Setup:** SSE GET with `Last-Event-ID: -1`

**Expected:** HTTP 400

```java
assertEquals(400, status);
assertTrue(errorBody.contains("negative event id"));
```

---

### Category C: Concurrent SSE + Concurrent Poll

**Purpose:** Expose ordering issues from `SSE-LAST-EVENT-ID-REPLAY-CONSISTENCY.md` §3, Scenario A (MEDIUM severity).

#### C1: Replay Not Skipped by Concurrent `pollSseEvent()` After Defensive Copy

**Setup:**

1. Initialize session, subscribe to resource
2. Send 10 notifications (events 1–10)
3. Background thread: repeatedly call `handler.pollSseEvent()` to consume events 1–3
4. SSE GET with `Last-Event-ID: 0` (replay all)

**Expected (before Slice 1):** Replay may contain only events 4–10 (events 1–3 silently skipped by concurrent poll)
**Expected (after Slice 1):** Replay contains events 1–10 (snapshot protects iteration)

```java
// Before Slice 1 — this may fail intermittently:
Set<Long> missedIds = replayEvents.stream()
    .map(SseReader::parseEventId)
    .collect(Collectors.toSet());
// Should contain 1 through 10
assertEquals(IntStream.rangeClosed(1, 10).boxed().collect(Collectors.toSet()), missedIds);
```

#### C2: Replay Order is Strictly Increasing (No Out-of-Order IDs)

**Setup:** From B1 state (3 events replayed)

**Invariant:** IDs in replay are always strictly increasing (no duplicates, no inversions)

```java
List<Long> ids = replayEvents.stream()
    .map(SseReader::parseEventId)
    .collect(Collectors.toList());
for (int i = 1; i < ids.size(); i++) {
    assertTrue(ids.get(i) > ids.get(i - 1),
        "Event IDs must be strictly increasing, but " + ids.get(i - 1) + " -> " + ids.get(i));
}
```

---

### Category D: Rate-Limit 429 Injection

#### D1: POST Request Rate Limit Returns 429 with Valid JSON-RPC Body

**Setup:**

1. Configure `read(2, 10, 3)` — burst limit of 2
2. Initialize session
3. POST `resources/list` twice (succeeds)
4. POST `resources/list` a third time

**Expected:** HTTP 429 with valid JSON-RPC body

```java
assertEquals(429, conn.getResponseCode());
String body = readBody(conn.getErrorStream());
assertTrue(body.startsWith("{"));
assertTrue(body.contains("\"jsonrpc\":\"2.0\""));
assertTrue(body.contains("\"code\":429") || body.contains("\"code\":-32029"));
assertTrue(body.contains("Too Many Requests"));
```

#### D2: POST 429 Includes X-RateLimit-* Headers When Status Available

**Setup:** From D1, with non-null `RateLimitStatus`

**Expected:** Headers present

```java
assertEquals("2", conn.getHeaderField("X-RateLimit-Limit"));
assertEquals("0", conn.getHeaderField("X-RateLimit-Remaining"));
assertNotNull(conn.getHeaderField("X-RateLimit-Reset"));
```

#### D3: SSE Permit Exhaustion 429 Returns Clean JSON Body (No SSE Data Mixed In)

**Purpose:** Validates Slice 0 + Gap #5 fix.

**Setup:**

1. Start server with `maxSseConnections = 1`
2. Fill permit with client-1
3. Client-2 requests SSE

**Expected:** Clean 429 body, no SSE event data

```java
String raw = readRawResponse(client2Conn);
// Must not contain SSE markers
assertFalse(raw.contains("id:"),   "429 body must not contain 'id:' SSE field");
assertFalse(raw.contains("data:"), "429 body must not contain 'data:' SSE field");
assertFalse(raw.contains("event:"),"429 body must not contain 'event:' SSE field");
// Must be valid JSON-RPC
assertTrue(raw.contains("\"jsonrpc\":\"2.0\""));
assertTrue(raw.contains("\"code\":429"));
```

#### D4: SSE 429 Has `Content-Type: application/json`

**Setup:** From D3

```java
assertEquals("application/json; charset=UTF-8", conn.getContentType());
```

#### D5: SSE 429 Body Code is 429 (Transport Convention)

**Setup:** From D3

**Expected:** Body `code` equals HTTP status (transport convention, per RATE-LIMIT-429-BEHAVIOR-SPEC decision)

```java
assertTrue(body.contains("\"code\":429"));
// Note: not -32029 — transport-layer convention documented in Slice 3
```

---

### Category E: Disconnect and Permit Release

#### E1: Client Disconnect Releases SSE Permit (IOException Path)

**Setup:**

1. Start server with `maxSseConnections = 1`
2. Client-1 opens SSE connection
3. Client-2 attempts SSE — should block (no permits)
4. Abruptly disconnect Client-1 (close socket without completing request)
5. Client-2 retries SSE

**Expected:** Client-2 succeeds on retry (permit released)

```java
client1.close();
Thread.sleep(100);  // allow server to process disconnect
SSEClient client2Retry = openSseConnection(sessionId);
assertEquals(200, client2Retry.getResponseCode());
assertTrue(client2RetryEvents.stream().anyMatch(e -> e.contains("event:connected")));
```

#### E2: SSE GET with `DELETE` Session Terminates Permits

**Setup:**

1. Client-1 opens SSE
2. Client-1 sends `DELETE /mcp` session teardown

**Expected:** DELETE succeeds (204), permit released

```java
delete(transport.getUrl(), sessionId);
assertEquals(204, status);
// Permit is released; new SSE connection should succeed
SSEClient client2 = openSseConnection(sessionId);
assertEquals(200, client2.getResponseCode());
```

#### E3: Idle Timeout (5 min) Releases SSE Permit

**Setup:**

1. Open SSE connection, do NOT read events
2. Wait 300+ seconds (use `Thread.sleep` in test — acceptable for unit test)

**Expected:** After timeout, permit released, new SSE succeeds

```java
Thread.sleep(305_000);  // 5 min + buffer
SSEClient client2 = openSseConnection(sessionId);
assertEquals(200, client2.getResponseCode());
```

#### E4: Permit Not Leaked on Unexpected Exception in SSE Handler

**Setup:** Start server with `maxSseConnections = 1`; fill the permit with Client-1.

**Expected:** Even if Client-1's stream throws mid-flight, permit is not double-held.

```java
// Count active permits: tryAcquire() returns false immediately if one is held
Semaphore snapshot = getSseSemaphore(handler);  // via reflection
assertEquals(1, snapshot.availablePermits());  // 1 was taken by Client-1, 1 remaining (total=2)
```

---

### Category F: Current Ordering Bugs (Expose Before Fixes)

These tests are expected to **FAIL on current code** and **PASS after Slice 0**.

#### F1: HTTP 200 Status Is Set BEFORE SSE Body Is Written

**Current behavior:** `setStatus(200)` at line 359 is called AFTER replay write+flush at line 347.

**Test:** Inspect raw HTTP response stream:

```java
// Before fix — assertion FAILS:
String raw = rawHttpResponse(sseConn);
int statusLinePos = raw.indexOf("HTTP/1.1");
int bodyPos = raw.indexOf("id:");
// After fix: status line (200) must precede any SSE body bytes
assertTrue(statusLinePos < bodyPos, "HTTP status must appear before SSE body in stream");
```

#### F2: SSE Headers Are Set BEFORE Replay Body Is Written

**Current behavior:** `setContentType("text/event-stream")` at line 355 is called AFTER replay flush.

**Test:**

```java
// Before fix — assertion FAILS:
String raw = rawHttpResponse(sseConn);
int headersPos = raw.indexOf("Content-Type: text/event-stream");
int replayDataPos = raw.indexOf("event: message");
assertTrue(headersPos < replayDataPos, "SSE Content-Type header must precede replay data");
```

#### F3: Permit Is Acquired BEFORE Replay

**Current behavior:** `tryAcquire()` at line 349 runs AFTER replay.

**Test:**

```java
// Before fix — assertion FAILS:
// We cannot directly observe acquire order, but we can observe:
// After fix: a 429 must NEVER have SSE data in the body (see D3)
// Before fix: 429 at line 349 has already written replay data
```

#### F4: Crash in `getMissedEvents()` Does Not Leak Permit

**Setup:** Mock `getMissedEvents()` to throw `RuntimeException`

**Before fix:** `finally` releases unacquired permit → `IllegalReleaseException`
**After fix:** `getMissedEvents()` is inside the `try { permitHeld }` block, so `finally` only releases if permit was
actually acquired

```java
// Inject fault into handler.getMissedEvents via spy/proxy
doThrow(new RuntimeException("synthetic")).when(handler)
    .getMissedEvents(eq(sessionId), eq(0L));

// Before fix: assertThrows(IllegalReleaseException.class, () -> handleGet(...))
// After fix: handleGet() returns error cleanly, no IllegalReleaseException
```

---

## Part III — Test Execution Matrix

| ID | Category        | Test Name                      | Pre-Fix Expected | Post-Fix Expected | Test Class                          |
|----|-----------------|--------------------------------|------------------|-------------------|-------------------------------------|
| A1 | Concurrent      | Two clients succeed            | PASS             | PASS              | `McpGrizzlyLiveTest`                |
| A2 | Concurrent      | Third client gets 429          | PASS             | PASS              | `McpGrizzlyLiveTest`                |
| A3 | Concurrent      | SSE 429 clean (no SSE data)    | **FAIL**         | PASS              | `McpSse429CleanTest` (new)          |
| B1 | Reconnect       | Replay monotonic ID order      | PASS             | PASS              | `McpSseLastEventIdTest` (new)       |
| B2 | Reconnect       | Reconnect skips delivered      | PASS             | PASS              | `McpSseLastEventIdTest`             |
| B3 | Reconnect       | Reconnect at max ID — empty    | PASS             | PASS              | `McpSseLastEventIdTest`             |
| B4 | Reconnect       | Reconnect at unknown ID        | PASS             | PASS              | `McpSseLastEventIdTest`             |
| B5 | Reconnect       | Invalid Last-Event-ID → 400    | PASS             | PASS              | `McpSseLastEventIdTest`             |
| B6 | Reconnect       | Negative Last-Event-ID → 400   | PASS             | PASS              | `McpSseLastEventIdTest`             |
| C1 | Concurrent poll | Defensive copy prevents skip   | **FAIL**         | PASS              | `McpSseReplayConsistencyTest` (new) |
| C2 | Concurrent poll | Replay IDs strictly increasing | PASS             | PASS              | `McpSseReplayConsistencyTest`       |
| D1 | Rate-limit      | POST 429 valid JSON-RPC        | PASS             | PASS              | `McpIntegrationTest` (extend)       |
| D2 | Rate-limit      | POST 429 has X-RateLimit-*     | PASS             | PASS              | `McpIntegrationTest`                |
| D3 | Rate-limit      | SSE 429 clean body             | **FAIL**         | PASS              | `McpSse429CleanTest`                |
| D4 | Rate-limit      | SSE 429 Content-Type           | **FAIL**         | PASS              | `McpSse429CleanTest`                |
| D5 | Rate-limit      | SSE 429 body code = 429        | PASS             | PASS              | `McpSse429CleanTest`                |
| E1 | Disconnect      | Disconnect releases permit     | PASS             | PASS              | `McpSseDisconnectTest` (new)        |
| E2 | Disconnect      | DELETE releases permit         | PASS             | PASS              | `McpSseDisconnectTest`              |
| E3 | Disconnect      | Idle timeout releases permit   | PASS             | PASS              | `McpSseSessionTimeoutTest`          |
| E4 | Disconnect      | No permit leak on exception    | PASS             | PASS              | `McpSseDisconnectTest`              |
| F1 | Ordering bug    | Status before body             | **FAIL**         | PASS              | `McpSseOrderingBugTest` (new)       |
| F2 | Ordering bug    | Headers before replay          | **FAIL**         | PASS              | `McpSseOrderingBugTest`             |
| F3 | Ordering bug    | 429 has no SSE data            | **FAIL**         | PASS              | `McpSseOrderingBugTest`             |
| F4 | Ordering bug    | Crash no permit leak           | **FAIL**         | PASS              | `McpSseOrderingBugTest`             |

**Total tests:** 26
**Expected failures on current code:** 8 (A3, C1, D3, D4, F1–F4)
**Expected passes on current code:** 18

---

## Part IV — Suggested Test File Layout

```
src/test/java/io/github/vinhphan812/mcp/
    McpSseLastEventIdTest.java         — Categories B, C2
    McpSse429CleanTest.java            — Categories A3, D3, D4, D5
    McpSseReplayConsistencyTest.java   — Categories C1, C2
    McpSseDisconnectTest.java          — Categories E1, E2, E4
    McpSseOrderingBugTest.java         — Categories F1–F4
    (extend) McpIntegrationTest.java   — D1, D2
```

All new test classes follow the `@Nested` pattern inside a `@Test class` using
`GrizzlyStreamableServerTransportProvider` per-test, with `transport.stop()` in `@AfterEach`.

---

## Appendix: Key Source Locations

| Item                                       | File                                                      | Lines     |
|--------------------------------------------|-----------------------------------------------------------|-----------|
| `handleGet()` — broken ordering            | `McpGrizzlyHandler.java`                                  | 322–393   |
| `handlePost()` — fragile string match      | `McpGrizzlyHandler.java`                                  | 282–294   |
| `writeError()` — transport-layer error     | `McpGrizzlyHandler.java`                                  | 442–470   |
| `getMissedEvents()` — weak iterator        | `McpProtocolHandler.java`                                 | 2018–2031 |
| `pollSseEvent()` — only removal path       | `McpProtocolHandler.java`                                 | 2002–2008 |
| `SessionState.pendingEvents`               | `McpProtocolHandler.java`                                 | ~260–286  |
| `McpResponse` inner class                  | `McpProtocolHandler.java`                                 | ~130–160  |
| `MAX_QUEUED_EVENTS`                        | `McpProtocolHandler.java`                                 | ~260      |
| Rate-limit denial paths (L586, 598, 619)   | `McpProtocolHandler.java`                                 | 570–625   |
| `GrizzlyStreamableServerTransportProvider` | `transport/GrizzlyStreamableServerTransportProvider.java` | —         |
| Existing SSE live test pattern             | `McpGrizzlyLiveTest.java`                                 | —         |
| Existing queue overflow test               | `McpQueueOverflowTest.java`                               | —         |
