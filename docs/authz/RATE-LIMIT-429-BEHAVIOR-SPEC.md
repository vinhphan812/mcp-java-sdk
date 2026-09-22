# Rate-Limit 429 Behavior, Error Framing, and Disconnect Protocol

**Spec ID:** RATE-LIMIT-429-BEHAVIOR
**Date:** 2026-09-21
**Status:** Active
**Parent Tasks:** `t_59da6f1b`
**Reference Source Files:**

- `McpGrizzlyHandler.java` — HTTP transport (denial output)
- `McpProtocolHandler.java` — MCP protocol + rate-limit logic
  **Supersedes:** Partial gaps in ADR-0016 (permit ordering), ADR-0017 (error framing)
  **Requires Implementation:** `t_...` (child tasks, to be created)

---

## 1. Scope and Definitions

### 1.1 In Scope

Four concrete questions answered for `McpProtocolHandler` + `McpGrizzlyHandler`:

| #  | Question                                                                                    | Layer     |
|----|---------------------------------------------------------------------------------------------|-----------|
| Q1 | What HTTP response is sent for a 429, and does it write any body before the status/headers? | Transport |
| Q2 | What is the exact format of the 429 error body? Is it valid JSON-RPC?                       | Transport |
| Q3 | What happens when an SSE client drops mid-stream?                                           | Transport |
| Q4 | Is the SSE permit released on every exit path?                                              | Transport |

### 1.2 Out of Scope

- Protocol-level retry guidance (Retry-After header client behavior)
- Category concurrent counters for POST requests (they are tracked in `incrementMethodConcurrentCount` /
  `decrementMethodConcurrentCount` — verified separately)
- Session rate-limit records (`sessionRateLimits` cleanup on timeout/disconnect)
- The SSE permit ordering fix (governed by ADR-0016 / `t_60011d87`)

### 1.3 Definitions

| Term                                | Definition                                                                                                              |
|-------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| **429 denial path**                 | One of the two code paths that return HTTP 429 to the client                                                            |
| **Path A: SSE permit exhaustion**   | `handleGet()` line 349: `!sseConnections.tryAcquire()` → `writeError(429, ...)`                                         |
| **Path B: POST request rate limit** | `handlePost()` line 282–294: handler returns `-32029` body; Grizzly detects and calls `writeRateLimitedError(429, ...)` |
| **Headers-only**                    | HTTP status + headers written with zero body bytes before the denial body                                               |
| **Clean denial**                    | HTTP response with no corruption: status, headers, and body are all self-consistent                                     |
| **Permit release**                  | `sseConnections.release()` called exactly once per acquired permit                                                      |
| **Disconnect**                      | Client closes its read end of the SSE TCP connection                                                                    |

---

## 2. Denial Path Analysis

### 2.1 Path A: SSE Permit Exhaustion (`handleGet`)

**Current code (lines 322–351):**

```java
// [L322] WRONG ORDER — body written before permit check
if (lastEventId != null) {
    String missed = handler.getMissedEvents(sessionId, lastEventId);
    response.getWriter().write(missed);   // ← BODY WRITTEN
    response.getWriter().flush();          // ← BODY FLUSHED
}

// [L349]
if (!sseConnections.tryAcquire()) {
    writeError(response, 429, "Too many active SSE connections"); // ← 429
    return;
}
```

**Problem:** If `lastEventId != null`, the replay SSE body is written and flushed BEFORE `tryAcquire()` is called. If
`tryAcquire()` then fails, `writeError()` overwrites a partially-committed response. The client sees a corrupted
response (mixed SSE data + JSON error).

**Correct ordering (per ADR-0016 AC-1, AC-2):** The permit MUST be acquired BEFORE any body bytes are written. The fix
moves `tryAcquire()` to before the replay block.

**429 behavior when fixed:**

1. `tryAcquire()` is called with no prior body output → always headers-only so far
2. `writeError(response, 429, ...)` is called
3. `writeError` (3-arg, lines 442–443) delegates to `writeError(6-arg, lines 451–459)`
4. `writeError(6-arg)` performs:
    - `response.setContentType("application/json")` — overwrites any default; no prior content-type set
    - `response.setCharacterEncoding("UTF-8")`
    - `response.setStatus(429)`
    - Sets `X-RateLimit-*` headers (NOT set here — `writeError(3-arg)` passes nulls for limit/remaining/reset)
    - Writes JSON-RPC error body via `response.getWriter().write(...)`

**Decision Q1 (Path A):** The 429 response is NOT headers-only. The body is written by `writeError`. However, the order
IS correct: headers (Content-Type, status) are set before the body write, because `writeError` is only called when
`tryAcquire()` has NOT been called yet (so no prior body). After the ADR-0016 fix, this path is clean.

### 2.2 Path B: POST Request Rate Limit

**Current code (lines 282–294):**

```java
// [L282–284] String-match detection — fragile but functional
boolean isRateLimited = result.getBody() != null &&
        result.getBody().contains("\"code\":-32029");

if (isRateLimited) {
    McpProtocolHandler.RateLimitStatus status =
            handler.getSessionRateLimitStatus(sessionId);
    if (status != null) {
        writeRateLimitedError(response, 429, "Too Many Requests",
                status.limit, status.remaining, status.resetTime);
    } else {
        writeError(response, 429, "Too Many Requests");
    }
    return;
}
```

**Precondition:** The request body was fully read (lines 242–249) and parsed (lines 258–266) BEFORE this check. If
rate-limited, the body bytes were already consumed from the socket. This is acceptable for POST (body buffering is
standard); it would NOT be acceptable for SSE.

**Problem:** The string-match `"\"code\":-32029"` is fragile. If `GSON` serializes differently (e.g., no spaces,
different ordering), the check could silently fail.

**Recommendation (out of scope for this spec — flag for follow-up):** Replace string-match detection with a structured
field on `McpResponse`, e.g., `result.isRateLimited()` boolean.

**429 behavior (Path B):**

- `writeRateLimitedError(response, 429, "Too Many Requests", limit, remaining, reset)` is called when `status != null`
- Sets `Content-Type: application/json`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, then writes
  JSON-RPC body
- Falls back to `writeError(response, 429, "Too Many Requests")` when `status == null` (no X-RateLimit-* headers)

**Decision Q1 (Path B):** Body is written by `writeError`. Headers are set before body. This is a clean denial.

---

## 3. Error Framing Format

### 3.1 Consistent JSON-RPC Error Body

All `writeError` calls produce an identical JSON structure, regardless of HTTP status code:

```json
{
  "jsonrpc": "2.0",
  "id": null,
  "error": {
    "code": <HTTP-status>,
    "message": "<human-readable phrase>"
  }
}
```

**Observations:**

- `id` is always `null` in transport-layer `writeError` calls (no request ID available at this layer; the handler never
  has the parsed JSON-RPC `id` when calling `writeError`). This differs from protocol-layer `errorResponse()` which
  preserves the request `id`.
- The `code` in the body equals the HTTP status, NOT the JSON-RPC error code. For rate limits specifically, `-32029`
  appears in the protocol-layer body (returned by `McpProtocolHandler`) and the HTTP status is `429`.
- For SSE permit exhaustion (`writeError(response, 429, "Too many active SSE connections")`), the body code is `429` (
  matching HTTP) — this is inconsistent with the POST path where the body code is `-32029`.

**Inconsistency identified:**

| Path                                  | HTTP Status | Body `code` | Body `message`                    |
|---------------------------------------|-------------|-------------|-----------------------------------|
| POST rate limit (via handlePost)      | 429         | -32029      | "Too Many Requests: ..."          |
| SSE permit exhaustion (via handleGet) | 429         | 429         | "Too many active SSE connections" |
| Other errors (400, 401, 403, etc.)    | N           | N           | human-readable                    |

**Decision Q2 — Standardize 429 body format:**

All 429 responses MUST use the following body structure:

```json
{
  "jsonrpc": "2.0",
  "id": null,
  "error": {
    "code": -32029,
    "message": "Too Many Requests: <specific-reason>"
  }
}
```

- Body `code` is ALWAYS `-32029` (transport-layer 429s only)
- Body `message` includes the specific reason from the protocol layer (for Path B) or a fixed string (for Path A)
- HTTP `X-RateLimit-*` headers are included when available (Path B with `RateLimitStatus`, Path A does not include them
  since there is no equivalent status tracker for SSE permits)

**Proposed `writeError` change for SSE permit exhaustion:** Pass `-32029` as the code:

```java
// Current:
writeError(response, 429, "Too many active SSE connections");

// Proposed (for consistency):
writeError(response, 429, "Too many active SSE connections: concurrent connection limit reached");
```

And update `writeError` body to use `-32029` for status 429, or introduce a `write429Error` variant that hard-codes
`code: -32029`.

**Alternative (simpler):** Do NOT change the body code in transport-layer `writeError` (keep `code = status`). Accept
that transport-layer and protocol-layer codes differ. Document this as intentional: transport-layer codes are
HTTP-level, protocol-layer codes are JSON-RPC-level. Clients MUST inspect the HTTP status, not the body code, to
determine if a 429 occurred.

**Decision:** Adopt the alternative. Transport-layer `writeError` continues to use `code: status`. Protocol-layer
`errorResponse` uses `-32029`. This is intentional: HTTP 429 maps to body code 429 at transport layer, and to JSON-RPC
-32029 at protocol layer. Both are valid; the HTTP status is the authoritative signal.

---

## 4. Disconnect Protocol (SSE Client Drop)

### 4.1 Detection Mechanism

`McpGrizzlyHandler.handleGet()` detects client disconnects via `IOException` thrown from
`response.getWriter().write(...)` or `response.getWriter().flush(...)` inside the polling loop:

```java
try {
    // polling loop — write/flush can throw IOException when client disconnects
} catch (IOException e) {
    // Client disconnected mid-stream — release permit immediately.
} finally {
    if (permitHeld) {
        sseConnections.release();
        permitHeld = false;
    }
}
```

**How it works:** When the client closes its read end of the TCP connection, Grizzly's output stream write throws a
`SocketException` or `Connection reset by peer`. This is caught by the `catch (IOException e)` block.

### 4.2 Behavior on Disconnect

| Step | Action                                                                        |
|------|-------------------------------------------------------------------------------|
| 1    | `IOException` thrown from a write/flush in the polling loop                   |
| 2    | `catch` block swallows the exception (no re-throw)                            |
| 3    | `finally` block runs: `sseConnections.release()` called, `permitHeld = false` |
| 4    | Method returns; permit count incremented by 1                                 |

**Guarantee:** `permitHeld` boolean prevents double-release if `finally` runs after the catch block already set it to
`false`. However, the `permitHeld = false` assignment in the `finally` block (line 390) is redundant — it is set only in
`finally`, never in `catch`, so the guard is harmless but adds clarity.

**Decision Q3:** On client disconnect, the permit is released in the `finally` block. The `IOException` is silently
swallowed (no re-throw, no logging at ERROR level — this is expected behavior). The method returns cleanly.

### 4.3 Edge Cases

| Scenario                                                          | Behavior                                                                                                  |
|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Client disconnects BEFORE permit acquired                         | N/A — no permit held                                                                                      |
| Client disconnects AFTER `tryAcquire()` but BEFORE headers set    | `IOException` from first `flush()` → `finally` releases permit (correct)                                  |
| Client disconnects AFTER headers set but BEFORE `connected` event | Same as above                                                                                             |
| Client disconnects during `getMissedEvents()` replay              | After ADR-0016 fix: replay happens AFTER permit acquired; `IOException` caught → permit released          |
| `Thread.sleep()` interrupted                                      | `InterruptedException` caught, `Thread.currentThread().interrupt()` set, loop breaks → `finally` releases |
| Session terminated by DELETE                                      | `hasSession()` returns false → loop exits → `finally` releases                                            |
| Idle timeout (5 min)                                              | `System.currentTimeMillis() - start >= 300000` → loop exits → `finally` releases                          |

---

## 5. Permit Release Guarantees

### 5.1 SSE Permit (`sseConnections` Semaphore)

The SSE permit is acquired in `handleGet()` with `sseConnections.tryAcquire()`. The `permitHeld` boolean guards the
release:

```java
if (!sseConnections.tryAcquire()) {
    writeError(response, 429, "Too many active SSE connections");
    return;          // ← NO permit acquired, no release needed
}
boolean permitHeld = true;
try {
    // ... headers, replay, connected event, polling loop ...
} catch (IOException e) {
    // Client disconnected — permit released in finally
} finally {
    if (permitHeld) {
        sseConnections.release();
        permitHeld = false;
    }
}
```

**Exit paths from `handleGet()`:**

| Exit Point                                                    | Permit Held? | Released? | Via                 |
|---------------------------------------------------------------|--------------|-----------|---------------------|
| Line 324: `isInvalidOrigin` → `writeError` → `return`         | No           | N/A       | —                   |
| Line 328: `isUnauthorized` → `writeError` → `return`          | No           | N/A       | —                   |
| Line 333: missing/invalid session → `writeError` → `return`   | No           | N/A       | —                   |
| Line 338: `parseLastEventId` throws → `writeError` → `return` | No           | N/A       | —                   |
| Line 351: `!tryAcquire()` → `writeError` → `return`           | No           | N/A       | —                   |
| Polling loop: `IOException` (disconnect)                      | Yes          | Yes       | `catch` → `finally` |
| Polling loop: `InterruptedException` → `break`                | Yes          | Yes       | `finally`           |
| Polling loop: `!hasSession()` (DELETE)                        | Yes          | Yes       | `finally`           |
| Polling loop: idle timeout (5 min)                            | Yes          | Yes       | `finally`           |
| Polling loop: `Thread.currentThread().isInterrupted()`        | Yes          | Yes       | `finally`           |

**Decision Q4 (SSE):** All 9 exit paths are covered. The `permitHeld` boolean correctly gates the release. No path can
leak a permit.

### 5.2 Category Concurrent Counters (`incrementMethodConcurrentCount`)

These are NOT semaphore permits — they are `AtomicInteger` counters inside `CategoryRateLimitState`. They track how many
concurrent in-flight requests exist per category per session.

**Current placement (verified against source lines 631, 762, 768, 774, 782, 792):**

```java
// [L631] Incremented BEFORE method execution
incrementMethodConcurrentCount(sessionId, method, toolScopes);

// ... method execution ...

// [L762] Decremented in normal success path
decrementMethodConcurrentCount(sessionId, method, toolScopes);

// [L768] Decremented when result is null (notification)
decrementMethodConcurrentCount(sessionId, method, toolScopes);

// [L774] Decremented in catch (McpErrorException)
decrementMethodConcurrentCount(sessionId, method, toolScopes);

// [L782] Decremented in catch (Throwable)
decrementMethodConcurrentCount(sessionId, method, toolScopes);
```

**Note:** When `handleRequestResponse()` returns a rate-limit denial (lines 586, 598, 619),
`decrementMethodConcurrentCount` is NOT called — because `incrementMethodConcurrentCount` was NOT called before the
denial. The increment is inside the method AFTER the rate-limit check. This is correct: a denied request never acquired
a concurrent slot.

**Audit: No decrement without increment (rate-limit paths):**

| Denial                                  | Increment called? | Decrement called? | Leak?   |
|-----------------------------------------|-------------------|-------------------|---------|
| `initialize` concurrent sessions (L586) | No (after check)  | No                | Correct |
| IP/session rate limit (L598)            | No (after check)  | No                | Correct |
| Category rate limit (L619)              | No (after check)  | No                | Correct |

**Decision Q4 (Category Concurrent Counters):** Correct by construction — denied requests never increment, so never need
to decrement.

### 5.3 Global Rate-Limit Sliding Window Records (`ipRateLimits`, `sessionRateLimits`)

These are `ConcurrentHashMap<String, RateLimitRecord>` entries. They are NOT released per-request — they decay over time
via the sliding window algorithm (`RateLimitRecord.allowRequest` evicts old entries). The cleanup thread prunes
zero-count entries periodically (lines 421–431).

**No explicit release on disconnect/timeout** — this is correct. The sliding window is a rate tracker, not a resource
holder. Releasing on disconnect would reset the client's rate limit unfairly.

---

## 6. Combined Response Specification

### 6.1 429 Response Contract

Every 429 response MUST satisfy ALL of the following:

| Property              | Value                                                                           |
|-----------------------|---------------------------------------------------------------------------------|
| HTTP Status           | `429 Too Many Requests`                                                         |
| Content-Type          | `application/json; charset=UTF-8`                                               |
| X-RateLimit-Limit     | Present when `RateLimitStatus` is available (Path B); absent otherwise (Path A) |
| X-RateLimit-Remaining | Present when `RateLimitStatus` is available (Path B); absent otherwise (Path A) |
| X-RateLimit-Reset     | Present when `RateLimitStatus` is available (Path B); absent otherwise (Path A) |
| Body format           | JSON-RPC 2.0 error (see 6.2)                                                    |
| SSE data in body      | NEVER — body is never mixed with SSE events                                     |

### 6.2 429 Body Format

```json
{
  "jsonrpc": "2.0",
  "id": null,
  "error": {
    "code": 429,
    "message": "<specific-reason>"
  }
}
```

For POST rate limits, `message` is inherited from the protocol-layer denial: `"Too Many Requests: <reason>"`. For SSE
permit exhaustion, `message` is: `"Too many active SSE connections"`.

### 6.3 429 Header Ordering

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json; charset=UTF-8
X-RateLimit-Limit: <int>       [Path B only, when status != null]
X-RateLimit-Remaining: <int>    [Path B only, when status != null]
X-RateLimit-Reset: <unix-epoch> [Path B only, when status != null]
Content-Length: <bytes>

{"jsonrpc":"2.0","id":null,"error":{"code":429,"message":"..."}}
```

**Headers are always set before body** — `writeError` sets Content-Type, status, and X-RateLimit-* headers before
calling `response.getWriter().write(body)`.

---

## 7. Open Items (Flagged for Follow-Up)

| #  | Item                                                                                                                 | Owner       | Notes                                                           |
|----|----------------------------------------------------------------------------------------------------------------------|-------------|-----------------------------------------------------------------|
| O1 | Replace fragile `"\"code\":-32029"` string match in `handlePost` with structured `McpResponse.isRateLimited()` field | dev-backend | Breakage risk: GSON serialization order                         |
| O2 | Add `RateLimitStatus`-equivalent for SSE permit exhaustion (X-RateLimit-* headers on Path A)                         | dev-backend | No tracker exists; `sseConnections` Semaphore has no status API |
| O3 | ADR-0016 SSE permit ordering fix must land before Path A 429 behavior is considered fully correct                    | dev-backend | Current ordering allows body-write before permit check          |

---

## 8. Acceptance Criteria

| AC   | Criterion                                                                      | Verification                                                                 |
|------|--------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| AC-1 | `handleGet()` acquires SSE permit BEFORE any body bytes are written            | Source review (post ADR-0016 fix)                                            |
| AC-2 | Every 429 response has `Content-Type: application/json`                        | `writeError` verified                                                        |
| AC-3 | Every 429 response body is valid JSON-RPC 2.0                                  | `writeError` payload structure verified                                      |
| AC-4 | No SSE event data appears in any 429 body                                      | `writeError` never called after SSE headers/body writes in Path A (post fix) |
| AC-5 | SSE permit is released on every exit path from `handleGet()`                   | 9-path exit analysis in §5.1                                                 |
| AC-6 | SSE permit is released on client disconnect (IOException)                      | `catch (IOException e)` → `finally` verified                                 |
| AC-7 | Category concurrent counters are never leaked (no increment without decrement) | Source audit in §5.2                                                         |
| AC-8 | 429 body `code` equals HTTP status (transport-layer convention)                | `writeError` verified                                                        |

---

## 9. Related Documents

| Document                                                   | Relationship                                                    |
|------------------------------------------------------------|-----------------------------------------------------------------|
| `docs/adr/ADR-0016-sse-permit-flow-verification.md`        | Governs SSE permit acquisition ordering; this spec builds on it |
| `docs/adr/ADR-0017-sse-permit-response-flow.md`            | Superseded by ADR-0016; state machine reference only            |
| `docs/testing/PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md` | Test spec for 429 + permit behavior; ACs map to test cases      |
| `docs/architecture/MCP-SECURITY-SYNTHESIS-TIER2.md`        | Tier 2 security synthesis; rate-limit design                    |
| `docs/adr/ADR-0011-security-rate-limiting.md`              | Original rate-limit architecture                                |
| `docs/security/SECURITY-TRIPLE-TRIAGE-2026-09-21.md`       | Triple triage: AuthContext, DefaultApiKeyStore, bindSessionToIp |
