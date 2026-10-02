# ADR-0022 — MRTR Server-to-Client Foundation and Elicitation

**Status:** Proposed
**Date:** 2026-10-02
**Authors:** MCP Java SDK team
**GitHub:** [#7](https://github.com/vinhphan812/mcp-java-sdk/issues/7)
**Parent:** t_3dc88728 (stateless protocol mode, commit `ea26c8c2`)

---

## Context

GitHub #7 tracks the MRTR (Method Request/Response Transport) server-to-client foundation and the Elicitation capability. This ADR is the first deliverable: an approved implementation design plus decomposed backend follow-up cards.

The MCP specification defines a bidirectional protocol where the server can initiate:

1. **Server-to-client requests** — the server sends a JSON-RPC request to the client, and the client responds. This is the MRTR foundation.
2. **Elicitation** — a typed server-initiated request for user confirmation or input from the client.

The existing `McpProtocolHandler` has a working SSE event queue (`pendingEvents`, `pollSseEvent`), progress notifications, and cancellation handling. The stateless protocol mode (`2026-07-28`) is also implemented. The following areas are identified by GitNexus analysis as requiring design before implementation:

- Correlation IDs (matching server-initiated requests to responses)
- Request timeouts and cooperative cancellation for server-initiated requests
- Shutdown cleanup for in-flight server-initiated requests
- Malformed and duplicate reply handling
- Transport-neutral routing (SSE transport is assumed today)
- Typed elicitation API design
- 2026 deprecation posture for Sampling, Roots, and Logging

**Reference specification:** Model Context Protocol (MCP) — [modelcontextprotocol.io](https://modelcontextprotocol.io), revision as of 2026-07-28.

---

## Decisions

### 1. Correlation ID Model

**Decision:** Use a `progressToken`-like string as the correlation ID for server-initiated requests, stored in a `ConcurrentHashMap<String, ServerInitiatedRequest>` keyed by the token.

**Rationale:** The existing progress token infrastructure in `_meta.progressToken` already establishes a client-supplied token pattern. For server-initiated requests the server generates the token (a `UUID`) and includes it in the request's `_meta.progressToken`. The client echoes it back in the JSON-RPC `id` field of the response. The protocol handler matches the response `id` against the in-flight map to route the reply.

**Schema:**
```java
public final class ServerInitiatedRequest {
    public final String requestId;           // server-generated UUID token
    public final String method;              // e.g. "elicitation/create"
    public final Map<String, Object> params;
    public final String sessionId;           // target session
    public final long enqueuedAt;            // System.currentTimeMillis()
    public final long expiresAt;            // enqueuedAt + timeoutMs
    public final CompletableFuture<Map<String, Object>> responseFuture;
}
```

**Token format:** `uuid:<UUID>` for all server-generated tokens — clients can distinguish them from client-generated tokens.

**Response matching:** `McpProtocolHandler.handleServerInitiatedResponse(String requestId, Map<String, Object> response)` resolves the `CompletableFuture`.

---

### 2. Request Timeouts

**Decision:** Per-request timeout configurable via `McpServerConfig.Builder.serverRequestTimeout(Duration)`; default 30 seconds.

**Rationale:** Server-initiated requests must not block the SSE queue indefinitely. Each request is associated with a timeout; expired requests are cancelled and the `CompletableFuture` completed with error `{-32001, "Server request timed out"}`.

**Implementation:** A dedicated `ScheduledExecutorService` (single thread, daemon) is used to schedule timeout callbacks. The executor is started lazily on first use and shut down during `McpProtocolHandler.shutdown()`.

**Cleanup on expiry:**
1. Remove entry from `serverInitiatedRequests`.
2. Cancel SSE event subscription if any.
3. Complete `responseFuture` with timeout error.

---

### 3. Cancellation

**Decision:** Support three cancellation axes:

1. **Client-initiated** — existing `notifications/cancelled` with `requestId` already implemented. Extend to match server-initiated request tokens.
2. **Server-initiated timeout** — described in Section 2.
3. **Application-initiated** — `McpProtocolHandler.cancelServerRequest(String requestId)` callable by the application.

**Rationale:** The existing `McpRegistry.isCancelled(sessionId, requestId)` mechanism is session-scoped. For server-initiated requests the cancellation token is the server-generated request ID. A separate `serverInitiatedRequests` map is used so cancellation does not pollute the session-level cancellation set.

**Cooperative cancellation contract:** Tool authors call `McpRegistry.isCancelled(sessionId, requestId)` to check cancellation. For server-initiated requests, the same method is overloaded to accept `requestId` alone (since there is no session context from the tool's perspective). The check returns `true` if the request has been cancelled or timed out.

---

### 4. Shutdown Cleanup

**Decision:** On `McpProtocolHandler.shutdown()`:

1. Iterate `serverInitiatedRequests` and complete every pending `CompletableFuture` with `{-32000, "Server shutting down"}`.
2. Clear `serverInitiatedRequests`.
3. Shut down the scheduled executor (拒絕 new tasks, allow in-flight to run or be cancelled).
4. Delegate to existing session cleanup (`closeAllSessions()`).

**Rationale:** In-flight server-initiated requests must not silently disappear on server shutdown. Applications receive an explicit error rather than an unresolved future.

---

### 5. Malformed and Duplicate Reply Handling

**Decision:**

- **Malformed reply:** If the response body is not valid JSON or missing the `id` field, log a warning and discard.
- **Duplicate reply:** If a response arrives for a requestId that is not in `serverInitiatedRequests` (already resolved or expired), log a warning and discard.
- **Missing requestId:** If a server-initiated response has no `id`, log and discard (cannot correlate).

**Rationale:** SSE is a lossy-but-ordered stream in the face of network issues. The protocol must degrade gracefully.

---

### 6. Transport-Neutral Routing

**Decision:** Introduce a `ServerRequestTransport` interface:

```java
public interface ServerRequestTransport {
    /**
     * Sends a server-initiated JSON-RPC request to the client.
     * @param sessionId target session
     * @param request  JSON-RPC request body
     * @param timeoutMs per-request timeout in milliseconds
     * @return a CompletableFuture that resolves with the JSON-RPC response body
     */
    CompletableFuture<Map<String, Object>> sendRequest(
            String sessionId, String request, long timeoutMs);

    /**
     * Checks whether this transport supports server-initiated requests.
     */
    boolean supportsServerRequests();
}
```

**Implementations:**
- `SseServerRequestTransport` — uses the SSE event queue (`pendingEvents`) for outgoing; a dedicated long-poll endpoint for incoming responses.
- `WebSocketServerRequestTransport` (future) — sends requests over WebSocket; receives responses on the same WebSocket connection.

**Default:** SSE implementation used unless `McpServerConfig.Builder.serverRequestTransport(...)` is explicitly set. If the transport does not support server requests, all elicitation methods return `{-32601, "Server requests not supported by this transport"}`.

**Rationale:** Decoupling the request correlation logic from the SSE transport enables future WebSocket support without changing the protocol handler core.

---

### 7. Typed Elicitation API

**Decision:** Design the elicitation capability as follows.

**Elicitation SPI** (`io.github.vinhphan812.mcp.api.spi.McpElicitationExtension`):

```java
public interface McpElicitationExtension extends McpTaskExtension {
    String NAMESPACE = "io.modelcontextprotocol/elicitation";

    /**
     * Elicits a confirmation from the client.
     * @param sessionId target session
     * @param message  prompt message
     * @param options  optional labelled actions
     * @return a CompletableFuture resolving to the selected action name, or cancelled on timeout/cancel
     */
    CompletableFuture<String> elicitConfirmation(
            String sessionId, String message, List<ElicitAction> options);

    /**
     * Elicits a text input from the client.
     * @param sessionId target session
     * @param message  prompt message
     * @param defaultValue optional default value
     * @return a CompletableFuture resolving to the input string, or cancelled on timeout/cancel
     */
    CompletableFuture<String> elicitInput(
            String sessionId, String message, String defaultValue);
}
```

**Method routing:**
- `sampling/createMessage` → `McpProtocolHandler.handleSamplingRequest(params)` (Sampling, see Section 9)
- `elicitation/create` → elicitation extension (this section)

**Protocol wire shape** (2026-07-28 spec):

```json
// Server → Client request
{
  "jsonrpc": "2.0",
  "id": "<uuid-token>",
  "method": "elicitation/create",
  "params": {
    "message": "Are you sure you want to delete this record?",
    "requestedSchema": {
      "type": "object",
      "properties": {
        "action": { "type": "string", "enum": ["confirm", "cancel"] }
      }
    },
    "_meta": { "progressToken": "<uuid-token>" }
  }
}

// Client → Server response (uses same id)
{
  "jsonrpc": "2.0",
  "id": "<uuid-token>",
  "result": {
    "action": "confirm"
  }
}
```

**Builder API** (public, for tool authors):
```java
McpProtocolHandler handler = ...;

// Fluent confirmation elicitation
String action = handler.elicitConfirmation(sessionId, "Delete record #42?", List.of(
    new ElicitAction("confirm", "Delete"),
    new ElicitAction("cancel",  "Keep")
)).get(30, TimeUnit.SECONDS);

// Fluent input elicitation
String name = handler.elicitInput(sessionId, "Enter your name:", "Anonymous").get();
```

---

### 8. Progress and Cancellation for Elicitation

**Decision:** Elicitation requests use the same `progressToken` infrastructure as tool progress. The elicitation request carries a server-generated token in `_meta.progressToken`. The client sends:

1. `notifications/progress` with interim state.
2. The final `result` in the JSON-RPC response `id`-matched response.

Cancellation (`notifications/cancelled`) with the server-generated requestId terminates the elicitation `CompletableFuture` with `CancellationException`.

---

### 9. 2026 Deprecation Posture

**Decision:** Document the following deprecation posture for the three capabilities currently listed as P2:

#### 9a. Sampling (`sampling/createMessage`)

**Posture:** Mark as **deprecated / stub-only** in the 2026 spec context.

The sampling capability allows the server to request the client to generate content (e.g. image, audio). In the MCP 2026-07-28 spec this is defined but the server's role is primarily consumer-side. Implement as a no-op stub returning `{-32601, "Sampling not implemented"}` for the 2026 release, with a `McpServerConfig.Builder.samplingEnabled(boolean)` flag defaulting to `false`.

Rationale: The primary value of the SDK is server-side. Sampling requires significant client-side infrastructure that is out-of-scope for this release.

#### 9b. Roots (`roots/list`)

**Posture:** **Already implicitly unsupported.**

The SDK does not implement `roots/list`. No capability advertisement is emitted for roots. No design change needed; document the exclusion in the compatibility matrix.

#### 9c. Logging (`logging/setLevel`, `notifications/message`)

**Posture:** **Retained and stabilized.**

The logging capability is already implemented (`config.logging`, `handleSetLogLevel`, `notifyLogMessage`). Continue to support it. The 2026 spec does not deprecate logging.

---

### 10. Error Code Assignments

| Code | Name | Use |
|------|------|-----|
| `-32001` | `RESULT_NOT_COMPLETE` | Server request timed out (existing) |
| `-32002` | `RESULT_ALREADY_TERMINAL` | Elicitation already resolved/cancelled |
| `-32003` | `SERVER_REQUEST_TIMEOUT` | New: server-initiated request timed out |
| `-32004` | `ELICITATION_REJECTED` | New: client rejected elicitation |

Error codes `-32003` and `-32004` are added to `McpErrorCodes`.

---

## Consequences

### Positive
- Elicitation enables server-initiated confirmation dialogs, unlocking interactive tool flows (e.g. "confirm before delete").
- Typed SPI (`McpElicitationExtension`) gives applications a clean extension point without coupling to protocol internals.
- Transport-neutral design future-proofs the architecture for WebSocket support.
- Timeout and cancellation cover the reliability requirements for production deployments.
- Shutdown cleanup prevents orphaned futures.

### Negative / Trade-offs
- `ScheduledExecutorService` adds a thread to the handler's lifecycle. Must be explicitly shut down.
- SSE-only elicitation limits clients that only use POST streaming responses (no dedicated response channel). Future WebSocket transport resolves this.
- The `CompletableFuture`-based API blocks the caller's thread. Applications with strict latency requirements must use the async `elicitConfirmationAsync` variant.

### Deferred
- WebSocket server request transport implementation (out of scope for this design).
- Sampling stub implementation (stub-only, deferred).
- STDIO transport for local CLI tools (out of scope for HTTP-only server).

---

## Test Plan

| ID | Scenario | Expected |
|----|----------|----------|
| T1 | `elicitConfirmation()` resolves with client-selected action | Action string returned |
| T2 | `elicitConfirmation()` timeout | `CompletionException` with timeout cause |
| T3 | `elicitConfirmation()` cancelled by `notifications/cancelled` | `CancellationException` |
| T4 | `elicitInput()` resolves with user text | Text string returned |
| T5 | Duplicate client response for elicitation | Logged and discarded, no exception |
| T6 | Malformed client response (missing `id`) | Logged and discarded |
| T7 | `cancelServerRequest(requestId)` while elicitation in flight | `CancellationException` |
| T8 | `McpProtocolHandler.shutdown()` with pending elicitation | All futures completed with shutdown error |
| T9 | Elicitation on transport that does not support server requests | `{-32601}` error |
| T10 | `sampling/createMessage` returns stub error | `{-32601, "Sampling not implemented"}` |

---

## Open Questions

1. **Elicitation schema validation:** Should the server validate the client's response against `requestedSchema` before resolving the future, or trust the client? (Proposed: validate server-side; return error if invalid.)
2. **Elicitation queuing:** If multiple elicitation requests are queued for the same session, in what order are they shown? (Proposed: FIFO; each resolves before the next is sent.)
3. **Sampling stub level:** Should the stub return `{-32601}` or accept the request but return an empty/minimal result? (Proposed: `{-32601}` to signal the capability is not implemented.)
