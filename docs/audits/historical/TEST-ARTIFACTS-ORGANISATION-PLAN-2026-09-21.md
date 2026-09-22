# Test Artifacts Organisation Plan

**Task:**       t_425f836c
**Author:**     dev-qa
**Date:**       2026-09-21
**Scope:**      Tier 1 triage — non-destructive only. No deletion, no movement, no staging, no commits, no production
code changes.
**Workspace:**  D:/android/mcp-java-sdk

---

## 1. Inventory

### 1.1 Test Files

All files under `src/test/java/io/github/vinhphan812/mcp/` and its `transport/` subdirectory:

| File                                           | Git Status           | @Test count      | Notes                                                                        |
|------------------------------------------------|----------------------|------------------|------------------------------------------------------------------------------|
| `McpAuthorizationTest.java`                    | M (modified)         | 3                | Authorization denial, scopes, confirmationRequired                           |
| `McpClientCapabilitiesTest.java`               | tracked              | 6                | Capability negotiation                                                       |
| `McpExampleRegistrationTest.java`              | tracked              | 1                | Example tool registration                                                    |
| `McpGrizzlyLiveTest.java`                      | tracked              | 1                | Live transport test                                                          |
| `McpGrizzlySecurityMatrixTest.java`            | tracked              | 10               | Security header matrix                                                       |
| `McpGrizzlyResumabilityTest.java`              | tracked (transport/) | 3                | SSE resumability                                                             |
| `McpIntegrationTest.java`                      | M (modified)         | 8                | Full protocol flow, session/rate-limit integration                           |
| `McpListChangedNotificationTest.java`          | tracked              | 2                | list/changed notifications                                                   |
| `McpPaginationTest.java`                       | tracked              | 2                | Cursor-based pagination                                                      |
| `McpOwnerSessionTest.java`                     | D (deleted)          | —                | Removed — was empty/dead                                                     |
| `McpParamAnnotationValidationTest.java`        | **untracked**        | 2                | @McpParam / @McpParams annotation targets                                    |
| `McpParamCompileTimeValidationTest.java`       | **untracked**        | 1                | Compile-time usage pattern validation                                        |
| `McpProgressAndCancellationTest.java`          | tracked              | 3                | Progress notifications, cancellation                                         |
| `McpProtocolHandlerTest.java`                  | tracked              | 8                | Protocol handler unit                                                        |
| `McpQueueOverflowTest.java`                    | tracked              | 3                | Queue overflow handling                                                      |
| `McpRateLimitsConfigTest.java`                 | tracked              | 4                | RateLimits config builder                                                    |
| `McpRateLimitTest.java`                        | M (modified)         | **12 @Disabled** | ADR-011 rate limiting — all disabled at class level                          |
| `McpReflectionRegistrarDirectBindingTest.java` | tracked              | 2                | Reflection registrar + direct binding                                        |
| `McpRegistryConcurrencyTest.java`              | **untracked**        | 8                | Registry concurrency + immutability                                          |
| `McpSecurityConfigTest.java`                   | tracked              | 4                | Security config parsing                                                      |
| `McpServerConfigTest.java`                     | tracked              | 3                | Server config builder                                                        |
| `McpSessionSecurityTest.java`                  | D (deleted)          | —                | Removed — was empty class                                                    |
| `McpSessionTimeoutTest.java`                   | M (modified)         | 4                | Session timeout; **2 vacuous assertions** (lines 55, 70: `assertTrue(true)`) |
| `McpTasksTest.java`                            | tracked              | 8                | Task management                                                              |

**22 Java files total.** 3 deleted (empty/dead). 3 untracked. 3 modified.

### 1.2 Test Execution Results (latest `./gradlew test`)

```
BUILD SUCCESSFUL in 25s
Total discovered:  91
Passed:             79
Skipped:            12  (all from McpRateLimitTest @Disabled)
Failures:           0
```

Note: `McpRateLimitTest` is the ONLY source of skipped tests. No other class carries `@Disabled` at class or method
level.

### 1.3 Test Documentation

| Path                                                          | Content Domain                                 | Notes                                                                              |
|---------------------------------------------------------------|------------------------------------------------|------------------------------------------------------------------------------------|
| `test-plan.md`                                                | Rate-limit test plan + remediation roadmap     | Created from t_99339169; covers dead tests to delete, deterministic test structure |
| `test-requirements.md`                                        | Authorization scopes/confirmation requirements | Created from t_088a0d70; defines reflection-registration metadata preservation     |
| `docs/test/TEST-0001-sse-connection-release-streaming.md`     | SSE permit release on close                    | Implementation plan                                                                |
| `docs/test/TEST-0002-sse-validation-plan.md`                  | SSE connection validation plan                 | Implementation plan                                                                |
| `docs/test-specs/AUTH-SCOPES-CONFIRMATION-TEST-SPEC.md`       | 12-TC spec for scopes + confirmationRequired   | Created from t_9fc7b2b0; targets `McpReflectionAuthorizationTest.java`             |
| `docs/test-specs/LAST-EVENT-ID-REPLAY-TEST-SPEC.md`           | SSE Last-Event-ID replay behavior (4 TCs)      | Draft status                                                                       |
| `docs/test-specs/PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md` | SSE permit exhaustion + 429 (5 TCs)            | Draft status                                                                       |

---

## 2. Test Classification Table

| File                                     | Classification                             | Rationale                                                                                                                                                                                                                                                                                                                                                                                                                                            | Recommendation                                                                                                                                                            |
|------------------------------------------|--------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpParamAnnotationValidationTest.java`  | **Test-only artifact**                     | Tests verify @McpParam and @McpParams `@Target` annotation attributes. These are annotation-contract tests — they validate Java annotation metadata, not runtime behavior. They FAIL currently (expected: @McpParam @Target should include METHOD; @McpParams @Target should include METHOD). No duplicate coverage exists.                                                                                                                          | Integrate: track the file, use as regression anchor for annotation contract. No overlap with existing tests.                                                              |
| `McpParamCompileTimeValidationTest.java` | **Required regression coverage**           | Validates that @McpParam on parameters works correctly in the existing `McpReflectionRegistrarDirectBindingTest.DirectTools` fixture. This documents the working usage pattern and guards against regression if the annotation or reflection logic changes. Tests `McpReflectionRegistrarDirectBindingTest` internals via reflection.                                                                                                                | Integrate: track the file. Consider folding into `McpReflectionRegistrarDirectBindingTest.java` as inner-class tests, or keep separate to maintain single-responsibility. |
| `McpRegistryConcurrencyTest.java`        | **Required regression coverage**           | 8 tests covering: (a) `getToolDefinition` returns deep-immutable copies, (b) concurrent unique/same-name tool registration is thread-safe, (c) concurrent reads+writes never throw `ConcurrentModificationException`, (d) listener notification semantics, (e) listener exception isolation, (f) concurrent registration with `requiredScopes`/`confirmationRequired` metadata. No overlap with existing tests (existing tests are single-threaded). | Integrate: track the file. Best location: `src/test/java/io/github/vinhphan812/mcp/core/` (alongside `McpRegistry`). Ensure no naming collision.                          |
| `McpOwnerSessionTest.java`               | **Deleted (was obsolete)**                 | Git shows `D` — file was removed. No remaining references in surviving test files.                                                                                                                                                                                                                                                                                                                                                                   | No action needed; already deleted.                                                                                                                                        |
| `McpSessionSecurityTest.java`            | **Deleted (was empty)**                    | Git shows `D` — empty class was removed (confirmed by t_c2753596).                                                                                                                                                                                                                                                                                                                                                                                   | No action needed; already deleted.                                                                                                                                        |
| `McpRateLimitTest.java`                  | **Disabled artifact (partially dead)**     | 12 methods @Disabled at class level. 8 test groups defined (IP, session, category burst/sustained/concurrent, destructive caps, abuse score, ping-skip). The dead tests (placeholder methods, no-op `getAbuseScore`/`setAbuseScore`) are documented in `test-plan.md`. All 12 are listed for rewrite, not retention as-is.                                                                                                                           | Rewrite: see Implementation Task #1.                                                                                                                                      |
| `McpSessionTimeoutTest.java`             | **Tracked with vacuous assertions**        | 4 tests, all pass. Two `assertTrue(true)` at lines 55 and 70 — these assert nothing and always succeed regardless of behavior.                                                                                                                                                                                                                                                                                                                       | Fix: see Implementation Task #2.                                                                                                                                          |
| `McpIntegrationTest.java`                | **Tracked; testMiddlewareDenial was dead** | 8 tests, all pass. `testMiddlewareDenial()` method was previously commented-out dead code; confirmed absent in current scan.                                                                                                                                                                                                                                                                                                                         | No action needed.                                                                                                                                                         |

---

## 3. Canonical Documentation Map

| Domain                                               | Canonical Path                                                | Rationale                                                                                                                                  | Merged From / Supersedes                                                                                                                    |
|------------------------------------------------------|---------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| Rate-limit test plan                                 | `test-plan.md` (root)                                         | Already in use, referenced by `t_99339169`, covers full remediation roadmap including dead-test deletion list.                             | N/A                                                                                                                                         |
| Authorization scopes/confirmation requirements       | `test-requirements.md` (root)                                 | Already in use, referenced by `t_088a0d70`, defines metadata preservation requirements.                                                    | N/A                                                                                                                                         |
| Authorization scopes/confirmation test spec (12 TCs) | `docs/test-specs/AUTH-SCOPES-CONFIRMATION-TEST-SPEC.md`       | Authoritative test specification for `McpReflectionAuthorizationTest.java`. Complete given/when/then structure.                            | t_9fc7b2b0 output                                                                                                                           |
| SSE permit exhaustion + 429 behavior                 | `docs/test-specs/PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md` | 5 test cases with full setup/assertion code. Authoritative for SSE permit contract.                                                        | TEST-0001-sse-connection-release-streaming.md partially overlaps (permit release); docs overlap on SSE permit semantics.                    |
| SSE Last-Event-ID replay behavior                    | `docs/test-specs/LAST-EVENT-ID-REPLAY-TEST-SPEC.md`           | 4 test cases covering replay-after-headers, replay-after-permit, event filtering, duplicate prevention. Authoritative for replay contract. | TEST-0002-sse-validation-plan.md overlaps on SSE validation.                                                                                |
| SSE implementation plans                             | `docs/test/TEST-0001-sse-connection-release-streaming.md`     | Implementation HOW-TO (not a spec). Keep separate from test specs.                                                                         | Overlaps with PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md on permit semantics. Recommend merging permit-release sections into the spec doc. |
| SSE validation plan                                  | `docs/test/TEST-0002-sse-validation-plan.md`                  | Implementation HOW-TO. Overlaps with LAST-EVENT-ID-REPLAY-TEST-SPEC.md on connection validation.                                           | Recommend absorbing into the spec doc or consolidating under `docs/test/SSE-VALIDATION-PLAN.md`.                                            |
| Concurrency test plan                                | *(no doc yet)*                                                | McpRegistryConcurrencyTest has no planning doc.                                                                                            | Create `docs/test-specs/REGISTRY-CONCURRENCY-TEST-SPEC.md` — see Implementation Task #3.                                                    |

**Recommended consolidations:**

1. Merge SSE permit-release content from `TEST-0001-sse-connection-release-streaming.md` into
   `docs/test-specs/PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md` and archive `TEST-0001`.
2. Merge SSE validation content from `TEST-0002-sse-validation-plan.md` into
   `docs/test-specs/LAST-EVENT-ID-REPLAY-TEST-SPEC.md` and archive `TEST-0002`.

---

## 4. Naming Conventions

These are recommendations — do not enforce via tooling.

### Executable Tests

| Pattern                          | Use When                                                                                  |
|----------------------------------|-------------------------------------------------------------------------------------------|
| `<Feature>Test.java`             | Unit/integration tests for a single class or feature (e.g., `McpRegistryTest.java`)       |
| `<Feature>IntegrationTest.java`  | Full-stack or protocol-level integration tests (e.g., `McpIntegrationTest.java`)          |
| `<Feature>ConcurrencyTest.java`  | Concurrency/thread-safety tests for a component (e.g., `McpRegistryConcurrencyTest.java`) |
| `<Feature>SecurityTest.java`     | Security-specific tests (e.g., `McpGrizzlySecurityMatrixTest.java`)                       |
| `<Feature>ResumabilityTest.java` | Reconnection/resumability tests (e.g., `McpGrizzlyResumabilityTest.java`)                 |

### Test Specifications (docs)

| Pattern                             | Use When                                                                |
|-------------------------------------|-------------------------------------------------------------------------|
| `test-plan.md` (project root)       | Master test plan: strategy, test execution summary, remediation roadmap |
| `docs/test/<short-name>.md`         | Test implementation plans / HOW-TO guides                               |
| `docs/test-specs/<feature>-spec.md` | Formal test specifications with TC structure (given/when/then)          |

### Generated Evidence

| Pattern                                     | Use When                       |
|---------------------------------------------|--------------------------------|
| `test-evidence/run-<YYYY-MM-DD>.log`        | `./gradlew test --info` output |
| `test-evidence/<feature>-<YYYY-MM-DD>.json` | Structured test results export |
| `test-evidence/coverage-<YYYY-MM-DD>.xml`   | JaCoCo / coverage reports      |

---

## 5. Implementation Task List

### Task 1: Enable and rewrite McpRateLimitTest

**Problem:** All 12 methods are @Disabled at class level. The existing test structure uses production-scale rate
limits (60+ requests) making tests slow and non-deterministic. Placeholder methods (`getAbuseScore`, `setAbuseScore`)
and commented-out dead code make the file misleading.

**Acceptance criteria:**

- [ ] Class-level `@Disabled` removed from `McpRateLimitTest.java`
- [ ] Tests rewritten with low rate-limit values (burst=3, sustained=5) for fast, deterministic execution
- [ ] Real concurrency via `ExecutorService` + `CountDownLatch` (no fake serial "concurrency")
- [ ] Sustained-limit tests use `Thread.sleep()` or injectable `Clock` mock
- [ ] Placeholder methods removed (do not test `getAbuseScore`/`setAbuseScore` if they don't exist in production)
- [ ] All 12 tests pass in < 30 seconds
- [ ] `./gradlew test --tests "McpRateLimitTest"` succeeds with 12 passed, 0 skipped

**References:** `test-plan.md` Sections 3.1–3.2 define the test structure per group (A–H).

---

### Task 2: Fix vacuous assertions in McpSessionTimeoutTest

**Problem:** Two assertions (`assertTrue(true)`) at lines 55 and 70 assert nothing — they pass regardless of behavior
and give false confidence.

**Acceptance criteria:**

- [ ] `assertTrue(true)` at line 55 replaced with a meaningful assertion (e.g., verify session metadata, handler state,
  or timeout behavior)
- [ ] `assertTrue(true)` at line 70 replaced with a meaningful assertion
- [ ] `McpSessionTimeoutTest` continues to pass after changes
- [ ] Each fixed assertion documents what behavior it guards

---

### Task 3: Create REGISTRY-CONCURRENCY-TEST-SPEC

**Problem:** `McpRegistryConcurrencyTest.java` has no planning document. Its 8 test scenarios (immutability, thread-safe
registration, read/write race, listener semantics) are well-implemented but undocumented as a specification.

**Acceptance criteria:**

- [ ] Document created at `docs/test-specs/REGISTRY-CONCURRENCY-TEST-SPEC.md`
- [ ] Each of the 8 test scenarios documented with: GIVEN/WHEN/THEN structure, key assertions, thread-safety property
  verified
- [ ] Document cross-references production code (`McpRegistry.java`, `McpReflectionRegistrar.java`) that the tests guard
- [ ] Anti-patterns section: what these tests do NOT cover (distributed concurrency, persistence)

---

### Task 4: Track and integrate untracked test files

**Problem:** Three test files exist on disk but are untracked by git. Two are required regression coverage; one is a
test-only artifact that guards annotation contracts.

**Acceptance criteria for each file:**

| File                                     | Action                            | Acceptance Criteria                                                                                                                                                                                 |
|------------------------------------------|-----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpParamAnnotationValidationTest.java`  | Track (git add)                   | File exists, `@McpParam` @Target test fails (expected — annotation needs fix), `@McpParams` @Target test fails (expected — annotation needs fix), both test failures are documented as known issues |
| `McpParamCompileTimeValidationTest.java` | Track (git add)                   | File exists, test passes (validates existing working usage), serves as regression anchor for parameter annotation                                                                                   |
| `McpRegistryConcurrencyTest.java`        | Track (git add) + move to `core/` | File moved to `src/test/java/io/github/vinhphan812/mcp/core/McpRegistryConcurrencyTest.java`; all 8 tests pass; no naming collision with existing `McpRegistryConcurrencyTest` (none exists)        |

**Note:** `McpRegistryConcurrencyTest` should NOT be renamed to `McpConcurrencyTest` — the current name clearly
associates it with `McpRegistry`. If a future `McpProtocolHandlerConcurrencyTest` is needed, that is a separate concern.

---

## 6. Validation Commands

```bash
# Run all unit tests
./gradlew test

# Run only McpRateLimitTest (should be 12 passed, 0 skipped after Task 1)
./gradlew test --tests "McpRateLimitTest"

# Run McpSessionTimeoutTest
./gradlew test --tests "McpSessionTimeoutTest"

# Run all concurrency tests
./gradlew test --tests "McpRegistryConcurrencyTest"

# Run the untracked annotation validation tests (expected to fail until annotation is fixed)
./gradlew test --tests "McpParamAnnotationValidationTest"

# Run the untracked compile-time validation tests (should pass)
./gradlew test --tests "McpParamCompileTimeValidationTest"

# Run specific authorization test
./gradlew test --tests "McpAuthorizationTest"

# Run integration tests
./gradlew test --tests "McpIntegrationTest"

# Run all tests with info output (for evidence)
./gradlew test --info 2>&1 | tee test-evidence/run-$(date +%Y-%m-%d).log

# Run tests filtered by package
./gradlew test --tests "io.github.vinhphan812.mcp.core.*"
./gradlew test --tests "io.github.vinhphan812.mcp.transport.*"

# Generate test report (HTML)
./gradlew test
open build/reports/tests/test/index.html

# Generate JaCoCo coverage report
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

---

## 7. No-Delete Rule

> **No test file or test documentation may be deleted without meeting ALL of the following conditions:**

**(a) Source-code-level coverage confirmation:**  
The exact behavior (not merely the category) tested by the file must be verified as covered by at least one other test
via direct code inspection — not guesswork, not "it probably covers this." The covering test must be in the current test
suite and passing.

**(b) Explicit approval from project owner:**  
A confirmed, documented approval from the project owner (or delegated maintainer) in the task comments, code review, or
project management system.

**(c) Conversion to documented obsolete status:**  
Instead of deletion, move the file to `docs/test/obsolete/` and create `docs/test/obsolete/<feature>-obsolete.md`
documenting: what was tested, why it became obsolete, and the confirmation from (a) or (b) above.

**This rule applies to:**

- All `*.java` files under `src/test/`
- All markdown files under `docs/test/` and `docs/test-specs/`
- Root-level test documentation (`test-plan.md`, `test-requirements.md`)

**This rule does NOT apply to:**

- Generated artifacts in `test-evidence/` (those are evidence, not source)
- Build outputs in `build/` (those are regenerated)
- IDE configuration files (`.idea/`, `.classpath`, etc.)

---

*Document produced from Tier 1 triage of D:/android/mcp-java-sdk test artifacts (task t_425f836c).*
