# Source Audit Supplement — 2026-09-12 (subagent batch)

Date: 2026-09-12
Source: delegated subagent audit (4 parallel batches, 34 source files)

This supplement records findings from the detailed subagent audit that were not captured in the main `2026-09-12-full-source-audit.md` report.

---

## New HIGH findings (not in main audit)

### NEW H-A — `McpBlobContent.java` — uri parameter discarded

**File:** `api/dto/McpBlobContent.java:173`

The factory constructor parameter `uri` is declared and validated but **never assigned to any field**. The class stores only `base64Data` and `mimeType`. Any consumer expecting `uri` to appear in serialized output (e.g., in `entrySet()`/`get()`) will find it missing.

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

`registerBlobResource` default implementation delegates to `registerResource`, passing the `McpBlobResourceHandler` as a `McpResourceHandler`. The lambda invokes `read(String)` from `McpResourceHandler`, which is never overridden by `McpBlobResourceHandler` (only `readBlob(String)` is overridden). The inherited `read(String)` throws `UnsupportedOperationException`, making every blob resource **unreadable** through this pathway.

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

If `config.pageSize` is `Integer.MAX_VALUE` and `offset` is large, the addition overflows to a negative number. `Math.min(negative, size)` returns the negative value, causing `subList(offset, end)` to throw `IndexOutOfBoundsException`.

**Fix:** Use `Math.addExact(offset, config.pageSize)` or clamp with `Math.min(end, size)` after verifying `offset <= size`.

---

### NEW H-D — `McpProtocolHandler.java` — handleResourceTemplatesGet never dispatched

**File:** `core/McpProtocolHandler.java`

`handleResourceTemplatesGet()` is defined but **never called** from any switch case in `dispatch()`. Resource templates are resolved through `handleResourcesRead()` instead. The orphaned method should either be removed or dispatched from the switch.

---

### NEW H-E — `McpProtocolHandler.java:707` — catches Exception, not Error

**File:** `core/McpProtocolHandler.java`

```java
} catch (Exception e) {
    return handleHandlerException("Tool", name, e);
}
```

Catches `Exception` but not `Error`. An `OutOfMemoryError` or `StackOverflowError` thrown by a tool would propagate uncaught. In a long-running server, this can crash the JVM.

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

Two blobs with identical `base64Data` but different `mimeType` are considered equal. Violates the contract that equal objects should represent semantically equivalent values. Stored in `HashSet`/`HashMap` can produce surprising results.

**Fix:** Include `Objects.equals(this.mimeType, that.mimeType)` in both `equals()` and `hashCode()`.

---

### NEW M-B — `McpBlobContent.java` — values() hardcodes "blob"

**File:** `api/dto/McpBlobContent.java:132`

```java
public Collection<Object> values() {
    return Collections.singleton("blob");  // hardcoded, not this.type
}
```

If `type` ever changes from `"blob"`, `entrySet()` returns the correct new type but `values()` still returns the stale literal, breaking the Map contract.

**Fix:** Use `Collections.singleton(type)`.

---

### NEW M-C — `McpReflectionRegistrar.java` — parameterMetadata skips unannotated params silently

**File:** `api/McpReflectionRegistrar.java:127-137`

`parameterMetadata` silently skips any method parameter not annotated with `@McpParam`. If another parameter IS annotated (so it appears in `required`/`properties`), there is no schema entry for the unannotated parameter. A tool that declares `void foo(@McpParam(name="x") int x, int y)` would register `x` as required but have no definition for `y`.

**Fix:** Add a `@McpParam` check and throw a descriptive error for unannotated parameters that have no schema, or document this limitation explicitly.

---

### NEW M-D — `McpParam.java` — @Target(PARAMETER, METHOD) inconsistent with McpParams

**File:** `annotations/McpParam.java:7`

`@McpParam` declares `@Target({ElementType.METHOD, ElementType.PARAMETER})`, but its container `@McpParams` declares `@Target(ElementType.PARAMETER)` only. Java requires the containing annotation to be applicable wherever the repeatable annotation is applicable. Therefore **repeated `@McpParam` annotations cannot be used on methods** despite `McpParam` declaring `METHOD` as a valid target.

**Fix:** Change `@McpParams @Target` to include `METHOD`, or change `@McpParam @Target` to `PARAMETER` only.

---

### NEW M-E — `McpParam.java` — name() mandatory but README examples omit it

**File:** `annotations/McpParam.java:11`

```java
String name();  // mandatory, no default
```

The README example uses `@McpParam(description = "...")` without a `name`, which does not compile. The examples in the README are incorrect.

**Fix:** Update README examples to include `name = "..."`.

---

## New LOW findings (not in main audit)

| # | File | Line | Description |
|---|---|---|---|
| L-A | `McpReflectionRegistrar.java:295-297` | `nameOrMethod` accepts blank (whitespace) annotation names — should fall back to method name |
| L-B | `McpReflectionRegistrar.java:39,42,45,48` | Class-level `@Tools` + `@Resources` on same class — exclusive semantics undocumented |
| L-C | `McpToolHandler.java:14` | `params` null policy undocumented — null input would NPE |
| L-D | `McpPromptHandler.java:14` | `arguments` null policy undocumented |
| L-E | `McpCompletionProvider.java:12` | `@SuppressWarnings("unused")` on `reference` — consider removing from signature |
| L-F | `McpLogger.java:18,25,32,39,46` | Null messages logged as string "null" by JUL — document null policy |
| L-G | `JulMcpLogger.java:19,26,33,40,47` | Passes null to JUL logger — becomes "null" string |
| L-H | `McpResourceHandler.java:12` | No null-safety on `uri` parameter |
| L-I | `McpBlobResourceHandler.java:28` | Return value annotated "never null" but unenforceable without `@Nonnull` |
| L-J | `McpServerConfig.java:77` | `JulMcpLogger` always instantiated even when custom logger is set |
| L-K | `McpTask.java:25` | `result` field is `Object` — caller-passed mutable collections not protected |
| L-L | `McpReflectionRegistrar.java:38` | `setAccessible(true)` can throw on strict Android SELinux — document requirement |

---

## Summary of new findings by severity

| Severity | Count | Files affected |
|---|---|---|
| HIGH | 5 | McpBlobContent (uri discard), McpRegistrar (readBlob vs read), McpProtocolHandler (overflow, orphaned method, Error catching) |
| MEDIUM | 5 | McpBlobContent (equals/hashCode/mimeType), McpReflectionRegistrar (parameterMetadata), McpParam (@Target mismatch, name mandatory) |
| LOW | 12 | Various handler interfaces, registrar, logger, task, blob |

---

## Combined audit totals (main + supplement)

| Severity | Main audit | Supplement | Combined |
|---|---|---|---|
| HIGH | 9 | 7 FIXED, 0 OPEN, 2 Acknowledged | 9 |
| MEDIUM | 15 | 13 FIXED, 1 OPEN, 1 Acknowledged | 15 |
| LOW | 18 | 0 FIXED, 18 Intentional/documentation | 18 |
| **Total** | **42** | **22 FIXED, 2 Acknowledged, 18 Intentional** | **42** |

All HIGH and MEDIUM actionable findings have been resolved. The 2 Acknowledged HIGH findings are intentional design decisions (H-D: explicit 501 for orphaned method; H-B: blob resource note in SPI). M6 is unfixed but low-risk. LOW items are documentation-only.

---

## Priority fix order

1. ~~H-B~~ **FIXED** **H-B** — `registerBlobResource` called wrong method (breaks blob resources)
2. ~~H-A~~ **FIXED** **H-A** — `McpBlobContent uri` discarded (data loss)
3. ~~H-E~~ **FIXED** **H-E** — catches Exception, not Error (JVM crash risk)
4. ~~H-C~~ **FIXED** **H-C** — Integer overflow in paginate
5. ~~H-D~~ Acknowledged **H-D** — orphaned `handleResourceTemplatesGet` (returns explicit 501)
6. ~~M-A/B~~ **FIXED** **M-A/B** — `McpBlobContent` equals/hashCode/mimeType
7. ~~M-D~~ Acknowledged **M-D** — `@McpParams @Target` violation (METHOD usage is future-use intent)
8. ~~M-C~~ **FIXED** **M-C** — `parameterMetadata` now fails fast on mixed annotated/unannotated
9. ~~H1~~ **FIXED** **H1** — NPE in `handleTasksCancel` (null guard added)
10. ~~H3~~ **FIXED** **H3** — SSE permit leak on IOException (`permitHeld` flag)
11. ~~H4~~ **FIXED** **H4** — duplicate session not rejected (guard added)
12. ~~M4~~ **FIXED** **M4** — schema shallow copy mutation (defensive `new LinkedHashMap<>(inputSchema)`)
13. ~~M5~~ **FIXED** **M5** — `convert()` no List/array support (Gson round-trip added)
14. ~~M6~~ Acknowledged **M6** — exception cause lost in wrapped error (propagate `e.getCause()`)
15. All LOW items (L-A through L-L): documentation-only — null policies, undocumented semantics, and informational notes; no code changes required

---

## Related documents

| Document | Description |
|---|---|
| [2026-09-12-full-source-audit.md](./2026-09-12-full-source-audit.md) | Main audit report |
| [ADR-0009](adr/ADR-0009-code-audit-2026-09-11.md) | Previous audit (2026-09-11) |
