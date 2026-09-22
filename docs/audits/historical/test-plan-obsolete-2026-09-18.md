# Rate-Limit Test Plan & Remediation Roadmap

**Task:** t_99339169
**Created:** 2026-09-20
**Scope:** `src/test` and test support only. No production fixes.

---

## 1. Current Test Suite State

### 1.1 Test Execution Summary (Verified)

```
Total Discovered: 91
Executed/Passed:  76
Skipped:          15
```

### 1.2 Test Files Analysis

| File                           | Status                   | Issues                                                                                                                            |
|--------------------------------|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `McpRateLimitTest.java`        | **14 methods @Disabled** | Class-level `@Disabled` blocks all tests. Placeholder methods for abuse score (no-op). Concurrent tests are serial, not parallel. |
| `McpSessionSecurityTest.java`  | **Empty**                | No tests - class exists but is blank.                                                                                             |
| `McpIntegrationTest.java`      | **9 tests pass**         | `testMiddlewareDenial()` has `@Test` but all code is commented out (does nothing).                                                |
| `McpSessionTimeoutTest.java`   | **5 tests pass**         | Tests are functional.                                                                                                             |
| `McpRateLimitsConfigTest.java` | **Unknown**              | Need verification.                                                                                                                |

---

## 2. Dead Tests: DELETE vs REPLACE

### 2.1 Tests to DELETE

| File                          | Method/Class             | Reason                                                                              |
|-------------------------------|--------------------------|-------------------------------------------------------------------------------------|
| `McpSessionSecurityTest.java` | **Entire class**         | Empty file - no tests to replace, just delete.                                      |
| `McpIntegrationTest.java`     | `testMiddlewareDenial()` | Commented-out test with `@Test` annotation - dead code. DELETE the method entirely. |

### 2.2 Tests to REPLACE

| File                    | Method                                | Replacement Strategy                                                                                               |
|-------------------------|---------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `McpRateLimitTest.java` | **All 14 methods**                    | Rewrite with low configurable RateLimits, real concurrency (CountDownLatch/ExecutorService), proper time strategy. |
| `McpRateLimitTest.java` | `testReadCategorySustainedLimit()`    | Replace placeholder with time-window test using `Thread.sleep()` or clock mock.                                    |
| `McpRateLimitTest.java` | `getAbuseScore()` / `setAbuseScore()` | Remove - use actual session state inspection or remove tests requiring these.                                      |

---

## 3. Deterministic Test Plan

### 3.1 Core Principles

1. **Low RateLimits**: Use `RateLimits.builder().read(burst, sustained, concurrent)` with small values (e.g., burst=3-5)
   for fast, deterministic tests.
2. **Client IP Path**: Tests must verify IP extraction works via:
    - Direct handler calls (no transport) - IP comes from `handleRequest(request, sessionId)` where the caller provides
      IP
    - Integration tests using `X-Forwarded-For` header (verified working in existing tests)
3. **Scope Categories**: Test each category independently:
    - **READ**: `tools/list`, `tools/call` (non-mutating), `resources/list`, `prompts/list`
    - **WRITE**: `resources/subscribe`, `resources/unsubscribe`, tool calls that modify state
    - **ADMIN**: Tools with `requiredScopes=["admin"]`
4. **Real Concurrency**: Use `ExecutorService` + `CountDownLatch` to launch N concurrent requests simultaneously.
5. **Time Strategy**: Use `Thread.sleep()` for sustained limit tests (acceptable trade-off) or inject a `Clock` mock if
   production code supports it.
6. **Abuse Score**: Test by triggering rate limits and verifying blocking behavior via response content.

### 3.2 Proposed Test Structure

#### Group A: IP-Based Rate Limiting (Low Values)

| Test                       | RateLimit Config                | Strategy                                       | Production Task |
|----------------------------|---------------------------------|------------------------------------------------|-----------------|
| `testIpRateLimitExceeded`  | `maxRequestsPerIpPerMinute = 5` | Make 5 requests from same IP, 6th should fail. | `tbd`           |
| `testIpRateLimitPerClient` | Two clients, same IP limit      | Verify separate IPs get separate buckets.      | `tbd`           |

#### Group B: Session-Based Rate Limiting (Low Values)

| Test                           | RateLimit Config                     | Strategy                                            | Production Task |
|--------------------------------|--------------------------------------|-----------------------------------------------------|-----------------|
| `testSessionRateLimitExceeded` | `maxRequestsPerSessionPerMinute = 5` | Make 5 requests in one session, 6th fails.          | `tbd`           |
| `testSessionIsolation`         | Two sessions, same IP                | Verify separate sessions have independent counters. | `tbd`           |

#### Group C: Category Burst Limits (Low Values)

| Test                          | RateLimit Config   | Strategy                                    | Production Task |
|-------------------------------|--------------------|---------------------------------------------|-----------------|
| `testReadCategoryBurstLimit`  | `.read(3, 10, 3)`  | 3 `tools/call` succeed, 4th fails.          | `tbd`           |
| `testWriteCategoryBurstLimit` | `.write(3, 10, 2)` | 3 `resources/subscribe` succeed, 4th fails. | `tbd`           |
| `testAdminCategoryBurstLimit` | `.admin(3, 10, 1)` | Admin tool with `requiredScopes=["admin"]`. | `tbd`           |

#### Group D: Category Sustained Limits

| Test                              | RateLimit Config                 | Strategy                                                                                                                                        | Production Task |
|-----------------------------------|----------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| `testReadCategorySustainedLimit`  | `.read(60, 5, 5)` + small window | Make 5 requests quickly, wait for burst to reset (~1s), make more, verify sustained kicks in. OR use `Thread.sleep()` to simulate time passage. | `tbd`           |
| `testWriteCategorySustainedLimit` | Similar to read                  | Same strategy.                                                                                                                                  | `tbd`           |

#### Group E: Concurrent Limits (Real Parallelism)

| Test                     | RateLimit Config                        | Strategy                                                                                                 | Production Task |
|--------------------------|-----------------------------------------|----------------------------------------------------------------------------------------------------------|-----------------|
| `testReadConcurrentCap`  | `.read(60, 200, 2)` + slow tool (100ms) | Launch 3 concurrent requests via `ExecutorService`. First 2 succeed, 3rd is rejected (concurrent limit). | `tbd`           |
| `testWriteConcurrentCap` | `.write(30, 100, 2)` + slow tool        | Same approach.                                                                                           | `tbd`           |
| `testAdminConcurrentCap` | `.admin(5, 15, 1)`                      | 1 admin tool at a time max.                                                                              | `tbd`           |

#### Group F: Destructive Tool Caps

| Test                         | RateLimit Config  | Strategy                                 | Production Task |
|------------------------------|-------------------|------------------------------------------|-----------------|
| `testDestructiveShutdownCap` | `.shutdown(2, 0)` | Call `shutdown` 2 times, 3rd fails.      | `tbd`           |
| `testDestructiveDeleteCap`   | `.delete(2, 0)`   | Call `delete_action` 2 times, 3rd fails. | `tbd`           |
| `testDestructiveUploadCap`   | `.upload(2, 0)`   | Call `upload_file` 2 times, 3rd fails.   | `tbd`           |

#### Group G: Abuse Score

| Test                          | RateLimit Config        | Strategy                                                                                        | Production Task |
|-------------------------------|-------------------------|-------------------------------------------------------------------------------------------------|-----------------|
| `testAbuseScoreAccumulates`   | Default                 | Make requests that trigger rate limits, verify abuse score increments (check logs or response). | `tbd`           |
| `testAbuseScoreBlocksSession` | Default + pre-set score | Manually inject high abuse score via test hooks, verify all requests blocked.                   | `tbd`           |

#### Group H: Skip Session-Optional Methods

| Test                     | RateLimit Config  | Strategy                                                                                             | Production Task |
|--------------------------|-------------------|------------------------------------------------------------------------------------------------------|-----------------|
| `testRateLimitSkipsPing` | `.read(3, 10, 3)` | Make 10 `ping` requests, then 3 `tools/call` - all 3 tool calls should succeed (ping doesn't count). | `tbd`           |

---

## 4. Test-to-Production-Task Mapping

| Test Group             | Suggested Kanban Task Title                                | Priority |
|------------------------|------------------------------------------------------------|----------|
| A: IP Rate Limits      | Implement deterministic IP-based rate limit tests          | High     |
| B: Session Rate Limits | Implement deterministic session-based rate limit tests     | High     |
| C: Category Burst      | Implement category burst limit tests (read/write/admin)    | High     |
| D: Sustained Limits    | Implement sustained limit tests with time simulation       | Medium   |
| E: Concurrent Limits   | Implement true concurrent limit tests with ExecutorService | High     |
| F: Destructive Caps    | Implement destructive tool cap tests                       | Medium   |
| G: Abuse Score         | Implement abuse score accumulation and blocking tests      | Medium   |
| H: Skip Methods        | Implement ping/methods-skip rate limit tests               | Low      |

Each task should include:

- Acceptance criteria: N tests pass with low RateLimits config
- Technical constraints: Java 8 compatible, JUnit 5
- Deliverable: Working tests in `McpRateLimitTest.java`

---

## 5. Gradle Commands for Test Subsets

### Run All Rate Limit Tests (Current - All Skipped)

```bash
./gradlew test --tests "McpRateLimitTest"
```

### Run Integration Tests Only

```bash
./gradlew test --tests "McpIntegrationTest"
```

### Run Session Timeout Tests

```bash
./gradlew test --tests "McpSessionTimeoutTest"
```

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Method

```bash
./gradlew test --tests "McpIntegrationTest.testResourceListHitsReadCategoryLimit"
```

### Run with XML Report (CI-friendly)

```bash
./gradlew test
# Results in: build/test-results/test/
```

### Run in Parallel (if configured)

```bash
./gradlew test --parallel
```

---

## 6. Java 8 / JUnit 5 Compatibility

- **Java Version**: `sourceCompatibility = JavaVersion.VERSION_1_8` (verified in `build.gradle`)
- **JUnit 5**: Using `org.junit.jupiter:junit-jupiter` via BOM `5.10.0`
- **Assertions**: Use `org.junit.jupiter.api.Assertions.*`
- **Test Annotation**: `@org.junit.jupiter.api.Test`
- **Disable**: `@org.junit.jupiter.api.Disabled`
- **Concurrency**: `java.util.concurrent.*` (available since Java 5)

---

## 7. Recommended First Steps

1. **DELETE** `McpSessionSecurityTest.java` (empty file)
2. **DELETE** `testMiddlewareDenial()` method from `McpIntegrationTest.java`
3. **CREATE** first production task for IP/Session rate limit tests
4. **ENABLE** `McpRateLimitTest` by removing class-level `@Disabled`
5. **REWRITE** tests with low RateLimits (e.g., burst=3) for fast, deterministic execution

---

## 8. Verification Checklist

- [ ] All McpRateLimitTest methods @Enabled (class-level @Disabled removed)
- [ ] McpSessionSecurityTest deleted
- [ ] testMiddlewareDenial deleted
- [ ] Tests use low RateLimits (burst=3-5) for speed
- [ ] Concurrent tests use ExecutorService + CountDownLatch
- [ ] Sustained tests use Thread.sleep() or clock mock
- [ ] All tests pass with Java 8 / JUnit 5
- [ ] Gradle commands verified for test subsets

---

*Document generated from task t_99339169 analysis. Update as tests are implemented.*
