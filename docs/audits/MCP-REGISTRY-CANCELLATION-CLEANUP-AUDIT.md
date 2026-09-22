# McpRegistry Cancellation Cleanup Lifecycle — Audit Findings

**Task:** t_ee9d675b  
**Date:** 2026-09-22  
**Files examined:** `McpRegistry.java`, `McpProtocolHandler.java`, `McpGrizzlyHandler.java`,
`GrizzlyStreamableServerTransportProvider.java`, `McpServer.java`, `McpProgressAndCancellationTest.java`

---

## 1. `clearCancellations` — Current Implementation and Contract

**Location:** `McpRegistry.java:376-378`

```java
public void clearCancellations(String sessionId) {
    if (sessionId != null) cancelledRequests.remove(sessionId);
}
```

**Contract:**

- Removes the entire `Set<String>` entry keyed by `sessionId` from the `cancelledRequests` `ConcurrentHashMap`.
- Idempotent: calling it on a non-existent key is a no-op (`.remove()` on CHM is safe).
- No return value, no exception on null input.
- The map is declared at line 41: `private final ConcurrentHashMap<String, Set<String>> cancelledRequests`.

The three operations on this map are:

| Method                                | Line    | Behaviour                                                                     |
|---------------------------------------|---------|-------------------------------------------------------------------------------|
| `cancelRequest(sessionId, requestId)` | 353-361 | Lazy-creates the session's `KeySet` via `putIfAbsent`, then adds `requestId`. |
| `isCancelled(sessionId, requestId)`   | 368-372 | Returns `true` if `requestId` is in the session's set.                        |
| `clearCancellations(sessionId)`       | 376-378 | Removes the session's entire entry.                                           |

---

## 2. Session Termination Paths — Leak Analysis

Four distinct termination paths exist. `clearCancellations` is called in **zero** of them.

### Path A: Graceful explicit DELETE (NORMAL CLOSE)

- **Entry:** `McpGrizzlyHandler.handleDelete()` at line 395
- **Call:** `handler.terminateSession(sessionId)` at line 409
- **`clearCancellations` called?** NO
- **`terminateSession` body** (`McpProtocolHandler.java:818-827`):
  ```java
  public void terminateSession(String sessionId) {
      if (sessionId != null) {
          SessionState state = sessions.remove(sessionId);
          if (state != null) {
              clearOwnerBinding(state);
              sessionRateLimits.remove(sessionId);  // clears rate limit
              LOGGER.info("Session terminated: " + sessionId);
          }
      }
  }
  ```
  Clears `sessions`, `sessionOwners`, `sessionRateLimits`. **Does NOT touch `cancelledRequests`.**

### Path B: Timeout-induced cleanup (IDLE TIMEOUT)

- **Entry:** `McpProtocolHandler` cleanup thread, started at line 413, runs every `rateLimits.sessionCleanupIntervalMs`
- **Trigger:** `now - entry.getValue().lastActivity > rateLimits.sessionTimeoutMs`
- **Call:** `sessions.remove(entry.getKey(), entry.getValue())` at line 423, then
  `sessionRateLimits.remove(entry.getKey())` at line 425
- **`clearCancellations` called?** NO
- Same partial cleanup as Path A — `cancelledRequests` entry for the expired session remains.

### Path C: Server-wide shutdown (FORCE KILL)

- **Entry:** `McpServer.stop()` at line 58, or `GrizzlyStreamableServerTransportProvider.stop()` at line 171
- **Call chain:** `protocolHandler.closeAllSessions()` (`McpProtocolHandler.java:832`)
- **`closeAllSessions` body** (lines 832-841):
  ```java
  public void closeAllSessions() {
      int count = sessions.size();
      for (SessionState state : sessions.values()) clearOwnerBinding(state);
      sessions.clear();
      sessionOwners.clear();
      sessionRateLimits.clear();
      if (count > 0) { LOGGER.info("Closed " + count + " MCP sessions"); }
  }
  ```
  Bulk-clears all session-scoped maps. **`cancelledRequests` is entirely absent from this method.**
- Additionally, `McpProtocolHandler.shutdown()` (line 445) clears `ipRateLimits` and `sessionRateLimits` but **never
  iterates or clears `cancelledRequests`**.

### Path D: Client disconnect during SSE (ERROR-INDUCED DISCONNECT)

- **Entry:** `McpGrizzlyHandler.handleGet()` at line 322 — the long-polling SSE endpoint
- **Trigger:** `IOException` at line 385 (`// Client disconnected mid-stream`)
- **Handler:** only releases the SSE connection permit (`sseConnections.release()`)
- **`terminateSession` called?** NO
- **`clearCancellations` called?** NO
- **Risk:** If the disconnected client's session is not otherwise cleaned up, its cancellation set leaks indefinitely.

---

## 3. Ownership — Who Should Call `clearCancellations`?

**Recommended single owner: `McpProtocolHandler`.**

Rationale:

- `McpProtocolHandler` owns all session state (`sessions`, `sessionOwners`, `sessionRateLimits`) and is the sole caller
  of `terminateSession`.
- It already coordinates cleanup across all four termination paths.
- Adding `clearCancellations` there avoids spreading session-teardown responsibility across `McpGrizzlyHandler` (
  transport) and `McpServer`/`GrizzlyStreamableServerTransportProvider` (bootstrap).
- The `registry` reference is already held by `McpProtocolHandler`; no new dependency is introduced.

**Alternative considered — `McpRegistry` itself:** Could implement `Closeable` and clear its own cancellation map in a
background thread. Rejected because it would require the registry to hold a reference to `sessions` or receive callbacks
to know when sessions end, creating a circular coupling. `McpProtocolHandler` is already the natural coordination point.

**Code change required:** Add `registry.clearCancellations(sessionId)` in two places in `McpProtocolHandler.java`:

1. Inside `terminateSession(String sessionId)` (line 818), after `sessionRateLimits.remove(sessionId)` and before the
   `LOGGER` call.
2. Inside the timeout cleanup loop (line 421-427), after `sessionRateLimits.remove(entry.getKey())`.

`closeAllSessions()` does not need a direct call since iterating all sessions through `terminateSession` is not
required — `closeAllSessions` is a bulk shutdown path called during server teardown where the registry itself is being
discarded. If the `McpServer` is shut down and discarded, the `cancelledRequests` map goes with it. However, for
robustness and consistency, a single `registry.clearCancellations(entry.getKey())` call per expired session in the
timeout loop is sufficient.

---

## 4. Lifecycle — When Exactly Should Cancellations Be Cleared?

**Must clear on all three scenarios:**

| Scenario                         | Cleared?  | Proposed fix                      |
|----------------------------------|-----------|-----------------------------------|
| Explicit DELETE (graceful close) | NO (leak) | Add to `terminateSession()`       |
| Idle timeout expiration          | NO (leak) | Add to timeout cleanup loop       |
| Server-wide `closeAllSessions()` | NO (leak) | Add loop over `sessions.keySet()` |

**Should NOT clear on:**

- Error-induced disconnect mid-SSE (Path D) — unless the session is also terminated by that same error path. Currently
  Path D does not call `terminateSession`, so no change is needed there unless the disconnect handler is extended to
  terminate the session.

**Ordering:** `clearCancellations` should be called after (or alongside) `sessionRateLimits.remove(sessionId)`, before
logging, to ensure atomicity of session teardown from the perspective of the cancellation map.

---

## 5. Required Test Scenarios

The existing test `notificationCancelledRecordsAndClearsPerSession` (`McpProgressAndCancellationTest.java:65-86`) only
tests the happy path (manual call to `clearCancellations`). The following scenarios are missing:

### T1: `terminateSession` clears cancellation state

```
1. init session
2. send notifications/cancelled for requestId "99"
3. assert registry.isCancelled(sessionId, "99") == true
4. call handler.terminateSession(sessionId)
5. assert registry.isCancelled(sessionId, "99") == false
```

### T2: Idle timeout expires and clears cancellation state

```
1. init session with short sessionTimeoutMs (e.g. 100ms)
2. send notifications/cancelled for requestId "99"
3. assert registry.isCancelled(sessionId, "99") == true
4. wait for cleanup thread to fire (sleep 2x timeout)
5. assert registry.isCancelled(sessionId, "99") == false
6. assert handler.hasSession(sessionId) == false
```

### T3: `closeAllSessions` clears cancellation state

```
1. init two sessions
2. send notifications/cancelled on each for requestId "x"
3. assert both sessions' cancellations recorded
4. call handler.closeAllSessions()
5. assert both sessions' cancellations cleared
```

### T4: Concurrent session teardown

```
1. init N sessions (e.g. 10)
2. cancel requestIds on each
3. trigger closeAllSessions concurrently with individual terminateSession calls
4. assert all cancellation sets cleared; no ConcurrentModificationException
```

---

## 6. Findings Summary

| # | Severity   | Location                                               | Issue                                                                                                                                             |
|---|------------|--------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | **HIGH**   | `McpProtocolHandler.java:818-827` (`terminateSession`) | `clearCancellations(sessionId)` not called; cancellation set leaks after explicit close                                                           |
| 2 | **HIGH**   | `McpProtocolHandler.java:421-427` (cleanup thread)     | `clearCancellations(entry.getKey())` not called after timeout eviction; cancellation set leaks after idle timeout                                 |
| 3 | **HIGH**   | `McpProtocolHandler.java:832-841` (`closeAllSessions`) | All sessions' cancellation sets survive bulk close; map grows unbounded with repeated server restarts                                             |
| 4 | **MEDIUM** | `McpGrizzlyHandler.java:385-391` (SSE disconnect)      | `terminateSession` not called on client disconnect mid-stream; session may survive but cancellation state not cleaned unless separately timed out |
| 5 | **LOW**    | `McpProgressAndCancellationTest.java:65-86`            | Existing test manually calls `clearCancellations` instead of triggering actual session termination; does not catch any of the above leaks         |

**Root cause:** `cancelledRequests` was added to `McpRegistry` (commit unknown) but `clearCancellations` was never wired
into any of the session termination paths in `McpProtocolHandler`. The method exists and is tested in isolation but has
no callers in production code.

---

## Recommended Fix (Delta)

In `McpProtocolHandler.java`, two additions:

**In `terminateSession()` (after line 823):**

```java
registry.clearCancellations(sessionId);
```

**In the cleanup thread loop (after line 425):**

```java
registry.clearCancellations(entry.getKey());
```

**In `closeAllSessions()` (after line 835, before/after `sessions.clear()`):**

```java
for (String sid : sessions.keySet()) registry.clearCancellations(sid);
```

Or equivalently, since `sessions` is cleared unconditionally, iterate the key set before clearing:

```java
sessions.keySet().forEach(registry::clearCancellations);
sessions.clear();
```

All three methods should also be covered by new test cases (Section 5, T1-T4).
