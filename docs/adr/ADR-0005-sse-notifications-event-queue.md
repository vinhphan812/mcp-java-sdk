# ADR-0005 — Server-Initiated Notifications via SSE Event Queue

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

MCP servers need to send notifications to clients asynchronously — for example, when a subscribed resource changes or when a task completes. HTTP is request-response; the client cannot receive a push without an open connection. The Streamable HTTP profile solves this with a long-lived GET connection that acts as an event stream.

## Decision

The SDK uses Server-Sent Events (SSE) over a long-lived HTTP GET connection:

1. After `initialize`, the client opens `GET /mcp` with the session ID.
2. The server immediately sends a `endpoint/message` event with the session metadata.
3. The server sends ping events every 60 seconds.
4. When a server-initiated notification is triggered, it is queued into the session's event queue and dequeued by the SSE polling loop.
5. Events are formatted as SSE with `id:` and `data:` fields.
6. When the client reconnects with `Last-Event-ID`, the server replays all queued events with IDs greater than the requested ID.

### Event format

```text
id: 42
data: {"jsonrpc":"2.0","method":"notifications/resources/list_changed","params":{"uri":"demo://catalog"}}

id: 43
data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed","params":{}}

```

### Event queue

Each session maintains:

```java
AtomicLong nextEventId;                          // monotonically increasing
ConcurrentLinkedQueue<SseEvent> eventQueue;      // max 1000 events (FIFO, oldest dropped)
```

`SseEvent` holds `{id: long, body: String}` where `body` is the JSON-RPC notification string.

### CRLF sanitisation

SSE data fields are sanitised before transmission: `\r` and `\n` inside JSON body strings are escaped as `\\r` and `\\n`. This prevents HTTP response splitting.

## Consequences

**Positive:**

- SSE is simpler than WebSocket and works over HTTP/1.1.
- Event IDs enable at-least-once delivery with replay.
- The queue is bounded (1000 events) to prevent unbounded memory growth.
- Server-initiated notifications are decoupled from request threads.

**Negative:**

- SSE is unidirectional: the server cannot receive data on the same connection.
- Each client session requires its own persistent GET connection.
- The queue is in-memory; events are lost on server restart.
- Events beyond the queue window are silently dropped.
- Long-lived connections may interact with corporate proxies that timeout or buffer.
