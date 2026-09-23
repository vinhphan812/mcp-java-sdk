# Scopes and Authorization: Reflection Registration Gap and Specification

**Canonical document** for the scopes/confirmation metadata registration domain.
**Status:** Gap unfixed — reflection registration does not forward annotation metadata to the registry.

---

## 1. Verified Source State (as of 2026-09-21)

The following is anchored to live source, not planning documents.

### 1.1 `@McpTool` Annotation (`annotations/McpTool.java`, lines 30, 37)

```java
// McpTool.java lines 30, 37
String[] scopes() default {};
boolean confirmationRequired() default false;
```

Both attributes exist with safe defaults. No changes needed to the annotation.

### 1.2 `McpReflectionRegistrar.registerTool()` (`api/McpReflectionRegistrar.java`, lines 54-72)

**Confirmed gap.** The method reads `annotation.scopes()` and `annotation.confirmationRequired()`
but discards them — it calls only the 5- or 6-param overload of the registrar:

```java
// Current code (lines 54-72) — metadata silently dropped
private static void registerTool(Object target, Method method, McpTool annotation,
                                 McpRegistrar registrar) {
    String name = nameOrMethod(annotation.name(), method);
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    parameterMetadata(method, properties, required);
    validateReturn(method, Map.class, "tool");
    Map<String, Object> outputSchema = parseOutputSchema(annotation.outputSchema());
    McpToolHandler delegate = arguments -> invokeMap(target, method, arguments);
    McpToolHandler handler = outputSchema != null
            ? new ToolHandlerWithSchema(delegate, outputSchema) : delegate;
    // Use 6-param overload to pass outputSchema; null is safe when no schema was declared
    if (outputSchema != null) {
        registrar.registerTool(name, annotation.description(), properties, required,
                outputSchema, handler);
    } else {
        registrar.registerTool(name, annotation.description(), properties, required, handler);
    }
}
```

`annotation.scopes()` and `annotation.confirmationRequired()` are never read.
Consequently, `@McpTool(scopes = {"admin"}, confirmationRequired = true)` has no effect
when tools are registered via `McpReflectionRegistrar`.

### 1.3 `McpRegistrar` SPI Interface (`api/spi/McpRegistrar.java`)

**Confirmed gap.** The interface declares only two `registerTool` overloads:

| Overload | Parameters                                                        | Location    |
|----------|-------------------------------------------------------------------|-------------|
| 5-param  | `name, description, inputSchema, required, handler`               | lines 23-24 |
| 6-param  | `name, description, inputSchema, required, outputSchema, handler` | lines 36-38 |

No `requiredScopes` or `confirmationRequired` parameter exists in the SPI. A third-party
`McpRegistrar` implementation cannot receive authorization metadata through the SPI contract.

### 1.4 `McpRegistry` (`core/McpRegistry.java`, lines 105-135)

**Infrastructure confirmed present.** The 7-argument overload already stores `requiredScopes`
and `confirmationRequired` in the tool definition map:

```java
// McpRegistry.java lines 105-135 — 7-argument overload
public void registerTool(String name, String description, Map<String, Object> inputSchema,
             List<String> required, List<String> requiredScopes,
             boolean confirmationRequired, McpToolHandler handler) {
    // ...
    if (requiredScopes != null && !requiredScopes.isEmpty()) {
        tool.put("requiredScopes", new ArrayList<>(requiredScopes));
    }
    if (confirmationRequired) {
        tool.put("confirmationRequired", true);
    }
    // ...
}
```

No source changes needed here.

### 1.5 `McpProtocolHandler.handleToolsCall()` (`core/McpProtocolHandler.java`, lines 1486-1526)

**Infrastructure confirmed present.** The protocol handler already:

- Reads `requiredScopes` from the tool definition (line 1506)
- Reads `confirmationRequired` from the tool definition (line 1521)
- Calls `authorization.denial()` before handler invocation (lines 1516-1525)

However, the error code for authorization denial is `-32029` (line 1524), the same as
rate-limit denial. This is an open implementation gap — the correct MCP error code for
authorization denial is `-32001`. See §4.3.

```java
// McpProtocolHandler.java line 1524
throw new McpErrorException(-32029, "Authorization denied: " + denial);
```

Rate-limit denial uses `-32029` at line 1512. These should be distinct.

### 1.6 `McpProtocolHandler` as `McpRegistrar` Implementer

`McpProtocolHandler` implements `McpRegistrar` (line 54) and forwards tool registrations
to its internal `McpRegistry` via the 5- and 6-param overloads only (lines 1863-1873).
It does **not** override a metadata-aware SPI method (because none exists in the SPI yet).

---

## 2. Metadata Flow

```
@McpTool(scopes={"admin"}, confirmationRequired=true)
public Map<String,Object> adminPanel(...) { ... }

       ↓ annotation attributes declared (McpTool.java ✓)
       ↓ attributes read but discarded (McpReflectionRegistrar.java ✗)
       ↓ no SPI overload exists to carry them (McpRegistrar.java ✗)
       ↓ McpRegistry 7-arg exists but unreachable (McpRegistry.java ✓)
       ↓ McpProtocolHandler authorization check reads from definition (✓)
          but definition is missing scopes/confirm fields ← GAP
```

---

## 3. Implementation Requirements

The following changes are needed to fix the gap. No changes are needed to the annotation,
`McpRegistry`, or `McpProtocolHandler` (infrastructure is already in place).

### 3.1 Add 7-param `registerTool` to `McpRegistrar` SPI

**File:** `src/main/java/io/github/vinhphan812/mcp/api/spi/McpRegistrar.java`
**Change:** Add a `default` method after the existing 6-param overload (line 38).

```java
/**
 * Registers an MCP tool definition and its handler with authorization metadata.
 *
 * <p>The default implementation ignores {@code requiredScopes} and
 * {@code confirmationRequired} — metadata is silently dropped.  Registrars
 * that support authorization (e.g. {@link io.github.vinhphan812.mcp.core.McpRegistry})
 * override this method to store the values in the tool definition map.
 *
 * @param name                   tool name
 * @param description            tool description
 * @param inputSchema            tool input property definitions
 * @param required               required input names
 * @param requiredScopes         authorisation scopes for this tool; may be {@code null}
 * @param confirmationRequired    whether this tool requires user confirmation before execution
 * @param handler                tool handler
 * @since 1.1
 */
default void registerTool(String name, String description,
                         Map<String, Object> inputSchema, List<String> required,
                         List<String> requiredScopes, boolean confirmationRequired,
                         McpToolHandler handler) {
    // Safe fallback: delegate to the 6-param overload, discarding metadata.
    // Override in implementations that store authorization data.
    registerTool(name, description, inputSchema, required, null, handler);
}
```

Java 8 compatibility: the `default` keyword on interface methods is Java 8 bytecode;
the SDK targets `JavaVersion.VERSION_1_8`. No post-Java-8 types used.

### 3.2 Add `@Override` in `McpRegistry`

**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java`
**Change:** Add `@Override` annotation to the existing 7-arg method to document SPI contract satisfaction.

```java
@Override  // ← add this
public void registerTool(String name, String description, Map<String, Object> inputSchema,
             List<String> required, List<String> requiredScopes,
             boolean confirmationRequired, McpToolHandler handler) {
    // Existing method body unchanged (lines 108-135)
    ...
}
```

### 3.3 Update `McpReflectionRegistrar.registerTool()` to Forward Metadata

**File:** `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java`
**Change:** Replace the method body at lines 54-72 to read and forward annotation metadata.

```java
private static void registerTool(Object target, Method method, McpTool annotation,
                                 McpRegistrar registrar) {
    String name = nameOrMethod(annotation.name(), method);
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    parameterMetadata(method, properties, required);
    validateReturn(method, Map.class, "tool");

    Map<String, Object> outputSchema = parseOutputSchema(annotation.outputSchema());
    String[] annotationScopes = annotation.scopes();
    boolean annotationConfirm = annotation.confirmationRequired();
    List<String> scopes = annotationScopes.length > 0
            ? Arrays.asList(annotationScopes) : null;

    McpToolHandler delegate = arguments -> invokeMap(target, method, arguments);
    McpToolHandler handler = outputSchema != null
            ? new ToolHandlerWithSchema(delegate, outputSchema) : delegate;

    // Forward metadata when scopes or confirmationRequired are declared;
    // otherwise fall through to preserve existing behavior.
    if (scopes != null || annotationConfirm) {
        registrar.registerTool(name, annotation.description(), properties, required,
                scopes, annotationConfirm, handler);
    } else if (outputSchema != null) {
        registrar.registerTool(name, annotation.description(), properties, required,
                outputSchema, handler);
    } else {
        registrar.registerTool(name, annotation.description(), properties, required, handler);
    }
}
```

`java.util.Arrays` is already imported (used in `findParams` at line 176).

### 3.4 Fix Authorization Denial Error Code

**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`
**Line:** ~1524
**Change:** Replace `-32029` with `-32001` for authorization denials.

The MCP standard uses `-32001` for "Authorization Denied" and `-32029` is reserved for
rate limiting. These represent distinct failure modes and must use distinct codes.

```java
// Current:
throw new McpErrorException(-32029, "Authorization denied: " + denial);
// Change to:
throw new McpErrorException(-32001, "Authorization denied: " + denial);
```

This is a breaking JSON-RPC change for clients that currently interpret `-32029` as
authorization denial. A CHANGELOG entry is required.

---

## 4. Compatibility Constraints

| Consumer                                                 | Impact                                                  | Mitigation               |
|----------------------------------------------------------|---------------------------------------------------------|--------------------------|
| Third-party `McpRegistrar` implementers                  | Existing `.class` files link unchanged (default method) | None required            |
| Direct callers of 5/6-param overloads                    | No change                                               | None required            |
| `@McpTool` users without `scopes`/`confirmationRequired` | No change                                               | None required            |
| `@McpTool` users with `scopes`/`confirmationRequired`    | Metadata now preserved with `McpRegistry`               | Annotations take effect  |
| `McpRegistry` existing registrations                     | No change                                               | None required            |
| Clients interpreting `-32029` as auth denial             | Must migrate to `-32001`                                | CHANGELOG entry required |

---

## 5. What This Enables

After implementation:

```java
@Tools
public class AdminTools {
    @McpTool(
        name = "delete-user",
        description = "Permanently removes a user account",
        scopes = {"admin:users"},
        confirmationRequired = true
    )
    public Map<String, Object> deleteUser(
            @McpParam(name = "userId", required = true) String userId) {
        // ...
    }
}

McpReflectionRegistrar.register(new AdminTools(), registry);
// ✅ "admin:users" scope reaches McpAuthorization.denial()
// ✅ confirmationRequired=true gates handler invocation
// ✅ tools/list response includes requiredScopes + confirmationRequired
// ✅ Authorization denial returns error code -32001
```

---

## 6. Open Implementation Gaps

The following gaps remain open unless source proves otherwise:

| #  | Gap                                                   | Evidence                                | Status        |
|----|-------------------------------------------------------|-----------------------------------------|---------------|
| G1 | `McpReflectionRegistrar` discards annotation metadata | `McpReflectionRegistrar.java:54-72`     | **Unfixed**   |
| G2 | `McpRegistrar` SPI has no metadata-aware overload     | `McpRegistrar.java` only 5- and 6-param | **Unfixed**   |
| G3 | `McpProtocolHandler` uses `-32029` for auth denial    | `McpProtocolHandler.java:1524`          | **Unfixed**   |
| G4 | `McpReflectionAuthorizationTest` (12 TCs) not written | No such test file exists                | **Unwritten** |

**No regression test proves G1 is fixed.** The reflection scopes/confirmation defect
remains open until a test passes that exercises `@McpTool(scopes=..., confirmationRequired=...)`
registered via `McpReflectionRegistrar` and verifies the values appear in the registry
definition and reach the authorization callback.

---

## 7. Related Decisions

- **ADR-0007** (Annotation-Based Registration): Established the `@McpTool` annotation model.
  This contract extends that model with authorization metadata.
- **`REFLECTION-TOOL-POLICY-TRIAGE.md` line 16**: Incorrectly states "McpRegistrar already
  stores requiredScopes and confirmationRequired" — only `McpRegistry` does, not the SPI.
  The triage document has been archived to `docs/audits/historical/`.

---

## 8. Validation Checklist

- [ ] `./gradlew test --console=plain` — all tests pass
- [ ] `tools/list` response includes `requiredScopes` and `confirmationRequired`
  for annotated tools registered via `McpReflectionRegistrar`
- [ ] Authorization denial returns error code `-32001`
- [ ] Rate-limit denial returns error code `-32029`
- [ ] CHANGELOG.md documents the breaking error-code change
- [ ] `git diff --check` — no whitespace errors
