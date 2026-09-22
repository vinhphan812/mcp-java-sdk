# MCP Compatibility Plan

Status: P0 and P1 work complete; P2 follow-up remains. This is a compatibility plan, not a claim of certification or
external-client interoperability.

Reference documentation:

- [PROJECT-GUIDE.md](../guides/PROJECT-GUIDE.md) — architecture and usage guide.
- [API-REFERENCE.md](../guides/API-REFERENCE.md) — complete public API surface.
- [IMPLEMENTATION-STATUS.md](../guides/IMPLEMENTATION-STATUS.md) — completed, incomplete, and unverified areas.
- [GRIZZLY-EXAMPLE.md](../guides/GRIZZLY-EXAMPLE.md) — standalone Grizzly example.
- [MCP-PORTING-PLAN.md](./MCP-PORTING-PLAN.md) — package inventory and porting notes.
- [docs/adr/](../adr/) — architecture decision records documenting key design choices.

## Scope

This project is a lightweight Java 8-compatible MCP server implementation. It is not the official
`modelcontextprotocol/java-sdk` and does not claim full feature parity.

## Target baseline

Target protocol baseline: `2025-11-25` (the latest stable baseline implemented by this Java 8 SDK). The newer
documentation/specification snapshot `2026-07-28` was used for audit comparison but is not advertised as implemented.

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
- [ ] Sampling.
- [ ] Elicitation.
- [ ] Async API.
- [ ] STDIO transport.
- [ ] Typed schema model.

## Compatibility limitations

Until P1/P2 work is complete, documentation must not claim full compliance with the latest MCP specification. Existing
custom APIs remain source-compatible where possible, but wire behaviour follows the target MCP specification.

## Validation

Local evidence currently includes `./gradlew.bat --no-daemon test --console=plain` with 49 passing tests, focused
protocol capability tests, security-matrix tests, pagination/list-change/task tests, and in-process Grizzly smoke tests.
This does not establish external client interoperability. The following remains the broader validation checklist:

1. `./gradlew clean test build --console=plain`
2. Start Grizzly on port `0`.
3. Send real HTTP `initialize` request.
4. Read returned `Mcp-Session-Id` and negotiated protocol version.
5. Send `notifications/initialized` and verify no response body.
6. Send `tools/list`, `resources/list`, `resources/read`, `prompts/list`, and `prompts/get` using that session.
7. Verify invalid Origin, content type, accept header, and missing session behaviour.
8. Stop server and verify port release.
