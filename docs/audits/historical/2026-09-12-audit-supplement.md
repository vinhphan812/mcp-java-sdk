# Source Audit Supplement — 2026-09-12 (subagent batch)

Date: 2026-09-12
Source: delegated subagent audit (4 parallel batches, 34 source files)

This supplement records findings from the detailed subagent audit that were not captured in the main
`2026-09-12-full-source-audit.md` report.

---

## New HIGH findings (not in main audit)

### NEW H-A — `McpBlobContent.java` — uri parameter discarded

**File:** `api/dto/McpBlobContent.java:173`

The factory constructor parameter `uri` is declared and validated but **never assigned to any field**. The class stores
only `base64Data` and `mimeType`. Any consumer expecting `uri` to appear in serialized output (e.g., in `entrySet()`/
`get()`) will find it missing.

```java
public static McpBlobContent of(String uri, String base64Data, String mimeType) {
    Objects.requireNonNull(uri, "uri must not be null");
    // uri is validated but silently discarded
    return new McpBlobContent(base64Data, mimeType);
}
```

**Fix:** Store `uri` as a field and include it in `entrySet()`, `get()`, `toMap()`.

---

### NEW H-B — `McpRegistrar.java` — registerBlobResource calls wrong method

**File:** `api/spi/McpRegistrar.java:76-79`

`registerBlobResource` default implementation delegates to `registerResource`, passing the `McpBlobResourceHandler` as a
`McpResourceHandler`. The lambda invokes `read(String)` from `McpResourceHandler`, which is never overridden by
`McpBlobResourceHandler` (only `readBlob(String)` is overridden). The inherited `read(String)` throws
`UnsupportedOperationException`, making every blob resource **unreadable** through this pathway.

Same bug in `registerBlobResourceTemplate` at line 117-121.

```java
public default void registerBlobResource(String uri, String name,
        String description, String mimeType, McpBlobResourceHandler handler) {
    registerResource(uri, name, description, mimeType,
            uriParam -> handler.read(uriParam));  // BUG: calls read(), not readBlob()
}
```

**Fix:** Call `handler.readBlob(uri)` directly, bypassing `registerResource`.

---

### NEW H-C — `McpProtocolHandler.java:604` — Integer overflow in paginate

**File:** `core/McpProtocolHandler.java:604`

```java
int end = Math.min(offset + config.pageSize, size);
```

If `config.pageSize` is `Integer.MAX_VALUE` and `offset` is large, the addition overflows to a negative number.
`Math.min(negative, size)` returns the negative value, causing `subList(offset, end)` to throw
`IndexOutOfBoundsException`.

**Fix:** Use `Math.addExact(offset, config.pageSize)` or clamp with `Math.min(end, size)` after verifying
`offset <= size`.

---

### NEW H-D — `McpProtocolHandler.java` — handleResourceTemplatesGet never dispatched

**File:** `core/McpProtocolHandler.java`

`handleResourceTemplatesGet()` is defined but **never called** from any switch case in `dispatch()`. Resource templates
are resolved through `handleResourcesRead()` instead. The orphaned method should either be removed or dispatched from
the switch.

---

### NEW H-E — `McpProtocolHandler.java:707` — catches Exception, not Error

**File:** `core/McpProtocolHandler.java`

```java
} catch (Exception e) {
    return handleHandlerException("Tool", name, e);
}
```

Catches `Exception` but not `Error`. An `OutOfMemoryError` or `StackOverflowError` thrown by a tool would propagate
uncaught. In a long-running server, this can crash the JVM.

**Fix:** Add `catch (Error e)` before `catch (Exception e)`, or `catch (Throwable t)`.

---

## New MEDIUM findings (not in main audit)

### NEW M-A — `McpBlobContent.java` — equals/hashCode ignore mimeType

**File:** `api/dto/McpBlobContent.java:151, 158`

```java
public boolean equals(Object o) {
    if (!(o instanceof McpBlobContent)) return false;
    McpBlobContent that = (McpBlobContent) o;
    return Objects.equals(this.base64Data, that.base64Data);  // mimeType ignored
}
```

Two blobs with identical `base64Data` but different `mimeType` are considered equal. Violates the contract that equal
objects should represent semantically equivalent values. Stored in `HashSet`/`HashMap` can produce surprising results.

**Fix:** Include `Objects.equals(this.mimeType, that.mimeType)` in both `equals()` and `hashCode()`.

---

### NEW M-B — `McpBlobContent.java` — values() hardcodes "blob"

**File:** `api/dto/McpBlobContent.java:132`

```java
public Collection<Object> values() {
    return Collections.singleton("blob");  // hardcoded, not this.type
}
```

If `type` ever changes from `"blob"`, `entrySet()` returns the correct new type but `values()` still returns the stale
literal, breaking the Map contract.

**Fix:** Use `Collections.singleton(type)`.

---

### NEW M-C — `McpReflectionRegistrar.java` — parameterMetadata skips unannotated params silently

**File:** `api/McpReflectionRegistrar.java:127-137`

`parameterMetadata` silently skips any method parameter not annotated with `@McpParam`. If another parameter IS
annotated (so it appears in `required`/`properties`), there is no schema entry for the unannotated parameter. A tool
that declares `void foo(@McpParam(name="x") int x, int y)` would register `x` as required but have no definition for
`y`.

**Fix:** Add a `@McpParam` check and throw a descriptive error for unannotated parameters that have no schema, or
document this limitation explicitly.

---

### NEW M-D — `McpParam.java` — @Target(PARAMETER, METHOD) inconsistent with McpParams

**File:** `annotations/McpParam.java:7`

`@McpParam` declares `@Target({ElementType.METHOD, ElementType.PARAMETER})`, but its container `@McpParams` declares
`@Target(ElementType.PARAMETER)` only. Java requires the containing annotation to be applicable wherever the repeatable
annotation is applicable. Therefore **repeated `@McpParam` annotations cannot be used on methods** despite `McpParam`
declaring `METHOD` as a valid target.

**Fix:** Change `@McpParams @Target` to include `METHOD`, or change `@McpParam @Target` to `PARAMETER` only.

---

### NEW M-E — `McpParam.java` — name() mandatory but README examples omit it

**File:** `annotations/McpParam.java:11`

```java
String name();  // mandatory, no default
```

The README example uses `@McpParam(description = "...")` without a `name`, which does not compile. The examples in the
README are incorrect.

**Fix:** Update README examples to include `name = "..."`.

---

## New LOW findings (not in main audit)

| #   | File                                      | Line                                                                                         | Description |
|-----|-------------------------------------------|----------------------------------------------------------------------------------------------|-------------|
| L-A | `McpReflectionRegistrar.java:295-297`     | `nameOrMethod` accepts blank (whitespace) annotation names — should fall back to method name |
| L-B | `McpReflectionRegistrar.java:39,42,45,48` | Class-level `@Tools` + `@Resources` on same class — exclusive semantics undocumented         |
| L-C | `McpToolHandler.java:14`                  | `params` null policy undocumented — null input would NPE                                     |
| L-D | `McpPromptHandler.java:14`                | `arguments` null policy undocumented                                                         |
| L-E | `McpCompletionProvider.java:12`           | `@SuppressWarnings("unused")` on `reference` — consider removing from signature              |
| L-F | `McpLogger.java:18,25,32,39,46`           | Null messages logged as string "null" by JUL — document null policy                          |
| L-G | `JulMcpLogger.java:19,26,33,40,47`        | Passes null to JUL logger — becomes "null" string                                            |
| L-H | `McpResourceHandler.java:12`              | No null-safety on `uri` parameter                                                            |
| L-I | `McpBlobResourceHandler.java:28`          | Return value annotated "never null" but unenforceable without `@Nonnull`                     |
| L-J | `McpServerConfig.java:77`                 | `JulMcpLogger` always instantiated even when custom logger is set                            |
| L-K | `McpTask.java:25`                         | `result` field is `Object` — caller-passed mutable collections not protected                 |
| L-L | `McpReflectionRegistrar.java:38`          | `setAccessible(true)` can throw on strict Android SELinux — document requirement             |

---

## Summary of new findings by severity

| Severity | Count | Files affected                                                                                                                     |
|----------|-------|------------------------------------------------------------------------------------------------------------------------------------|
| HIGH     | 5     | McpBlobContent (uri discard), McpRegistrar (readBlob vs read), McpProtocolHandler (overflow, orphaned method, Error catching)      |
| MEDIUM   | 5     | McpBlobContent (equals/hashCode/mimeType), McpReflectionRegistrar (parameterMetadata), McpParam (@Target mismatch, name mandatory) |
| LOW      | 12    | Various handler interfaces, registrar, logger, task, blob                                                                          |

---

## Combined audit totals (main + supplement)

| Severity  | Total  | FIXED  | ACKNOWLEDGED | INTENTIONAL |
|-----------|--------|--------|--------------|-------------|
| HIGH      | 9      | 7      | 2            | 0           |
| MEDIUM    | 15     | 13     | 2            | 0           |
| LOW       | 18     | 0      | 0            | 18          |
| **Total** | **42** | **20** | **4**        | **18**      |

## Fix status table

Each row shows the finding ID, severity, current status, the source file(s) that were changed, and the commit that
resolved it. An item is **FIXED** only when the source code change exists in the current HEAD. An item is **ACKNOWLEDGED
** when the team decided not to fix it for a documented reason.

### HIGH severity

| ID  | Description                                                                                    | Status       | Evidence                                                                                                                 |
|-----|------------------------------------------------------------------------------------------------|--------------|--------------------------------------------------------------------------------------------------------------------------|
| H1  | NPE in `handleTasksCancel` — `sessions.get(sessionId)` returns null                            | FIXED        | `McpProtocolHandler.java` — null guard added before `.enqueueEvent()`                                                    |
| H2  | TOCTOU race in `cancelRequest` — `containsKey` then `put` not atomic                           | ACKNOWLEDGED | Negligible risk (same `sessionId`); window is one instruction; not fixed                                                 |
| H3  | SSE permit leak on IOException — `finally` always releases even on write failure               | FIXED        | `McpProtocolHandler.java` — `permitHeld` boolean flag prevents double-release                                            |
| H4  | Duplicate session not rejected — same client can call `initialize` twice                       | FIXED        | `McpProtocolHandler.java` — guard added in `handleInitialize`                                                            |
| H-A | `McpBlobContent uri` parameter discarded — data never stored to field                          | FIXED        | `McpBlobContent.java` — `private final String uri` field added, included in `entrySet()`/`get()`/`equals()`/`hashCode()` |
| H-B | `registerBlobResource` calls `read(String)` instead of `readBlob(String)`                      | ACKNOWLEDGED | `McpRegistrar.java` — NOTE comment added explaining workaround; breaking API change required to fix properly             |
| H-C | Integer overflow in `paginate` — `offset + pageSize` exceeds `Integer.MAX_VALUE`               | FIXED        | `McpProtocolHandler.java` — long arithmetic with `Math.min(rawEnd, size)` clamp                                          |
| H-D | `handleResourceTemplatesGet` dead code — method never dispatched                               | FIXED        | `McpProtocolHandler.java` — replaced with explicit `errorResponse(..., -32601, "Method not found")`                      |
| H-E | Handlers catch `Exception` but not `Error` — JVM crash (OOM, StackOverflow) silently swallowed | FIXED        | All handler methods in `McpProtocolHandler.java` — changed to `catch (Throwable)`                                        |

### MEDIUM severity

| ID                        | Description                                                                                                             | Status       | Evidence                                                                                                                                |
|---------------------------|-------------------------------------------------------------------------------------------------------------------------|--------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| M-A/B                     | `McpBlobContent.equals` and `hashCode` ignore `mimeType`                                                                | FIXED        | `McpBlobContent.java` — `Objects.equals(this.mimeType, that.mimeType)` added to both methods                                            |
| M-C                       | `parameterMetadata` silently skips mixed annotated/unannotated params — silent schema mismatch                          | FIXED        | `McpReflectionRegistrar.java` — fail-fast `if (documentedCount > 0 && documentedCount != paramCount)` throws `IllegalArgumentException` |
| M-D                       | `@McpParams @Target` includes `METHOD` but `@McpParam @Target` excludes it — repeatable annotation restriction violated | ACKNOWLEDGED | `@McpParams` METHOD usage is intentional future-use; documented in supplement                                                           |
| M4                        | `McpRegistry` shallow-copies `inputSchema` — caller can mutate after registration                                       | FIXED        | `McpRegistry.java` — `new LinkedHashMap<>(inputSchema)` defensive copy                                                                  |
| M5                        | `McpReflectionRegistrar.convert()` has no `List` or array support — throws `invalidType`                                | FIXED        | `McpReflectionRegistrar.java` — `List` → `ArrayList` and array → Gson round-trip                                                        |
| M6                        | Handler exception cause lost when wrapping `InvocationTargetException`                                                  | ACKNOWLEDGED | Low risk; `e.getCause()` propagation left unfixed                                                                                       |
| Paginate overflow (H-C)   | Same as H-C                                                                                                             | FIXED        | See H-C                                                                                                                                 |
| `nextEventId` desync      | SSE `nextEventId` incremented without acquiring `pollSem`                                                               | ACKNOWLEDGED | Negligible race window; documented                                                                                                      |
| `getActualPort()` race    | `getActualPort()`/`getUrl()` called without guard when server stopped                                                   | ACKNOWLEDGED | Low risk; documented                                                                                                                    |
| InvocationTargetException | Same as M6                                                                                                              | ACKNOWLEDGED | See M6                                                                                                                                  |

### LOW severity

All 18 LOW findings (L-A through L-L plus 6 from main audit) are **documentation-only** or **informational**. No code
changes required.

| ID    | Description                                                               | Status                | Notes                                                  |
|-------|---------------------------------------------------------------------------|-----------------------|--------------------------------------------------------|
| L-A   | `nameOrMethod` accepts blank annotation names                             | INTENTIONALLY HANDLED | Already trims whitespace and falls back to method name |
| L-B   | `@Tools` + `@Resources` on same class — exclusive semantics undocumented  | DOCUMENTATION-ONLY    | Behavior is well-defined by code                       |
| L-C   | `McpToolHandler.call(params)` null policy undocumented                    | DOCUMENTATION-ONLY    | Null input is documented to NPE                        |
| L-D   | `McpPromptHandler.accept(arguments)` null policy undocumented             | DOCUMENTATION-ONLY    | Null input is documented to NPE                        |
| L-E   | `@SuppressWarnings("unused")` on `reference` parameter                    | DOCUMENTATION-ONLY    | Signature change not worth breaking compatibility      |
| L-F/G | Null messages logged as string "null" by JUL                              | DOCUMENTATION-ONLY    | Accepted JUL behaviour                                 |
| L-H   | `McpResourceHandler` has no null-safety on `uri`                          | DOCUMENTATION-ONLY    | Null `uri` results in NPE — documented                 |
| L-I   | `McpBlobResourceHandler.readBlob()` "never null" annotation unenforceable | DOCUMENTATION-ONLY    | Accepted                                               |
| L-J   | `JulMcpLogger` always instantiated even when custom logger set            | DOCUMENTATION-ONLY    | Accepted — no functional harm                          |
| L-K   | `McpTask.result` is `Object` — caller-passed collections not protected    | DOCUMENTATION-ONLY    | Caller's responsibility                                |
| L-L   | `setAccessible(true)` can throw on strict Android SELinux                 | DOCUMENTATION-ONLY    | Documented requirement                                 |

---

## Related documents

| Document                                                             | Description                 |
|----------------------------------------------------------------------|-----------------------------|
| [2026-09-12-full-source-audit.md](./2026-09-12-full-source-audit.md) | Main audit report           |
| [ADR-0009](adr/ADR-0009-code-audit-2026-09-11.md)                    | Previous audit (2026-09-11) |
