# ADR-0011 Follow-up — Configurable Rate Limits and Security Constants

**Status:** Proposed
**Date:** 2026-09-14
**Authors:** MCP Java SDK team
**Supersedes:** Section "Configuration" in [ADR-0011-security-rate-limiting.md](./ADR-0011-security-rate-limiting.md)

## Context

ADR-0011 was implemented with rate-limit and security constants hardcoded as `public static final` on
`McpProtocolHandler`. This makes it impossible for consumers to tune the limits for their environment without forking
the SDK. The ADR-0011 "Configuration" section promised:

> All limits are public constants in `McpProtocolHandler` and overridable via `McpServerConfig`. The config builder
> accepts optional rate-limit overrides.

The override mechanism was never implemented. Twenty-four constants are referenced 2-7 times each in `checkRateLimit`,
`checkToolRateLimit`, `checkDestructiveCap`, `addAbuseScore`, `startCleanupThread`, and `enqueue`.

## Decision

Introduce a `RateLimits` value object. `McpServerConfig` carries an optional `RateLimits`. When no override is supplied,
`McpProtocolHandler` uses the same defaults as today — backward compatible.

## Goals

- Expose every rate-limit, queue, abuse-score, and destructive-cap value through `McpServerConfig.Builder`
- Keep `McpProtocolHandler.MAX_*` constants as the documented defaults
- Zero behaviour change when consumers do not override
- Java 8 compatible, no new external dependencies

## Non-goals

- Runtime reconfiguration (mutating limits after `start()`)
- Distributed rate limiting (Redis, etc.)
- Per-tool override (only per-server)

## Design

### New type: `RateLimits`

`io.github.vinhphan812.mcp.api.config.RateLimits` — immutable value object with builder.

```java
public final class RateLimits {
    // Session lifecycle
    public final int maxConcurrentSessions;
    public final long sessionTimeoutMs;
    public final long sessionCleanupIntervalMs;

    // Notification queue
    public final int maxPendingNotificationsPerSession;

    // IP / session request limits
    public final int maxRequestsPerIpPerMinute;
    public final int maxRequestsPerSessionPerMinute;

    // Per-category caps
    public final int readBurst, readSustained, readConcurrent;
    public final int writeBurst, writeSustained, writeConcurrent;
    public final int adminBurst, adminSustained, adminConcurrent;

    // Destructive-tool lifetime caps and cooldowns
    public final int shutdownCap, deleteCap, uploadCap;
    public final long shutdownCooldownMs, deleteCooldownMs, uploadCooldownMs;

    // Abuse scoring
    public final int abuseScoreBlockThreshold;

    // Sliding-window windows (advanced — defaults are sensible)
    public final long rateLimitWindowMs;
    public final long rateLimitSustainedWindowMs;

    // Destructive tool set (consumer-extensible)
    public final Set<String> destructiveTools;

    public static RateLimits defaults() { ... }
    public Builder toBuilder() { ... }
    public static Builder builder() { ... }

    public static final class Builder {
        public Builder maxConcurrentSessions(int v) { ... }
        public Builder sessionTimeoutMs(long v) { ... }
        public Builder maxRequestsPerIpPerMinute(int v) { ... }
        public Builder readBurst(int v) { ... }
        public Builder shutdownCap(int v) { ... }
        public Builder shutdownCooldownMs(long v) { ... }
        public Builder abuseScoreBlockThreshold(int v) { ... }
        public Builder destructiveTools(Set<String> tools) { ... }
        public RateLimits build() { ... }
    }
}
```

`RateLimits.defaults()` returns a value object whose fields are populated from the existing `McpProtocolHandler` public
constants — so the canonical defaults live in exactly one place.

### `McpServerConfig`

```java
public final class McpServerConfig {
    // existing fields ...
    public final RateLimits rateLimits;

    public RateLimits getRateLimits() { return rateLimits; }
}

public static final class Builder {
    private RateLimits rateLimits;

    /** Override rate-limit configuration. Pass {@code null} to use defaults. */
    public Builder rateLimits(RateLimits value) {
        this.rateLimits = value;
        return this;
    }

    public Builder build() {
        RateLimits effective = rateLimits != null ? rateLimits : RateLimits.defaults();
        return new McpServerConfig(this, effective);
    }
}
```

### `McpProtocolHandler`

- Field `private final RateLimits rateLimits;` initialised in the 4-arg constructor from `config.getRateLimits()`.
- Replace every reference to a constant of interest (e.g. `MAX_REQUESTS_PER_IP_PER_MINUTE`, `CATEGORY_READ_BURST_LIMIT`,
  `ABUSE_SCORE_BLOCK_THRESHOLD`) with `rateLimits.maxRequestsPerIpPerMinute`, `rateLimits.readBurst`,
  `rateLimits.abuseScoreBlockThreshold`.
- Public constants stay declared as `public static final` and are referenced from `RateLimits.defaults()` — single
  source of truth.
- The `DESTRUCTIVE_TOOLS` constant becomes `rateLimits.getDestructiveTools()` so consumers can extend the set.

## Migration

1. Default config produces identical behaviour to v1.1.0-pre.
2. Existing tests pass without changes.
3. New tests added under `src/test/java/.../McpRateLimitsConfigTest.java`:
    - `defaultsMatchConstants` — `RateLimits.defaults().readBurst == McpProtocolHandler.CATEGORY_READ_BURST_LIMIT`
    - `overridesAreHonoured` —
      `new McpProtocolHandler(registry, config.rateLimits(RateLimits.builder().readBurst(3).build()))` rejects the 4th
      call
    - `nullOverrideUsesDefaults` — `config.rateLimits(null)` matches default behaviour
    - `customDestructiveToolSet` — extends DESTRUCTIVE_TOOLS

## ADR-0011 update

Append to ADR-0011 after the existing "Configuration" section:

```
## Follow-up: Configurable RateLimits (2026-09-14)

The constants in `McpProtocolHandler` are now the documented defaults.
A `RateLimits` value object (io.github.vinhphan812.mcp.api.config.RateLimits)
carries overrides. Pass `config.rateLimits(...)` from the server builder.
See ADR-0011 follow-up for the schema and migration notes.
```

## Acceptance criteria

- `./gradlew clean test` BUILD SUCCESSFUL, all 86 existing tests still pass.
- New `McpRateLimitsConfigTest.java` covers default-vs-override.
- `McpServerConfig.Builder.rateLimits(null)` behaves identically to no call.
- `RateLimits.defaults()` returns values matching the public constants on `McpProtocolHandler`.
- `McpProtocolHandler.MAX_*` constants remain and equal the `defaults()`.
- ADR-0011 follow-up section committed.

## Files affected

| File                                                | Action                                                             |
|-----------------------------------------------------|--------------------------------------------------------------------|
| `api/config/RateLimits.java`                        | NEW                                                                |
| `api/config/McpServerConfig.java`                   | Add `rateLimits` field, builder method, getter                     |
| `core/McpProtocolHandler.java`                      | Add `rateLimits` field; replace ~24 constant refs with field reads |
| `test/McpRateLimitsConfigTest.java`                 | NEW                                                                |
| `docs/adr/ADR-0011-follow-up-rate-limits-config.md` | NEW (this document)                                                |
| `docs/adr/ADR-0011-security-rate-limiting.md`       | Append follow-up section                                           |

## Phases

### Phase 1 — `RateLimits` value object

Create the immutable type and builder. Default values come from constants on `McpProtocolHandler`.

### Phase 2 — `McpServerConfig` integration

Add `rateLimits` field, builder method, getter. Default is `RateLimits.defaults()`.

### Phase 3 — `McpProtocolHandler` refactor

Replace hardcoded references with `rateLimits.X` reads. Verify public constants still equal `defaults()`.

### Phase 4 — Tests

Add `McpRateLimitsConfigTest.java` covering defaults, override, null-override, custom destructive set.

### Phase 5 — ADR-0011 cross-reference

Append follow-up section to ADR-0011. Commit ADR follow-up separately.

## Risks

- **Risk:** Refactor breaks behaviour because constants move.
  **Mitigation:** All values stay numeric — only the read location changes. Existing tests pass unchanged.
- **Risk:** New `RateLimits` class bloats public API surface.
  **Mitigation:** Single class, fully immutable, builder is optional. Constants stay on `McpProtocolHandler`.
- **Risk:** Consumers who overrode constants by reflection lose behaviour.
  **Mitigation:** Document migration in ADR. Add `RateLimits.defaults()` entry point.
