# Test Requirements: Preserve tool scopes and confirmation in reflection registration

## Summary of Design Approach

The fix involves updating `McpReflectionRegistrar.registerTool` to extract `@McpTool` metadata (`scopes` and
`confirmationRequired`) and leverage the existing 7-parameter `McpRegistry.registerTool` overload.
To maintain backward compatibility with `McpRegistrar` implementations that only support the 5-parameter method,
`McpReflectionRegistrar` will perform a runtime check (`instanceof McpRegistry`). If supported, it calls the 7-parameter
overload; otherwise, it falls back to the 5-parameter version.

## Test Scenarios

### 1. Scopes survive reflection registration and reach McpAuthorization

* **Goal**: Verify that scopes defined in `@McpTool` are correctly passed to `McpAuthorization` upon tool invocation.
* **Setup**:
    * Register a tool using `McpReflectionRegistrar` from a class with `@McpTool(scopes={"test-scope"})`.
    * Configure `McpServerConfig` with a custom `McpAuthorization` implementation that inspects passed scopes.
* **Assertion**: When the tool is called, the custom `McpAuthorization` implementation receives `{"test-scope"}` in the
  `requiredScopes` array.

### 2. confirmationRequired prevents handler invocation when denied

* **Goal**: Verify that tools with `confirmationRequired=true` can be denied based on `McpAuthorization` feedback.
* **Setup**:
    * Register a tool with `@McpTool(confirmationRequired=true)`.
    * Configure `McpAuthorization` to return a denial string when `confirmationRequired` is `true`.
* **Assertion**: Tool invocation returns an error (denial string) and the tool handler is not executed (verify via a
  side-effect flag, e.g., an array `called` as used in `McpAuthorizationTest`).

### 3. Backward compatibility with existing non-scoped tools

* **Goal**: Ensure existing tools without explicit `scopes` or `confirmationRequired` (default values) continue to work.
* **Setup**:
    * Register a tool without `@McpTool` metadata (defaults used).
    * Invoke the tool.
* **Assertion**: Tool executes successfully without being denied by `McpAuthorization`.

### 4. Denial message detection for rate-limit vs authorization

* **Goal**: Verify that denial messages (from rate limit vs authorization) are distinguishable or correctly handled,
  aiming for the agreed-upon error mapping.
* **Setup**:
    * Configure a tool with mandatory `scopes`. Call without `scopes`.
    * Configure a tool with rate limit threshold. Exceed the limit.
* **Assertion**: Verify distinct error responses.

## Test Structure and Assertion Points

### Proposed Test File: `McpReflectionAuthorizationTest.java`

```java
// Test provider class for reflection registration
class TestScopesProvider {
    @McpTool(name = "scoped-tool", description = "Tool with scope", scopes = {"admin"})
    public Map<String, Object> scopedTool(Map<String, Object> args) { ... }

    @McpTool(name = "confirm-tool", description = "Tool requiring confirmation", confirmationRequired = true)
    public Map<String, Object> confirmTool(Map<String, Object> args) { ... }

    @McpTool(name = "plain-tool", description = "Plain tool without metadata")
    public Map<String, Object> plainTool(Map<String, Object> args) { ... }
}
```

### Assertion Points

| Test                                    | Key Assertions                                                                                                                                               |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Scopes survive registration             | 1. `McpAuthorization.denial` receives correct `requiredScopes` array. 2. Tool definition in registry contains `requiredScopes`.                              |
| confirmationRequired prevents execution | 1. `McpAuthorization.denial` receives `confirmationRequired=true`. 2. Handler `called` flag remains `false`. 3. Response contains error with denial message. |
| Backward compatibility                  | 1. Plain tool executes successfully. 2. `McpAuthorization.denial` receives empty/null scopes array.                                                          |
| Denial message distinction              | 1. Authorization denial: error code -32029, message contains "denial" or scope detail. 2. Rate-limit denial: error code -32029 (see note below).             |

**Note on Error Codes**: The test `McpAuthorizationTest.java` expects error code `-32029` for authorization denial, but
the current implementation in `McpProtocolHandler.java` does NOT yet return this code. This is a known gap. Coordinate
with the core implementation task to add the proper error code handling.

## Existing Test Files to Extend

| File                        | Current Coverage                           | Recommended Extension                                     |
|-----------------------------|--------------------------------------------|-----------------------------------------------------------|
| `McpAuthorizationTest.java` | Authorization denial, null auth allows all | Add tests using `McpReflectionRegistrar` path             |
| `McpIntegrationTest.java`   | Full protocol flow                         | Add tool call with scopes via reflection-registered tools |

## Validation Checklist

- [ ] Unit tests in `McpReflectionAuthorizationTest.java` pass.
- [ ] Integration via `McpIntegrationTest.java` confirms metadata appears in `tools/list`.
- [ ] Backward compatibility: existing tests (without scopes/confirmationRequired) pass.
- [ ] Authorization denial blocks handler execution (handler not called).
- [ ] Rate-limit vs authorization denial messages distinguishable.
