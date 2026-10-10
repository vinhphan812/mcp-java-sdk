# Tool Schema Validation Guide

> **Spec reference:** [MCP 2026-07-28 §Tool Schemas](https://modelcontextprotocol.io/specification)
> **Dialect:** JSON Schema 2020-12 (`https://json-schema.org/draft/2020-12/schema`)
> **Server version:** `feat/elicitation-v2` / `feat/schema-validation`

---

## Overview

Every tool registered with `McpRegistry.registerTool(…)` carries an **input schema** (describing the arguments the client may send) and optionally an **output schema** (describing the shape the tool must return). The SDK validates incoming arguments and outgoing results against these schemas to catch contract violations early and provide clients with actionable diagnostics.

---

## Supported dialect

The validator uses [networknt/json-schema-validator 1.5.0](https://github.com/networknt/json-schema-validator) with `SpecVersion.VersionFlag.V202012`, which implements **JSON Schema 2020-12** — the same dialect the MCP 2026 specification mandates for tool schemas.

Supported keywords (non-exhaustive):

| Category | Keywords |
|---|---|
| Type | `type`, `nullable` |
| Numeric | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf` |
| String | `minLength`, `maxLength`, `pattern`, `format` |
| Array | `items`, `minItems`, `maxItems`, `uniqueItems` |
| Object | `properties`, `required`, `additionalProperties`, `patternProperties` |
| Enum | `enum`, `const` |
| Conditional | `if`, `then`, `else` |
| Reference | `$ref` (local only; `#`-anchored) |

---

## Validation policy

### Registration-time (static)

When a tool is registered, `SchemaValidator.validateSchema(inputSchema)` and `SchemaValidator.validateSchema(outputSchema)` are called. Invalid schemas throw `IllegalArgumentException` immediately, before the tool enters the registry. This catches misconfiguration at startup rather than at runtime.

### Request-time (runtime)

| Mode | Protocol version | Behaviour |
|---|---|---|
| **Stateless** (2026) | `2026-07-28` | Full JSON Schema 2020-12 validation of input and output. Violations return JSON-RPC error `-32602 Invalid params`. |
| **Sessioned** (2025) | `2025-11-25` | Legacy behaviour: only `required[]` array is checked. Type, format, numeric, and other constraints are **not** enforced. |

The `stateless` flag is `true` when:
- `McpServerConfig.ProtocolMode.STATELESS` is set, **or**
- the request carries `protocolVersion: "2026-07-28"` in its top-level or `params`.

### Response-time (runtime)

When an `outputSchema` is declared, the tool result is validated against it before the response is returned. A violation causes a JSON-RPC error `-32602`.

---

## Output schema

Declare an output schema when the tool's return shape is contractually significant (e.g. structured results consumed programmatically):

```java
Map<String, Object> outputSchema = new LinkedHashMap<>();
outputSchema.put("type", "object");
outputSchema.put("properties", Map.of(
        "status", Map.of("type", "string", "enum", List.of("ok", "error")),
        "value", Map.of("type", "number")
));
outputSchema.put("required", List.of("status"));

registry.registerTool("compute", "Compute something",
        inputSchema, List.of("x"),
        outputSchema,
        args -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "ok");
            r.put("value", ((Number) args.get("x")).doubleValue() * 2);
            return r;
        });
```

---

## Defensive copies (no silent mutation)

The registry makes a **deep defensive copy** of every `inputSchema` and `outputSchema` at registration time. Mutations to the original `Map` objects after `registerTool(…)` returns do **not** affect the stored schemas, and the stored schemas are exposed as immutable snapshots through `getToolDefinition(name)`.

---

## Error diagnostics

Schema validation errors are surfaced as JSON-RPC `-32602` with a human-readable `message` that includes the specific keyword that failed and why:

```
Invalid tool arguments: $.age: expected type Number, found String
```

For output schema violations:

```
Tool result violates outputSchema: $.result: expected type String, found Integer
```

---

## Limitations

- `$ref` supports local `#`-anchored references only. External schema files or remote `$id` are not resolved.
- Custom `format` validators (e.g. `date-time`, `email`) are loaded from the networknt bundle but behaviour depends on the validator version.
- In 2025 sessioned mode, the legacy `required[]` check does not support nested schema validation. Use the 2026 stateless mode for full schema enforcement.

---

## Migration checklist

To enable full 2020-12 validation:

1. **Switch to stateless mode** or ensure clients send `protocolVersion: "2026-07-28"` in their requests.
2. **Audit existing tool schemas** — ensure `type`, `properties`, and `required` are correctly declared.
3. **Add `outputSchema`** to tools that return structured data.
4. **Run the test suite** (`./gradlew test`) — all `ToolSchemaValidationTest` cases must pass.
5. **Check for `-32602` errors in production logs** — these indicate tools returning data that violates the declared schema.
