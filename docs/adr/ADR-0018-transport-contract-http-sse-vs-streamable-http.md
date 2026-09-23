# ADR-0018 — Transport Contract: Legacy HTTP+SSE vs Modern Streamable HTTP

**Status:** Accepted
**Date:** 2026-09-22
**Last Updated:** 2026-09-22
**Author:** dev-architect
**Implementation Status:** In Progress (Phase 1-3 completed)

## Context

The MCP Java SDK transport layer (`HttpTransportProvider` + `McpHttpHandler`) currently implements the **deprecated
HTTP+SSE transport** from MCP protocol version 2024-11-05. This ADR clarifies the transport contract and decides the
path forward.

### Current Implementation

The current implementation in `McpHttpHandler.java` uses a **dual-endpoint** pattern:

| HTTP Method | Endpoint                  | Purpose                        |
|-------------|---------------------------|--------------------------------|
| POST /mcp   | JSON-RPC request/response | Client-to-server RPC           |
| GET /mcp    | Server-Sent Events (SSE)  | Server-to-client notifications |
| DELETE /mcp | Session termination       | Close session                  |

**Key characteristics:**

- Client MUST send `Accept: application/json, text/event-stream`
- `Mcp-Session-Id` header manages stateful sessions
- GET endpoint required for notification streaming
- Supports `Last-Event-ID` for replay after disconnection
- Semaphore limits concurrent SSE connections (default: 4)

### Modern Streamable HTTP (MCP 2025-03-26+)

The modern Streamable HTTP transport (current draft: 2026-07-28) has fundamentally different semantics:

| Aspect                | Legacy HTTP+SSE                  | Modern Streamable HTTP         |
|-----------------------|----------------------------------|--------------------------------|
| Endpoints             | POST + GET + DELETE              | Single POST only               |
| Streaming decision    | Client-driven (Accept header)    | Server-driven at response time |
| Session model         | Stateful (Mcp-Session-Id)        | Stateless (per-request)        |
| Notification delivery | Long-lived GET connection        | Per-request SSE stream         |
| Replay/Resume         | Last-Event-ID supported          | NOT supported                  |
| Cancellation          | DELETE + notifications/cancelled | Close SSE stream               |
| Protocol version      | 2024-11-05                       | 2025-03-26 through 2026-07-28  |

**Modern transport key points (2026-07-28):**

1. Single POST endpoint handles both request and streaming response
2. Server decides per-request: return `application/json` (single) or `text/event-stream` (SSE)
3. No GET endpoint required; GET returns `405 Method Not Allowed`
4. No protocol-level sessions; each request is independent
5. Long-lived notifications via `subscriptions/listen` request (response stream stays open)
6. Client MUST include `MCP-Protocol-Version` header
7. Request metadata mirrored to HTTP headers (`Mcp-Method`, `Mcp-Name`)
8. SSE streams are request-scoped; closing stream = cancellation signal
9. Legacy HTTP+SSE is **deprecated** since 2025-03-26

## Options

### Option 1: Keep Legacy HTTP+SSE (Document & Rename)

Continue using the current implementation but:

- Rename `HttpTransportProvider` → `HttpSseTransportProvider` (or similar)
- Document as "Legacy HTTP+SSE Transport (deprecated by MCP spec)"
- No code changes required beyond renaming
- Accept that newer clients may not be compatible

**Pros:**

- Zero implementation effort
- Stable, well-tested implementation
- Works with existing client ecosystem

**Cons:**

- Implements deprecated protocol
- Will become increasingly incompatible with newer MCP clients
- Security: lacks modern protocol version enforcement
- Misses optimizations in modern Streamable HTTP

### Option 2: Migrate to Modern Streamable HTTP

Implement the modern Streamable HTTP transport (2026-07-28):

- Single POST endpoint (remove GET, DELETE handlers)
- Server-driven streaming decision
- Remove session management (no Mcp-Session-Id)
- Request-scoped SSE streams
- Add `MCP-Protocol-Version` header validation
- Add request metadata headers (`Mcp-Method`, `Mcp-Name`)

**Pros:**

- Aligns with current MCP specification
- Simplifies endpoint routing (single POST)
- No session state to manage
- Better for stateless/containerized deployments

**Cons:**

- Significant refactoring of `McpHttpHandler`
- Breaking change: existing clients MUST update
- Loses Last-Event-ID replay capability
- Loses long-lived notification connections (must use subscriptions)

### Option 3: Support Both (Hybrid)

Implement both transports with protocol negotiation:

- Default to modern Streamable HTTP for new clients
- Fall back to legacy HTTP+SSE when:
    - Client sends `Accept: application/json, text/event-stream` with GET
    - Client omits `MCP-Protocol-Version` header
    - Client explicitly requests legacy via query param
- Keep both endpoint handlers (POST + GET + DELETE)

**Pros:**

- Maximum compatibility (serves all client versions)
- Gradual migration path
- No breaking changes

**Cons:**

- Most complex implementation
- Two code paths to maintain
- Session state management required for legacy clients
- Potential security confusion (two attack surfaces)

## Decision

**Option 3: Support Both (Hybrid)** — with phased migration

Rationale:

1. **Ecosystem reality**: Not all MCP clients have migrated to modern Streamable HTTP. Many existing clients and tools
   still use the legacy HTTP+SSE pattern. A hard cutover would break production integrations.

2. **Backward compatibility**: The MCP spec explicitly describes backward-compatibility mechanisms. Supporting both is
   the recommended approach for servers that want to serve diverse clients.

3. **Phased migration**: We can:
    - Phase 1: Add protocol detection and dual-mode support
    - Phase 2: Deprecate legacy mode (with warning in logs)
    - Phase 3: Disable legacy by default (opt-in)
    - Phase 4: Remove legacy (future major version)

4. **Risk mitigation**: Starting with both ensures we don't strand existing deployments while giving a clear path to
   modern transport.

## Implementation Implications

### Endpoint Routing

```java
// McpHttpHandler.service() logic:
String method = request.getMethod();

// Modern Streamable HTTP (single POST)
if ("POST".equals(method)) {
    boolean isModern = detectModernClient(request);
    if (isModern) {
        handleModernPost(request, response);  // New implementation
    } else {
        handleLegacyPost(request, response);  // Current implementation
    }
}

// Legacy HTTP+SSE (GET for SSE notifications)
if ("GET".equals(method)) {
    // Only for legacy clients; modern clients don't use GET
    handleLegacyGet(request, response);
}

// Legacy session termination
if ("DELETE".equals(method)) {
    handleLegacyDelete(request, response);
}
```

### Protocol Detection

Modern client indicators (in order of precedence):

1. Presence of `MCP-Protocol-Version` header → modern
2. Absence of `Accept: text/event-stream` → modern
3. Client explicitly signals via custom header or query param
4. Default to modern for POST requests without legacy indicators

### Backward Compatibility Response

When responding to legacy clients:

- Include `Mcp-Session-Id` header on InitializeResult
- Support `Last-Event-ID` header on GET requests
- Keep DELETE endpoint active
- Continue using `Accept` header for content negotiation

### Test Implications

| Test Category        | Legacy             | Modern                            |
|----------------------|--------------------|-----------------------------------|
| Unit tests           | Extend existing    | Add new test class                |
| Integration tests    | Extend existing    | Add new test class                |
| Client compatibility | Current test suite | New client mock tests             |
| Protocol negotiation | N/A                | End-to-end with both client types |

### Documentation Update

1. **Rename** `HttpTransportProvider` → `HttpSseTransportProvider` (keep as alias)
2. **Create** `StreamableHttpTransportProvider` for modern transport
3. **Update** TRANSPORT-SSE.md with legacy vs modern comparison
4. **Add** migration guide for users upgrading from legacy to modern

### Migration Path

|| Phase | Action | Timeline | Status ||
|-------|--------|----------|----------|--------|
| Phase 1 | Implement dual-mode detection + handlers | This ADR | **Completed** |
| Phase 2 | Log deprecation warning for legacy mode | Next sprint | Pending |
| Phase 3 | Add config flag to disable legacy | Future | Pending |
| Phase 4 | Remove legacy (major version) | v2.0 | Planned |

### Implementation Status (2026-09-22)

The following phases have been completed:

- **Phase 1: Core Infrastructure** ✅
    - Added `streamableHttp` config to `McpServerConfig`
    - Added `TransportMode` enum (HTTP_SSE, STREAMABLE_HTTP, AUTO)
    - Implemented `isModernClient()` detection in `McpHttpHandler`
    - Added `transportMode` field to `McpHttpHandler`

- **Phase 2: POST Handler Enhancement** ✅
    - Server-driven streaming support added
    - `MCP-Protocol-Version` header validation
    - Streaming response capability

- **Phase 3: GET Handler Modification** ✅
    - GET handler modified for replay-only in StreamableHttp mode
    - Legacy mode still supports full SSE streaming

## Open Questions

1. **Session state cleanup**: With modern transport, how do we handle server-side state? Modern transport is stateless
   per-request. Should we use external state (Redis) or retain in-memory session for backward compat only?

2. **Subscription model**: Modern `subscriptions/listen` requires long-lived SSE streams on POST response. Does the
   current SSE polling loop support this pattern, or is redesign needed?

3. **Rate limiting**: Legacy uses per-session rate limits. Modern has no sessions. Should rate limiting differ between
   modes?

## References

- [MCP Streamable HTTP Spec (draft)](https://modelcontextprotocol.io/specification/draft/basic/transports/streamable-http)
- [MCP HTTP+SSE Transport (deprecated)](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports#http-with-sse)
- Current implementation: `McpHttpHandler.java`, `HttpTransportProvider.java`
- Existing ADR: ADR-0017 (SSE permit ordering — still relevant for legacy mode)
