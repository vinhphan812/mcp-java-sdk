# Tool Schema Validation Guide

> **Spec reference:** [MCP 2026-07-28 §Tool Schemas](https://modelcontextprotocol.io/specification)
> **Dialect:** JSON Schema 2020-12 (subset)
> **Server version:** master / v1.1.2

---

## Overview

Every tool registered with `McpRegistry.registerTool(...)` carries an **input schema** (describing the arguments the client may send) and optionally an **output schema** (describing the shape the tool must return). The SDK validates incoming arguments and outgoing results against these schemas to catch contract violations early and provide clients with actionable diagnostics.

---

## Supported dialect

The SDK uses a **custom Gson/Guava validator** targeting JSON Schema 2020-12 behaviour for the keywords listed below. It is **not** a full JSON Schema 2020-12 implementation; unsupported keywords are silently ignored.

### Enforced keywords

| Category     | Keywords                                                                              |
|-------------|--------------------------------------------------------------------------------------|
| Type        | `type`, `nullable`                                                                  |
| Numeric     | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`             |
| String      | `minLength`, `maxLength`, `pattern`                                                  |
| Array       | `items`, `minItems`, `maxItems`, `uniqueItems`                                         |
| Object      | `properties`, `required`, `additionalProperties`, `patternProperties`                |
| Enum        | `enum`, `const`                                                                    |
| Conditional | `if`, `then`, `else`                                                               |
| Reference   | `$ref` (local `#`-anchored only; no remote resolution)                            |
| Definition  | `$defs` (local definition storage; referenced by `#/$defs/<name>`)               |

### Explicitly excluded / not enforced

The following JSON Schema 2020-12 keywords are **accepted but not enforced**. A schema containing these keywords is not rejected; the keywords are silently ignored.

| Keyword family     | Reason                                               |
|------------------|------------------------------------------------------|
| `format`          | Annotation-only; not enforced.                        |
| `contentEncoding`  | Binary data handling; out of scope for this SDK.       |
| `prefixItems`      | Array tuple semantics; not used by MCP tool schemas.    |
| `dependentRequired`| Dependency vocabulary; not used by MCP tool schemas.   |
| `unevaluated*`     | Dynamic scope; not used by MCP tool schemas.           |
| Remote `$ref`      | SSRF risk; local `#`-anchored only.                  |

---

## Validation policy

### Registration-time (static)

When a tool is registered, `SchemaValidator.validateSchema(inputSchema)` and `SchemaValidator.validateSchema(outputSchema)` are called. Invalid schemas throw `IllegalArgumentException` immediately, before the tool enters the registry.

### Request-time (runtime)

| Mode          | Protocol version  | Behaviour                                                                                          |
|--------------|-----------------|-----------------------------------------------------------------------------------------------------|
| Stateless    | 2026-07-28     | Subset JSON Schema 2020-12 validation of input and output. Violations return JSON-RPC error `-32602`. |
| Sessioned    | 2025-11-25     | Legacy: only `required[]` array is checked. Type and other constraints are not enforced.        |

### Response-time (runtime)

When an `outputSchema` is declared, the tool result is validated before the response is returned. A violation causes JSON-RPC error `-32602`.

---

## Error diagnostics

```
Invalid tool arguments: $.age: expected type Number, found String
Tool result violates outputSchema: $.result: expected type String, found Integer
```

---

## Defensive copies (no silent mutation)

The registry makes a deep defensive copy of every `inputSchema` and `outputSchema` at registration time. Mutations to the original `Map` objects after `registerTool(...)` returns do **not** affect the stored schemas.

---

## Limitations

- `$ref` supports local `#`-anchored references only. External schema files or remote `$id` are not resolved.
- `format`, `contentEncoding`, `prefixItems`, `dependentRequired`, `unevaluatedProperties` are accepted and ignored. They do not provide runtime validation guarantees.
- In 2025 sessioned mode, only `required[]` is checked. Use 2026 stateless mode for subset 2020-12 enforcement.

---

## Migration checklist

To enable schema validation in stateless mode:

1. **Switch to stateless mode** or ensure clients send `protocolVersion: "2026-07-28"` in their requests.
2. **Audit existing tool schemas** — ensure `type`, `properties`, and `required` are correctly declared.
3. **Add `outputSchema`** to tools that return structured data.
4. **Run the test suite** (`./gradlew test`) — all `ToolSchemaValidationTest` cases must pass.
5. **Check for `-32602` errors in production logs** — these indicate tools returning data that violates the declared schema.
