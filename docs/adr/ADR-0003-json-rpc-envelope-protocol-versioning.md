# ADR-0003 — JSON-RPC 2.0 Envelope, Protocol Versioning, and Capability Advertisement

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

The MCP protocol uses JSON-RPC 2.0 over HTTP. Clients and servers must agree on a protocol version, and the server must advertise its capabilities. Some JSON-RPC fields are polymorphic (e.g. `params` can be an array, object, or null), and protocol versions change over time.

## Decisions

### JSON-RPC 2.0 enforcement

The protocol handler validates the JSON-RPC envelope strictly:

- `jsonrpc` field must be exactly `"2.0"`.
- Requests with an `id` receive a JSON-RPC response body.
- Notifications (no `id`) receive no response body — HTTP status 200 with empty body.
- Invalid envelopes return `{"jsonrpc": "2.0", "error": {"code": -32600, "message": "Invalid Request"}, "id": null}`.

### Protocol version negotiation

The server supports a fixed set of protocol versions defined in `McpServerConfig`:

```java
.version("2025-11-25")  // default
```

The `initialize` handler validates the client's requested version:

- If the client requests `null` or an empty string: the server's configured version is used.
- If the client requests a supported version: that version is used.
- If the client requests an unsupported or non-string value: `Invalid params` error is returned.

Version strings are compared by exact equality against the configured version and a hardcoded list of accepted legacy versions.

### Capability advertisement

Capabilities are advertised in the `initialize` response based on `McpServerConfig` flags:

| Capability  | Advertised when       |
| ----------- | --------------------- |
| `tools`     | `config.tools == true`  |
| `resources` | `config.resources == true` |
| `prompts`   | `config.prompts == true` |
| `logging`   | `config.logging == true`  |
| `completions` | `config.completions == true` |
| `tasks`     | `config.tasks == true`   |
| `experimental` | `config.experimental != null` |

Protected methods (tools, resources, prompts, logging, tasks, completions) return `Method not found` if their capability flag is `false`.

### `resources/subscribe` — capability coupling

`resources/subscribe` and `resources/unsubscribe` are available only when both `config.resources` and `config.resourceSubscriptions` are `true`. The capability advertisement reflects this coupling.

## Consequences

**Positive:**

- Strict envelope validation prevents malformed JSON-RPC from reaching application code.
- Capability coupling prevents clients from calling subscription methods when subscriptions are disabled.
- Protocol version comparison by exact string equality is deterministic and easy to audit.

**Negative:**

- The hardcoded list of accepted protocol versions requires a code change to add new versions.
- Experimental features are not validated against any schema.
- No graceful degradation: an unknown version is an error, not a fallback.
