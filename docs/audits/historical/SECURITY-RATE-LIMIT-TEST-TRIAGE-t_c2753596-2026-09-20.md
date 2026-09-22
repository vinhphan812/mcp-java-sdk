# Security / Rate-Limit Test Triage Report

**Task:** t_c2753596
**Author:** dev-qa
**Date:** 2026-09-20
**Status:** Complete
**Scope:** `src/test` only — no production code edits

---

## 1. Inventory (Step 1)

JUnit XML results confirmed via `./gradlew test --rerun-tasks`.

| File                           | Discovered Tests | Run | Skipped | Failed | Status                                             |
|--------------------------------|------------------|-----|---------|--------|----------------------------------------------------|
| `McpRateLimitTest.java`        | 15               | 0   | **15**  | 0      | DISABLED at class level (`@Disabled`)              |
| `McpRateLimitsConfigTest.java` | 4                | 4   | 0       | 0      | HEALTHY — out of scope (configuration only)        |
| `McpIntegrationTest.java`      | 9                | 9   | 0       | 0      | HEALTHY                                            |
| `McpSessionSecurityTest.java`  | 0                | 0   | 0       | 0      | EMPTY — file exists but contains zero test methods |
| `McpSessionTimeoutTest.java`   | 4                | 4   | 0       | 0      | HEALTHY                                            |

**Per-test breakdown for `McpRateLimitTest` (all 15 skipped):**

| #  | Test Method                                | Class-level State | Individual State                            | Assessment                                                     |
|----|--------------------------------------------|-------------------|---------------------------------------------|----------------------------------------------------------------|
| 1  | `testIpRateLimitExceeded`                  | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 2  | `testSessionRateLimitExceeded`             | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 3  | `testReadCategoryBurstLimit`               | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 4  | `testWriteCategoryBurstLimit`              | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 5  | `testAdminCategoryBurstLimit`              | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 6  | `testReadCategorySustainedLimit`           | `@Disabled`       | `@Disabled("Sustained limit test is slow")` | **VACUOUS** — also `assertTrue(true)` stub                     |
| 7  | `testReadConcurrentCap`                    | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 8  | `testWriteConcurrentCap`                   | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 9  | `testAdminConcurrentCap`                   | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 10 | `testDestructiveShutdownCap`               | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 11 | `testDestructiveDeleteCap`                 | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 12 | `testDestructiveUploadCap`                 | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |
| 13 | `testAbuseScoreAccumulates`                | `@Disabled`       | —                                           | **VACUOUS when disabled** — `getAbuseScore()` always returns 0 |
| 14 | `testAbuseScoreBlocksSession`              | `@Disabled`       | —                                           | **VACUOUS when disabled** — `setAbuseScore()` is a no-op       |
| 15 | `testRateLimitSkipsSessionOptionalMethods` | `@Disabled`       | —                                           | **VACUOUS when disabled**                                      |

**`McpIntegrationTest` — `testMiddlewareDenial` (commented out):**

Lines 73-95 are fully commented-out Java code with a `// Test skipped - apiKeyMiddleware method not implemented` note.
The method declaration (`@Test void testMiddlewareDenial() throws Exception`) is NOT commented, so JUnit still discovers
it — but the body only has a comment and no assertions. This makes it a **VACUOUS test** (always passes with 0
assertions).

**`McpSessionSecurityTest` — empty file:**

```
package io.github.vinhphan812.mcp;
/**
 * Session security tests.
 * Note: UUID session validation is not yet implemented.
 */
class McpSessionSecurityTest {}
```

Zero test methods. File exists as a stub. **DELETE**.

---

## 2. Decision Matrix (Step 2)

### DELETE (no recoverable value)

| Test / File                                              | Reason                                                                                                                                                         |
|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpSessionSecurityTest.java` (entire file)              | Zero test methods. Stub placeholder.                                                                                                                           |
| `testReadCategorySustainedLimit` (in `McpRateLimitTest`) | `assertTrue(true)` stub — no test logic whatsoever. Comment says "full test would make 200+ calls over 5 minutes".                                             |
| `testAbuseScoreAccumulates` (in `McpRateLimitTest`)      | `getAbuseScore()` always returns 0 — stub helper, no test. `addAbuseScore` calls were removed from implementation (per `RATE-LIMIT-TRIAGE-SPEC.md` Section 4). |
| `testAbuseScoreBlocksSession` (in `McpRateLimitTest`)    | `setAbuseScore()` is a no-op stub helper — no test.                                                                                                            |
| `testMiddlewareDenial` (in `McpIntegrationTest`)         | Fully commented body; `apiKeyMiddleware` feature not implemented; no assertions.                                                                               |

### REPLACE (has recoverable intent, fixable with low threshold)

| Test Method                                | Fix Strategy                                                                                                                                                                                                                      | Complexity                     |
|--------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------|
| `testIpRateLimitExceeded`                  | Rewrite using configurable `RateLimits` with `ip(3, ...)` for fast execution. Verify `-32029` in 4th request.                                                                                                                     | Low — sequential loop          |
| `testSessionRateLimitExceeded`             | Same pattern as IP test; `session(3, ...)` config.                                                                                                                                                                                | Low                            |
| `testReadCategoryBurstLimit`               | `RateLimits.builder().read(3, 10, 3)`. 4th request must be denied.                                                                                                                                                                | Low                            |
| `testWriteCategoryBurstLimit`              | `RateLimits.builder().write(3, 10, 2)`.                                                                                                                                                                                           | Low                            |
| `testAdminCategoryBurstLimit`              | `RateLimits.builder().admin(3, 10, 1)`.                                                                                                                                                                                           | Low                            |
| `testReadConcurrentCap`                    | **Keep disabled until race condition is fixed** — the core race condition bug (TOCTOU check-before-increment) means concurrent cap tests will give unreliable results. Add `CountDownLatch` harness after Phase 1 implementation. | High — blocked on prod bug fix |
| `testWriteConcurrentCap`                   | Same as above.                                                                                                                                                                                                                    | High — blocked                 |
| `testAdminConcurrentCap`                   | Same as above.                                                                                                                                                                                                                    | High — blocked                 |
| `testDestructiveShutdownCap`               | Rewrite using `RateLimits.builder().shutdown(3, 600_000L)`. Sequential loop works for cap tests.                                                                                                                                  | Low                            |
| `testDestructiveDeleteCap`                 | `RateLimits.builder().destructiveTools(Set.of("delete_action")).destructiveCapDelete(3, 120_000L)`.                                                                                                                               | Medium                         |
| `testDestructiveUploadCap`                 | `RateLimits.builder().destructiveCapUpload(3, 60_000L)`.                                                                                                                                                                          | Medium                         |
| `testRateLimitSkipsSessionOptionalMethods` | Rewrite: make 200 pings, then one tool call. Sequential loop. Verify tool call succeeds after 200 pings.                                                                                                                          | Low                            |

### NOT DECIDED (requires further analysis)

| Test                                      | Notes                                                                                                                                                                                   |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpRateLimitTest.testSustainedLimitTest` | The sustained-limit behavior (sliding window over minutes) cannot be tested at low thresholds without mocking the clock. Block on Phase 3 spike to assess Clock/TestableClock strategy. |

---

## 3. Dependency Mappings (Step 3)

### For tests to REPLACE

**Core implementation classes:**

| Class                | File                                                | Role                                               |
|----------------------|-----------------------------------------------------|----------------------------------------------------|
| `McpProtocolHandler` | `src/main/java/.../core/McpProtocolHandler.java`    | Rate-limit enforcement entry point                 |
| `RateLimits`         | `src/main/java/.../api/config/RateLimits.java`      | Configuration builder with configurable thresholds |
| `McpServerConfig`    | `src/main/java/.../api/config/McpServerConfig.java` | Holds `RateLimits` instance                        |
| `SessionState`       | inner class of `McpProtocolHandler`                 | Holds per-session counters and abuse score         |

**Key methods for test access:**

| Method                                                     | Visibility      | Purpose                                    |
|------------------------------------------------------------|-----------------|--------------------------------------------|
| `McpProtocolHandler.handleRequestResponse(String, String)` | public          | Submit JSON-RPC request with session ID    |
| `McpProtocolHandler.hasSession(String)`                    | public          | Verify session state                       |
| `McpProtocolHandler.terminateSession(String)`              | public          | Clean up session                           |
| `McpServerConfig.builder().rateLimits(RateLimits)`         | public          | Override defaults                          |
| `RateLimits.builder().ip(int, int, int)`                   | public          | IP rate limit config                       |
| `RateLimits.builder().read(int, int, int)`                 | public          | Read category config                       |
| `RateLimits.builder().shutdown(int, long)`                 | public          | Shutdown destructive cap config            |
| `SessionState.setAbuseScore(int)`                          | package-private | For abuse score tests (needs verification) |

**SSE/event streaming path:** Not needed for any of these tests — they use `handleRequestResponse()` directly (
synchronous).

**Reflection utilities:** None needed. All required state is accessible through public API or package-private
test-helpers added per `RATE-LIMIT-TRIAGE-SPEC.md` Section 1.6.

### Gradle commands per test class

| Test Class                                | Command                                                                                                                                                      |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpRateLimitTest` (unit, direct handler) | `./gradlew test --tests "*McpRateLimitTest*" --rerun-tasks`                                                                                                  |
| `McpIntegrationTest` (HTTP transport)     | `./gradlew test --tests "*McpIntegrationTest*" --rerun-tasks`                                                                                                |
| `McpSessionTimeoutTest` (unit)            | `./gradlew test --tests "*McpSessionTimeoutTest*" --rerun-tasks`                                                                                             |
| `McpRateLimitsConfigTest` (config)        | `./gradlew test --tests "*McpRateLimitsConfigTest*" --rerun-tasks`                                                                                           |
| Full security suite                       | `./gradlew test --tests "*McpRateLimitTest" --tests "*McpIntegrationTest" --tests "*McpSessionTimeoutTest" --tests "*McpRateLimitsConfigTest" --rerun-tasks` |

### Preconditions before enabling concurrent-cap tests

Per `RATE-LIMIT-TRIAGE-SPEC.md` Section 1.1 (CRITICAL gap):

1. **Race condition fix** — `incrementMethodConcurrentCount` must be moved before `checkMethodRateLimit` in
   `handleRequestResponse()`.
2. **Decrement-on-failure** — `decrementMethodConcurrentCount` must be called when the check fails.
3. Only then are `testReadConcurrentCap`, `testWriteConcurrentCap`, `testAdminConcurrentCap` reliable.

---

## 4. Release Rule (Step 4)

```
POLICY: SEC-TEST-QUALITY-001

1. VACUOUS TESTS — MUST DELETE
   Any test that:
   - Contains only `assertTrue(true)` / `assertTrue("message", true)` with no logic
   - Calls a stub helper that has no implementation (always returns 0/null/no-op)
   - Has a fully commented-out body with no live assertions
   → Delete immediately. No replacement required.

2. COMMENTED-OUT TESTS — MUST FIX OR DELETE
   Any test method with a commented-out body:
   → Owner has 1 sprint to either:
     (a) Implement the feature and enable the test, OR
     (b) Delete the commented stub entirely
   → No exceptions for commented code in active test classes.

3. DISABLED TESTS — JIRA TICKET REQUIRED
   Any `@Disabled` annotation without a linked JIRA ticket in the `@Disabled("...")` reason:
   → Remove the annotation or add a JIRA ticket reference.
   → Concurrent-cap rate-limit tests (`testReadConcurrentCap`, etc.) are
     explicitly excepted: they MUST remain disabled until Phase 1 of
     `docs/RATE-LIMIT-TRIAGE-SPEC.md` (race condition fix) is implemented.
   → Sustained-limit tests (`testReadCategorySustainedLimit`) are excepted
     pending a clock-mocking spike.

4. EMPTY TEST FILES — MUST DELETE
   Any test class with zero `@Test` methods:
   → Delete the file immediately. Stub files create false positives in
     test discovery and mislead developers.

5. DISABLED CLASSES — REPLACE, THEN ENABLE
   A class-level `@Disabled` (which skips all children):
   → Rewrite with deterministic thresholds before removing `@Disabled`.
   → Do NOT remove `@Disabled` from a class and leave vacuous inner tests.

6. ABUSE SCORE TESTS — PENDING ARCHITECTURE DECISION
   Tests relying on `addAbuseScore()` are blocked because the implementation
   removed those calls (RATE-LIMIT-TRIAGE-SPEC.md Section 4).
   → Until the abuse scoring contract is re-established and documented
     in ADR-0011, `testAbuseScoreAccumulates` and `testAbuseScoreBlocksSession`
     remain deleted (vacuous stubs).
```

---

## 5. Summary of Actions

| ID     | File                                              | Action            | Rationale                                                                 |
|--------|---------------------------------------------------|-------------------|---------------------------------------------------------------------------|
| ACT-01 | `McpSessionSecurityTest.java`                     | **DELETE**        | Zero test methods; pure stub                                              |
| ACT-02 | `McpRateLimitTest.testReadCategorySustainedLimit` | **DELETE** method | `assertTrue(true)` placeholder; sustained-limit testing needs clock spike |
| ACT-03 | `McpRateLimitTest.testAbuseScoreAccumulates`      | **DELETE** method | `getAbuseScore()` stub — always 0                                         |
| ACT-04 | `McpRateLimitTest.testAbuseScoreBlocksSession`    | **DELETE** method | `setAbuseScore()` stub — no-op                                            |
| ACT-05 | `McpIntegrationTest.testMiddlewareDenial`         | **DELETE** method | Fully commented; `apiKeyMiddleware` not implemented                       |

> **ACT-05 resolution (2026-09-20):** `McpIntegrationTest.testMiddlewareDenial` method confirmed deleted. No such method
> exists in `src/test/java/io/github/vinhphan812/mcp/McpIntegrationTest.java`. Search across all test sources (
`grep -rn testMiddlewareDenial src/test/`) returned zero results. ACT-05 is complete.
> | ACT-06 | `McpRateLimitTest` (class-level `@Disabled`) | **KEEP disabled, REPLACE** | Class-level `@Disabled` remains
> until all vacuous children are fixed and replaced with deterministic tests |
> | ACT-07 | `McpRateLimitTest` concurrent-cap tests (tests 7-9) | **KEEP disabled** pending race fix | TOCTOU race
> condition (RATE-LIMIT-TRIAGE-SPEC.md) must be fixed first |
> | ACT-08 | `McpRateLimitTest.testSustainedLimitTest` | **KEEP disabled** pending spike | Needs clock-mocking
> architecture decision |
> | ACT-09 | Remaining 9 `McpRateLimitTest` methods (IP, session, category burst, destructive caps, skip-ping) | **
> REPLACE
** | Rewrite with `RateLimits` builder, low thresholds, sequential execution |

---

## 6. Downstream Tasks (child cards to create)

| Title                                                 | Assignee    | Parents    | Body summary                                                                                 |
|-------------------------------------------------------|-------------|------------|----------------------------------------------------------------------------------------------|
| Rewrite rate-limit tests: IP/session/category burst   | dev-qa      | t_c2753596 | Replace 6 sequential-loop tests with `RateLimits.builder()` low thresholds                   |
| Rewrite destructive-cap rate-limit tests              | dev-qa      | t_c2753596 | Replace `testDestructiveShutdownCap`, `testDestructiveDeleteCap`, `testDestructiveUploadCap` |
| Rewrite `testRateLimitSkipsSessionOptionalMethods`    | dev-qa      | t_c2753596 | Rewrite ping+tool-call sequence test                                                         |
| Spike: clock-mocking for sustained-limit tests        | dev-backend | t_c2753596 | Assess `RateLimits` clock strategy; decide if `TestableClock` / `Clock.fixed()` is viable    |
| Delete `McpSessionSecurityTest.java` and stub methods | dev-qa      | t_c2753596 | File-level deletion + method deletions per ACT-01 through ACT-05                             |

---

## 7. Reference

- Triage spec: `docs/RATE-LIMIT-TRIAGE-SPEC.md` (author: dev-backend, status: Complete)
- Repair spec: `docs/MCP-RateLimit-Repair-Spec.md`
- ADR: `docs/adr/ADR-0011-security-rate-limiting.md`
- Previous test plan: `docs/MCP-RateLimit-Repair-Spec.md` Section 5 (dependency graph)
