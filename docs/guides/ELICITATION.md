# Elicitation — Server-to-Client Requests

Elicitation enables an MCP server to prompt the client for confirmation, input, or structured data before
continuing a tool execution. The client responds asynchronously and the server resumes with the result.

This guide covers:

- what elicitation is and when to use it;
- enabling the capability in `McpServerConfig`;
- the async Java API with `CompletableFuture`;
- the protocol wire shape (JSON-RPC over HTTP/SSE);
- timeout, cancellation, and error handling;
- relationship to the deprecated Sampling and Roots capabilities.

For the architectural design, see [ADR-0022](../adr/ADR-0022-server-to-client-mrtr-elicitation-foundation.md).
For the deprecation strategy for Sampling and Roots, see [ADR-0023](../adr/ADR-0023-deprecation-sampling-roots-logging.md).

---

## When to Use Elicitation

Use elicitation whenever a tool needs human-in-the-loop confirmation or structured input before proceeding:

| Scenario | Elicitation type | Example |
|---|---|---|
| Destructive action ("delete record #42?") | Confirmation | `elicitConfirmation` with labelled actions |
| User-facing text input | Text input | `elicitInput` for a free-form string |
| Structured data (filename, date, etc.) | Text input with default | `elicitInput` with a suggested default value |

Elicitation is **not** the right tool when:

- the decision can be made programmatically (use conditional logic instead);
- you need a file upload or binary data (out of scope for the current release);
- you need to prompt for multiple fields at once (submit multiple `elicitInput` calls sequentially).

---

## Enabling Elicitation

Elicitation requires the `elicitation` capability to be enabled and is only available over HTTP/SSE transport.
It is not available over STDIO or Streamable HTTP POST responses.

```java
McpServer server = McpServer.builder()
        .config(McpServerConfig.builder()
                .serverName("my-server")
                .serverVersion("1.0.0")
                .protocolVersion("2026-07-28")          // required for elicitation
                .protocolMode(ProtocolMode.STATELESS)    // required for elicitation
                .elicitation(true)                       // enable elicitation capability
                .elicitationTimeoutMs(60_000)            // optional: default 60 000 ms (60 s)
                .build())
        .host("127.0.0.1")
        .port(3011)
        .endpoint("/mcp")
        .build()
        .register(new MyTools());

server.start();
```

The `initialize` response advertises `elicitation: {}` in the server capabilities when `elicitation(true)` is set.

---

## Async API — CompletableFuture

All elicitation methods return a `CompletableFuture` — the call does not block while waiting for the client
to respond. The future resolves when the client sends a response, or completes exceptionally on timeout,
cancellation, or error.

### Import the types

```java
import io.github.vinhphan812.mcp.api.dto.ElicitAction;
import io.github.vinhphan812.mcp.api.dto.ElicitRequest;
import io.github.vinhphan812.mcp.api.dto.ElicitationResult;
import io.github.vinhphan812.mcp.api.utils.McpElicitationException;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
```

### Confirmation — labelled actions

The server presents a message and a fixed set of labelled actions (e.g. "Delete" / "Cancel"). The future
resolves to the label of the selected action, or completes exceptionally if the client declines or the
request times out.

```java
McpProtocolHandler handler = protocolHandler;  // obtain from server or handler

CompletableFuture<String> future = handler.elicitConfirmation(
        sessionId,
        "Delete record #42?",
        List.of(
                new ElicitAction("confirm", "Delete the record"),
                new ElicitAction("cancel",  "Keep the record")
        ),
        30_000  // timeout milliseconds; omit to use config default
);

// Blocking wait (use in tests or sync contexts):
try {
    String action = future.get(30, TimeUnit.SECONDS);
    if ("confirm".equals(action)) {
        // proceed with deletion
    }
} catch (ExecutionException e) {
    // client declined, timed out, or an error occurred
    Throwable cause = e.getCause();
    if (cause instanceof McpElicitationException ex) {
        System.out.println("Elicitation error " + ex.getCode() + ": " + ex.getMessage());
    }
}
```

### Confirmation — handling decline explicitly

Use `ElicitationResult` when you need to distinguish a decline from a timeout programmatically:

```java
handler.elicitConfirmation(sessionId, "Delete record #42?", actions, 30_000)
        .thenApply(result -> {
            // result is the selected action label; never null on success
            return result;
        })
        .exceptionally(ex -> {
            Throwable cause = ex.getCause();
            if (cause instanceof McpElicitationException) {
                int code = ((McpElicitationException) cause).getCode();
                // -32004 = ELICITATION_REJECTED (client declined)
                // -32003 = SERVER_REQUEST_TIMEOUT
            }
            return null;
        });
```

### Text input — with default value

Use `elicitInput` for free-form text. If the client explicitly declines, the default value is returned
instead of throwing an exception.

```java
String name = handler.elicitInput(
        sessionId,
        "Enter your name:",
        "Anonymous",   // default value returned if client declines
        30_000         // timeout
).get(30, TimeUnit.SECONDS);

System.out.println("Client entered: " + name);
```

If no default value is appropriate, pass `null` — in this case a decline throws `McpElicitationException`:

```java
// No sensible default: decline becomes an exception
handler.elicitInput(sessionId, "Enter the filename:", null, 30_000)
        .get(30, TimeUnit.SECONDS);  // throws ExecutionException on decline
```

### Fully asynchronous pipeline

For reactive or async-first applications, chain the future:

```java
CompletableFuture<String> confirmFuture = handler.elicitConfirmation(
        sessionId, "Delete record #42?", actions, 30_000);

confirmFuture
        .thenAccept(action -> {
            if ("confirm".equals(action)) {
                // Step 1: confirmed — proceed with the action
                doDelete(42);
            }
        })
        .exceptionally(ex -> {
            // Step 1: handle failure (timeout, decline, error)
            handleElicitationError(ex.getCause());
            return null;
        })
        .thenCompose(v -> {
            // Step 2: after confirmation, elicit filename
            return handler.elicitInput(sessionId, "Filename:", "output.txt", 30_000);
        })
        .thenAccept(filename -> {
            // Step 3: use filename
            saveToFile(filename);
        });
```

---

## Error Handling

`McpElicitationException` extends `RuntimeException` and carries a JSON-RPC-compatible error code:

| Code | Constant | Meaning |
|------|----------|---------|
| `-32003` | `SERVER_REQUEST_TIMEOUT` | Client did not respond within the timeout period |
| `-32004` | `ELICITATION_REJECTED` | Client explicitly declined or dismissed the prompt |
| `-32601` | `METHOD_NOT_FOUND` | Transport does not support server-initiated requests (e.g., STDIO) |
| `-32602` | `INVALID_PARAMS` | Unknown session or malformed request |

The exception is unwrapped from the `ExecutionException` thrown by `CompletableFuture.get()`:

```java
try {
    future.get(timeout, TimeUnit.SECONDS);
} catch (ExecutionException e) {
    if (e.getCause() instanceof McpElicitationException ex) {
        switch (ex.getCode()) {
            case McpElicitationException.SERVER_REQUEST_TIMEOUT:
                System.err.println("Client did not respond in time");
                break;
            case McpElicitationException.ELICITATION_REJECTED:
                System.err.println("Client declined the request");
                break;
            default:
                System.err.println("Elicitation error " + ex.getCode() + ": " + ex.getMessage());
        }
    }
}
```

### Timeout configuration

The timeout applies **per request**, not per session. Configure globally on `McpServerConfig`:

```java
.elicitationTimeoutMs(60_000)  // 60 seconds (default)
```

Or per-call by passing a positive value to the elicitation method:

```java
handler.elicitConfirmation(sessionId, message, actions, 10_000);  // 10-second override
```

---

## Transport Requirements

Elicitation requires **HTTP/SSE transport** — specifically the `SseServerRequestTransport`.

It is **not available** over:

- STDIO transport (no SSE channel for server-initiated requests)
- Streamable HTTP POST responses (no persistent SSE channel)

If the transport does not support server-initiated requests, all elicitation methods return a
`CompletableFuture` completed exceptionally with `McpElicitationException(-32601, "Server requests not supported by this transport")`.

---

## Protocol Wire Shape

When the server sends `elicitation/create` over the SSE event stream:

```json
// Server → Client (SSE event, method notification)
event: message
data: {"jsonrpc":"2.0","id":"uuid:a1b2c3d4-5678-90ab-cdef-1234567890ab","method":"elicitation/create","params":{"message":"Delete record #42?","requestedSchema":{"type":"object","properties":{"action":{"type":"string","enum":["confirm","cancel"]}}},"_meta":{"progressToken":"uuid:a1b2c3d4-5678-90ab-cdef-1234567890ab"}}}
```

When the client responds (POST to the HTTP endpoint):

```json
// Client → Server (HTTP POST response)
{
  "jsonrpc": "2.0",
  "id": "uuid:a1b2c3d4-5678-90ab-cdef-1234567890ab",
  "result": { "action": "confirm" }
}
```

On decline or dismiss:

```json
{
  "jsonrpc": "2.0",
  "id": "uuid:a1b2c3d4-5678-90ab-cdef-1234567890ab",
  "result": { "action": "" }
}
```

---

## Relationship to Sampling and Roots

Elicitation, Sampling, and Roots are all P2 capabilities from the MCP 2026-07-28 spec. Their
postures differ:

| Capability | Status | Why |
|---|---|---|
| **Elicitation** | Implemented | Server-initiated confirmation/input; useful for interactive tool flows |
| **Sampling** (`sampling/createMessage`) | Deprecated / Stub-only | Server requests client to generate content; requires client-side infrastructure not available in a server-only SDK |
| **Roots** (`roots/list`) | Not implemented | Client advertises filesystem boundaries; SDK has no filesystem awareness and no natural source of truth |

See [ADR-0023](../adr/ADR-0023-deprecation-sampling-roots-logging.md) for the full deprecation rationale.

---

## Summary

| Item | Detail |
|---|---|
| Enable | `McpServerConfig.Builder.elicitation(true)` |
| Protocol mode | `STATELESS` / `2026-07-28` |
| Transport | HTTP/SSE only |
| API entry point | `McpProtocolHandler.elicitConfirmation`, `elicitInput` |
| Return type | `CompletableFuture<T>` |
| Timeout error | `-32003` (`SERVER_REQUEST_TIMEOUT`) |
| Decline error | `-32004` (`ELICITATION_REJECTED`) |
| Transport unsupported | `-32601` (`METHOD_NOT_FOUND`) |
| Default timeout | 60 000 ms (configurable via `elicitationTimeoutMs`) |
| Correlation token | `uuid:<UUID>` format in JSON-RPC `id` and `_meta.progressToken` |
