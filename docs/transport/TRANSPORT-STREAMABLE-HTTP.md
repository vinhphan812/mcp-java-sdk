# Transport: Streamable HTTP

**Status:** Active
**Last Updated:** 2026-09-22
**Reference:** `McpHttpHandler`, `HttpTransportProvider`

## Overview

The MCP Java SDK supports two transport modes:

| Mode                   | Protocol Version        | Endpoints           | Session Model |
|------------------------|-------------------------|---------------------|---------------|
| Legacy HTTP+SSE        | 2024-11-05 (deprecated) | POST + GET + DELETE | Stateful      |
| Modern Streamable HTTP | 2025-03-26+             | POST only           | Stateless     |

## Transport Modes

### TransportMode Enum

```java
public enum TransportMode {
    /** Legacy HTTP+SSE (deprecated by MCP spec) */
    HTTP_SSE,
    /** Modern Streamable HTTP (current spec) */
    STREAMABLE_HTTP,
    /** Auto-detect based on client request */
    AUTO
}
```

### Configuration

```java
// Via HttpTransportProvider
HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.AUTO)  // Default: auto-detect
    .maxSseConnections(4)
    .build();

// Via McpServerConfig
McpServerConfig config = McpServerConfig.builder()
    .serverName("my-server")
    .streamableHttp(true)   // Enable modern transport
    .build();
```

## Client Detection

The SDK automatically detects client type based on request headers:

| Indicator                              | Client Type |
|----------------------------------------|-------------|
| `MCP-Protocol-Version` header present  | Modern      |
| `Accept: text/event-stream` in request | Legacy      |
| No `Accept` header, POST only          | Modern      |

### Detection Logic

```java
private boolean isModernClient(Request request) {
    // 1. Check MCP-Protocol-Version header (strongest signal)
    String protocolVersion = request.getHeader("Mcp-Protocol-Version");
    if (protocolVersion != null) {
        return true;  // Modern client signals protocol version
    }

    // 2. Check Accept header
    String accept = request.getHeader("Accept");
    if (accept != null && accept.toLowerCase().contains("text/event-stream")) {
        return false;  // Legacy client explicitly requests SSE
    }

    // 3. Default to modern for POST-only clients
    return "POST".equals(request.getMethod());
}
```

## Endpoint Behavior

### Modern Streamable HTTP (STREAMABLE_HTTP)

| HTTP Method | Behavior                                                     |
|-------------|--------------------------------------------------------------|
| POST        | JSON-RPC request; server decides response type (JSON or SSE) |
| GET         | Returns 405 Method Not Allowed                               |
| DELETE      | Ignored (stateless)                                          |

### Legacy HTTP+SSE (HTTP_SSE)

| HTTP Method | Behavior                                |
|-------------|-----------------------------------------|
| POST        | JSON-RPC request/response               |
| GET         | Long-lived SSE stream for notifications |
| DELETE      | Session termination                     |

## Response Types

### Modern Transport Response Flow

```
POST /mcp
├── Request: JSON-RPC body
├── Server processes request
├── Server checks for pending notifications
│   ├── If pending → SSE streaming response
│   └── If none → JSON response
└── Response
    ├── application/json (single response)
    └── text/event-stream (streaming)
```

### Server-Driven Streaming

The server decides response type based on:

1. **Pending notifications**: If `hasPendingNotifications(sessionId)` returns true
2. **Request type**: `notifications/listen` subscriptions trigger streaming
3. **Client capability**: MCP-Protocol-Version header presence

## SSE Event Format

Standard SSE events follow this format:

```
id: <event-id>
event: <event-type>
data: <json-payload>

```

### Event Types

| Event       | Description                      |
|-------------|----------------------------------|
| `connected` | Initial connection established   |
| `message`   | Server-to-client notification    |
| `ping`      | Keep-alive heartbeat (every 30s) |
| `cancelled` | Subscription cancelled           |

### Example Events

```bash
# Connected event
id: 1
event: connected
data: {"sessionId":"abc123"}

# Message notification
id: 2
event: message
data: {"method":"notifications/message","params":{"level":"info","message":"Task completed"}}

# Ping (no data)
id: 3
event: ping
data: {}
```

## Session Management

### Legacy Mode (HTTP_SSE)

- Sessions created on first POST with Initialize
- Session ID returned in `Mcp-Session-Id` header
- GET endpoint maintains long-lived connection
- DELETE terminates session and cleans up resources

### Modern Mode (STREAMABLE_HTTP)

- Stateless per-request (no session required)
- Optional session via `Mcp-Session-Id` header (backward compat)
- Each POST request is independent
- No GET endpoint required

## Configuration Examples

### Modern-Only Server

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("modern-server")
    .streamableHttp(true)  // Use modern transport only
    .build();

HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.STREAMABLE_HTTP)
    .build();
```

### Legacy-Only Server (Deprecated)

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("legacy-server")
    .streamableHttp(false)  // Use legacy transport
    .build();

HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.HTTP_SSE)
    .build();
```

### Auto-Detect Server (Recommended)

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("auto-server")
    .streamableHttp(true)  // Default is true
    .build();

HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.AUTO)  // Default
    .maxSseConnections(4)
    .build();
```

## Migration Guide

### Upgrading from Legacy to Modern

1. **Update client configuration**:
    - Remove GET endpoint handling
    - Include `MCP-Protocol-Version` header
    - Use single POST endpoint only

2. **Session handling**:
    - Remove dependency on `Mcp-Session-Id` for new clients
    - For backward compatibility, still handle the header

3. **Notification delivery**:
    - Legacy: Long-lived GET connection
    - Modern: Use `notifications/listen` subscription via POST

### Testing Migration

```bash
# Test modern transport
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2025-03-26" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}'

# Test legacy transport
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}'
```

## Limits and Constraints

| Parameter           | Default   | Configurable                      |
|---------------------|-----------|-----------------------------------|
| Max SSE connections | 4         | Yes (via `maxSseConnections`)     |
| SSE idle timeout    | 5 minutes | Yes (via `sseIdleTimeoutSeconds`) |
| Request body max    | 10MB      | Yes (via Jetty config)            |

## Related Documentation

- [ADR-0017: SSE Permit Acquisition and Response Flow](./adr/ADR-0017-sse-permit-response-flow.md)
- [ADR-0018: Transport Contract - HTTP+SSE vs Streamable HTTP](./adr/ADR-0018-transport-contract-http-sse-vs-streamable-http.md)
- [SSE Implementation Details](./SSE-IMPLEMENTATION-SLICES-AND-TEST-PLAN.md)
- [SSE Connection State Machine](./SSE-CONNECTION-STATE-MACHINE.md)
- [SSE Last-Event-ID Replay](./SSE-LAST-EVENT-ID-REPLAY.md)

## Deprecation Notes

The Legacy HTTP+SSE transport is **deprecated** as of MCP specification 2025-03-26. New deployments should use Modern
Streamable HTTP. The SDK maintains backward compatibility but will:

1. Log deprecation warnings when legacy clients connect
2. Default to auto-detection (modern preferred)
3. Remove legacy mode in SDK v2.0
