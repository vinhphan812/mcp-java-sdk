# MCP Rate-Limit and Security Audit — 2026-09-14

## Audit status

- Status: Open — findings require remediation and re-verification
- Scope: `McpProtocolHandler`, `RateLimits`, `McpServerConfig`, security tests, and related API documentation
- Audit method: source inspection, dependency inspection, documentation comparison, and Gradle test execution
- Repository: `D:\android\mcp-java-sdk`
- Important limitation: passing tests do not close this audit while security tests remain disabled

## Executive summary

The current rate-limit and security implementation is not ready for acceptance. The build currently compiles and enabled
tests pass, but the audit found correctness, concurrency, package-boundary, policy-ownership, API, and documentation
defects.

The most important problems are:

1. `api.config.RateLimits` imports `core.McpProtocolHandler`, reversing the intended dependency direction.
2. Security defaults are duplicated between `McpProtocolHandler` and `RateLimits` rather than owned by one API-level
   policy.
3. `DESTRUCTIVE_TOOLS` remains application-specific hardcoded core state, and custom destructive tools are detected but
   not actually capped by `checkDestructiveCap`.
4. Session admission, category concurrent admission, and destructive cap/cooldown accounting are not atomic.
5. Rate-limit quota is consumed before authorisation, so rejected calls can consume operational quotas.
6. Two notification queues use different capacity and overflow policies; one remains hardcoded and silently drops
   events.
7. The main rate-limit, owner-session, and session-timeout tests remain disabled.

## Evidence baseline

The following command was run after correcting an intermediate compile error introduced while inspecting queue
configuration:

```text
./gradlew.bat --no-daemon clean test --console=plain
BUILD SUCCESSFUL in 39s
```

This result proves compilation and enabled-test execution only. It does not prove the disabled security behaviours,
concurrency correctness, or runtime protocol behaviour.

## Findings

### SEC-001 — API-to-core dependency inversion

- Severity: Critical
- Status: Open
- Evidence: `src/main/java/io/github/vinhphan812/mcp/api/config/RateLimits.java:3,88-114`
- Finding: `RateLimits` imports `McpProtocolHandler` to obtain defaults. The consumer-facing `api.config` package
  therefore depends on the implementation package `core`.
- Impact: The package layering is reversed and the public configuration API cannot be reused independently of the
  protocol implementation.
- Required action: Move security defaults to an API/config-owned type. `RateLimits` must not import `core`. The
  dependency must be `core -> api.config`, never `api.config -> core`.

### SEC-002 — No single source of truth for defaults

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:72-140`; `RateLimits.java:88-114`
- Finding: Defaults are declared in `McpProtocolHandler`, then copied by the `RateLimits.Builder`. The handler remains
  the policy owner even though configuration is advertised as the policy owner.
- Impact: Future edits can update one set of defaults and leave the other inconsistent. The architecture and ADR claim a
  cleaner configuration boundary than the source implements.
- Required action: Define defaults once in `api.config`, preferably in a documented `McpSecurityDefaults` or equivalent
  policy type. Keep compatibility aliases only where required, and test equality between compatibility constants and the
  canonical defaults.

### SEC-003 — Application-specific destructive names in SDK core

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:138-144`
- Finding: The open-source core embeds names such as `set_mcp_api_key` and `revoke_mcp_api_key`. These are
  application-specific policy decisions, not MCP protocol requirements.
- Impact: The SDK imposes one application's security vocabulary on every consumer and makes the core harder to reuse.
- Required action: Remove application-specific names from core defaults. Provide a generic data-driven destructive
  policy API. Consumers must explicitly configure their own destructive tools.

### SEC-004 — Custom destructive tools are not enforced

- Severity: High
- Status: Open
- Evidence: detection at `McpProtocolHandler.java:884-885`; enforcement switch at `McpProtocolHandler.java:937-982`
- Finding: `rateLimits.destructiveTools` can contain custom names, but `checkDestructiveCap` only handles hardcoded
  `shutdown`, delete, and upload cases. A custom name passes detection but has no cap or cooldown policy.
- Impact: The public configuration gives a false impression of protection. Custom destructive operations can bypass the
  intended cap and cooldown.
- Required action: Replace the name set plus switch with a map such as `Map<String, DestructiveToolPolicy>`. Every
  configured destructive tool must have an explicit cap and cooldown, or configuration must be rejected.

### SEC-005 — Non-atomic maximum session admission

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:514-518, 794-805`
- Finding: The handler checks `sessions.size()` and creates a session in separate operations.
- Impact: Concurrent `initialize` calls can all pass the check and exceed `maxConcurrentSessions`.
- Required action: Add an atomic admission path using a bounded counter, permit, or synchronized compare-and-update
  operation. Release the reservation if initialization fails.

### SEC-006 — Non-atomic category concurrency admission

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:888-925, 999-1005, 1012-1019`
- Finding: Category counters are checked with `get()` and incremented later in `handleToolsCall`.
- Impact: Concurrent calls can collectively exceed configured read/write/admin in-flight limits.
- Required action: Reserve a permit during admission using a semaphore or atomic bounded increment, then release the
  reservation in `finally`.

### SEC-007 — Non-atomic destructive cap and cooldown state

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:933-975`
- Finding: Cap checks, counter increments, and timestamp writes are separate operations.
- Impact: Concurrent destructive requests can exceed lifetime caps or bypass cooldowns.
- Required action: Protect each destructive policy state transition with a lock or an atomic state object updated by
  compare-and-set.

### SEC-008 — Quota consumed before authorisation

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:1269-1285`; destructive accounting at `McpProtocolHandler.java:933-975`
- Finding: Rate-limit and destructive accounting occurs before the authorisation callback.
- Impact: Unauthorised calls consume burst quota, destructive quota, cooldown state, and potentially abuse score.
- Required action: Define and document accounting semantics. Recommended order: validate request, authorise, atomically
  admit/reserve quota, invoke the handler, release in-flight permits in `finally`.

### SEC-009 — Two notification queues have incompatible policies

- Severity: High
- Status: Open
- Evidence: `McpProtocolHandler.java:246-249, 261-264, 1697-1707, 224-225`
- Finding: `pendingNotifications` uses configurable capacity and overflow handling, while `pendingEvents` uses hardcoded
  capacity `1000` and silently drops the oldest event. Registry, log, and SSE replay paths use `pendingEvents`.
- Impact: The configured queue limit does not cover all notifications. Events can be lost without an overflow callback
  or exception.
- Required action: Consolidate the queues and apply one configured policy, or expose separate clearly named capacities
  and overflow policies in `RateLimits`.

### SEC-010 — Owner replacement can lose the new owner mapping

- Severity: Medium
- Status: Open
- Evidence: `McpProtocolHandler.java:794-806`
- Finding: Replacing an existing owner session removes the old session and clears the owner binding, but the new session
  is not reliably reinserted into `sessionOwners`.
- Impact: The new session may exist without a working owner-to-session lookup.
- Required action: Implement owner replacement as an atomic compare-remove and put sequence, ensuring a newer binding
  cannot be erased by cleanup of the previous session.

### SEC-011 — Direct shutdown does not close sessions

- Severity: Medium
- Status: Open
- Evidence: `McpProtocolHandler.java:418-424`; lifecycle coupling in `McpServer.stop()`
- Finding: `shutdown()` stops cleanup and clears rate-limit maps but does not clear or close active sessions.
- Impact: Direct callers can leave active sessions and queued events retained after shutdown.
- Required action: Make `shutdown()` a complete lifecycle operation, or make it internal and expose one unambiguous
  close operation.

### SEC-012 — Sliding-window boundary is ambiguous

- Severity: Medium
- Status: Open
- Evidence: `McpProtocolHandler.java:178-198`
- Finding: Expiration uses `now - timestamp > windowMs`, so a request exactly `windowMs` old remains counted.
- Impact: The effective window is longer than the documented interval at the boundary.
- Required action: Define the interval contract and use an explicit boundary (`>=` where the lower bound is exclusive).
  Add deterministic clock-controlled tests.

### SEC-013 — IP and session admission is not transactional

- Severity: Medium
- Status: Open
- Evidence: `McpProtocolHandler.java:840-858`
- Finding: The IP record may accept and record a request before the session record rejects it.
- Impact: Rejected requests consume IP quota and accounting differs between scopes.
- Required action: Either coordinate check-and-commit across both scopes or explicitly document fail-closed accounting
  and test it.

### SEC-014 — Security tests are disabled

- Severity: High
- Status: Open
- Evidence: `src/test/java/io/github/vinhphan812/mcp/McpRateLimitTest.java`; `McpOwnerSessionTest.java`;
  `McpSessionTimeoutTest.java`
- Finding: The primary tests for rate limits, owner sessions, timeout, sustained limits, concurrency, and destructive
  caps are disabled or incomplete.
- Impact: The passing build does not protect the security behavior that was added.
- Required action: Rewrite tests against the public API, inject a clock for time behavior, use barriers for concurrency
  tests, remove class-level `@Disabled`, and keep only genuinely external/integration tests separate.

### SEC-015 — Configuration validation is incomplete

- Severity: Medium
- Status: Open
- Evidence: `RateLimits.java:116-133`
- Finding: The builder checks positivity but not cross-field policy invariants or blank destructive names.
- Impact: Invalid configurations can be accepted and produce surprising enforcement.
- Required action: Validate nonblank names, sustained-window relationships, limit relationships, and the requirement
  that each destructive tool has a policy.

### DOC-001 — RateLimits is not discoverable in API documentation

- Severity: High
- Status: Open
- Evidence: `docs/API-REFERENCE.md`; `docs/PROJECT-GUIDE.md:340-369`
- Finding: The main API reference does not document `RateLimits` and its builder. The guide mentions `.rateLimits(...)`
  but does not show a usable `RateLimits.builder()` example.
- Required action: Add a complete Java 8-compatible configuration example and document every public policy field and
  validation rule.

### DOC-002 — API reference contains duplicated content

- Severity: Medium
- Status: Open
- Evidence: `docs/API-REFERENCE.md` contains a repeated document body in the latter half.
- Required action: Remove the duplicate body, then validate headings and internal links.

### DOC-003 — Documentation/API contract drift

- Severity: High
- Status: Open
- Evidence: `docs/API-REFERENCE.md` and `src/main/java`
- Findings include incorrect capability defaults, a documented method that does not exist, incorrect
  completion/tool-handler signatures, and omitted `McpToolHandler.getOutputSchema()`.
- Required action: Audit the API reference against source signatures and update it as one controlled documentation
  change.

### DOC-004 — Java 8 incompatibility in examples

- Severity: High
- Status: Open
- Evidence: `docs/API-REFERENCE.md:687-899`
- Finding: Examples use `Map.of(...)`, which is Java 9+, while the SDK targets Java 8 and Android-compatible runtimes.
- Required action: Replace examples with Java 8-compatible `LinkedHashMap`, `Collections.singletonMap`, and
  `Collections.singletonList` patterns.

### DOC-005 — Reflection metadata must be re-verified

- Severity: High
- Status: Open
- Evidence: `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java`;
  `src/main/java/io/github/vinhphan812/mcp/annotations/McpTool.java`
- Finding: The audit found a potential mismatch between `@McpTool` security attributes and reflection registration. This
  must be verified against the current source before closing.
- Required action: Confirm that `scopes()` and `confirmationRequired()` are read and passed into the registry, then add
  an active regression test.

## Target architecture

```text
io.github.vinhphan812.mcp
├── api/config/
│   ├── McpServerConfig
│   ├── McpSecurityDefaults       # canonical SDK defaults; no core imports
│   ├── RateLimits                 # immutable consumer policy
│   └── DestructiveToolPolicy      # generic name -> cap/cooldown policy
├── api/spi/
│   └── McpAuthorization
├── core/
│   ├── McpProtocolHandler         # consumes policy; owns no application policy names
│   ├── McpRegistry
│   └── McpServer
└── transport/
```

Dependency rule:

```text
api.config does not depend on core
core depends on api.config and api.spi
transport depends on core
```

## Required remediation order

1. Freeze and document the current API; do not add more policy fields to the current mixed design.
2. Move canonical defaults out of `McpProtocolHandler` and remove the `api.config -> core` dependency.
3. Replace destructive-tool set plus switch with data-driven policies.
4. Fix authorization ordering and atomic admission/reservation semantics.
5. Consolidate or separately configure notification queues.
6. Fix owner replacement and shutdown lifecycle.
7. Rewrite and enable security tests with deterministic clock/concurrency controls.
8. Synchronise API reference, guide, ADRs, and audit indexes.
9. Run multiple verification rounds: unit tests, full Gradle test/build/Javadoc, static duplicate scan,
   API/documentation comparison, and runtime transport tests.

## Acceptance criteria for closing this audit

- No `api.config` source imports `core`.
- No application-specific destructive names remain in core defaults.
- Every configured destructive tool has an enforced cap and cooldown policy.
- Session, in-flight category, and destructive admissions are atomic under concurrency.
- Authorization ordering and rejected-request accounting are documented and tested.
- All notification paths use a documented configurable capacity/overflow policy.
- `McpRateLimitTest`, `McpOwnerSessionTest`, and `McpSessionTimeoutTest` are active and passing.
- API documentation contains one copy of the reference and Java 8-compatible examples.
- `git diff --check` passes.
- `./gradlew.bat --no-daemon clean test` passes with the security tests enabled.
- Javadoc and relevant static checks pass without unresolved warnings.

## Related evidence

- [`historical/LOC-AUDIT.md`](./historical/LOC-AUDIT.md)
- [`historical/LOC-AUDIT-summary.md`](./historical/LOC-AUDIT-summary.md)
- [`../adr/ADR-0011-security-rate-limiting.md`](../adr/ADR-0011-security-rate-limiting.md)
- [`../adr/ADR-0011-follow-up-rate-limits-config.md`](../adr/ADR-0011-follow-up-rate-limits-config.md)
