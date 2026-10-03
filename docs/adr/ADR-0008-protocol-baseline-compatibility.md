# ADR-0008 — Protocol Baseline and Compatibility Scope

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

The MCP specification evolves. New protocol versions add features and change wire behaviour. A library must decide which
protocol versions to support, what scope of the protocol to implement, and how to communicate its limitations to
consumers.

## Decision

### Protocol baseline

The library targets MCP `2025-11-25` as the default sessioned mode and also supports configurable STATELESS MCP
`2026-07-28`. STATELESS mode does not require an MCP session for initialize or subsequent requests. This implementation
is not a claim of full MCP certification or external-client interoperability.

### Implemented scope

| Category                                     | Implemented   | Not implemented |
|----------------------------------------------|---------------|-----------------|
| JSON-RPC 2.0 envelope                        | Yes           | —               |
| `initialize` + session                       | Yes           | —               |
| `tools/list`, `tools/call`                   | Yes           | —               |
| `resources/list`, `resources/read`           | Yes           | —               |
| Resource templates                           | Yes           | —               |
| `prompts/list`, `prompts/get`                | Yes           | —               |
| `completion/complete`                        | Yes           | —               |
| `logging/setLevel` + `notifications/message` | Yes           | —               |
| `tasks/get`, `tasks/result`, `tasks/cancel`  | Yes (bounded) | `tasks/create`  |
| List-changed notifications                   | Yes           | —               |
| Pagination (cursor)                          | Yes           | —               |
| `Last-Event-ID` replay                       | Yes           | —               |
| Resource blob content                        | Yes           | —               |
| Tool `outputSchema`                          | Yes           | —               |
| Sampling                                     | No            | Yes             |
| Elicitation                                  | Partial       | Yes (full flow) |
| Progress/cancellation                        | No            | Yes             |
| `tasks/create`                               | No            | Yes             |
| Async server API                             | No            | Yes             |
| STDIO transport                              | No            | N/A — not provided |
| Typed schema model                           | Partial       | Yes             |

### Compatibility strategy

1. **Capability flags** — each capability (tools, resources, prompts, logging, completions, tasks) is individually
   configurable. Disabled capabilities return `Method not found`.
2. **Unknown methods** — any unrecognized JSON-RPC method returns `Method not found`; the server does not crash or
   produce invalid JSON-RPC.
3. **Parameter validation** — invalid params return `Invalid params`; type mismatches return `Invalid params`; missing
   required args return `Invalid params`.
4. **Protocol version enforcement** — unsupported protocol versions in `initialize` return an error; the server never
   advertises a version it does not actually support.

## Consequences

**Positive:**

- The scope is explicit and auditable.
- Capability flags allow consumers to control the surface area.
- Unknown method handling prevents a broken client from crashing the server.

**Negative:**

- Partial MCP compliance may confuse consumers who expect full support.
- The protocol version list is hardcoded; adding new versions requires a code change.
- External client interoperability is verified through two complementary approaches:
  (1) custom interop harness covering HTTP (`harness/`), and
  (2) the official MCP conformance test suite (`@modelcontextprotocol/conformance`) run in CI.
  Known conformance gaps are tracked in `conformance-baseline.yml`.
