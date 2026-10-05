# MCP 2026-07-28 Subscriptions / Listen

`examples/src/main/java/io/github/vinhphan812/mcp/examples/ListenExample.java` demonstrates
the MCP 2026-07-28 `listens/subscribe` and `listens/unsubscribe` protocol.

> **STDIO is not supported.** These APIs are available only over HTTP/Streamable HTTP/SSE.

## Topics

The server publishes notifications on the following topics:

| Topic               | When emitted                                    |
|---------------------|-----------------------------------------------|
| `tools`             | A tool is registered or removed                |
| `resources`         | A resource or resource template is registered  |
| `prompts`           | A prompt is registered or removed             |
| `resources/updated` | A resource's content changes (via `notifyResourceUpdated`) |

## Opening a subscription

```json
POST /mcp
Content-Type: application/json
Mcp-Protocol-Version: 2026-07-28

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "listens/subscribe",
  "params": {
    "topics": ["tools", "resources/updated"],
    "_meta": {
      "subscriptionId": "my-sub-1",
      "bufferSize": 50
    }
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "subscription": {
      "token": "3",
      "topics": ["tools", "resources/updated"],
      "bufferSize": 50,
      "maxCapacity": 100
    }
  }
}
```

The server assigns a `token` used for unsubscribing. The `bufferSize` is the
effective queue size (clamped to `maxCapacity`).

## Receiving notifications

Notifications are streamed to the client over the HTTP response using SSE.
After subscribing, the client opens a second POST request with `Accept: text/event-stream`
to receive events:

```json
POST /mcp
Content-Type: application/json
Accept: application/json, text/event-stream
Mcp-Protocol-Version: 2026-07-28

{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "listens/poll",
  "params": {
    "token": "3"
  }
}
```

**Response (SSE stream):**

```
event: message
data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed"}

event: message
data: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"demo://catalog"}}
```

> **Note:** The `listens/poll` endpoint is transport-specific. The SDK's HTTP transport
> handler drains the subscription's event queue and streams them as SSE frames to the client.

## Closing a subscription

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "listens/unsubscribe",
  "params": {
    "token": "3"
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "unsubscribed": true
  }
}
```

## Programmatic resource update

Applications push resource-change events through the protocol handler:

```java
McpServer server = McpServer.builder()
    .register(new MyToolProvider())
    .register(new MyResourceProvider())
    .build();

server.start();

// When the resource content changes:
server.getProtocolHandler().notifyResourceUpdated("demo://catalog");
```

All active listener subscriptions subscribed to `resources/updated` receive the notification.

## Bounded buffer semantics

Each listener subscription has its own bounded notification queue. When the queue is full,
the oldest event is silently dropped. Configure the maximum with:

```java
McpServerConfig config = McpServerConfig.builder()
    .maxListenerBufferSize(200)  // default: 100
    .build();
```

Clients can request a smaller buffer via `_meta.bufferSize` in the subscribe request; the
server clamps it to `maxListenerBufferSize`.

## Mixed-era isolation

Listener subscriptions are **sessionless** — they do not use `Mcp-Session-Id` and operate
independently of the legacy 2025 `resources/subscribe` session-based mechanism.
Both can coexist; they maintain separate notification channels.
