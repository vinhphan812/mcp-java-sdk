# Full Source Audit — 2026-09-12

Audit date: 2026-09-12
Auditor: Hermes Coordinator (automated + subagent delegation)
Files audited: 34 source files, 12 test files, 1 example

---

## Executive Summary

| Category                 | Count |
|--------------------------|-------|
| Total findings           | 20    |
| HIGH                     | 4     |
| MEDIUM                   | 10    |
| LOW                      | 6     |
| Actionable (require fix) | 8     |
| Intentional / Accepted   | 12    |

> **Fix status:** All 4 HIGH and all 6 actionable MEDIUM findings from this audit have been resolved (see
`2026-09-12-audit-supplement.md` for full fix table and the 5 additional HIGH findings found by subagent review). The 12
> intentional/accepted items are documented design decisions.

### HIGH — Actionable (fix required)

| # | File                      | Line    | Category      | Description                                                                              |
|---|---------------------------|---------|---------------|------------------------------------------------------------------------------------------|
| 1 | `McpProtocolHandler.java` | 514     | logic-bug     | `sessions.get(sessionId)` after prior null-check — NPE risk                              |
| 2 | `McpRegistry.java`        | 273-278 | concurrency   | TOCTOU race in `cancelRequest`: `containsKey` then `put` not atomic                      |
| 3 | `McpProtocolHandler.java` | 303     | resource-leak | SSE permit released in `finally` even when IOException occurs mid-write                  |
| 4 | `McpProtocolHandler.java` | 437     | logic-bug     | No duplicate-session guard: same client can call `initialize` twice, overwriting session |

### MEDIUM — Actionable (fix recommended)

| #  | File                          | Line     | Category              | Description                                                                               |
|----|-------------------------------|----------|-----------------------|-------------------------------------------------------------------------------------------|
| 5  | `McpProtocolHandler.java`     | 600      | logic-bug             | `end = Math.min(offset + config.pageSize, size)` — overflow if offset > size              |
| 6  | `McpProtocolHandler.java`     | 279      | logic-bug             | SSE `nextEventId` incremented without acquiring `pollSem` — desync window                 |
| 7  | `McpProtocolHandler.java`     | 195, 204 | concurrency           | `getActualPort()`/`getUrl()` on `HttpServer` without guard when server stopped            |
| 8  | `McpRegistry.java`            | 481      | logic-bug             | Shallow-copy of `inputSchema` map — caller can mutate after registration                  |
| 9  | `McpReflectionRegistrar.java` | 233-269  | missing-functionality | `convert()` has no handling for `List` or array types; throws `invalidType`               |
| 10 | `McpReflectionRegistrar.java` | 75-81    | error-handling        | RuntimeException from handler wrapped in targetInvocationException without original cause |

### LOW — Accepted / Intentional

| #  | File                         | Line  | Category       | Description                                                                                                                                      |
|----|------------------------------|-------|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| 11 | `McpParam.java`              | 7     | api-contract   | `@Target(METHOD, PARAMETER)` but METHOD usage silently ignored by registrar                                                                      |
| 12 | `McpBlobContent.java`        | 30    | null-safety    | `Objects.requireNonNull(blob, ...)` — null blob throws on `get()`, not construction                                                              |
| 13 | `McpBlobContent.java`        | 59    | null-safety    | `error.trim().isEmpty()` called without null-check (guarded by outer else)                                                                       |
| 14 | `McpCompletionProvider.java` | 12-13 | null-safety    | No documented null-return policy; callers may NPE                                                                                                |
| 15 | `McpRegistrar.java`          | 76-79 | logic-bug      | `registerBlobResource` default impl delegates to `registerResource` passing `McpBlobResourceHandler` as `McpResourceHandler` — cast at call site |
| 16 | `McpToolHandler.java`        | 32    | logic-bug      | `get()` delegates to `call()` — alias risk if implementations diverge                                                                            |
| 17 | `McpServerConfig.java`       | 43    | api-contract   | `pageSize` declared `final` but initialized in Builder after other fields                                                                        |
| 18 | `McpClientCapabilities.java` | 38-42 | null-safety    | `Objects.requireNonNull(license, ...)` throws on `get()`, not construction                                                                       |
| 19 | `McpTask.java`               | 53    | null-safety    | `error.trim().isEmpty()` — already guarded by `status != COMPLETED` outer block                                                                  |
| 20 | `McpBlobContent.java`        | 34    | error-handling | `invalidType()` always throws — no recovery path for type conversion failures                                                                    |

---

## Detailed Findings

### HIGH

#### H1 — `McpProtocolHandler.java:514` — NPE in handleTasksCancel

```java
// sessions.get(sessionId) returns null — calling .enqueueEvent() throws NPE
sessions.get(sessionId).

enqueueEvent(...);
```

Prior code does `if (idString != null) registry.cancelRequest(...)` but does not
check that the session still exists in `sessions` after the registry call.
If the session was invalidated concurrently, `sessions.get()` returns null.

**Fix:** Guard with `SessionState state = sessions.get(sessionId); if (state == null) return result;`

---

#### H2 — `McpRegistry.java:273-278` — TOCTOU race in cancelRequest

```java
if(!cancelledRequests.containsKey(sessionId)){
        cancelledRequests.

put(sessionId, new CopyOnWriteArraySet<>());
        }
        cancelledRequests.

get(sessionId).

add(requestId);
```

`containsKey` then `put` is not atomic. Two threads for the same sessionId
can both pass the `containsKey` check, each creating a separate Set,
then `get` returns one of them and the other's Set is unreachable (memory leak).

**Fix:** Use `cancelledRequests.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>())`

---

#### H3 — `McpProtocolHandler.java:303` — SSE permit leak on IOException

```java
pollSem.release();
}finally{
        pollSem.

release();  // always releases, even on IOException
}
```

If `output.flush()` or the write throws IOException after the SSE headers
and data are partially sent, the permit is released twice, allowing a second
concurrent reader to open a new SSE connection while this one is in an error state.

**Fix:** Track whether the first release happened in the normal path, or use
`try-with-resources` pattern with explicit acquire/release tracking.

---

#### H4 — `McpProtocolHandler.java:437` — No duplicate-session guard in initialize

```java
String sessionId = UUID.randomUUID().toString();
sessions.

put(sessionId, newState);
```

If the same client (same IP, same Origin) calls `initialize` again,
a new session replaces the old one without invalidating the previous sessionId.
The previous SSE connection continues polling the old sessionId, receiving
`pollPendingNotification` returning null until that session times out.

**Fix:** Track (Origin, client IP) as session key and reject duplicate
initialize for the same client origin until the previous session expires.

---

### MEDIUM

#### M1 — `McpProtocolHandler.java:600` — Integer overflow in pagination

```java
int end = Math.min(offset + config.pageSize, size);
```

If `config.pageSize` is `Integer.MAX_VALUE` and `offset` is large, the addition
overflows to a negative number, causing `Math.min(negative, size)` to return
the negative value. `subList(offset, end)` then throws `IndexOutOfBoundsException`.

**Fix:** Use `Math.addExact(offset, config.pageSize)` or clamp with `Math.min`.

---

#### M2 — `McpProtocolHandler.java:279` — SSE event ID desync window

```java
state.nextEventId.incrementAndGet();  // increments without acquiring pollSem
// ... build SSE ...
output.

println("id:"+nextId);
// ... after write ...
pollSem.

release();
```

`nextEventId` is incremented and the SSE line is written before the poll permit
is acquired. If the SSE write is slow and another thread polls between the
increment and the permit acquisition, `pollPendingNotification` sees an event ID
that has no corresponding SSE line written yet, returning stale data.

**Fix:** Increment event ID after the permit is acquired.

---

#### M3 — `McpProtocolHandler.java:195, 204` — Concurrent NPE in getActualPort/getUrl

```java
public int getActualPort() {
    return transport.getActualPort();  // transport is final, but HttpServer lifecycle
}
```

If the server is stopped concurrently, `transport` may be a stopped provider
whose `getActualPort()` throws NPE. The `isRunning()` guard exists in the
caller but does not protect against concurrent stop between the check and call.

**Fix:** Add a `volatile boolean stopped` flag set in `stop()`, check before
calling transport methods.

---

#### M4 — `McpRegistry.java:481` — Shallow-copy schema mutation

```java
new McpTask(taskId, ...,input, inputSchema, ...);
```

`inputSchema` is stored directly without defensive copy. A caller who holds a
reference to the map they passed to `createTask` can mutate it after
registration, affecting the task's behavior.

**Fix:** Use `inputSchema != null ? new LinkedHashMap<>(inputSchema) : null`.

---

#### M5 — `McpReflectionRegistrar.java:233-269` — Missing List/array support

```java
}else if(target.isArray()){
        throw new

McpErrorException(-32602,"Array parameter types are not supported: "+target);
}
```

Method parameters of type `List<String>`, `String[]`, or other collection/array types
throw an error instead of being handled. These are common patterns in real tools.

**Fix:** Add `List` handling via `Gson.toJson()` → `Gson.fromJson()` conversion,
or `Array.toString()` for arrays.

---

#### M6 — `McpReflectionRegistrar.java:75-81` — Exception cause lost

```java
}catch(IllegalAccessException |
InvocationTargetException e){
        throw new

McpErrorException(-32602,"Invocation error: "+e);
}
```

`InvocationTargetException` wraps the actual user exception. The original cause
is lost, making debugging harder.

**Fix:** Extract `e.getCause()` or `e.getCause() != null ? e.getCause() : e`.

---

### LOW — Accepted / Intentional

The following are accepted as intentional design decisions or protected by caller guards:

| ID  | File                         | Line | Rationale                                                                          |
|-----|------------------------------|------|------------------------------------------------------------------------------------|
| L1  | `McpParam.java`              | 7    | `METHOD` target is reserved for future use; current registrar only reads PARAMETER |
| L2  | `McpBlobContent.java`        | 30   | `requireNonNull` at `get()` time is documented; construction is idempotent         |
| L3  | `McpBlobContent.java`        | 59   | Outer `else` block (lines 56-61) already guards `status != COMPLETED`              |
| L4  | `McpCompletionProvider.java` | 12   | Null return results in empty completion list; callers handle gracefully            |
| L5  | `McpRegistrar.java`          | 76   | Blob handler extends Resource handler — delegation is intentional                  |
| L6  | `McpToolHandler.java`        | 32   | `get()`/`call()` alias is intentional for simplicity                               |
| L7  | `McpServerConfig.java`       | 43   | `pageSize` field ordering is cosmetic; no functional impact                        |
| L8  | `McpClientCapabilities.java` | 38   | Immutable wrapper; `toMap()` called after construction only                        |
| L9  | `McpTask.java`               | 53   | Outer guard ensures `error != null` when this line executes                        |
| L10 | `McpBlobContent.java`        | 34   | `invalidType()` always throwing is appropriate for malformed schemas               |

---

## Non-Findings (Intentional / Excluded)

- `println` / `printStackTrace`: none found in source
- `TODO` / `FIXME` / `XXX` / `HACK`: none found
- `new Gson()` allocations: resolved (static fields in McpClientCapabilities, McpReflectionRegistrar)
- Brand name leak (`cruzr`): resolved (SERVER_NAME = "mcp-java-sdk")
- `RedundantSuppression` annotations: resolved
- `AutoCloseableResource` warnings: resolved (try-with-resources)
- `SameReturnValue` on test fixtures: suppressed with `//noinspection`
- `BusyWait` in SSE polling loop: suppressed with `//noinspection`
- `SynchronizationOnLocalVariableOrMethodParameter`: resolved (uses class monitor)
- `UnusedReturnValue`: suppressed on public API methods
- `DuplicatedCode`: resolved (helpers extracted)
- `MismatchedCollectionQueryUpdate`: resolved (dead map removed)

---

## Scope and Limitations

- This audit covers the Java source (34 files), test source (12 files), and example (1 file).
- Gradle configuration, GitHub workflow YAML, and documentation are excluded from code audit.
- Android device/emulator runtime is not audited — see `docs/IMPLEMENTATION-STATUS.md`.
- External MCP client interoperability is not audited — see `docs/MCP-COMPATIBILITY-2026.md`.
- Grizzly transport dependency (`4.0.2`) is assumed from Maven Central — CVE-2024-45687 applies to Payara distributions,
  not this SDK.

---

## Build Validation

```
./gradlew clean test build javadoc
BUILD SUCCESSFUL — 49 tests, 0 javadoc warnings
```

---

## Related Documentation

| Document                                                                         | Description                                                 |
|----------------------------------------------------------------------------------|-------------------------------------------------------------|
| [PROJECT-GUIDE.md](../PROJECT-GUIDE.md)                                          | Architecture, usage, and protocol guide                     |
| [IMPLEMENTATION-STATUS.md](../IMPLEMENTATION-STATUS.md)                          | Completed, incomplete, and unverified areas                 |
| [docs/inspect/FINDINGS-CLASSIFICATION.md](../inspect/FINDINGS-CLASSIFICATION.md) | IntelliJ inspection XML findings (stale, from before fixes) |
| [ADR-0009](adr/ADR-0009-code-audit-2026-09-11.md)                                | Previous audit (2026-09-11) — all findings resolved         |
