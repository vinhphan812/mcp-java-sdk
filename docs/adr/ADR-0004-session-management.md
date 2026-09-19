# ADR-0004 — Session Management and Lifecycle

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

MCP requires a session between client and server for stateful communication. Sessions are created during `initialize`,
associated with a unique ID, and must be validated on subsequent requests. Sessions expire after 5 minutes of inactivity
and can be terminated by the client via `DELETE /mcp` or by the server internally.

## Decisions

### Session creation

A session is created in `handleInitialize`:

1. A new `UUID.randomUUID().toString()` is generated as the session ID.
2. A `SessionState` record is created and stored in `sessions: Map<String, SessionState>`.
3. The session ID is returned in the `Mcp-Session-Id` HTTP response header.
4. The client must include this ID in the `Mcp-Session-Id` request header on all subsequent requests.

### Session validation

`hasSession(sessionId)` checks that the session exists before routing a request. Some methods are session-optional:

- `initialize` — no session required.
- `ping` — no session required.
- All other methods — session required.

A missing or invalid session on a protected method returns error `-32001` "Missing or invalid MCP session".

### Session termination

Sessions are terminated by:

- `DELETE /mcp` — client-initiated via HTTP DELETE with valid session header.
- `terminateSession(id)` — server-initiated via the protocol handler.
- `close()` — `McpServer.stop()` calls `terminateSession` for all open sessions.
- **Expiry:** `MAX_SESSION_IDLE_MS = 300000L` (5 minutes). The SSE polling loop checks `hasSession` on every iteration;
  if the session has been removed, the loop exits cleanly.

### Session state

```java
private static class SessionState {
    final String sessionId;
    final long createdAt;
    final Set<String> subscriptions;  // subscribed resource URIs
    final AtomicLong nextEventId;      // SSE event ID counter
    final ConcurrentLinkedQueue<SseEvent> eventQueue;  // replay buffer (max 1000)
}
```

### SSE replay

When a client reconnects with `Last-Event-ID: N`, the transport calls `handler.getMissedEvents(sessionId, N)`, which
returns all queued events with IDs greater than `N` in SSE format. After replay, the client continues polling from the
current event tail. Events older than the replay window (beyond 1000 queued events) are silently dropped.

## Consequences

**Positive:**

- Session IDs are unguessable UUIDs.
- The replay buffer prevents event loss on brief reconnections.
- The 5-minute idle timeout prevents unbounded session accumulation.
- Session cleanup is deterministic: removed from the map, subscriptions cleared, SSE loop exits.

**Negative:**

- Sessions are in-memory only; a server restart loses all sessions.
- No sticky session or session persistence across server restarts.
- Events dropped from the replay buffer cannot be recovered.
- No session expiry notification to the client; the SSE loop simply ends.
