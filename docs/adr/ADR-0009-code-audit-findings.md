# ADR-0009 — Code Audit Findings (2026-09-11)

**Status:** Accepted
**Date:** 2026-09-11
**Authors:** MCP Java SDK team

## Context

An independent code audit was conducted on all 29 production Java files after the P0/P1
implementation was completed. The audit checked import correctness, null-safety,
exception handling, resource leaks, concurrency safety, API contracts, and logic correctness.

## Decisions

### 1. Fix number-overflow in parameter conversion

**Finding:** `McpReflectionRegistrar.convert()` silently truncated numeric parameters
when a `Number` argument overflowed the target type (Byte, Short, Integer).
A handler declaring `int` would receive a truncated value if the caller passed a
Long outside Integer range.

**Decision:** Add range validation. The 3 affected types now throw
`IllegalArgumentException` with a descriptive message before truncation occurs.
Float/Double and Long are accepted without range checks.

### 2. Fix task result field omission

**Finding:** `McpTask.toMap()` omitted the `result` key entirely for non-completed
tasks. A client receiving the JSON could not distinguish "result field absent" from
"result is null" — both serialize identically but have different semantic meaning in
typed parsers.

**Decision:** Always include `result` in the output map. The field is `null` for
WORKING, FAILED, and CANCELLED states; it holds the value for COMPLETED.

### 3. Add `apiKeySupplier(Supplier<String>)` to transport and server builder

**Finding:** `McpServer.Builder` only exposed `apiKey(String)`, which embeds the
secret in the builder instance. Applications needing rotating secrets had no supported path.

**Decision:** Add `apiKeySupplier(Supplier<String>)` to both
`HttpTransportProvider` and `McpServer.Builder`.
The plain `apiKey(String)` overload now delegates to `() -> value`.
Application secrets must not be hard-coded; suppliers can read from environment
variables or secret stores.

## Accepted non-decisions (documented as acceptable)

The following findings were reviewed and accepted without code changes:

### Concurrency: queue size check in SSE polling

`pendingEvents.size() > 0` followed by `pendingEvents.poll()` is not
atomic. In the worst case the queue is drained between the check and the poll,
causing one unnecessary sleep-cycle before the next check.
This is benign: no data is lost and the event is delivered on the next poll.
The race window is sub-millisecond and SSE delivery has a 1-second polling
interval. Fixing this with finer-grained locking or compare-and-set would add
complexity without practical benefit.

### Long ID precision in JSON-RPC responses

Gson serializes Long as a JSON integer without loss of precision. The concern
about JavaScript's 53-bit safe integer limit applies only when a JSON number is
parsed by a JavaScript consumer — which is the caller's responsibility.
The MCP specification does not mandate JSON number range constraints.

### `inputSchema=null` creates empty schema

When `registerTool` is called with `inputSchema=null`, an empty schema is stored.
This is the intended behaviour for handlers that build schemas dynamically
or use only `@McpParam` annotations. Callers who pass `null` explicitly are opting
into this contract. No change.

### Redundant Set import in builder method

`allowedOrigins(Set<String>)` re-imports `java.util.Set` in the method signature.
This is a style preference, not an error. Not changed.

## Severity summary

| Severity  |  Count | Fixed | Accepted |
|-----------|-------:|------:|---------:|
| HIGH      |      3 |     2 |        1 |
| MEDIUM    |      4 |     1 |        3 |
| LOW       |     11 |     0 |       11 |
| **Total** | **18** | **3** |   **15** |

## Verification

```bash
./gradlew.bat --no-daemon clean test --console=plain
BUILD SUCCESSFUL
46 tests passed, 0 failures
```

---

**Superseded by** [2026-09-12-full-source-audit.md](../audits/2026-09-12-full-source-audit.md)
and [2026-09-12-audit-supplement.md](../audits/2026-09-12-audit-supplement.md).
