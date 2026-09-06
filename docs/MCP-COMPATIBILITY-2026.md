# MCP Compatibility Plan

Status: baseline implementation documented; P1/P2 follow-up remains

Reference:

- https://modelcontextprotocol.io/docs
- https://modelcontextprotocol.io/specification/2026-07-28
- https://github.com/modelcontextprotocol/java-sdk

## Scope

This project is a lightweight Java 8-compatible MCP server implementation. It is not the official `modelcontextprotocol/java-sdk` and does not claim full feature parity.

## Target baseline

Target protocol baseline: `2025-11-25` (the latest stable baseline implemented by this Java 8 SDK). The newer documentation/specification snapshot `2026-07-28` was used for audit comparison but is not advertised as implemented.

The server must accept a client protocol version only when it is supported. Unsupported versions must return an initialize error rather than silently advertising a different version.

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
- Add an ephemeral-port live HTTP smoke test.

## P1 compatibility work

- Cursor pagination for list methods.
- `notifications/tools/list_changed`, `notifications/resources/list_changed`, and `notifications/prompts/list_changed`.
- SSE message IDs and `Last-Event-ID` resume behavior.
- Resource `blob` contents.
- Tool `outputSchema`, `structuredContent`, annotations, icons, and task metadata.
- `completion/complete`.
- Logging and progress/cancellation notifications.

## P2 compatibility work

- Sampling.
- Elicitation.
- Tasks.
- Async API.
- STDIO transport.
- Typed schema model.

## Compatibility limitations

Until P1/P2 work is complete, documentation must not claim full compliance with the latest MCP specification. Existing custom APIs remain source-compatible where possible, but wire behavior follows the target MCP specification.

## Validation

Required before accepting P0:

1. `./gradlew clean test build --console=plain`
2. Start Grizzly on port `0`.
3. Send real HTTP `initialize` request.
4. Read returned `Mcp-Session-Id` and negotiated protocol version.
5. Send `notifications/initialized` and verify no response body.
6. Send `tools/list`, `resources/list`, `resources/read`, `prompts/list`, and `prompts/get` using that session.
7. Verify invalid Origin, content type, accept header, and missing session behavior.
8. Stop server and verify port release.
