# MCP Compatibility Plan

Status: P0, P1, and P2 core work substantially complete. External conformance verification infrastructure exists
(`harness/` + official `@modelcontextprotocol/conformance` CLI). Full feature-by-feature compliance matrix: see
[MCP-CONFORMANCE-MATRIX.md](./MCP-CONFORMANCE-MATRIX.md).

Reference documentation:

- [Project Guide](../guides/PROJECT-GUIDE.md) — architecture and usage guide.
- [API Reference](../guides/API-REFERENCE.md) — complete public API surface.
- [Implementation Status](../guides/IMPLEMENTATION-STATUS.md) — completed, incomplete, and unverified areas.
- [HTTP-TRANSPORT-EXAMPLE.md](../guides/HTTP-TRANSPORT-EXAMPLE.md) — standalone HTTP/SSE transport example.
- [MCP-PORTING-PLAN.md](./MCP-PORTING-PLAN.md) — package inventory and porting notes.
- [docs/adr/](../adr/) — architecture decision records documenting key design choices.

## Scope

This project is a lightweight Java 11-compatible MCP server implementation. It is not the official
`modelcontextprotocol/java-sdk` and does not claim full feature parity.

## Target baseline

The server supports two configurable protocol modes: sessioned MCP `2025-11-25` by default (with legacy compatibility)
and STATELESS MCP `2026-07-28`. In STATELESS mode, initialize and subsequent requests do not require an MCP session.
This is an implementation capability, not a claim of full MCP certification or external-client interoperability.

The server must accept a client protocol version only when it is supported. Unsupported versions must return an
initialize error rather than silently advertising a different version.

## P0 compatibility work

- Validate JSON-RPC 2.0 envelope.
- Distinguish requests from notifications. Notifications do not receive JSON-RPC responses.
- Require initialization before protected methods.
- Create one session for one initialize exchange and reject invalid session reuse.
- Return negotiated `protocolVersion`, `serverInfo`, and capability metadata.
- Use `resources/read` for resolved resource-template URIs. Do not expose custom `resources/templates/get`.
- Return JSON-RPC errors for invalid params and unknown names.
- Emit prompt messages with text inside `content`.
- Validate Streamable HTTP `Content-Type`, `Accept`, `Origin`, and `Mcp-Session-Id` rules.
- Preserve `Mcp-Protocol-Version` on HTTP responses where required.
- Add an ephemeral-port live HTTP smoke test. (Present in the current test suite; covered by local verification.)

## P1 compatibility work

- Cursor pagination for list methods (implemented and tested).
- `notifications/tools/list_changed`, `notifications/resources/list_changed`, and `notifications/prompts/list_changed` (
  implemented and tested).
- SSE message IDs (implemented and tested); `Last-Event-ID` replay is implemented and tested.
- Resource `blob` contents (implemented via `McpBlobResourceHandler` and `McpBlobContent`).
- Tool `outputSchema` via `@McpTool(outputSchema = "...")` annotation attribute.
- `completion/complete` (implemented with registered providers; initialize advertisement is configuration-driven).
- Logging (`logging/setLevel` and `notifications/message`) (implemented; initialize advertisement is
  configuration-driven).

## P2 compatibility work

- [x] Progress/cancellation notifications (progress tokens in `_meta`, `notifications/progress`)
- [x] Client-initiated `notifications/cancelled` (cancels by request id)
- [x] `tasks/create` (task-producing requests with `tasks/task` notifications)
- [x] STDIO transport (`StdioTransportProvider`; line-delimited JSON-RPC; clean EOF shutdown)
- [~] Sampling — deferred stub: returns `{-32601, "Sampling not implemented"}` by default;
  opt-in via `McpServerConfig.Builder.samplingEnabled(true)`.  See ADR-0022 §9a.
- [~] Elicitation — designed in ADR-0022 §1–§8; implementation is follow-up work.
- [ ] Async API.
- [ ] Typed schema model.

## Conformance verification

External client interoperability is verified through two complementary approaches:

1. **Custom interop harness** (`harness/test_http_client.py`, `harness/test_stdio_client.py`) —
   simulates independent MCP clients making HTTP and STDIO requests; covers all major protocol
   methods, header routing, and error cases.

2. **Official MCP conformance CLI** (`@modelcontextprotocol/conformance`) —
   runs the spec-authoritative test suite; CI jobs run both `active` and `draft` (2026-07-28)
   suites.  Known failures are tracked in `conformance-baseline.yml`.

See [MCP-CONFORMANCE-MATRIX.md](./MCP-CONFORMANCE-MATRIX.md) for the full feature-by-feature
compliance status and the official conformance CLI integration guide.
