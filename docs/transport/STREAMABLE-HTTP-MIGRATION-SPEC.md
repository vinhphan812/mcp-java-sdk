# Streamable HTTP Migration Specification

**Status:** Draft
**Date:** 2026-09-22
**Reference:** `McpHttpHandler.java`, `McpProtocolHandler.java`, `McpServerConfig`, `HttpTransportProvider`

## 1. Overview

This document specifies the migration from Legacy HTTP+SSE (deprecated) to Modern Streamable HTTP for the MCP Java SDK.

### 1.1 Migration Scope

| Component     | Current                   | Target                                |
|---------------|---------------------------|---------------------------------------|
| POST /mcp     | JSON-RPC only             | JSON-RPC + optional SSE streaming     |
| GET /mcp      | Long-lived SSE stream     | Reconnection/replay only              |
| DELETE /mcp   | Session termination       | Session termination (legacy mode)     |
| Accept header | Client-driven             | Server-driven (application/json only) |
| Session model | Stateful (Mcp-Session-Id) | Hybrid: session-aware + stateless     |

### 1.2 Transport Modes

Two transport modes will be supported:

1. **HttpSseMode** (Legacy, deprecated): POST + GET dual endpoint
2. **StreamableHttpMode** (Modern): Single POST with server-driven streaming

## 2. API Changes

### 2.1 McpServerConfig

Add `streamableHttp` boolean (default `true`):

```java
// Builder addition
public Builder streamableHttp(boolean value) {
    this.streamableHttp = value;
    return this;
}
```

### 2.2 HttpTransportProvider

Add transport mode selection:

```java
public enum TransportMode {
    /** Legacy HTTP+SSE (deprecated) */
    HTTP_SSE,
    /** Modern Streamable HTTP */
    STREAMABLE_HTTP,
    /** Auto-detect based on client request */
    AUTO
}

public HttpTransportProvider transportMode(TransportMode mode) {
    this.transportMode = mode;
    return this;
}
```

## 3. Handler Layer Changes

### 3.1 McpHttpHandler Modifications

#### POST Handler Changes

| Aspect                   | Current                                              | Target                                    |
|--------------------------|------------------------------------------------------|-------------------------------------------|
| Accept header validation | Requires both application/json AND text/event-stream | Accepts application/json (server decides) |
| Streaming response       | Not supported                                        | Server decides: JSON or SSE               |
| Response type            | Always application/json                              | Server-driven: 200 + JSON or 200 + SSE    |

**POST Flow (StreamableHttpMode):**

```
1. Validate request (origin, protocol, content-type)
2. Parse JSON-RPC body
3. Handle request via McpProtocolHandler
4. Check if streaming needed (server-initiated notifications pending)
5. If streaming needed:
   a. Switch to SSE response
   b. Send initial JSON response as first SSE event
   c. Continue polling for notifications
   d. Stream until request completes or client disconnects
6. If no streaming needed:
   a. Return normal JSON response
```

#### GET Handler Changes

| Aspect      | Current               | Target                   |
|-------------|-----------------------|--------------------------|
| Function    | Long-lived SSE stream | Reconnection/replay only |
| Live events | Yes                   | No (replay only)         |
| Timeout     | 5 minutes             | Configurable             |

**GET Flow (StreamableHttpMode):**

```
1. Validate session
2. Parse Last-Event-ID header
3. Replay missed events from event log
4. Send "connected" event
5. Return immediately (no live polling)
```

### 3.2 Protocol Detection Logic

```java
private boolean isModernClient(Request request) {
    // Modern client indicators:
    // 1. MCP-Protocol-Version header present
    // 2. Accept header doesn't include text/event-stream
    // 3. No prior session (stateless)
    String protocolVersion = request.getHeader("Mcp-Protocol-Version");
    if (protocolVersion != null) {
        return supportsProtocolVersion(protocolVersion);
    }
    String accept = request.getHeader("Accept");
    if (accept != null && !accept.toLowerCase().contains("text/event-stream")) {
        return true;
    }
    return false;
}
```

## 4. Protocol Layer Changes

### 4.1 McpProtocolHandler

#### Streaming Response Support

Add method to check if streaming is needed:

```java
/**
 * Returns true if the session has pending notifications that should
 * be delivered via SSE streaming on the response connection.
 */
public boolean hasPendingNotifications(String sessionId);
```

#### handleRequestResponse() Enhancement

The method needs to support returning a streaming response indicator:

```java
public static class McpResponse {
    private final String body;
    private final String sessionId;
    private final boolean streaming;  // NEW: indicates SSE streaming needed

    public McpResponse(String body, String sessionId) {
        this(body, sessionId, false);
    }

    public McpResponse(String body, String sessionId, boolean streaming) {
        this.body = body;
        this.sessionId = sessionId;
        this.streaming = streaming;
    }

    public boolean isStreaming() {
        return streaming;
    }
    // ... existing getters
}
```

### 4.2 SSE Event Handling

Existing methods remain for GET reconnection path:

- `pollSseEvent(String sessionId)` - Get next event with ID
- `getMissedEvents(String sessionId, long afterEventId)` - Replay missed events

New method for POST streaming:

- `pollPendingNotification(String sessionId)` - Get notification without SSE formatting

## 5. Session Management

### 5.1 Hybrid Session Model

| Mode            | Session Required | Session Header                             | Lifetime    |
|-----------------|------------------|--------------------------------------------|-------------|
| HTTP_SSE        | Yes              | Mcp-Session-Id                             | Long-lived  |
| STREAMABLE_HTTP | Optional         | Mcp-Session-Id (legacy) / None (stateless) | Per-request |

### 5.2 Session Binding

- **Legacy mode**: Full session management (create, validate, terminate)
- **Modern mode**: Session optional; stateless per-request when possible

## 6. Configuration

### 6.1 Builder Configuration

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("my-server")
    .streamableHttp(true)        // NEW: enable Streamable HTTP mode
    .streaming(true)             // Existing: SSE capability advertisement
    .build();
```

```java
HttpTransportProvider transport = new HttpTransportProvider(handler)
    .transportMode(TransportMode.AUTO)  // Default: auto-detect
    .maxSseConnections(4)
    .build();
```

## 7. Backward Compatibility

### 7.1 Client Detection

| Indicator                         | Client Type |
|-----------------------------------|-------------|
| Accept: text/event-stream         | Legacy      |
| MCP-Protocol-Version: 2025-03-26+ | Modern      |
| No Accept header, POST only       | Modern      |

### 7.2 Response Behavior

| Client Type | POST Response    | GET Response             |
|-------------|------------------|--------------------------|
| Legacy      | application/json | text/event-stream (live) |
| Modern      | Server decides   | 405 Method Not Allowed   |

## 8. Error Handling

### 8.1 Rate Limiting

- **Legacy mode**: Per-session rate limits (existing)
- **Modern mode**: Per-IP rate limits (new)

### 8.2 Error Responses

Standard JSON-RPC error format for both modes:

```json
{
  "jsonrpc": "2.0",
  "id": null,
  "error": {
    "code": -32029,
    "message": "Too Many Requests: ..."
  }
}
```

## 9. Implementation Phases

### Phase 1: Core Infrastructure

1. Add `streamableHttp` config to McpServerConfig
2. Add `TransportMode` enum to HttpTransportProvider
3. Update protocol detection logic in McpHttpHandler

### Phase 2: POST Handler Enhancement

1. Modify POST handler to support streaming response
2. Add `hasPendingNotifications()` method to McpProtocolHandler
3. Implement SSE response switching in POST handler

### Phase 3: GET Handler Modification

1. Convert GET to replay-only in StreamableHttpMode
2. Remove live polling from GET in modern mode
3. Keep existing behavior for legacy mode

### Phase 4: Deprecation & Defaults

1. Log deprecation warnings for HTTP+SSE mode
2. Default to StreamableHttp mode
3. Add migration guide documentation

## 10. Testing

### 10.1 Unit Tests

| Test                  | Coverage                       |
|-----------------------|--------------------------------|
| ProtocolDetectionTest | Client type detection          |
| StreamingResponseTest | POST SSE switching             |
| ReplayOnlyGetTest     | GET replay without live events |

### 10.2 Integration Tests

| Test                   | Coverage              |
|------------------------|-----------------------|
| McpGrizzlyLiveTest     | Update for dual-mode  |
| StreamableHttpModeTest | Modern transport only |
| LegacyHttpSseModeTest  | Legacy transport only |
| ReconnectionTest       | Last-Event-ID replay  |

## 11. Documentation Updates

### 11.1 Files to Update

1. **Rename**: `TRANSPORT-SSE.md` → `TRANSPORT-STREAMABLE-HTTP.md`
2. **Update**: ADR-0017 SSE permit/response spec
3. **Create**: Migration guide for legacy clients

### 11.2 API Documentation

Add Javadoc for new methods:

- `McpServerConfig.streamableHttp`
- `HttpTransportProvider.transportMode()`
- `McpResponse.isStreaming()`

## 12. Open Questions

1. **Session cleanup**: Should modern mode use different session timeout?
2. **Subscription model**: Does `subscriptions/listen` require separate implementation?
3. **Metrics**: Should we track mode usage for migration planning?
4. **Max connections**: Should modern mode have different SSE connection limits?

## 13. Validation

### 13.1 Build Validation

```bash
./gradlew compileJava compileTestJava
```

### 13.2 Test Validation

```bash
./gradlew test
```

### 13.3 Specific Test Classes

- `McpGrizzlyLiveTest` - Extended for dual-mode
- `McpIntegrationTest` - SSE streaming tests
- New: `StreamableHttpModeTest`
