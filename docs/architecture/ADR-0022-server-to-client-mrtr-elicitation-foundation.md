# ADR-0022: Server-to-Client MRTR Elicitation Foundation

**Status:** Accepted

**Date:** 2026-10-02

**Author:** dev-backend

## Context

The MCP protocol currently only supports client-to-server requests. GitHub issue #7 requires implementing **server-to-client interaction** — enabling the MCP server to:
- Send requests to the client (not just responses)
- Receive responses from the client
- Handle timeouts and cancellations for these server-initiated requests

This is known as **MRTR** (Message Routing / Request-Response Tracking) in the MCP spec.

## Decision

We will implement:
1. **Correlation IDs** — unique identifiers to match server-initiated requests to responses
2. **Request timeouts** — configurable timeout for server-initiated requests
3. **Cooperative cancellation** — allow cancellation of pending elicitation requests
4. **Typed elicitation API** — `ElicitRequest` and `ElicitationMessage` types
5. **Transport-neutral routing** — support both SSE and STDIO transports
6. **Capability advertisement** — advertise elicitation in `initialize` response

## Architecture

### 1. Correlation IDs

Each server-initiated request carries a unique `requestId` (string). The client must include this `requestId` in its response to enable matching.

```
Server → Client: { method: "elicitation/request", params: { requestId: "uuid", message: {...} } }
Client → Server: { method: "elicitation/response", params: { requestId: "uuid", message: {...} } }
```

### 2. Request Timeout

Server-initiated requests have a configurable `timeoutMs`. If the client doesn't respond within this window, the request is cancelled and an error is propagated.

### 3. Elicitation Types

```java
// Server → Client request
public class ElicitRequest {
    private String requestId;      // Correlation ID
    private String message;       // Prompt/question for the user
    private Map<String, Object> metadata;  // Optional context
    private long timeoutMs;       // Response timeout
}

// Client → Server response  
public class ElicitationMessage {
    private String requestId;      // Must echo the request's requestId
    private String content;       // User's response content
    private boolean cancelled;     // Whether user cancelled
}
```

### 4. Transport Abstraction

Elicitation works over:
- **SSE**: Push `elicitation/request` as an SSE event; client sends `POST /mcp` with `elicitation/response`
- **STDIO**: Send `elicitation/request` as JSON-RPC; client responds with `elicitation/response`

## Capability Advertisement

In `initialize` response:
```json
{
  "capabilities": {
    "elicitation": {
      "requestTimeoutMs": 60000
    }
  }
}
```

## Top-Level Extensions Array (ADR-0023)

The `initialize` / `server/discover` responses include a top-level `extensions` array
listing all negotiated MCP extensions.  Each entry is returned by the extension's
`advertiseExtension(String protocolVersion)` method, allowing extensions to supply
version-specific metadata (name, version, capabilities, schema).

```json
{
  "protocolVersion": "2026-07-28",
  "capabilities": { ... },
  "extensions": [
    {
      "name": "io.modelcontextprotocol/tasks",
      "version": "1.0",
      "capabilities": { "listChanged": false }
    }
  ],
  "serverInfo": { "name": "...", "version": "..." }
}
```

**Rules:**
- `extensions` is **absent** when no extension is registered, or when every
  registered extension returns `null` from `advertiseExtension()`.
- `advertiseExtension()` is only called when `supports(protocolVersion)` returns `true`
  for the negotiated version.
- The method receives the **negotiated** (client-requested) protocol version, not the
  server default.
- The array is built in the order extensions are registered (currently: `tasksExtension` only).
- The design is forward-compatible: adding new extension slots (e.g. `promptsExtension`)
  requires only a new `if (ext != null)` branch — the JSON structure is already agreed.

## Consequences

- **Positive**: Enables bidirectional MCP communication; matches MCP spec
- **Positive**: Transport-neutral design works for SSE and STDIO
- **Negative**: Requires client implementation of `elicitation/response` method

## Implementation Notes

- Correlation IDs use UUID format for uniqueness
- Timeout defaults to 60 seconds (configurable)
- Cancellation uses existing `notifications/cancelled` mechanism
- Elicitation requests are stored in a pending requests map keyed by correlation ID
- Thread-safe concurrent access to pending requests

## References

- MCP Specification: Server-to-Client Interaction (MRTR)
- GitHub Issue: #7 — Implement MRTR server-to-client elicitation
