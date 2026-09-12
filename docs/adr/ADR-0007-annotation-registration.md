# ADR-0007 — Annotation-Based Registration with Reflection Registrar

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

The SDK provides two registration paths: imperative (call `registrar.registerTool(...)` directly) and declarative (annotate methods and scan with `McpReflectionRegistrar`). The annotation approach reduces boilerplate for typical use cases while the imperative approach supports dynamic or generated registrations.

## Decision

### Annotation model

```
@Tools    → scan class for @McpTool methods
@Resources → scan class for @McpResource and @McpResourceTemplate methods
@Prompts  → scan class for @McpPrompt methods
```

Each annotated method is registered through the `McpRegistrar` SPI:

```java
@Tools
public class MyTools {
    @McpTool(name = "greet", description = "Greets a user")
    public Map<String, Object> greet(
            @McpParam(name = "name", required = true) String name) {
        return Map.of("text", "Hello, " + name + "!");
    }
}

McpReflectionRegistrar registrar = new McpReflectionRegistrar();
registrar.register(new MyTools(), myRegistrar);
```

### Parameter binding

Two modes are supported:

1. **Map mode** — one `Map<String, Object>` parameter: JSON arguments passed through unchanged.
2. **Direct mode** — individually annotated `@McpParam` parameters: arguments are bound by name and converted to the declared Java type.

Type conversion covers: `String`, `boolean`, `Boolean`, `int`, `Integer`, `long`, `Long`, `double`, `Double`, and `Map<String, Object>`.

### Return type enforcement

| Annotation        | Return type               |
| ---------------- | ------------------------ |
| `@McpTool`       | `Map<String, Object>`    |
| `@McpPrompt`      | `Map<String, Object>`   |
| `@McpResource`    | `String`                 |
| `@McpResourceTemplate` | `String`           |

The registrar validates return types at registration time and throws `IllegalArgumentException` on mismatch.

### Tool outputSchema

`@McpTool(outputSchema = "{...}")` accepts a raw JSON string. The registrar parses it with Gson and stores it in the registry. When `tools/list` is called, the schema is embedded in the response:

```json
{
  "name": "my-tool",
  "description": "...",
  "outputSchema": { "type": "object", "properties": { ... } }
}
```

## Consequences

**Positive:**

- Developers write less boilerplate; the registrar handles schema construction.
- `@McpParam` enables type-safe parameter binding without manual conversion.
- Return type validation catches misconfigured handlers at registration rather than at runtime.
- The annotation model is familiar from JAX-RS, Spring, and other Java frameworks.

**Negative:**

- Reflection scanning adds a startup cost proportional to the number of annotated methods.
- Methods annotated with multiple conflicting annotations produce an `IllegalArgumentException`.
- The annotation model is not suitable for dynamically generated or middleware-wrapped handlers; use the imperative API instead.
- Output schema is a raw JSON string, not a typed object; consumers must validate the string format.
