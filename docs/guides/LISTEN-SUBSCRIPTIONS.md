# MCP SEP-2575 Subscriptions / Listen

This document describes the high-throughput subscription mechanism introduced in SEP-2575, replacing legacy resource subscription methods for 2026-07-28 and later protocol versions.

> **STDIO is not supported.** These APIs are available only over HTTP-based transports.

## Listen Subscriptions (`subscriptions/listen`)

Subscription is handled via a single, long-lived POST streaming request.

### Opening the subscription

The client initiates a request to `subscriptions/listen`. This initiates a streaming response where the server pushes notifications.

```json
POST /mcp/subscriptions/listen
Content-Type: application/json
Mcp-Protocol-Version: 2026-07-28

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "subscriptions/listen",
  "params": {
    "topics": ["tools", "resources/updated"],
    "_meta": {
      "io.modelcontextprotocol/subscriptionId": "my-sub-1"
    }
  }
}
```

### Protocol flow

1. **Request:** The client sends the POST request above.
2. **First Frame:** The server immediately sends an acknowledged notification frame:
   ```json
   {"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged"}
   ```
3. **Streaming:** The server pushes subsequent notifications as they occur over the same HTTP stream.
4. **Correlation:** Notifications include `_meta["io.modelcontextprotocol/subscriptionId"]` matching the client request to allow multi-subscription multiplexing.

### Cancellation and Shutdown

- **Cancellation:** The client closes the HTTP request stream to cancel the subscription.
- **Shutdown:** When the client terminates the connection, the server finalizes pending operations and sends a `resultType` notification confirming stop if required by the transport.

## Legacy Compatibility

This mechanism is isolated from legacy `resources/subscribe` (2025-era). Servers may implement both, but notifications are strictly segregated:

- **SEP-2575 (`subscriptions/listen`):** Managed via HTTP streaming.
- **Legacy (`resources/subscribe`):** Managed via session-based JSON-RPC POST request/response.

Server implementations **must not** leak events between these legacy and 2026-07-28 subscription channels.
