# Design: Minimal Compatible Metadata Registration

## Problem Statement

The `@McpTool` annotation defines `scopes()` and `confirmationRequired()` attributes (lines 27-37), but
`McpReflectionRegistrar.registerTool()` never reads them (lines 54-72). This causes metadata loss during
reflection-based registration, preventing:

- Scopes from reaching `McpAuthorization` for enforcement
- `confirmationRequired` from gating handler invocation

## Current Architecture

```
┌─────────────────────────┐      ┌──────────────────┐
│   @McpTool              │      │  McpRegistrar    │
│   - name                │      │  (SPI interface)│
│   - description        │─────▶│  - registerTool()│
│   - outputSchema       │      │    (5 params)    │
│   - scopes ❌           │      └────────┬─────────┘
│   - confirmationReq ❌ │               │
└─────────────────────────┘               │
                                          ▼
                               ┌────────────────────┐
                               │ McpRegistry        │
                               │ - registerTool(5)  │
                               │ - registerTool(7)   │
                               │   ✅ scopes        │
                               │   ✅ confirmReq    │
                               └────────────────────┘
```

**Key finding**: `McpRegistry` already has a 7-parameter `registerTool()` overload (lines 90-111) that accepts
`requiredScopes` and `confirmationRequired`. The SPI interface `McpRegistrar` only exposes 5 and 6-parameter versions.

## Design Decision

**Approach**: Detect `McpRegistry` at runtime and call its extended overload directly, preserving backward compatibility
with third-party `McpRegistrar` implementations.

### Implementation in McpReflectionRegistrar

```java
private static void registerTool(Object target, Method method, McpTool annotation,
                                 McpRegistrar registrar) {
    String name = nameOrMethod(annotation.name(), method);
    // ... existing parameter handling ...
    
    // NEW: Extract metadata from annotation
    String[] scopes = annotation.scopes();
    boolean confirmationRequired = annotation.confirmationRequired();
    
    // NEW: Route to appropriate overload based on registrar type
    if (registrar instanceof McpRegistry) {
        // Use 7-parameter overload (backward-compatible - only called when metadata present)
        ((McpRegistry) registrar).registerTool(
            name, annotation.description(), properties, required,
            scopes.length > 0 ? Arrays.asList(scopes) : null,
            confirmationRequired,
            handler);
    } else {
        // Fallback to SPI-compatible method (metadata lost - documented limitation)
        if (scopes.length > 0 || confirmationRequired) {
            // TODO: Log warning about metadata loss for non-McpRegistry registrars
        }
        if (outputSchema != null) {
            registrar.registerTool(name, annotation.description(), properties, required,
                    outputSchema, handler);
        } else {
            registrar.registerTool(name, annotation.description(), properties, required, handler);
        }
    }
}
```

### Alternative: Metadata Container (if runtime detection is unacceptable)

Create a wrapper that preserves metadata without changing SPI:

```java
public class ToolRegistration {
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final List<String> required;
    private final List<String> scopes;
    private final boolean confirmationRequired;
    private final McpToolHandler handler;
    
    // Builder pattern...
}

public interface McpRegistrar {
    // Existing methods...
    
    // NEW: Optional metadata-aware registration
    default void registerTool(ToolRegistration registration) {
        // Falls back to basic registration, metadata ignored by default
        registerTool(registration.getName(), registration.getDescription(),
            registration.getInputSchema(), registration.getRequired(),
            registration.getHandler());
    }
}
```

However, this adds complexity and the runtime detection approach is simpler.

## Integration Points

| Component                | Change Required                                          | Risk                           |
|--------------------------|----------------------------------------------------------|--------------------------------|
| `McpReflectionRegistrar` | Add metadata extraction + instanceof McpRegistry routing | Low - only adds new code paths |
| `McpRegistrar` (SPI)     | None                                                     | N/A                            |
| `McpRegistry`            | None - already has the overload                          | N/A                            |
| `McpProtocolHandler`     | None - already reads from definition                     | N/A                            |

## Backward Compatibility

1. **Third-party McpRegistrar implementations**: Continue working unchanged; metadata is silently lost (document as
   limitation)
2. **Existing @McpTool users without scopes/confirmationRequired**: No behavioral change
3. **Existing McpRegistry direct registration**: No change

## What This Enables

After implementation, this code works:

```java
@Tools
public class MyProvider {
    @McpTool(scopes = {"admin"}, confirmationRequired = true)
    public Map<String, Object> deleteAllData(Map<String, Object> args) {
        // ...
    }
}

McpReflectionRegistrar.register(new MyProvider(), registry);
// ✅ scopes="admin" and confirmationRequired=true are preserved
// ✅ McpAuthorization.denial() receives the scopes
// ✅ Handler invocation is gated by confirmationRequired
```

## Placeholder for Coordination

**Error codes**: The task specifies "Do NOT decide conflicting error codes". This design does not prescribe error codes.
When `McpAuthorization.denial()` returns a message, `McpProtocolHandler` throws `-32029` (see line 1524). Coordinate
with core task to determine if this is appropriate or if a new error code should be used for authorization failures.

## Summary

| Requirement                                               | Satisfied                                  |
|-----------------------------------------------------------|--------------------------------------------|
| Backward compatible with existing reflection registration | ✅ Yes - no breaking changes                |
| Works with current McpRegistrar/McpRegistry contract      | ✅ Yes - McpRegistry already has the method |
| Allows scopes to reach McpAuthorization                   | ✅ Yes - stored in tool definition          |
| Allows confirmationRequired to gate handler invocation    | ✅ Yes - checked in protocol handler        |
| Minimal API extension                                     | ✅ Yes - no interface changes required      |
