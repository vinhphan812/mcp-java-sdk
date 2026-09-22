# Triage Spec: Silent Blob-Resource Registration Through Public SPI

**Task:** t_82050dbd
**Date:** 2026-09-20
**Scope:** Tier 1 — triage/specification only; do not implement in this card.

---

## 1. Verify Current State

### 1.1 McpRegistrar Interface

`src/main/java/io/github/vinhphan812/mcp/api/spi/McpRegistrar.java`

| Method                                                         | Kind              | Notes                                                   |
|----------------------------------------------------------------|-------------------|---------------------------------------------------------|
| `registerTool(...)` x2                                         | abstract          | requires impl                                           |
| `registerResource(uri, name, desc, handler)`                   | default           | delegates to 4-param overload                           |
| `registerResource(uri, name, desc, mimeType, handler)`         | abstract          | requires impl                                           |
| **`registerBlobResource(uri, name, desc, mimeType, handler)`** | **default no-op** | NOTE comment: "callers should use McpRegistry directly" |
| `registerResourceTemplate(...)` x2                             | default/abstract  | mirrors resource pattern                                |
| **`registerBlobResourceTemplate(...)`**                        | **default no-op** | NOTE comment: "callers should use McpRegistry directly" |
| `registerPrompt(...)`                                          | abstract          | requires impl                                           |
| `registerCompletionProvider(...)`                              | abstract          | requires impl                                           |
| `notifyResourceUpdated(...)`                                   | abstract          | from `McpResourceUpdateListener`                        |

Both `registerBlobResource` (line 76-80) and `registerBlobResourceTemplate` (line 118-123) have empty bodies — they
silently no-op.

### 1.2 McpRegistry Implementation

`src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java`

| Method                                         | Override?       | Behavior                                                                                                                                                                                    |
|------------------------------------------------|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `registerBlobResource(...)` (line 159)         | YES `@Override` | Fully implemented: acquires lock, checks uniqueness against `resourceHandlers`, adds to `registeredResources` + `resourceHandlers`                                                          |
| `registerBlobResourceTemplate(...)` (line 204) | YES `@Override` | Fully implemented: acquires lock, checks uniqueness against `resourceTemplateHandlers`, adds to `registeredResourceTemplates` + `resourceTemplateHandlers` + `blobResourceTemplateHandlers` |

`McpRegistry` also maintains a separate `blobResourceTemplateHandlers` map (line 36) used by
`getBlobResourceTemplateHandlers()` for protocol dispatch.

### 1.3 McpProtocolHandler Implementation

`src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`

`McpProtocolHandler` implements `McpRegistrar` (line 54) and wraps an `McpRegistry` instance. It delegates all tool,
resource, and resource-template registrations to the registry:

- `registerTool(...)` — delegates to `registry.registerTool(...)`
- `registerResource(...)` — delegates to `registry.registerResource(...)`
- `registerResourceTemplate(...)` — delegates to `registry.registerResourceTemplate(...)`

**Critical gap:** `registerBlobResource(...)` and `registerBlobResourceTemplate(...)` are **NOT overridden** —
`McpProtocolHandler` inherits the interface defaults and silently no-ops. This is the root bug.

### 1.4 McpReflectionRegistrar

`src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java`

Does NOT process blob resources. There is no `@McpBlobResource` annotation — only `@McpResource` and
`@McpResourceTemplate` are handled. `McpReflectionRegistrar` always calls
`registerResourceTemplate(uri, name, desc, mimeType, uri -> invokeString(...))` with a `String`-returning handler.

### 1.5 Protocol Dispatch

`McpProtocolHandler.handleResourcesRead` (line 1668-1673) checks `if (handler instanceof McpBlobResourceHandler)` at
runtime and calls `readBlob(uri)` — the protocol layer correctly dispatches to blob handlers when registered. The
`findTemplateHandler` method (line 1715) also consults `getBlobResourceTemplateHandlers()`.

---

## 2. Problem Root Cause

```
McpServer (McpProtocolHandler wrapper)
  └─ implements McpRegistrar
       └─ registerBlobResource(...)      ← INHERITS default no-op! (not overridden)
            └─ registry.registerBlobResource(...)  ← NEVER CALLED
```

When an application calls `mcpServer.registerBlobResource(uri, name, desc, mimeType, handler)`, the call reaches
`McpProtocolHandler`, which has no override — the registration silently drops. The handler never appears in
`McpRegistry.registeredResources`, `resourceHandlers`, or `blobResourceTemplateHandlers`, so the protocol layer's
dispatch (`handleResourcesRead`) will never find it.

The only working path is `McpServer.register(provider)` → `McpReflectionRegistrar` →
`McpRegistry.registerResourceTemplate(...)` directly — but this also doesn't handle blobs because no `@McpBlobResource`
annotation exists.

---

## 3. Candidate Fixes

### Option A: Override in McpProtocolHandler (Recommended)

Add two method overrides in `McpProtocolHandler` that delegate to the wrapped registry, matching the existing pattern
for all other registration methods.

**Pros:**

- Minimal, targeted change
- Consistent with every other registration method in `McpProtocolHandler`
- No API contract change
- Source and binary compatible
- Existing `McpRegistry` implementations already do the right thing
- No third-party SPI impact (callers using `McpProtocolHandler` / `McpServer` get working registration)

**Cons:**

- Does not address `McpRegistrar` default methods directly
- Does not help third-party `McpRegistrar` implementations that rely on the default

### Option B: Change Default in McpRegistrar to Throw UnsupportedOperationException

Replace the empty body with
`throw new UnsupportedOperationException("Blob resources must be registered via McpRegistry directly")`.

**Pros:**

- Forces callers to use `McpRegistry` directly — explicit failure instead of silent no-op
- Documents the intended design

**Cons:**

- Breaking change: any third-party `McpRegistrar` implementation relying on the default no-op would break
- More disruptive than Option A

### Option C: Make Methods Abstract

Remove the defaults and require all implementors to provide implementations.

**Pros:**

- Forces implementations to be explicit

**Cons:**

- Breaking change: all existing `McpRegistrar` implementations must add these methods
- `McpProtocolHandler` already has this problem (it currently silently no-ops — making it abstract would at least
  surface the gap as a compile error)
- Binary breaking change for .class files

---

## 4. Recommended Approach: Option A

**Fix:** Add `@Override registerBlobResource(...)` and `@Override registerBlobResourceTemplate(...)` to
`McpProtocolHandler` delegating to the wrapped registry.

**Rationale:**

1. `McpProtocolHandler` is the public entry point (`McpServer`), so fixing it closes the main gap.
2. `McpRegistry` already has correct implementations — no need to change defaults.
3. The note comments in the interface defaults ("callers should use McpRegistry directly") already document the intended
   pattern — fixing the protocol handler makes this true.
4. The default no-op remains available for any edge case where a third-party SPI implementor needs a no-op.

**Supplements:**

- **Document the limitation** in `McpRegistrar` Javadoc: state that calling `registerBlobResource*` on
  `McpProtocolHandler` without an override was silently dropping registrations, and that the override has now been
  added.
- **Add a `@since`** tag to the new overrides.

---

## 5. Specific API Changes

### 5.1 McpProtocolHandler.java

Add two new method overrides after the existing `registerResourceTemplate` delegate:

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

Placement: after line 1910 (`registerResourceTemplate` 4-param override), before `onRegistryChanged`.

### 5.2 McpRegistrar.java

Update Javadoc for `registerBlobResource` and `registerBlobResourceTemplate` to clarify behavior:

```java
/**
 * Registers an MCP blob resource that returns binary data.
 * The handler's {@link McpBlobResourceHandler#readBlob(String)} result is
 * serialised as MCP {@code type=blob} content.
 *
 * @param uri         resource URI
 * @param name        resource name
 * @param description resource description
 * @param mimeType    resource MIME type
 * @param handler     blob resource handler
 * @see McpRegistry#registerBlobResource(String, String, String, String, McpBlobResourceHandler)
 */
default void registerBlobResource(...) { /* empty */ }
```

(Same pattern for `registerBlobResourceTemplate`)

---

## 6. Test Specifications

### Test 6.1: McpProtocolHandlerBlobRegistrationTest.java (new file)

**Package:** `io.github.vinhphan812.mcp`

**Purpose:** Verify that `McpProtocolHandler` correctly forwards blob registrations to the registry.

```java
// Test 1: registerBlobResource forwards to registry
@Test
void registerBlobResource_forwardsToRegistry() throws Exception {
    McpProtocolHandler handler = new McpProtocolHandler();
    McpBlobResourceHandler blobHandler = mock(McpBlobResourceHandler.class);
    when(blobHandler.readBlob("blob://test")).thenReturn(
        new McpBlobContent(new byte[]{1,2,3}, "application/octet-stream"));

    handler.registerBlobResource("blob://test", "Test Blob", "desc", "application/octet-stream", blobHandler);

    // Verify handler is retrievable
    assertNotNull(handler.getResourceHandler("blob://test"));
    assertSame(blobHandler, handler.getResourceHandler("blob://test"));
    // Verify registered in registry
    assertEquals(1, handler.getRegisteredResources().size());
    assertEquals("blob://test", handler.getRegisteredResources().get(0).get("uri"));
}

// Test 2: registerBlobResourceTemplate forwards to registry and populates blobResourceTemplateHandlers
@Test
void registerBlobResourceTemplate_forwardsToRegistry() throws Exception {
    McpProtocolHandler handler = new McpProtocolHandler();
    McpBlobResourceHandler blobHandler = mock(McpBlobResourceHandler.class);

    handler.registerBlobResourceTemplate("blob://template/{id}", "Template Blob", "desc",
        "image/png", blobHandler);

    assertSame(blobHandler, handler.getResourceTemplateHandler("blob://template/{id}"));
    assertEquals(1, handler.getRegisteredResourceTemplates().size());
}

// Test 3: calling registerBlobResource on McpProtocolHandler (before fix) would be a no-op
// This test documents the fixed behavior — it MUST register, not no-op.
@Test
void registerBlobResource_doesNotSilentlyNoop() throws Exception {
    McpProtocolHandler handler = new McpProtocolHandler();
    McpBlobResourceHandler blobHandler = mock(McpBlobResourceHandler.class);

    handler.registerBlobResource("blob://silent-test", "Silent", "desc", "text/plain", blobHandler);

    // The resource must appear in the list — this would fail if it silently no-oped
    boolean found = handler.getRegisteredResources().stream()
        .anyMatch(r -> "blob://silent-test".equals(r.get("uri")));
    assertTrue(found, "registerBlobResource must not silently fail");
}

// Test 4: duplicate registration throws IllegalArgumentException
@Test
void registerBlobResource_duplicateThrows() throws Exception {
    McpProtocolHandler handler = new McpProtocolHandler();
    McpBlobResourceHandler blobHandler = mock(McpBlobResourceHandler.class);

    handler.registerBlobResource("blob://dup", "Dup", "desc", "text/plain", blobHandler);
    assertThrows(IllegalArgumentException.class, () ->
        handler.registerBlobResource("blob://dup", "Dup2", "desc2", "text/plain", blobHandler));
}
```

### Test 6.2: Integration test — blob resource roundtrip

**File:** existing `McpIntegrationTest.java` or new `McpBlobIntegrationTest.java`

- Start server, call `MCPProtocolHandler.handleResourcesRead` with a blob URI
- Assert `McpBlobContent` shape in response: `{"type":"blob","blob":"<base64>","mimeType":"..."}`

---

## 7. Migration / Breaking Change Assessment

| Dimension                                 | Impact                                                                                                                                                                                  |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Source compatibility                      | **None** — added new methods to `McpProtocolHandler`; no existing code uses these methods (they were no-ops)                                                                            |
| Binary compatibility                      | **None** — adding methods to a class does not break existing .class files                                                                                                               |
| Third-party McpRegistrar impls            | **None** — the default no-op remains in the interface; existing implementations continue working                                                                                        |
| Third-party McpProtocolHandler subclasses | **Low** — if a subclass overrides the blob methods (unlikely, since they were no-ops), the new override in parent class may affect resolution order; mark `@Override` in test to verify |

**Migration steps for downstream users:**
None required. The fix makes previously-broken API calls work correctly. No API signatures changed.

---

## 8. Documentation Requirements

1. **McpRegistrar Javadoc update** — add `@see McpRegistry#registerBlobResource` cross-reference and a note that
   `McpProtocolHandler` now delegates these calls.
2. **Release note** — "Fixed: `McpProtocolHandler.registerBlobResource(...)` and `registerBlobResourceTemplate(...)` now
   correctly register handlers instead of silently no-oping."
3. **Optional**: Add `@McpBlobResource` / `@McpBlobResourceTemplate` annotations for `McpReflectionRegistrar` processing
   in a future card (out of scope for this fix).

---

## 9. Summary

| Item              | Detail                                                                                                                               |
|-------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| Root cause        | `McpProtocolHandler` implements `McpRegistrar` but does not override `registerBlobResource*` — inherits interface default empty body |
| Impact            | Any call to `mcpServer.registerBlobResource(...)` silently drops; blob handler never registered; protocol dispatch fails at runtime  |
| Fix               | Add two `@Override` methods in `McpProtocolHandler` delegating to `registry.registerBlobResource*`                                   |
| Risk              | Very low — targeted addition; existing registry implementations are correct                                                          |
| Test coverage gap | No tests existed for blob registration via `McpRegistrar` / `McpProtocolHandler`                                                     |
