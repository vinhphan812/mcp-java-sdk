# Tier 2 Triage: Blob-Resource SPI and Resource-Template Endpoint Contract

**Task:** t_de5615c8
**Date:** 2026-09-21
**Scope:** Tier 2 — specification/design only; no source writes, staging, or commits.
**Reference audit:** Prior Tier 1 spec at `docs/architecture/blob-resource-spi-triage-spec.md` (task t_82050dbd,
2026-09-20).

---

## Executive Summary

Two distinct audit findings were investigated:

1. **`McpRegistrar` blob registration SPI** — `registerBlobResource` and `registerBlobResourceTemplate` are default
   no-op methods. Only `McpRegistry` has working implementations. The Tier 1 spec (t_82050dbd) already designed the fix;
   this document confirms the design and adds a dispatch-symmetry gap analysis.

2. **`resources/templates/get` dispatch** — `McpProtocolHandler.handleResourceTemplatesGet` (lines 1689–1706) is dead
   code. The dispatcher (lines 693–695) hard-rejects this method with an explicit error and guidance to use
   `resources/read` with a resolved URI. This is an intentional architectural decision, not a bug. The dead handler
   should be removed.

---

## Finding 1: `McpRegistrar` Blob Registration SPI

### 1.1 Evidence

**`McpRegistrar.java` — interface defaults**

```
Line 76–80  default void registerBlobResource(...)
               Body: empty (// NOTE: callers should use McpRegistry directly)

Line 118–123 default void registerBlobResourceTemplate(...)
               Body: empty (// NOTE: callers should use McpRegistry directly)
```

**`McpRegistry.java` — working implementations**

```
Line 159  @Override registerBlobResource(...)
               → acquires lock, checks uniqueness against resourceHandlers,
                  adds to registeredResources + resourceHandlers

Line 204  @Override registerBlobResourceTemplate(...)
               → acquires lock, checks uniqueness against resourceTemplateHandlers,
                  adds to registeredResourceTemplates + resourceTemplateHandlers
                  + blobResourceTemplateHandlers
```

**`McpProtocolHandler.java` — implements McpRegistrar, MISSING overrides**

```
Line 54   public class McpProtocolHandler implements McpRegistrar, McpRegistryChangeListener
Line 1875–1908  registerTool (x2), registerResource (x2), registerResourceTemplate (x2)
                 ALL delegate to registry — correct pattern followed.
MISSING: registerBlobResource override
MISSING: registerBlobResourceTemplate override
```

Result: `McpProtocolHandler` inherits the interface default no-ops. Any call to `mcpServer.registerBlobResource(...)`
silently drops.

**`McpReflectionRegistrar.java` — no blob support**

```
Lines 42–47  registerResource(...) → calls registrar.registerResource(...)
              registerResourceTemplate(...) → calls registrar.registerResourceTemplate(...)
              No @McpBlobResource annotation exists.
              Always calls the String-returning overload, never McpBlobResourceHandler.
```

**`McpProtocolHandler.handleResourcesRead` — protocol dispatch is correct**

```
Line 1672–1673  if (handler instanceof McpBlobResourceHandler)
                    return blobContents(uri, ((McpBlobResourceHandler) handler).readBlob(uri));
                    // else: resourceContents(uri, handler.read(uri))
```

The protocol layer correctly dispatches to `readBlob` when the handler is registered. The registration gap is the only
problem.

---

### 1.2 API Surface Classification

| Method                                  | Location                                   | Status                                                                         |
|-----------------------------------------|--------------------------------------------|--------------------------------------------------------------------------------|
| `registerTool(...)` x2                  | `McpRegistrar`                             | abstract — requires impl; `McpProtocolHandler` and `McpRegistry` both override |
| `registerResource(...)` x2              | `McpRegistrar`                             | 1 default (4-param), 1 abstract (5-param); both overridden                     |
| **`registerBlobResource(...)`**         | `McpRegistrar`                             | **default no-op** — stub, not dead code (see 1.4)                              |
| `registerResourceTemplate(...)` x2      | `McpRegistrar`                             | mirrors resource pattern; both overridden                                      |
| **`registerBlobResourceTemplate(...)`** | `McpRegistrar`                             | **default no-op** — stub, not dead code (see 1.4)                              |
| `registerPrompt(...)`                   | `McpRegistrar`                             | abstract — overridden                                                          |
| `registerCompletionProvider(...)`       | `McpRegistrar`                             | abstract — overridden                                                          |
| `notifyResourceUpdated(...)`            | `McpResourceUpdateListener`                | abstract — overridden                                                          |
| `McpBlobResourceHandler.readBlob(uri)`  | Handler interface                          | **fully supported** at protocol dispatch level                                 |
| `McpBlobContent` DTO                    | `api/dto/McpBlobContent.java`              | **fully supported**                                                            |
| `@McpResource` annotation               | `api/annotations/McpResource.java`         | Supported; reflection registrar maps to `registerResource`                     |
| `@McpResourceTemplate` annotation       | `api/annotations/McpResourceTemplate.java` | Supported; reflection registrar maps to `registerResourceTemplate`             |
| `@McpBlobResource` annotation           | —                                          | **NOT EXISTENT** — gap in reflection registrar                                 |

---

### 1.3 Tier 1 Fix Confirmation

The Tier 1 spec (t_82050dbd) already produced a complete fix design. This document confirms the fix is correct and
identifies one additional gap not covered by that spec.

**Fix from Tier 1 (Option A — Recommended):**

Add to `McpProtocolHandler.java`, after line 1908 (`registerResourceTemplate` override), before `onRegistryChanged` (
line 1913):

```java
@Override
public void registerBlobResource(String uri, String name, String description,
                                String mimeType, McpBlobResourceHandler handler) {
    registry.registerBlobResource(uri, name, description, mimeType, handler);
}

@Override
public void registerBlobResourceTemplate(String uriTemplate, String name,
                                         String description, String mimeType,
                                         McpBlobResourceHandler handler) {
    registry.registerBlobResourceTemplate(uriTemplate, name, description, mimeType, handler);
}
```

**Additional gap not in Tier 1 spec:**

The fix closes the primary gap for `McpProtocolHandler` / `McpServer` callers. However, the default no-ops remain in
`McpRegistrar`. The note comments in the defaults ("callers should use McpRegistry directly") are misleading — the
intended path is via `McpProtocolHandler`, not `McpRegistry` directly. An implementer who reads the interface and calls
`mcpProtocolHandler.registerBlobResource(...)` gets silent no-op (before the fix) or correct registration (after the
fix). The note should be updated to reflect the actual fixed behavior.

---

### 1.4 `registerBlobResource*` Are Stubs, Not Dead Code

The no-op default methods are **stubs** by design, not dead code:

- They preserve source compatibility for any third-party `McpRegistrar` implementation that inherits them and
  intentionally no-ops (e.g., a test mock, a minimal implementation).
- `McpProtocolHandler` (the primary public entry point) should override them — that is the fix.
- Removing the defaults would break source compatibility for any such implementor.
- The Tier 1 fix makes the stubs unreachable for the primary code path, but they remain on the interface for SPI
  flexibility.

**Classification:** Stub (intentional, source-compatible, to be superseded by `McpProtocolHandler` override).

---

### 1.5 `McpRegistrar` Repair Design Options

#### Option A: Override in `McpProtocolHandler` (Tier 1 Recommended, Confirmed)

Add the two `@Override` methods delegating to `registry.registerBlobResource*`. **Status: Correct. Use this.**

Tradeoffs documented in Tier 1 spec. Key rationale: `McpProtocolHandler` is the public entry point, fixing it closes the
main gap without breaking any existing SPI implementor.

#### Option B: Change Default to `throw UnsupportedOperationException`

Replace empty body with
`throw new UnsupportedOperationException("Blob resources must be registered via McpRegistry or McpProtocolHandler")`.

**Tradeoffs:**

- Pro: Explicit failure instead of silent no-op; documents the intended design.
- Con: Breaking change — any third-party `McpRegistrar` implementation inheriting the default would suddenly throw.
- Verdict: Do NOT use. The no-op default is the correct choice for SPI flexibility.

#### Option C: Make Methods Abstract

Remove defaults; force all implementors to provide implementations.

**Tradeoffs:**

- Pro: Forces every `McpRegistrar` impl to be explicit.
- Con: Breaking change; `McpProtocolHandler` would need the override anyway (same amount of work as Option A with added
  churn).
- Verdict: Do NOT use.

**Recommendation: Option A (confirmed).** The Tier 1 fix design is correct. No deviation needed.

---

## Finding 2: `resources/templates/get` Dispatch Gap

### 2.1 Evidence

**`McpProtocolHandler.java:693–695` — dispatcher hard-rejects the method:**

```java
case "resources/templates/get":
    return new McpResponse(errorResponse(id, -32601,
            "Method not found: resources/templates/get — use resources/read with resolved URI"), sessionId);
```

**`McpProtocolHandler.java:1689–1706` — orphaned handler method:**

```java
private Map<String, Object> handleResourceTemplatesGet(Map<String, Object> params) {
    if (params == null) return errorResourceResult("Missing params");
    String uri = (String) params.get("uri");
    if (uri == null) return errorResourceResult("Missing resource URI");
    // ... resolves template, calls readBlob or read, returns contents
}
```

This method is defined but never called from the dispatcher's switch statement.

**`McpProtocolHandler.java:963–964` — `methodCategory` includes `resources/templates/get`:**

```java
case "resources/templates/list":
case "resources/templates/get":   // ← included in CATEGORY_READ
    return CATEGORY_READ;
```

This means the method is correctly categorized for rate limiting purposes even though it is unreachable via dispatch.

**`docs/architecture/MCP-COMPATIBILITY-2026.md` — explicit architectural decision (line 35):**

> "Use `resources/read` for resolved resource-template URIs. Do not expose custom `resources/templates/get`."

---

### 2.2 Classification

**`handleResourceTemplatesGet` is dead code** — defined but unreachable. The dispatch rejection is **intentional by
architectural decision**.

The method should be removed. Retaining it creates three hazards:

1. **Misleading maintenance signal** — a future developer sees the method and attempts to wire it up, not knowing it was
   intentionally disabled.
2. **Category-rate-limit dead code path** — `methodCategory` includes `resources/templates/get` in `CATEGORY_READ`, but
   the dispatch branch for this method never executes. This creates a phantom entry in the rate-limit categorization
   table.
3. **Compliance confusion** — the error message says "Method not found" (JSON-RPC -32601) which implies the method is
   unknown, not intentionally unsupported. A client receiving this error may interpret it as a bug rather than a
   protocol constraint.

---

### 2.3 Dispatch Behavior Decision: Disable

**Recommendation: Disable `resources/templates/get` (remove dead handler).**

Rationale:

1. The architectural decision in `MCP-COMPATIBILITY-2026.md` explicitly states this endpoint is not exposed. The dead
   handler contradicts documented intent.
2. The replacement contract (`resources/read` with resolved URI) is already implemented and tested.
3. Removing the dead code reduces maintenance surface and eliminates the misleading method.
4. The `methodCategory` entry for `resources/templates/get` should also be removed to eliminate the orphaned rate-limit
   categorization.

**Required removal:**

- `McpProtocolHandler.java:1689–1706` — `handleResourceTemplatesGet` method
- `McpProtocolHandler.java:963–964` — `case "resources/templates/get":` in `methodCategory` switch

**Error message clarification (optional improvement):** Change the dispatch rejection message from:

```
"Method not found: resources/templates/get — use resources/read with resolved URI"
```

to:

```
"Unsupported: resources/templates/get is not exposed by this server — " +
"use resources/read with a resolved (non-template) URI instead"
```

This makes the rejection intent clear without implying a bug.

---

## Finding 3: `McpReflectionRegistrar` — No Blob Annotation Support

### 3.1 Evidence

`McpReflectionRegistrar.java` processes `@McpResource` and `@McpResourceTemplate` but has no equivalent
`@McpBlobResource` or `@McpBlobResourceTemplate` annotations.

The `BlobResourceExample.java` example uses `@McpResource` with a `String`-returning method that manually
Base64-encodes:

```java
@McpResource(uri = "demo://blob-image", name = "Demo Image Blob",
        description = "Binary resource example", mimeType = "image/png")
public String image(String ignoredUri) {
    byte[] dummyImage = new byte[]{1, 2, 3, 4};
    return Base64.getEncoder().encodeToString(dummyImage);  // manual encoding
}
```

This is a workaround — the method returns `String` (Base64), not `McpBlobContent`. The annotation cannot express "this
returns a blob" because no `@McpBlobResource` annotation exists.

The reflection registrar calls `registerResourceTemplate(uri, name, desc, mimeType, uri -> invokeString(...))` which
stores a plain `McpResourceHandler`, not `McpBlobResourceHandler`. Even if `McpProtocolHandler` is fixed to forward blob
registrations, the reflection path cannot register blob handlers.

---

### 3.2 Gap Classification

**Gap: No `@McpBlobResource` annotation for `McpReflectionRegistrar`.** This is separate from the `McpRegistrar` bug —
it is a feature gap. Applications using annotation-based registration cannot express blob resources.

**Current workaround:** Return `Base64.getEncoder().encodeToString(bytes)` from a `@McpResource`-annotated method. This
works because the protocol layer serializes the `String` return as text content, not blob content. This is semantically
incorrect (the MIME type says `image/png` but the content is text/Base64) but functional.

---

## Summary Table

| Item                                                        | Classification      | Action                                                        |
|-------------------------------------------------------------|---------------------|---------------------------------------------------------------|
| `McpRegistrar.registerBlobResource` default no-op           | Stub (intentional)  | Superseded by `McpProtocolHandler` override (Tier 1 fix)      |
| `McpRegistrar.registerBlobResourceTemplate` default no-op   | Stub (intentional)  | Superseded by `McpProtocolHandler` override (Tier 1 fix)      |
| `McpProtocolHandler` missing blob overrides                 | **Bug**             | Fix: add two `@Override` methods (Tier 1 Option A, confirmed) |
| `handleResourceTemplatesGet` method                         | **Dead code**       | Remove method + `methodCategory` case                         |
| `methodCategory` case for `resources/templates/get`         | Orphaned dead code  | Remove (paired with handler removal)                          |
| Dispatch rejection message for `resources/templates/get`    | Misleading wording  | Clarify to "unsupported" instead of "not found"               |
| `@McpBlobResource` / `@McpBlobResourceTemplate` annotations | Feature gap         | Future work; out of scope for this triage                     |
| `McpReflectionRegistrar` blob processing                    | Feature gap         | Future work; out of scope for this triage                     |
| `McpBlobResourceHandler.readBlob` protocol dispatch         | **Fully supported** | No action needed                                              |
| `McpBlobContent` DTO                                        | **Fully supported** | No action needed                                              |

---

## Required Tests

### A. Blob Registration via `McpProtocolHandler` (Tier 1 Fix Validation)

File: `src/test/java/io/github/vinhphan812/mcp/McpBlobRegistrationTest.java` (new)

| Test                                              | Scenario                                                                              | Expected                                                                                            |
|---------------------------------------------------|---------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `registerBlobResource_forwardsToRegistry`         | Call `handler.registerBlobResource(...)`, then `handleResourcesRead`                  | Handler retrievable via `getResourceHandler`; `readBlob` called; `McpBlobContent` shape in response |
| `registerBlobResourceTemplate_forwardsToRegistry` | Call `handler.registerBlobResourceTemplate(...)`, resolve a template URI              | Handler retrievable via `getResourceTemplateHandler`; `readBlob` called                             |
| `registerBlobResource_notSilentNoop`              | Call `registerBlobResource` and verify resource appears in `getRegisteredResources()` | Resource definition present with correct URI                                                        |
| `registerBlobResource_duplicateThrows`            | Call `registerBlobResource` twice with same URI                                       | `IllegalArgumentException` thrown                                                                   |
| `registerBlobResourceTemplate_duplicateThrows`    | Call `registerBlobResourceTemplate` twice with same URI template                      | `IllegalArgumentException` thrown                                                                   |
| `handleResourcesRead_blobDispatch`                | Register blob handler, call `handleResourcesRead` with blob URI                       | Response shape: `{"contents":[{"type":"blob","blob":"<base64>","mimeType":"..."}]}`                 |
| `handleResourcesRead_stringDispatch_stillWorks`   | Register string handler via `registerResource`                                        | String dispatch unchanged; no regression                                                            |

### B. `resources/templates/get` Dispatch Removal

| Test                                   | Scenario                                         | Expected                                  |
|----------------------------------------|--------------------------------------------------|-------------------------------------------|
| `resources_templates_get_rejected`     | Send `resources/templates/get` JSON-RPC request  | `-32601` error with "unsupported" message |
| `resources_read_withResolvedUri_works` | Send `resources/read` with resolved template URI | Correct content response; no regression   |

### C. Reflection Registrar Blob Feature Gap (Document Only)

- No test needed now — feature gap, out of scope.
- When `@McpBlobResource` annotation is added, test: annotation-processed blob handler returns `McpBlobContent` shape
  via `handleResourcesRead`.

---

## Documentation Requirements

| Doc                                                  | Change                                                                                                                                                               |
|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `docs/architecture/blob-resource-spi-triage-spec.md` | Superseded by this Tier 2 doc; mark as historical                                                                                                                    |
| `docs/architecture/MCP-COMPATIBILITY-2026.md`        | Already correct (line 35 documents the decision); add cross-reference to removal of dead handler                                                                     |
| `McpRegistrar.java` Javadoc                          | Update `registerBlobResource` and `registerBlobResourceTemplate` NOTE comments to state that `McpProtocolHandler` now overrides and delegates these calls            |
| `McpProtocolHandler.java` Javadoc                    | Add `@since` on new override methods                                                                                                                                 |
| `McpProtocolHandler.java` dispatch block             | Clarify `resources/templates/get` error message from "not found" to "unsupported"                                                                                    |
| Release notes                                        | "Fixed: `McpProtocolHandler.registerBlobResource*` now correctly registers handlers instead of silently no-oping. Removed dead `handleResourceTemplatesGet` method." |

---

## Out of Scope (Future Work)

- `@McpBlobResource` and `@McpBlobResourceTemplate` annotations for `McpReflectionRegistrar`
- `McpReflectionRegistrar` blob handler processing
- `BlobResourceExample.java` rewrite to use proper `McpBlobResourceHandler` (currently uses manual Base64 workaround)
- Any changes to `McpRegistry` — its implementations are correct and require no changes

---

## References

- Tier 1 spec (t_82050dbd): `docs/architecture/blob-resource-spi-triage-spec.md`
- Compatibility plan: `docs/architecture/MCP-COMPATIBILITY-2026.md`
- `McpProtocolHandler.java:54` — class declaration
- `McpProtocolHandler.java:693–695` — dispatch rejection
- `McpProtocolHandler.java:963–964` — orphaned `methodCategory` case
- `McpProtocolHandler.java:1689–1706` — dead `handleResourceTemplatesGet`
- `McpProtocolHandler.java:1875–1908` — existing registration overrides
- `McpRegistrar.java:76–80` — `registerBlobResource` default no-op
- `McpRegistrar.java:118–123` — `registerBlobResourceTemplate` default no-op
- `McpRegistry.java:159` — working `registerBlobResource` impl
- `McpRegistry.java:204` — working `registerBlobResourceTemplate` impl
- `McpReflectionRegistrar.java:42–51` — no blob annotation processing
- `BlobResourceExample.java:9–14` — manual Base64 workaround
