# Reflection Tool Policy Metadata Triage

## Classification

Tier 2 — a public registration SPI, reflection processing, registry state, protocol enforcement, tests, and examples
meet at this boundary. This is a design-only outcome; no production source was changed by this card.

## Verified current state

`McpTool` already declares `scopes()` and `confirmationRequired()` with safe defaults (`{}` and `false`).

`McpReflectionRegistrar.registerTool()` currently passes only name, description, input schema, required inputs, optional
output schema, and handler. It does not read either policy attribute. Consequently, annotation metadata is absent from
the registry definition.

`McpRegistry` already stores `requiredScopes` and `confirmationRequired` through its metadata-aware seven-argument
overload. `McpProtocolHandler.handleToolsCall()` reads those values, calls `McpAuthorization.denial(...)` before
`handler.call(...)`, and currently maps both rate-limit and authorization denials to `-32029`. Error-code mapping is
owned by the core task and is intentionally not changed by this specification.

The existing `McpRegistrar` SPI has only five- and six-argument tool-registration methods. This is the actual contract
gap. In addition to `McpRegistry`, `McpProtocolHandler` implements this SPI, so a registrar-type check for `McpRegistry`
is insufficient: metadata is lost when reflection registration receives a protocol handler.

## Target design

Add one Java-8 `default` metadata-aware overload to `McpRegistrar`:

```
registerTool(name, description, inputSchema, required, outputSchema,
             requiredScopes, confirmationRequired, handler)
```

The default implementation delegates to the existing output-schema overload and deliberately ignores policy metadata.
This is binary- and source-compatible for existing third-party `McpRegistrar` implementations while making the
policy-loss fallback explicit.

Override the new overload in both built-in implementations:

1. `McpRegistry` stores `outputSchema`, `requiredScopes`, and `confirmationRequired` in one definition. Refactor
   existing overloads to delegate to this canonical path so the schema and metadata are not mutually exclusive.
2. `McpProtocolHandler` forwards the new overload to `McpRegistry`.
3. `McpReflectionRegistrar.registerTool()` always reads `annotation.scopes()` and `annotation.confirmationRequired()`,
   converts scopes to a list, and calls the new SPI overload. It must not use `instanceof McpRegistry` routing.

The registry should continue omitting `requiredScopes` when empty and `confirmationRequired` when false, preserving
present `tools/list` output for ordinary tools. The handler receives an empty scope array and `false` for those defaults
when authorisation is configured.

## Scope and ownership

| Work item               | Owner           | Files                                                                                                                                        | Acceptance criteria                                                                                                                                                                    |
|-------------------------|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Metadata registration   | dev-backend     | `McpReflectionRegistrar.java`, `McpRegistrar.java`, `McpRegistry.java`, `McpProtocolHandler.java`                                            | Reflected tools retain scopes, confirmation, and optional output schema via both registry and protocol-handler registrar paths.                                                        |
| Policy regression tests | dev-qa          | New `McpReflectionAuthorizationTest.java`; extend existing registration test only if useful                                                  | Tests prove definition persistence and authorization occurs before handler execution.                                                                                                  |
| Example/docs            | dev-pm          | `examples/.../AuthorizationExample.java`, `examples/.../tools/ToolWithScopesExample.java`, relevant user/API guide only after implementation | Example shows that annotations advertise requirements but enforcement requires a configured `McpAuthorization`; it must not imply an annotation alone grants or validates user scopes. |
| Error mapping           | core task owner | `McpProtocolHandler.java` as required                                                                                                        | Coordinate a single denial/error policy; this work item neither selects nor changes an error code.                                                                                     |

## Required tests

Add focused tests using an annotated provider registered through `McpReflectionRegistrar`.

1. Metadata preservation: assert the registered definition contains `requiredScopes` and `confirmationRequired`; use an
   annotated tool with `outputSchema` as well to prevent a schema/metadata regression.
2. Authorization propagation: configure an `McpAuthorization` spy, invoke the reflected tool through
   `McpProtocolHandler`, and assert it receives the declared scopes, confirmation flag, and input arguments.
3. Denial short-circuit: make the policy deny a confirmation-required tool; assert the response contains the denial text
   and that a handler side-effect counter remains zero.
4. Default compatibility: a reflected tool with no policy attributes remains callable and passes an empty scope array
   plus `false` to configured authorization.
5. SPI fallback compatibility: an existing custom registrar that implements only legacy methods still compiles and
   registers the handler through the default method. It is not expected to preserve policy metadata because the legacy
   contract cannot represent it.

The authorization-denial assertion must assert the denial message and lack of invocation. It must not lock this work
item to an error code until the core task publishes the mapping.

## Documentation and example requirements

`McpAuthorization` and `McpServerConfig.Builder.authorization(...)` already document the callback. Update the user/API
material only to state the reflection-registration guarantee after it is implemented, including:

- `@McpTool(scopes = {...}, confirmationRequired = true)` is metadata for a policy callback;
- absent `McpAuthorization` means allow by default;
- an authorization callback must obtain granted scopes and confirmation from the host application, not from tool
  arguments alone;
- legacy third-party registrar implementations receive a compatible fallback but cannot expose policy metadata until
  they override the new default SPI method.

Replace the permissive `AuthorizationExample` lambda with a clearly labelled conditional example or pair it with a
runnable bootstrap demonstrating a denial. Do not put fabricated user/session state inside `ToolWithScopesExample`.

## Compatibility and risk

- Existing annotated tools: unchanged, because defaults remain empty scopes and no confirmation.
- Existing direct `McpRegistry` registrations: unchanged.
- Existing third-party SPI implementations: unchanged at binary and source level due to the default method, but policy
  metadata cannot be materialised by an implementation that has not opted into the new overload.
- Public API: additive SPI expansion; release notes/API reference should identify the new default method.
- Shared hotspot: `McpProtocolHandler.java` is also used by the core error-mapping work. Land only the forwarding
  overload in the metadata change, or sequence it after the core patch to avoid overlapping edits.

## Validation

Run after implementation:

1. `./gradlew.bat --no-daemon test --tests io.github.vinhphan812.mcp.McpReflectionAuthorizationTest --console=plain`
2.

`./gradlew.bat --no-daemon test --tests io.github.vinhphan812.mcp.McpReflectionRegistrarDirectBindingTest --tests io.github.vinhphan812.mcp.McpAuthorizationTest --console=plain`

3. `./gradlew.bat --no-daemon test --console=plain`
4. `cd examples && ../gradlew.bat compileJava --console=plain`
5. `git diff --check` and a GitNexus `detect-changes --scope all` review before commit.

No runtime server/network test is required beyond the protocol-level tool-call regression unless the implementation
changes the Grizzly transport.
