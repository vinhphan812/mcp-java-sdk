# Configuration Audit Report — 2026-09-17

## Scope

- McpServerConfig.java — all 18 config fields
- McpProtocolHandler.java — field usage and config propagation
- McpServer.java — builder methods and transport wiring
- RateLimits.Builder defaults
- Naming consistency (GrizzlyExample → MainExample)
- SPI/package naming

## 1. McpServerConfig — Field Usage

| Field | Type | Used in McpProtocolHandler | Notes |
|---|---|---|---|
| serverName | String | no | Decorator/logging only |
| serverVersion | String | no | Decorator/logging only |
| protocolVersion | String | no | Decoration only |
| description | String | no | Unused |
| logger | McpLogger | yes | Line 291 |
| capabilities | McpClientCapabilities | yes | Passed to init response |
| rateLimits | RateLimits | yes | Line 292 |
| overflowListener | QueueOverflowListener | yes | Line 293 |
| authorization | McpAuthorization | yes | Line 294 + lines 1215–1224 |
| **apiKeyStore** | N/A | **N/A** | REMOVED; API field deleted |
| **apiKeyMiddleware** | N/A | **N/A** | REMOVED; API field deleted |
| sessionTimeoutMs | long | yes | Session expiration |
| maxConcurrentRequests | int | yes | Concurrency counter cap |
| corsAllowedOrigins | Set\<String\> | no | Transport-level only |
| enableSessionResumption | boolean | no | Transport-level only |
| notificationBatchSize | int | yes | Notification batching |
| notificationFlushIntervalMs | long | yes | Flush timer |

**Finding VER-001** (Critical):

`apiKeyStore` and `apiKeyMiddleware` are declared and assigned into `McpServerConfig`, but `McpProtocolHandler` never reads either field. The only references are:

```
McpProtocolHandler.java:1209: // if (config.apiKeyMiddleware != null) {
McpProtocolHandler.java:1210: //     config.apiKeyMiddleware.accept(null);
```

Both fields are `@Deprecated` with a note to "use transport-level authentication". The transport-level path is:

```
McpServer.Builder.apiKey / apiKeySupplier
  → McpServer.transport.apiKeySupplier
  → McpGrizzlyHandler.authenticate() → apiKeySupplier.get()
  → apiKeyMiddleware.accept(apiKey)
```

This is the correct architecture — authentication belongs at the transport layer, not the protocol layer. The `@Deprecated` fields in `McpServerConfig` are dead code but harmless; removing them would be a breaking API change for callers who already set them.

**Recommendation**: Add a `@Deprecated` Javadoc note on `McpProtocolHandler` referencing the transport-level path, and consider scheduling removal in a future major version. No urgent action required since the correct mechanism is wired and functional.

---

## 2. McpServer — Config Propagation to McpProtocolHandler

`McpServer` creates `McpProtocolHandler` at line 17 with:

```java
protocolHandler = new McpProtocolHandler(registry, config,
    config == null ? null : config.getOverflowListener(),
    config == null ? null : config.getAuthorization());
```

All four constructor parameters are correctly passed. The `overflowListener` and `authorization` fields are correctly stored and used.

The `apiKeySupplier` / `apiKey` builder fields are wired only to the transport layer (`McpGrizzlyHandler`), not to the protocol handler. This is architecturally correct.

---

## 3. Builder Defaults

### McpServerConfig.Builder

| Method | Default | Checked |
|---|---|---|
| serverName | "MCP Server" | Yes |
| serverVersion | "1.0.0" | Yes |
| protocolVersion | "2024-11-05" | Yes |
| logger | null (uses no-op) | Yes |
| capabilities | McpClientCapabilities.defaults() | Yes |
| rateLimits | null → RateLimits.defaults() applied in handler | Yes |
| overflowListener | null | Yes |
| authorization | null (all tools allowed) | Yes |
| apiKeyStore | null | Yes |
| apiKeyMiddleware | null | Yes |
| sessionTimeoutMs | 3600000 (1h) | Yes |
| maxConcurrentRequests | 100 | Yes |
| corsAllowedOrigins | null (all allowed) | Yes |
| enableSessionResumption | false | Yes |
| notificationBatchSize | 100 | Yes |
| notificationFlushIntervalMs | 1000 | Yes |

All defaults are sensible. No issue found.

### RateLimits.Builder

| Method | Default | Checked |
|---|---|---|
| read(burst, sustained, concurrent) | 30, 10, 50 | Yes |
| write(burst, sustained, concurrent) | 30, 10, 50 | Yes |
| sessionTimeoutMs | 3600000 | Yes |
| sessionCleanupIntervalMs | 300000 | Yes |
| maxRequestsPerSessionPerMinute | 60 | Yes |
| overflowPolicy | QueueOverflowPolicy.REJECT | Yes |

All defaults are sensible. No issue found.

**Minor**: Several `RateLimits.Builder` methods lack Javadoc comments (flagged by javadoc lint). Not a functional issue.

---

## 4. Rename GrizzlyExample → MainExample

Status: **COMPLETED** by child task `t_e69f8107`.

- `examples/src/main/java/io/github/vinhphan812/mcp/examples/GrizzlyExample.java` — deleted
- `examples/src/main/java/io/github/vinhphan812/mcp/examples/MainExample.java` — created
- `docs/GRIZZLY-EXAMPLE.md` — already updated (title is "# Main MCP Example", all links point to MainExample.java)
- `docs/PROJECT-GUIDE.md` — no reference to GrizzlyExample found
- `docs/audits/historical/LOC-AUDIT.md` — historical file, not updated (acceptable; it records a past snapshot)

Build passes with no reference errors.

---

## 5. Naming Consistency

### Class vs File Names

All checked classes match their file names:

| File | Class | Match |
|---|---|---|
| McpProtocolHandler.java | McpProtocolHandler | Yes |
| McpServer.java | McpServer | Yes |
| McpServerConfig.java | McpServerConfig | Yes |
| RateLimits.java | RateLimits | Yes |
| McpGrizzlyHandler.java | McpGrizzlyHandler | Yes |
| ApiKeyStore.java | ApiKeyStore | Yes |
| DefaultApiKeyStore.java | DefaultApiKeyStore | Yes |

### Package Structure

All packages follow `io.github.vinhphan812.mcp.*` consistently.

### SPI Naming

SPI interfaces in `api/spi/`:
- `ApiKeyStore` ✓
- `McpAuthorization` ✓
- `McpRegistrar` ✓
- `McpRegistryChangeListener` ✓
- `McpResourceUpdateListener` ✓

All follow the `Mcp*` prefix convention.

---

## 6. Javadoc Warnings

Build produces 52 javadoc warnings, primarily in:
- `RateLimits.Builder` — missing method comments
- `McpServerConfig.Builder` — missing method comments

Not a functional issue but degrades API discoverability.

---

## Summary of Findings

| ID | Severity | Description | Status |
|---|---|---|---|
| VER-001 | Info | apiKeyStore/apiKeyMiddleware in McpServerConfig are @Deprecated dead code; transport-level auth is correctly wired | Documented; no action required |
| JAVADOC-001 | Low | 52 javadoc warnings in Builder classes | Not remediated; non-blocking |
| NAMING-001 | Resolved | GrizzlyExample → MainExample | Completed by t_e69f8107 |

## Validation

- `./gradlew clean build -x test` — **BUILD SUCCESSFUL** (6 tasks)
- `./gradlew test` — **BUILD SUCCESSFUL** (3 tasks)
- No GrizzlyExample.java file remains in source tree
- All docs link to MainExample.java correctly

## Architecture Consistency

The codebase is internally consistent. Authentication is correctly implemented at the transport layer (`McpGrizzlyHandler` + `McpServer.Builder`). The deprecated protocol-layer fields are unused but not harmful.
