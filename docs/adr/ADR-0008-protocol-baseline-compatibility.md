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

The library targets MCP `2025-11-25` as the primary supported version. It also accepts `2025-03-26` for backward
compatibility.

The library does not claim support for newer snapshots (e.g. `2026-07-28`) and does not implement features from those
snapshots unless explicitly noted.

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
| STDIO transport                              | No            | Yes             |
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
- External client interoperability has not been verified.
