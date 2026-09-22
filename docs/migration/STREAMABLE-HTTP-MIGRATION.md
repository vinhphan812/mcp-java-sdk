# Migration Guide: Legacy HTTP+SSE to Streamable HTTP

**Audience:** Developers upgrading from legacy HTTP+SSE transport to modern Streamable HTTP
**MCP Java SDK Version:** 1.x (pre-2.0)

## Overview

The MCP Java SDK supports two transport modes:

1. **Legacy HTTP+SSE** (deprecated) — The original transport using POST + GET + DELETE endpoints
2. **Modern Streamable HTTP** — Current MCP specification using single POST with server-driven streaming

This guide helps you migrate from legacy to modern transport.

## Why Migrate?

| Benefit | Legacy | Modern |
|---------|--------|--------|
| Protocol alignment | Deprecated (2024-11-05) | Current (2025-03-26+) |
| Endpoint complexity | 3 endpoints | 1 endpoint |
| Session management | Stateful (session ID required) | Stateless (optional session) |
| Scaling | Session affinity needed | Stateless, no affinity |
| Client compatibility | Older clients | Modern MCP clients |

## Quick Start

### Before (Legacy Configuration)

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("my-server")
    .build();

HttpTransportProvider transport = new HttpTransportProvider(handler)
    .build();
```

### After (Modern Configuration)

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("my-server")
    .streamableHttp(true)  // Explicitly enable modern transport
    .build();

HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.STREAMABLE_HTTP)
    .build();
```

## Configuration Reference

### TransportMode Enum

```java
public enum TransportMode {
    /** Legacy HTTP+SSE - deprecated */
    HTTP_SSE,
    /** Modern Streamable HTTP - current spec */
    STREAMABLE_HTTP,
    /** Auto-detect based on client request (recommended) */
    AUTO
}
```

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `streamableHttp` | boolean | true | Enable modern transport |
| `transportMode` | TransportMode | AUTO | Transport selection |
| `maxSseConnections` | int | 4 | Max concurrent SSE connections |
| `sseIdleTimeoutSeconds` | long | 300 | SSE idle timeout |

### Recommended Configuration (Auto-Detect)

```java
HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.AUTO)  // Recommended: serves both client types
    .maxSseConnections(4)
    .sseIdleTimeoutSeconds(300)
    .build();
```

### Modern-Only Configuration

```java
HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.STREAMABLE_HTTP)
    .maxSseConnections(4)
    .build();
```

### Legacy-Only Configuration (Not Recommended)

```java
HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.HTTP_SSE)
    .maxSseConnections(4)
    .build();
```

## Client Requirements

### Modern Client Requirements

Modern clients must:

1. Send `MCP-Protocol-Version` header
2. Use POST only (no GET for notifications)
3. Handle server-driven response type (JSON or SSE)

```bash
# Example: Modern client request
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}'
```

### Legacy Client Requirements

Legacy clients:

1. Send `Accept: application/json, text/event-stream`
2. Use GET endpoint for notifications
3. Manage session via `Mcp-Session-Id` header

```bash
# Example: Legacy client request
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: session123" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}'

# Legacy: Get notifications via GET
curl -N http://localhost:8080/mcp \
  -H "Accept: text/event-stream" \
  -H "Mcp-Session-Id: session123"
```

## Behavior Differences

### Endpoint Behavior

| Operation | Legacy | Modern |
|-----------|--------|--------|
| JSON-RPC request | POST /mcp | POST /mcp |
| Notifications | GET /mcp (long-lived) | POST response stream |
| Session termination | DELETE /mcp | Not needed (stateless) |
| GET /mcp | SSE stream | 405 Method Not Allowed |

### Response Types

**Legacy:**
- POST returns `application/json`
- GET returns `text/event-stream`

**Modern:**
- POST returns either `application/json` OR `text/event-stream`
- Server decides based on pending notifications

### Session Management

**Legacy:**
```java
// Server creates session
response.setHeader("Mcp-Session-Id", sessionId);

// Client must include in subsequent requests
request.getHeaders("Mcp-Session-Id");
```

**Modern (optional):**
```java
// Session is optional - stateless by default
// If provided, used for backward compatibility
String sessionId = request.getHeader("Mcp-Session-Id");
```

## Breaking Changes

When migrating to modern-only transport:

1. **GET endpoint returns 405** — Clients cannot use GET for notifications
2. **No session required** — Remove `Mcp-Session-Id` dependency
3. **Server-driven streaming** — Clients must handle either JSON or SSE response
4. **No Last-Event-ID** — Modern transport doesn't support reconnection via GET

## Troubleshooting

### Issue: 405 Method Not Allowed on GET

**Cause:** Client trying to use legacy GET endpoint with modern transport mode.

**Solution:** 
- Use `TransportMode.AUTO` to support both
- Or update client to use modern Streamable HTTP

### Issue: No Notifications Received

**Cause:** Modern transport delivers notifications via POST response stream, not GET.

**Solution:** 
- For modern clients: subscribe via `notifications/listen` request
- For legacy clients: use `TransportMode.AUTO` or `HTTP_SSE`

### Issue: Session Not Found

**Cause:** Modern mode is stateless; session may not exist.

**Solution:**
- Use `TransportMode.AUTO` for backward compatibility
- Or explicitly create session in modern mode if needed

## Testing Your Migration

### Test Modern Transport

```bash
# Initialize with modern protocol
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-03-26",
      "capabilities": {},
      "clientInfo": {"name": "test", "version": "1.0"}
    }
  }'
```

### Test Legacy Transport

```bash
# Initialize with legacy Accept header
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "test", "version": "1.0"}
    }
  }'
```

## Deprecation Timeline

| Date | Action |
|------|--------|
| 2025-03-26 | Legacy HTTP+SSE deprecated by MCP spec |
| 2026-09-22 | SDK implements dual-mode (AUTO) |
| Future | Log deprecation warnings for legacy clients |
| Future | Add config to disable legacy mode |
| v2.0 | Remove legacy mode entirely |

## Related Documentation

- [Transport: Streamable HTTP](../transport/TRANSPORT-STREAMABLE-HTTP.md)
- [ADR-0018: Transport Contract](./adr/ADR-0018-transport-contract-http-sse-vs-streamable-http.md)
- [Migration Specification](../transport/STREAMABLE-HTTP-MIGRATION-SPEC.md)
