# Authorization Scopes & Confirmation — Test Specification

**Task:**       t_9fc7b2b0
**Author:**     dev-qa
**Created:**    2026-09-20
**Status:**     Specification only — implementation follows
**Test target:** `McpReflectionAuthorizationTest.java` (new)

---

## 1. Overview

The test class proves the end-to-end authorization pipeline: annotation attributes declared
on `@McpTool` methods (`scopes`, `confirmationRequired`) survive reflection registration and
reach `McpAuthorization.denial(...)` at tool-call time, and that a non-null denial short-
circuits handler invocation.

All tests use `McpReflectionRegistrar` as the registration entry point — never a direct
`McpRegistry.registerTool(...)` call — so the reflection path is exercised.

---

## 2. Test Fixtures

### 2.1 Annotated tool classes

These live inside `McpReflectionAuthorizationTest.java` (package-private, not a top-level
class) so they require no extra source files.

```java
// TC1–TC4: scopes + outputSchema (guards against schema/metadata regression)
@McpTool(name = "admin-panel", description = "Admin panel", scopes = {"admin"}, outputSchema = OUTPUT_SCHEMA)
public Map<String, Object> adminPanel(Map<String, Object> args) { /* ... */ }

// TC5–TC6: scopes only
@McpTool(name = "format-address", description = "Format address", scopes = {"format:address"})
public Map<String, Object> formatAddress(Map<String, Object> args) { /* ... */ }

// TC7–TC8: confirmationRequired only
@McpTool(name = "search-catalog", description = "Search catalog", confirmationRequired = true)
public Map<String, Object> searchCatalog(Map<String, Object> args) { /* ... */ }

// TC9–TC10: no policy attributes (default compatibility)
@McpTool(name = "simple-echo", description = "Echo args")
public Map<String, Object> simpleEcho(Map<String, Object> args) { /* ... */ }

// TC11–TC12: both attributes set
@McpTool(name = "delete-record", description = "Delete a record", scopes = {"write"},
         confirmationRequired = true)
public Map<String, Object> deleteRecord(Map<String, Object> args) { /* ... */ }
```

`OUTPUT_SCHEMA` is a static final JSON string field so the annotation can reference it
without a class-level constant — keeps the fixture self-contained.

### 2.2 Shared setup

```java
private McpRegistry          registry;
private McpProtocolHandler  handler;
private String              sessionId;

@BeforeEach
void setup() {
    registry = new McpRegistry();
    handler  = new McpProtocolHandler(registry,
              McpServerConfig.builder().tools(true).build());
    sessionId = handler.handleRequestResponse(
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null)
              .getSessionId();
}
```

### 2.3 Tool registration helper

```java
private void register(Object provider) {
    new McpReflectionRegistrar().register(provider, registry);
}
```

---

## 3. Test Cases

### TC1: Metadata preservation — requiredScopes in tool definition

```
GIVEN  an annotated tool class with @McpTool(scopes = {"admin"})
WHEN   McpReflectionRegistrar.register(...) is called
THEN   registry.getToolDefinition("admin-panel") contains
       key "requiredScopes" with value a List containing "admin"
AND    "confirmationRequired" is absent (not set, default false)
```

**Assertions:**

- `definition.get("requiredScopes")` is not null
- `((List<?>) definition.get("requiredScopes")).contains("admin")`
- `definition.containsKey("confirmationRequired")` is false

---

### TC2: Metadata preservation — confirmationRequired in tool definition

```
GIVEN  an annotated tool class with @McpTool(confirmationRequired = true)
WHEN   McpReflectionRegistrar.register(...) is called
THEN   registry.getToolDefinition("search-catalog") contains
       key "confirmationRequired" with value Boolean.TRUE
AND    "requiredScopes" is absent (not set, scopes default to empty)
```

**Assertions:**

- `definition.get("confirmationRequired")` equals `Boolean.TRUE`
- `definition.containsKey("requiredScopes")` is false

---

### TC3: Metadata preservation — both attributes present

```
GIVEN  an annotated tool class with @McpTool(scopes = {"write"}, confirmationRequired = true)
WHEN   McpReflectionRegistrar.register(...) is called
THEN   registry.getToolDefinition("delete-record") contains both
       "requiredScopes" (List with "write") and "confirmationRequired" (true)
```

**Assertions:**

- `((List<?>) def.get("requiredScopes")).contains("write")`
- `def.get("confirmationRequired")` equals `Boolean.TRUE`

---

### TC4: Metadata preservation — outputSchema alongside scopes (regression guard)

```
GIVEN  an annotated tool with both scopes and outputSchema
WHEN   McpReflectionRegistrar.register(...) is called
THEN   the tool definition contains "inputSchema", "requiredScopes", and "outputSchema"
       with non-null values for all three
```

**Assertions:**

- `def.get("inputSchema")` is a Map
- `def.get("requiredScopes")` is a List
- `def.get("outputSchema")` is a Map
- All three are present simultaneously (guards against schema/metadata exclusivity)

---

### TC5: Authorization receives scopes — happy path (null denial)

```
GIVEN  a registered tool annotated @McpTool(scopes = {"admin"})
AND    an McpAuthorization spy that records the scopes it received
WHEN   a tools/call request for "admin-panel" is sent
THEN   authorization.denial(...) was called with:
         arg[0] (String[]) containing "admin"
         arg[1] (boolean) equal to false
         arg[2] (Map) equal to the call arguments
AND    the tool handler was invoked (side-effect counter incremented)
AND    the JSON-RPC response contains "result"
```

**Implementation of the spy:**

```java
String[]  capturedScopes    = null;
boolean   capturedConfReq  = false;
Map<?, ?> capturedArgs     = null;

McpAuthorization spy = (scopes, conf, args) -> {
    capturedScopes   = scopes;
    capturedConfReq = conf;
    capturedArgs    = args;
    return null;  // allow
};
// Re-configure handler with the spy
handler = new McpProtocolHandler(registry,
    McpServerConfig.builder().tools(true).authorization(spy).build());
```

**Assertions:**

- `capturedScopes` is not null, length == 1, contains "admin"
- `capturedConfReq` is `false`
- `capturedArgs` equals the request arguments map
- Response does not contain "error"

---

### TC6: Authorization receives confirmationRequired — happy path (null denial)

```
GIVEN  a registered tool annotated @McpTool(confirmationRequired = true)
AND    an McpAuthorization spy
WHEN   a tools/call request for "search-catalog" is sent
THEN   authorization.denial(...) was called with:
         arg[0] (String[]) empty array
         arg[1] (boolean) equal to true
         arg[2] (Map) equal to the call arguments
AND    the tool handler was invoked
AND    the JSON-RPC response contains "result"
```

**Assertions:**

- `capturedScopes.length` == 0
- `capturedConfReq` is `true`
- Response does not contain "error"

---

### TC7: Authorization receives both attributes

```
GIVEN  a registered tool annotated @McpTool(scopes = {"write"}, confirmationRequired = true)
AND    an McpAuthorization spy
WHEN   a tools/call request for "delete-record" is sent
THEN   authorization.denial(...) was called with:
         arg[0] containing "write"
         arg[1] equal to true
         arg[2] the call arguments
AND    the tool handler was invoked
```

---

### TC8: Authorization receives empty scopes + false confirmation for default tool

```
GIVEN  a registered tool annotated @McpTool(name = "simple-echo") with no policy attributes
AND    an McpAuthorization spy
WHEN   a tools/call request for "simple-echo" is sent
THEN   authorization.denial(...) was called with:
         arg[0] an empty String array
         arg[1] equal to false
         arg[2] the call arguments
AND    the tool handler was invoked
```

**Assertions:**

- `capturedScopes` is an empty array (not null)
- `capturedConfReq` is `false`
- Handler was invoked

---

### TC9: Denial short-circuits handler — scope denial

```
GIVEN  a registered tool annotated @McpTool(scopes = {"admin"})
AND    an McpAuthorization that returns "Missing required scope: admin"
AND    a handler that sets an atomic flag on invocation
WHEN   a tools/call request for "admin-panel" is sent
THEN   the JSON-RPC response contains error code -32029
AND    the response body contains "Missing required scope: admin"
AND    the handler flag is NOT set (handler was never called)
```

**Implementation:**

```java
AtomicBoolean handlerCalled = new AtomicBoolean(false);
McpAuthorization deny = (scopes, conf, args) -> {
    if (Arrays.asList(scopes).contains("admin")) {
        return "Missing required scope: admin";
    }
    return null;
};
// tool body: handlerCalled.set(true); return Map.of(...)
```

**Assertions:**

- Response contains "-32029"
- Response contains "Missing required scope: admin"
- `handlerCalled.get()` is `false`

---

### TC10: Denial short-circuits handler — confirmationRequired denial

```
GIVEN  a registered tool annotated @McpTool(confirmationRequired = true)
AND    an McpAuthorization that returns "User confirmation required" when conf==true
AND    a handler with an invocation counter
WHEN   a tools/call request for "search-catalog" is sent
THEN   the response contains error -32029
AND    the response body contains "User confirmation required"
AND    the handler counter is zero (not incremented)
```

**Assertions:**

- Same pattern as TC9 but for `confirmationRequired`
- `handlerCalled.get()` is `false`

---

### TC11: Denial short-circuits handler — both attributes, scopes match but confirmation denied

```
GIVEN  a registered tool annotated @McpTool(scopes = {"write"}, confirmationRequired = true)
AND    an McpAuthorization that denies on confirmationRequired only
AND    a handler with an invocation counter
WHEN   a tools/call request for "delete-record" is sent
THEN   the response contains error -32029
AND    the response body contains the confirmation denial message
AND    the handler counter is zero
```

**Assertions:**

- `capturedScopes` contained "write"
- `capturedConfReq` was `true`
- `handlerCalled.get()` is `false`

---

### TC12: Authorization not configured — all tools allowed regardless of annotation

```
GIVEN  a registered tool annotated @McpTool(scopes = {"admin"}, confirmationRequired = true)
AND    no McpAuthorization configured (null)
WHEN   a tools/call request for "admin-panel" is sent
THEN   the handler is invoked (no authorization gate)
AND    the response contains "result" (not an error)
```

**Assertions:**

- Response does not contain "error"
- `handlerCalled.get()` is `true`

---

## 4. Coverage Summary

| Path                       | TC(s)              |
|----------------------------|--------------------|
| Scopes reach authorization | TC1, TC5           |
| confirmationRequired reach | TC2, TC6           |
| Both attributes together   | TC3, TC7, TC11     |
| OutputSchema + scopes      | TC4                |
| Default tool (no attrs)    | TC8                |
| Denial short-circuits      | TC9, TC10, TC11    |
| No auth configured → allow | TC12               |
| Happy path (null denial)   | TC5, TC6, TC7, TC8 |
| Denial path                | TC9, TC10, TC11    |

---

## 5. Anti-patterns to Avoid

- Do NOT assert on a specific error code string in TC9–TC11 — the code is owned by a separate
  task and `-32029` may change. Assert on the presence of any error and the denial message text.
- Do NOT test `McpRegistry.registerTool(...)` directly — the reflection path is the subject.
- Do NOT assume an empty scope array is `null` — the protocol handler converts to `new String[0]`.
- Do NOT assert on the full JSON-RPC envelope; assert on specific substrings that indicate the
  result or error, as response formatting may evolve.

---

## 6. File Location and Gradle Command

```
src/test/java/io/github/vinhphan812/mcp/McpReflectionAuthorizationTest.java
```

Run the new test class:

```
./gradlew test --tests io.github.vinhphan812.mcp.McpReflectionAuthorizationTest --console=plain
```

Run alongside existing authorization tests:

```
./gradlew test --tests io.github.vinhphan812.mcp.McpReflectionAuthorizationTest \
               --tests io.github.vinhphan812.mcp.McpAuthorizationTest \
               --console=plain
```

Full suite:

```
./gradlew test --console=plain
```
