# Registrar Contract: Authorization Metadata Registration

**Contract version:** 1.0
**Status:** Implementation-ready
**Audience:** Third-party `McpRegistrar` implementers, SDK maintainers

---

## 1. Background

`@McpTool` defines two authorization attributes:

```java
// McpTool.java lines 30, 37
String[] scopes() default {};
boolean confirmationRequired() default false;
```

`McpRegistry` already stores these in tool definitions (lines 105–135) and `McpProtocolHandler.handleToolsCall`
reads them back for authorization gating (lines 1487–1526). The gap is at the SPI boundary:
`McpRegistrar` does not expose a metadata-aware overload, and `McpReflectionRegistrar` reads the annotation
values but discards them instead of forwarding them.

---

## 2. Gap Analysis

| Method                                                                                                   | Who has it                         | Problem                                             |
|----------------------------------------------------------------------------------------------------------|------------------------------------|-----------------------------------------------------|
| `registerTool(name, desc, inputSchema, required, handler)`                                               | `McpRegistrar` SPI + `McpRegistry` | 5-param; no place for metadata                      |
| `registerTool(name, desc, inputSchema, required, outputSchema, handler)`                                 | `McpRegistrar` SPI + `McpRegistry` | 6-param; no place for metadata                      |
| `registerTool(name, desc, inputSchema, required, **requiredScopes**, **confirmationRequired**, handler)` | `McpRegistry` only                 | Not in SPI; third-party registrars cannot implement |

`McpReflectionRegistrar.registerTool()` (lines 54–72) reads `annotation.scopes()` and
`annotation.confirmationRequired()` but calls only the 5- or 6-param overload — metadata is silently dropped.

---

## 3. Design Decision

**Add a metadata-aware overload to `McpRegistrar` with a default implementation.**

This is preferred over the `instanceof McpRegistry` detection pattern because:

1. It is a pure SPI contract extension — no coupling from `McpReflectionRegistrar` to `McpRegistry`.
2. Third-party `McpRegistrar` implementers can override it or fall back to the default (metadata ignored).
3. No binary incompatibility: `default` keyword on interface methods requires Java 8+, which the SDK already targets.
4. `McpRegistry` will override the new method to preserve its existing 7-arg implementation (no behavior change).

---

## 4. Contract: `McpRegistrar` SPI Changes

Add to `io.github.vinhphan812.mcp.api.spi.McpRegistrar`:

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
 * @param description             tool description
 * @param inputSchema            tool input property definitions
 * @param required                required input names
 * @param requiredScopes           authorization scopes for this tool; may be {@code null}
 * @param confirmationRequired     whether this tool requires user confirmation before execution
 * @param handler                 tool handler
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

### Java 8 Compatibility Notes

- `default` keyword on interface methods requires Java 8+ — consistent with `build.gradle`
  (`sourceCompatibility = JavaVersion.VERSION_1_8`).
- `default` methods are binary-compatible by definition: existing `.class` files continue to link
  against the interface without recompilation.
- No use of `java.util.Optional`, `java.util.function.*`, or other post-Java-8 types in the
  interface declaration itself.

### Exact Parameter Types

| Param                  | Type                  | Notes                                        |
|------------------------|-----------------------|----------------------------------------------|
| `name`                 | `String`              | Non-null tool identifier                     |
| `description`          | `String`              | Human-readable description                   |
| `inputSchema`          | `Map<String, Object>` | JSON Schema `properties` object              |
| `required`             | `List<String>`        | Required parameter names                     |
| `requiredScopes`       | `List<String>`        | Authorization scopes; `null` means no scopes |
| `confirmationRequired` | `boolean`             | `true` to gate with user confirmation        |
| `handler`              | `McpToolHandler`      | Tool implementation                          |

### Relationship to Existing Overloads

The 7-param overload is **orthogonal** to the existing 5- and 6-param overloads:

```
registerTool(name, desc, inputSchema, required, handler)                        ← existing
registerTool(name, desc, inputSchema, required, outputSchema, handler)           ← existing
registerTool(name, desc, inputSchema, required, requiredScopes,
             confirmationRequired, handler)                                      ← NEW (default)
```

No existing overload is deprecated or removed. The 7-param overload is purely additive.

---

## 5. `McpRegistry` Changes

`McpRegistry` must override the new method to delegate to its existing 7-arg overload:

```java
@Override
public void registerTool(String name, String description,
                        Map<String, Object> inputSchema, List<String> required,
                        List<String> requiredScopes, boolean confirmationRequired,
                        McpToolHandler handler) {
    // Existing 7-arg method (lines 105–135) — no logic change needed.
    registerTool(name, description, inputSchema, required,
                 requiredScopes, confirmationRequired, handler);
}
```

The `@Override` annotation is added for compile-time verification of the SPI contract.
Behavior is identical to the existing method; no data changes.

---

## 6. `McpReflectionRegistrar` Changes

Update `registerTool()` (lines 54–72) to call the new SPI overload when annotation metadata
is present, otherwise fall back to the existing 5/6-param paths:

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

    // Always use the metadata-aware SPI overload when scopes or
    // confirmationRequired are set; otherwise the default handles it.
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

**Existing code removed:**

- Lines 66–71: the `if (outputSchema != null)` branching that discarded annotation metadata.

**New behavior:**

- `scopes` and `confirmationRequired` are now forwarded to the registrar.
- If the registrar's default implementation silently drops them (e.g. a third-party registrar),
  behavior is unchanged from the caller's perspective.

---

## 7. Backward Compatibility Guarantees

| Consumer                                                 | Impact                                                      | Mitigation              |
|----------------------------------------------------------|-------------------------------------------------------------|-------------------------|
| Third-party `McpRegistrar` implementers                  | Existing `.class` files link unchanged (default method)     | None required           |
| Direct callers of 5/6-param overloads                    | No change                                                   | None required           |
| `@McpTool` users without `scopes`/`confirmationRequired` | No change                                                   | None required           |
| `@McpTool` users with `scopes`/`confirmationRequired`    | Metadata now preserved with `McpRegistry`                   | Annotations take effect |
| `McpRegistry` existing registrations                     | No change                                                   | None required           |
| Android / API level < 24                                 | No change — `default` interface methods are Java 8 bytecode | None required           |

**No deprecation.** The 5- and 6-param overloads are retained indefinitely.
The 7-param overload is purely additive.

---

## 8. What This Enables

After implementation, this "just works":

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
```

---

## 9. Implementation Checklist

- [ ] Add 7-param `registerTool` with `default` implementation to `McpRegistrar`
- [ ] Add `@Override` of the new method to `McpRegistry` (no-op delegation)
- [ ] Update `McpReflectionRegistrar.registerTool()` to forward annotation metadata
- [ ] Add `import java.util.Arrays` to `McpReflectionRegistrar` if not already present
- [ ] Run `./gradlew test` — all existing tests pass
- [ ] Verify `tools/list` includes `requiredScopes` and `confirmationRequired` for annotated tools

---

## 10. Related Decisions

- **ADR-0007** (Annotation-Based Registration): Established the `@McpTool` annotation model.
  This contract extends that model with authorization metadata.
- **ADR-0010** (API Package Restructure): `McpRegistrar` lives in `api/spi/` — the SPI package
  designation confirms it is a public extension point for third-party implementers.
- **`REFLECTION-TOOL-POLICY-TRIAGE.md` line 16**: States "McpRegistrar already stores requiredScopes
  and confirmationRequired" — this is **incorrect**; only `McpRegistry` does, not the SPI interface.
  This contract corrects that claim.
