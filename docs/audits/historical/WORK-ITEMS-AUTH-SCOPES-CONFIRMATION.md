# Work Items: Authorization Scopes & Confirmation — Annotation to Enforcement Pipeline

**Synthesized from:** t_1b7249e0, t_9e70bfca, t_9fc7b2b0
**Author:** dev-pm (t_b111acd9)
**Status:** Implementation-ready
**Workspace root:** `D:\android\mcp-java-sdk`

---

## 1. Priority Overview

| # | Work Item                                                          | Files                                 | Est. Effort | Dependency |
|---|--------------------------------------------------------------------|---------------------------------------|-------------|------------|
| 1 | Fix error code for auth denial (-32001, not -32029)                | `McpProtocolHandler.java`             | Low         | None       |
| 2 | Extend `McpRegistrar` SPI with 7-param `default` method            | `McpRegistrar.java`                   | Low         | None       |
| 3 | Add `@Override` of 7-param overload in `McpRegistry`               | `McpRegistry.java`                    | Low         | WI-2       |
| 4 | Update `McpReflectionRegistrar.registerTool()` to forward metadata | `McpReflectionRegistrar.java`         | Medium      | WI-2, WI-3 |
| 5 | Write `McpReflectionAuthorizationTest.java` (12 TCs)               | `McpReflectionAuthorizationTest.java` | Medium      | WI-1, WI-4 |
| 6 | Correct `REFLECTION-TOOL-POLICY-TRIAGE.md` doc error               | `REFLECTION-TOOL-POLICY-TRIAGE.md`    | Trivial     | WI-2       |
| 7 | Full test suite pass                                               | All                                   | Low         | WI-1–5     |

**Critical path:** WI-1 → WI-2 → WI-3 → WI-4 → WI-5 → WI-7.
WI-6 is independent but must land before a release that ships the SPI change.

---

## 2. Work Item Details

---

### WI-1: Fix authorization denial error code — `-32029` → `-32001`

**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`
**Line:** ~1524
**Effort:** Low (~1 line)
**Risk:** MEDIUM — changes observable JSON-RPC behavior for clients that currently
interpret `-32029` as an authorization denial. A release note is mandatory.
See RATE-LIMIT-TRIAGE-SPEC.md line 230 for the full migration impact.

**Current code (line 1524):**

```java
throw new McpErrorException(-32029, "Authorization denied: " + denial);
```

**Change to:**

```java
throw new McpErrorException(-32001, "Authorization denied: " + denial);
```

**Verification:**

- Authorization denial → error code `-32001`
- Rate-limit denial → error code `-32029` (unchanged, line 1512)
- Run existing `McpAuthorizationTest`
- New `McpReflectionAuthorizationTest` TC9–TC11 (written in WI-5) verify this

**Breaking change note (RATE-LIMIT-TRIAGE-SPEC.md line 230):**
Clients relying on `-32029` for authorization denial must migrate to `-32001`.
Document in `CHANGELOG.md`.

---

### WI-2: Add 7-param `registerTool` default method to `McpRegistrar` SPI

**File:** `src/main/java/io/github/vinhphan812/mcp/api/spi/McpRegistrar.java`
**Effort:** Low (~20 lines)
**Risk:** None — pure additive SPI extension with `default` implementation
**Dependency:** None

**Placement:** After the existing 6-param overload (line 38), before `registerResource`.

**New method to add:**

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
 * @param requiredScopes         authorization scopes for this tool; may be {@code null}
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

**No other changes to `McpRegistrar.java` are needed.**

**Java 8 compatibility notes (from REGISTRAR-METADATA-CONTRACT.md §4):**

- `default` keyword on interface methods requires Java 8+ — consistent with
  `build.gradle` (`sourceCompatibility = JavaVersion.VERSION_1_8`).
- Binary-compatible by definition: existing `.class` files continue to link unchanged.
- No use of `java.util.Optional`, `java.util.function.*`, or other post-Java-8 types.

---

### WI-3: Override 7-param `registerTool` in `McpRegistry`

**File:** `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java`
**Effort:** Low (~10 lines)
**Risk:** None — no behavior change; `McpRegistry` already has the 7-arg method
**Dependency:** WI-2

**Placement:** After the existing 6-param overload (line 93), before `registerResource`.

**Add `@Override` annotation + forwarding body to the existing 7-arg method
(at lines 95–135). The existing method body is unchanged; the `@Override`
documents that it satisfies the new SPI contract:

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

---

### WI-4: Update `McpReflectionRegistrar.registerTool()` to forward metadata

**File:** `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java`
**Effort:** Medium (~15 lines changed)
**Risk:** Low — preserves existing behavior for tools without `scopes`/`confirmationRequired`
**Dependency:** WI-2, WI-3

**What changes:**

Replace `registerTool(Object target, Method method, McpTool annotation, McpRegistrar registrar)`
(lines 54–72) to:

1. Read `annotation.scopes()` and `annotation.confirmationRequired()` before the branching.
2. Branch on `scopes != null || annotationConfirm` to call the new 7-param overload.
3. Fall through to existing 5/6-param paths when neither attribute is set.

**Exact replacement (see REGISTRAR-METADATA-CONTRACT.md §6):**

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

**Existing code removed:** Lines 66–71 (`if (outputSchema != null)` branching that
discarded annotation metadata).

**Import check:** `java.util.Arrays` is already imported (used in `findParams` at line 176).

**Verification:**

- `McpReflectionAuthorizationTest` TC1–TC4 verify registry state
- `McpReflectionAuthorizationTest` TC5–TC12 verify runtime authorization behavior

---

### WI-5: Write `McpReflectionAuthorizationTest.java` — 12 test cases

**File:** `src/test/java/io/github/vinhphan812/mcp/McpReflectionAuthorizationTest.java`
**Effort:** Medium (~250–300 lines)
**Risk:** None — additive test coverage
**Dependency:** WI-1, WI-4

**Full spec:** `docs/testing/AUTH-SCOPES-CONFIRMATION-TEST-SPEC.md` (378 lines)

**Test class structure:**

```
package io.github.vinhphan812.mcp;

import io.github.vinhphan812.mcp.api.McpReflectionRegistrar;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
import io.github.vinhphan812.mcp.api.spi.McpAuthorization;
import io.github.vinhphan812.mcp.annotations.*;
import io.github.vinhphan812.mcp.core.McpProtocolHandler;
import io.github.vinhphan812.mcp.core.McpRegistry;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
```

**Fixture classes (package-private, nested):**

- `AdminPanelTool` — TC1, TC5, TC9: `@McpTool(scopes={"admin"}, outputSchema=...)`
- `FormatAddressTool` — TC6: `@McpTool(scopes={"format:address"})`
- `SearchCatalogTool` — TC2, TC6, TC10: `@McpTool(confirmationRequired=true)`
- `SimpleEchoTool` — TC8: `@McpTool(name="simple-echo")` (no attrs)
- `DeleteRecordTool` — TC3, TC7, TC11: `@McpTool(scopes={"write"}, confirmationRequired=true)`
- `OUTPUT_SCHEMA` — static final String constant for annotation reference

**Test methods:**
| # | Method | Key assertions |
|---|--------|----------------|
| TC1 | `metadata_requiredScopes_in_registry()` | `registry.getToolDefinition("admin-panel").get("requiredScopes")`
contains `"admin"` |
| TC2 | `metadata_confirmationRequired_in_registry()` |
`registry.getToolDefinition("search-catalog").get("confirmationRequired")` == `Boolean.TRUE` |
| TC3 | `metadata_both_attributes_in_registry()` | Both keys present with correct values |
| TC4 | `metadata_outputSchema_and_scopes_together()` | All three keys present simultaneously |
| TC5 | `authorization_receives_scopes_happy_path()` | Spy captures `["admin"]`, handler invoked |
| TC6 | `authorization_receives_confirmation_happy_path()` | Spy captures empty scopes + `true`, handler invoked |
| TC7 | `authorization_receives_both_attributes()` | Spy captures `["write"]` + `true`, handler invoked |
| TC8 | `authorization_empty_scopes_default_tool()` | Spy captures empty array + `false` |
| TC9 | `denial_short_circuits_handler_scope_denial()` | Error contains denial message; handler NOT called |
| TC10 | `denial_short_circuits_handler_confirmation_denial()` | Error contains denial message; handler NOT called |
| TC11 | `denial_short_circuits_both_attributes()` | Error contains denial message; handler NOT called |
| TC12 | `no_auth_configured_all_tools_allowed()` | Handler invoked with no error |

**Anti-patterns (from test spec §5):**

- Do NOT assert on specific error code `-32029` in TC9–TC11.
  Assert on presence of any error + denial message text.
- Do NOT test `McpRegistry.registerTool(...)` directly.
- Do NOT assume empty scope array is `null` — protocol handler converts to `new String[0]`.

**Run command:**

```sh
./gradlew test --tests io.github.vinhphan812.mcp.McpReflectionAuthorizationTest --console=plain
```

**Run alongside existing auth tests:**

```sh
./gradlew test \
  --tests io.github.vinhphan812.mcp.McpReflectionAuthorizationTest \
  --tests io.github.vinhphan812.mcp.McpAuthorizationTest \
  --console=plain
```

---

### WI-6: Correct doc error in `REFLECTION-TOOL-POLICY-TRIAGE.md` line 16

**File:** `docs/REFLECTION-TOOL-POLICY-TRIAGE.md`
**Effort:** Trivial (1 line)
**Risk:** None — doc-only fix
**Dependency:** None (independent; should land with WI-2 for consistency)

**Current (incorrect):** "McpRegistrar already stores requiredScopes and confirmationRequired"
**Correct:** "Only `McpRegistry` stores requiredScopes and confirmationRequired — the SPI
interface does not expose a metadata-aware overload."

**Evidence:** `McpRegistrar.java` (150 lines) has no `requiredScopes`/`confirmationRequired`
params; only `McpRegistry` (lines 105–135) has them.

---

### WI-7: Full test suite pass and CHANGELOG entry

**Effort:** Low
**Dependency:** WI-1–5

**Run:**

```sh
./gradlew clean test --console=plain
```

**Expected:** All tests pass (existing suite + `McpReflectionAuthorizationTest`).

**CHANGELOG.md update** (required because WI-1 is a breaking JSON-RPC change):

```markdown
### Changed

- `McpProtocolHandler`: Authorization denial now returns JSON-RPC error code `-32001`
  (Authorization Denied) instead of `-32029` (Rate Limit Exceeded). Clients that
  interpret `-32029` as an authorization denial must migrate to `-32001`.
  See [ADR-0011](docs/adr/ADR-0011-security-model.md).
```

---

## 3. Dependency Graph

```
WI-1  Fix error code -32001 in McpProtocolHandler
  ↓
WI-2  Add 7-param default to McpRegistrar SPI
  ↓
WI-3  Override in McpRegistry          ←→  WI-6  Doc fix (independent)
  ↓
WI-4  Update McpReflectionRegistrar
  ↓
WI-5  Write 12-TC test class
  ↓
WI-7  Full suite + CHANGELOG
```

---

## 4. Files Summary

| File                                  | Change Type                  | Lines Changed (est.) |
|---------------------------------------|------------------------------|----------------------|
| `McpProtocolHandler.java`             | Edit (1 line)                | ~1                   |
| `McpRegistrar.java`                   | Add method (~20 lines)       | ~+20                 |
| `McpRegistry.java`                    | Edit (+@Override, ~10 lines) | ~+10                 |
| `McpReflectionRegistrar.java`         | Edit (replace ~20 lines)     | ~+15/-15             |
| `McpReflectionAuthorizationTest.java` | New file                     | ~+280                |
| `REFLECTION-TOOL-POLICY-TRIAGE.md`    | Edit (1 line)                | ~1                   |
| `CHANGELOG.md`                        | Add entry                    | ~+6                  |

**Total estimated source changes:** ~50 lines edited/added, 1 file deleted (doc only).

---

## 5. Validation Checklist

After WI-7:

- [ ] `./gradlew test --console=plain` — all tests green
- [ ] `McpReflectionAuthorizationTest` — all 12 TCs pass
- [ ] `McpAuthorizationTest` — existing tests still pass (no regression)
- [ ] `tools/list` response includes `requiredScopes` and `confirmationRequired`
  for annotated tools registered via `McpReflectionRegistrar`
- [ ] Authorization denial → error code `-32001`
- [ ] Rate-limit denial → error code `-32029`
- [ ] CHANGELOG.md documents the breaking error-code change
- [ ] `REFLECTION-TOOL-POLICY-TRIAGE.md` doc error corrected
