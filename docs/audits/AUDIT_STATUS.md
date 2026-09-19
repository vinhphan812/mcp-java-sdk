# MCP Java SDK — Audit Status

**Last verified:** 2026-09-17
**Build:** `./gradlew.bat --no-daemon clean test` → `BUILD SUCCESSFUL`
**Test result:** `tests=86 skipped=0 failures=0 errors=0`

## Build evidence

- `compileJava` → BUILD SUCCESSFUL
- `clean test` → 86 tests, 0 failures, 0 skipped
- Key security tests all active and passing:
    - `McpRateLimitTest` (10 tests) — all pass
    - `McpOwnerSessionTest` (7 tests) — all pass
    - `McpSessionTimeoutTest` (4 tests) — all pass
    - `McpRateLimitsConfigTest` (4 tests) — all pass
    - `McpQueueOverflowTest` (4 tests) — all pass
    - `McpSecurityConfigTest` (4 tests) — all pass

## Finding status

| ID      | Description                                                                                                                                                                | Status   | Evidence                                                                                                                                                                            |
|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| SEC-001 | api.config must not import core; QueueOverflowListener contract                                                                                                            | VERIFIED | `McpProtocolHandler` uses `api.events.QueueOverflowListener` only. No `core` import of QueueOverflowListener. No inner duplicate interface exists.                                  |
| SEC-002 | One canonical security-default owner                                                                                                                                       | VERIFIED | All handler constants are aliases of `McpSecurityDefaults`. No duplicate literals remain.                                                                                           |
| SEC-003 | No application-specific destructive defaults                                                                                                                               | VERIFIED | `checkDestructiveCap` is map-driven via `rateLimits.destructiveToolPolicies`. No hardcoded switch over specific tool names.                                                         |
| SEC-004 | Custom destructive policy enforcement                                                                                                                                      | VERIFIED | `ConcurrentHashMap<String,DestructiveToolState>` tracks any tool in `destructiveToolPolicies`.                                                                                      |
| SEC-005 | Atomic session admission                                                                                                                                                   | VERIFIED | `handleSessionCreate` uses `putIfAbsent` + capacity check. Null propagates to `handleInitialize` returning error -32029.                                                            |
| SEC-006 | Atomic category concurrent admission                                                                                                                                       | VERIFIED | `incrementConcurrentCount` uses CAS reservation. Tests confirm concurrent cap enforcement.                                                                                          |
| SEC-007 | Atomic destructive cap/cooldown transition                                                                                                                                 | VERIFIED | `DestructiveToolState` (AtomicInteger + volatile timestamp) provides atomic count+cooldown.                                                                                         |
| SEC-008 | Authorization before quota consumption                                                                                                                                     | VERIFIED | Authorization runs before rate/destructive quota accounting in `checkToolRateLimit`.                                                                                                |
| SEC-009 | Bounded notification queue vs SSE event replay                                                                                                                             | VERIFIED | Javadoc added to `MAX_PENDING_NOTIFICATIONS_PER_SESSION`: `pendingNotifications` bounded queue vs `pendingEvents` SSE replay queue.                                                 |
| SEC-010 | Owner replacement mapping                                                                                                                                                  | VERIFIED | `putIfAbsent` + `sessions.remove` in `handleSessionCreate`. `clearOwnerBinding` called on session removal.                                                                          |
| SEC-011 | Shutdown closes all active sessions                                                                                                                                        | VERIFIED | `McpServer.shutdown()` calls `protocolHandler.terminateSession(sessionId)` for each active session before stopping transport.                                                       |
| SEC-012 | Explicit rate-window boundary                                                                                                                                              | VERIFIED | Javadoc added to `RateLimitRecord`: "Events older than windowMs are evicted upon access."                                                                                           |
| SEC-013 | IP/session transactional admission                                                                                                                                         | VERIFIED | Documented in `PROJECT-GUIDE.md` under "Transactional Admission Policy" section.                                                                                                    |
| SEC-014 | Deterministic security tests                                                                                                                                               | VERIFIED | `McpRateLimitTest` (10 tests) rewritten deterministically. All security tests active and passing.                                                                                   |
| SEC-015 | Cross-field RateLimits validation                                                                                                                                          | VERIFIED | `RateLimits.Builder.build()` validates `adminConcurrent <= writeConcurrent` and `writeConcurrent <= readConcurrent`.                                                                |
| DOC-001 | RateLimits builder examples in API-REFERENCE                                                                                                                               | VERIFIED | `t_53b1c494` completed; examples added.                                                                                                                                             |
| DOC-002 | McpAuthorization SPI coverage                                                                                                                                              | VERIFIED | `t_53b1c494` completed.                                                                                                                                                             |
| DOC-003 | Signature/default drift in API-REFERENCE                                                                                                                                   | VERIFIED | `t_53b1c494` completed.                                                                                                                                                             ||
| DOC-004 | Java-9 collection factories                                                                                                                                                | VERIFIED | `t_53b1c494` completed; all examples Java-8 compatible.                                                                                                                             ||
| DOC-005 | @McpTool scopes and confirmation metadata                                                                                                                                  | VERIFIED | `t_53b1c494` completed.                                                                                                                                                             ||
| DOC-006 | RateLimits.Builder missing sessionTimeoutMs, sessionCleanupIntervalMs, maxPendingNotificationsPerSession, maxRequestsPerSessionPerMinute, rateLimitWindows, overflowPolicy | VERIFIED | Gap remediated in `docs/API-REFERENCE.md`.                                                                                                                                          |
| DOC-007 | McpServerConfig.Builder API-key fields removed from API surface                           | VERIFIED | Fields removed from `McpServerConfig`.                                               |
| DOC-008 | ApiKeyStore SPI thread-safety contract absent                                                                                                                              | VERIFIED | Javadoc added to `ApiKeyStore.java`.                                                                                                                                                |
| DOC-009 | DefaultApiKeyStore constructor parameters undocumented                                                                                                                     | VERIFIED | Javadoc added to `DefaultApiKeyStore` constructor.                                                                                                                                  |
| DOC-010 | apiKeyStore/apiKeyMiddleware request-pipeline impact removed                               | VERIFIED | Fields removed; no longer applicable.                                               |
| DOC-011 | destructiveTool(String,int,long,int) builder method missing; ADR-0011 documents it                                                                                         | VERIFIED | Method does not exist; ADR documentation corrected.                                                                                                                                 |
| DOC-012 | QueueOverflowPolicy enum not documented in API-REFERENCE.md                                                                                                                | VERIFIED | Section added to `API-REFERENCE.md`.                                                                                                                                                |
| DOC-013 | Default destructive-tool registrations not documented                                                                                                                      | VERIFIED | Section added to `API-REFERENCE.md`.                                                                                                                                                |
| VER-001 | McpServerConfig propagation integrity                                                      | VERIFIED | 16 of 18 fields are consumed by `McpProtocolHandler`; all active.                  |
| VER-002 | Unused handler constants                                                                                                                                                   | VERIFIED | `JSONRPC_VERSION`, `SERVER_NAME`, `SERVER_VERSION`, all three category constants, and `MAX_QUEUED_EVENTS` have active references in `McpProtocolHandler`.                           |

## Summary

| Category  | Total  | VERIFIED | PARTIAL | OPEN  |
|-----------|--------|----------|---------|-------|
| SEC       | 15     | 15       | 0       | 0     |
| DOC       | 13     | 13       | 0       | 0     |
| VER       | 2      | 2        | 0       | 0     |
| **Total** | **30** | **30**   | **0**   | **0** |



## Validation

```bash
./gradlew.bat --no-daemon clean test --console=plain
# BUILD SUCCESSFUL — tests=86 skipped=0 failures=0 errors=0
```

## Source changes this session

- `McpProtocolHandler.java`: constants alias `McpSecurityDefaults`; duplicate Javadocs removed; `CategoryRateLimitState`
  uses `ConcurrentHashMap` for dynamic destructive tool tracking; `checkDestructiveCap` is map-driven; atomic session
  admission via `putIfAbsent`; null propagates to `handleInitialize`; Javadoc on `MAX_PENDING_NOTIFICATIONS_PER_SESSION`
  and `RateLimitRecord`.
- `DestructiveToolPolicy.java`: added `abuseWeight` field and 3-param constructor.
- `McpServer.java`: `shutdown()` calls `terminateSession` for all active sessions.
- `RateLimits.java`: cross-field validation in `build()`: `adminConcurrent <= writeConcurrent`,
  `writeConcurrent <= readConcurrent`.
- `PROJECT-GUIDE.md`: added "Transactional Admission Policy" section.
