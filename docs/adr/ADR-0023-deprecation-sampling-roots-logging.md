# ADR-0023 — Deprecation Strategy: Sampling, Roots, and Logging Capabilities

**Status:** Accepted
**Date:** 2026-10-05
**Authors:** MCP Java SDK team
**Parent:** [ADR-0022](ADR-0022-server-to-client-mrtr-elicitation-foundation.md) (Section 9: 2026 Deprecation Posture)

---

## Context

ADR-0022 §9 established the deprecation posture for three P2 MCP capabilities — Sampling, Roots, and Logging — but the decisions were recorded only as prose within that design document. This ADR formally documents the strategy as a standalone record, provides the explicit migration guidance the SDK team needs, and replaces the prose decisions in ADR-0022 §9.

The MCP 2026-07-28 specification carries three capabilities that have divergent postures for this SDK:

- **Sampling** (`sampling/createMessage`) — defined in spec but out-of-scope for a server-only SDK.
- **Roots** (`roots/list`) — defined in spec but never implemented; implicitly excluded.
- **Logging** (`logging/setLevel`, `notifications/message`) — fully implemented and retained.

---

## Decision

### 1. Sampling — Deprecated / Stub-Only

**What is deprecated:** `sampling/createMessage` (server-side generation of content such as images or audio on behalf of the client).

**Why:** The primary value of this SDK is server-side. Sampling requires the client to generate content — a client-side operation that would need a separate client-side library to exercise meaningfully. Implementing sampling in the server does not add capability; providing a stub that always returns an error does not add capability either. Keeping the stub is the minimum viable posture.

**Current behaviour:**
- The SDK does **not** advertise `sampling` in the `initialize` capabilities response.
- `McpServerConfig.Builder.samplingEnabled(true)` is not present; there is no flag to opt in.
- If a client sends `sampling/createMessage`, `McpProtocolHandler` returns `{-32601, "Sampling not implemented"}`.
- `McpClientCapabilities.getSampling()` always returns `null`.

**Migration path:** None — this capability is intentionally absent. Clients that need sampling should use a full MCP client SDK (e.g., the official `modelcontextprotocol/java-sdk`). The SDK server plays the client role in bidirectional MCP; a server that itself acts as a client to generate content is outside this SDK's scope.

**Migration timeline:** No migration path exists. This is an explicit design exclusion, not a deprecated-but-available feature.

---

### 2. Roots — Implicitly Excluded / Not Implemented

**What is excluded:** `roots/list` (advertising filesystem roots or workspace boundaries to the client).

**Why:** The SDK does not have filesystem awareness. Roots is fundamentally a client-side concept (the client advertises what directories the server may access). As a server implementation, the SDK does not have a natural source of truth for which paths the server considers in-scope. Implementing a static configuration knob for roots adds coupling without clear benefit.

**Current behaviour:**
- The SDK does **not** advertise `roots` in the `initialize` capabilities response.
- There is no `roots/list` handler dispatch in `McpProtocolHandler`.
- There is no `McpServerConfig.Builder.rootsEnabled(...)` method.
- `McpClientCapabilities` has no roots fields.

**Migration path:** None — this capability is not implemented and is not planned.

**Migration timeline:** No migration path exists. This is a deliberate omission.

---

### 3. Logging — Retained and Stabilised

**What is retained:** `logging/setLevel` (client controls server-side log verbosity) and `notifications/message` (server emits log events to the client).

**Why:** Logging is a server-initiated notification that is useful for observability in production deployments. The client can ask the server to increase or decrease verbosity, and the server emits structured log messages back to the client. This is orthogonal to the client-side capabilities of Sampling and Roots, and is fully implementable without filesystem or content-generation dependencies.

**Current behaviour:**
- Advertised when `McpServerConfig.Builder.logging(true)` is set.
- `logging/setLevel` is dispatched via `McpMethodNames.LOGGING_SET_LEVEL` in `McpProtocolHandler`.
- `notifyLogMessage(level, source, data)` is available on `McpProtocolHandler` to emit log events.
- `notifications/message` flows through the SSE event queue.

**Future:** Logging is stable. No breaking changes are planned. The `McpLogger` SPI (`api/logging/McpLogger`) provides a portable logging interface; `JulMcpLogger` adapts `java.util.logging`.

---

## Consequences

### Positive
- The deprecation posture is explicit and unambiguous, not implicit prose.
- Consumers of the SDK can clearly distinguish what is implemented, what is excluded, and what is retained.
- The `McpClientCapabilities` API surface is accurate to what is actually received from clients.
- SDK documentation can correctly represent the protocol implementation status.

### Negative
- Clients that depend on Sampling or Roots will not find those capabilities here. They must use a full MCP SDK.
- There is no `samplingEnabled(true)` opt-in flag — the stub is always silent. Applications that need to report "sampling not available" in-band cannot distinguish "not implemented" from "not advertised".

### Neutral
- The `McpServerConfig` builder remains unchanged for Sampling and Roots (no dead flag sitting unused).
- The `McpClientCapabilities` class accurately reflects what the protocol carries; no stub fields are present.

---

## Alternatives Considered

### Sampling: implement a minimal stub that accepts requests but returns empty content
Rejected: returning an empty result could be mistaken for a working feature by a client. A silent `-32601` error is clearer. A flag to enable the stub was considered but would give a false impression of capability.

### Sampling: forward to the client via MRTR (server-initiated request)
Rejected: this would require the server to act as an MCP client, which is architecturally backwards for this SDK and is outside the scope of the elicitation/MRTR design (ADR-0022). MRTR is for server-initiated requests from server to client, not for the server acting as a client.

### Roots: implement as a static configuration list
Rejected: a static configuration that the server blindly advertises without any enforcement is misleading. Without enforcement, the capability is decorative. Without filesystem-awareness, the SDK has no natural source for what to advertise.

---

## References

- [ADR-0022 — MRTR Server-to-Client Foundation and Elicitation](ADR-0022-server-to-client-mrtr-elicitation-foundation.md)
- [docs/guides/ELICITATION.md](../guides/ELICITATION.md) — elicitation feature guide and async API examples
- [docs/guides/API-REFERENCE.md](../guides/API-REFERENCE.md) — protocol method table and capability configuration
- [docs/architecture/MCP-COMPATIBILITY-2026.md](../architecture/MCP-COMPATIBILITY-2026.md) — P2 compatibility status
