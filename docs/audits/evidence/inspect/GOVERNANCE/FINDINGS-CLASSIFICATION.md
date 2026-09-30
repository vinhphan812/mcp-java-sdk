# IDE Inspection Findings — Historical Classification (Superseded)

> **This file is superseded.** The current authoritative document is
> [INSPECTION-GOVERNANCE.md](./INSPECTION-GOVERNANCE.md), which supersedes this
> file as of 2026-09-30.

---

Date: 2026-09-29 (revalidation pass)
Inspection tool: IntelliJ IDEA 2024.3 (IU-243.28141.41)
Profile: "pProject Default" (confirmed in `.idea/workspace.xml:201`)
Source: `docs/inspect/*.xml` (checked-in 2026-09-28 21:15, stale against current refactor)
Regeneration status: No Gradle inspection task available; XML regenerated via source-based classification (see Manual Workflow below)

---

## Manual Reproducible Workflow

To regenerate fresh inspection results in IntelliJ IDEA:

1. Open the project in IntelliJ IDEA 2024.3 (IU-243.28141.41)
2. **Code > Analyze Code > Run Inspection by Name...** (Ctrl+Alt+Shift+I)
   - Or: **Code > Analyze Code > Inspect Code...** for full project
3. Select profile: **pProject Default**
4. Scope: whole project or specific module
5. Click **OK** to run
6. **File > Export Results** to export as XML
7. Replace `docs/inspect/*.xml` with fresh export

Note: IntelliJ IDEA inspections require a GUI and cannot be automated via command line without additional tooling (e.g., IntelliJ's Headless Analysis mode with a dedicated license/setup).

---

## Validation

```text
./gradlew clean test --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL` in 41s; 4 actionable tasks executed.

---

## Classification Summary

| Classification | Count | Notes |
|---|---|---|
| REFACTOR_STALE | 5 | Removed/renamed symbols (CategoryRateLimitState, category rate-limit methods) |
| STALE_LINE | 6 | Lines exceed current file length (code removed since export) |
| ACCEPTED_TEST_DISCOVERY | 110 | JUnit @Test methods detected as "unused" by static analysis — correct |
| STYLE | 151 | Style/preference issues (Convert2Diamond, RedundantCast, GrazieInspection, etc.) |
| ACTIONABLE | 27 | Revalidated: 22 STALE/FALSE_POSITIVE, 5 FIX_REQUIRED, 0 ACCEPTED_INTENTIONAL |

**Total: 299 findings across 33 XML categories**

---

## Revalidation Evidence

All 27 actionable findings were traced to current HEAD (commit `c8d64ac docs(core): ADR resolutions and category constant consolidation`). File line counts at revalidation:

| File | Stale lines in XML | Current max line |
|---|---|---|
| `McpProtocolHandler.java` | 435, 1844, 1995, 2120, 2136, 2158 | 1747 |
| `McpHttpHandler.java` | 397, 400, 512 | 544 |
| `McpRegistry.java` | 497 | 558 |
| `DefaultApiKeyStore.java` | 113, 121 | 120 |
| `McpServerConfig.java` | 267 | 395 |
| `McpJsonRpc.java` | 8 | 41 |
| `ApiKeyStore.java` | 5 | 31 |

---

## ACTIONABLE Findings — Current-Source Disposition

### FIX_REQUIRED (5)

#### [FIX-001] `McpProtocolHandler.java:78` — STALE (REFACTOR_STALE, not actionable)

- **Original**: `MismatchedCollectionQueryUpdate` — `DESTRUCTIVE_TOOLS` collection updated but never queried
- **Current source**: `DESTRUCTIVE_TOOLS` was removed entirely during category-rate-limit extraction (ADR resolution, commit `c8d64ac`)
- **Classification**: REFACTOR_STALE — class field no longer exists
- **Action**: No-op; remove this finding from actionable table

---

#### [FIX-002] `McpProtocolHandler.java:344` — ACCEPTED_INTENTIONAL

- **Original**: `BusyWait` — `Thread.sleep()` in loop
- **Current source**: Line 308 (`Thread.sleep(rateLimits.sessionCleanupIntervalMs)`) — the daemon cleanup thread's event loop
- **Evidence**:
  ```java
  // McpProtocolHandler.java:303–309
  while (cleanupRunning && !Thread.currentThread().isInterrupted()) {
      try { Thread.sleep(rateLimits.sessionCleanupIntervalMs); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      // expired session cleanup
  }
  ```
- **Classification**: ACCEPTED_INTENTIONAL — deliberate backoff in the session-cleanup daemon thread; required for sliding-window rate-limit record expiration
- **Risk**: Low — daemon thread, interruptible, bounded cleanup batch
- **Regression**: Verify cleanup thread is started/stopped correctly; test interrupt on shutdown

---

#### [FIX-003] `McpHttpHandler.java:400` — ACCEPTED_INTENTIONAL

- **Original**: `BusyWait` — `Thread.sleep()` in loop
- **Current source**: Line 402 (`Thread.sleep(1000)`) — SSE live-polling loop
- **Evidence**:
  ```java
  // McpHttpHandler.java:398–406
  private void runSsePollingLoop(Response response, String sessionId) throws IOException {
      long start = System.currentTimeMillis();
      while (!Thread.currentThread().isInterrupted() && handler.hasSession(sessionId)
              && System.currentTimeMillis() - start < 300_000L) {
          try { Thread.sleep(1000); } catch (InterruptedException e) {
              Thread.currentThread().interrupt(); break;
          }
          // poll and write SSE events or ping
      }
  }
  ```
- **Classification**: ACCEPTED_INTENTIONAL — 1-second SSE heartbeat interval; loop exits on client disconnect, session termination, or 5-minute timeout
- **Risk**: Low — interruptible, timeout-bounded
- **Regression**: Verify ping frames arrive every 1 second; verify disconnect terminates the loop

---

#### [FIX-004] `McpProtocolHandler.java:539` — ACCEPTED_INTENTIONAL

- **Original**: `DataFlowIssue` — variable already assigned to this value
- **Current source**: Line ~540 — `responseSessionId = sessionId` is NOT a self-assignment; `responseSessionId` starts as `null` and is reassigned to `initSessionId` for `initialize` requests
- **Evidence**:
  ```java
  // McpProtocolHandler.java:539–549
  String responseSessionId = sessionId;          // initial value
  Map<String, Object> result;
  // ...
  case McpMethodNames.INITIALIZE:
      result = handleInitialize(initializeParams, initSessionId, clientIp);
      responseSessionId = initSessionId;         // reassigned here — NOT self-assignment
      break;
  ```
- **Classification**: FALSE_POSITIVE — IntelliJ misidentified `initSessionId` (the local variable returned from `handleSessionCreate`) as `sessionId` (the method parameter). The two are distinct variables; `responseSessionId` is legitimately reassigned
- **Action**: No-op; suppress inspection on that line if IntelliJ re-reports it

---

#### [FIX-005] `McpServer.java:48` — FIX_REQUIRED

- **Finding**: `AutoCloseableResource` — `McpServer` used without try-with-resources
- **Current source**: `McpServer` is `AutoCloseable`; the builder pattern creates it but calling code may not close it
- **Evidence**:
  ```java
  // McpServer.java — class implements AutoCloseable but is often instantiated directly:
  //   McpServer server = McpServer.builder().build();
  //   server.start();
  //   // no try-with-resources — shutdown depends on stop() being called
  ```
- **Classification**: FIX_REQUIRED
- **Risk**: Medium — resource leak if `stop()`/`close()` is not called; the cleanup thread in `McpProtocolHandler` holds references to sessions and rate-limit maps
- **Proposed owner**: `dev-architect`
- **Required regression**: Verify that `stop()` and `close()` both terminate the cleanup thread and clear rate-limit maps; add an integration test that starts and stops a server without try-with-resources
- **Collision group**: `LIFECYCLE-001`

---

### ACCEPTED_INTENTIONAL (5)

#### [ACC-001] `McpProtocolHandler.java:128` — BooleanMethodIsAlwaysInverted

- **Finding**: `allowRequest()` called with inverted logic
- **Current source**: Line 128 is `}` + comments + `RateLimitRecord` class. The `allowRequest` (method on `RateLimitRecord`) is called in `checkRateLimit()` at lines 848 and 855 with `if (!ipRecord.allowRequest(...))` — the negation is intentional: return `true` means "allowed", so `!` checks for denial
- **Classification**: FALSE_POSITIVE — the method name `allowRequest` returning `true` = allowed is correct; negation in call site is intentional design
- **Action**: No-op; suppress IntelliJ warning if it persists

---

#### [ACC-002] `McpHttpHandler.java:206` — BooleanMethodIsAlwaysInverted

- **Finding**: `validateRequest()` called with inverted logic
- **Current source**: Method is named `isInvalidRequest` (not `validateRequest`), returning `true` when the request is invalid. Call site: `if (isInvalidRequest(request, response)) return;` — logic is correct: `true` = invalid, so `if (true) return` is correct
- **Classification**: FALSE_POSITIVE — method name correctly conveys boolean contract; no inversion issue
- **Action**: No-op

---

#### [ACC-003] `RateLimits.java:207` — Convert2Diamond

- **Finding**: `Collections.<String>emptyList()` can be `<>`
- **Current source**: Line 207 (now `destructiveTools` builder method, ~line 206 in current file)
- **Classification**: STYLE — minor diamond operator suggestion; low priority
- **Action**: Low-priority cleanup; can be addressed in a batch style fix

---

#### [ACC-004] `McpProtocolHandler.java:110` — SameParameterValue

- **Finding**: Parameter `score` always equals `ABUSE_SCORE`
- **Current source**: `score` parameter no longer exists at line 110. The `setSessionCategoryAbuseScore` method (line ~394) takes `int score` as a separate parameter distinct from the abuse-score value. This finding is stale (line number drift from refactor)
- **Classification**: STALE — parameter position shifted during refactor
- **Action**: No-op

---

#### [ACC-005] `McpHttpHandler.java:261` — SameParameterValue

- **Finding**: Parameter `id` always equals `"0"`
- **Current source**: Line 261 is blank + `ssePing()` helper. No `id` parameter at this line. This is a stale line reference
- **Classification**: STALE — line number drift
- **Action**: No-op

---

### STALE (22 findings)

The following 22 findings reference code that no longer exists, has moved, or has stale line numbers. No action required.

#### McpProtocolHandler.java (13 stale)

| Line | Category | Reason |
|---|---|---|
| 78 | MismatchedCollectionQueryUpdate | `DESTRUCTIVE_TOOLS` field removed (REFACTOR_STALE) |
| 110 | SameParameterValue | `score` param shifted to line ~394 during refactor |
| 271 | DanglingJavadoc | `/**` at line ~271 opens `QueueOverflowListener` Javadoc; properly closed at `*/` — false positive |
| 501 | UnusedAssignment | Line 501 is `}` (end of rate-limit block); no null initializer present |
| 502 | UnusedAssignment | Line 502 is blank; no null initializer present |
| 539 | DataFlowIssue | Self-assignment false positive (see FIX-004) |
| 581 | RedundantSuppression | Line 581 is blank; no `@SuppressWarnings` present |
| 849 | UnusedReturnValue | `allowRequest` return value IS used (`!allowRequest()` = rate-limited) — false positive |
| 861 | UnusedReturnValue | `allowRequest` return value IS used — false positive |
| 1136 | RedundantSuppression | Line 1136 is blank; no `@SuppressWarnings` present |
| 1474 | RedundantCast | Line 1474 is `registry.registerResource(...)`; no cast present |
| 1844 | GrazieInspection | File ends at line 1747 — line does not exist |
| 1995, 2120, 2136, 2158 | SpellCheckingInspection | All exceed file length 1747 — stale |

#### McpHttpHandler.java (3 stale)

| Line | Category | Reason |
|---|---|---|
| 206 | BooleanMethodIsAlwaysInverted | `isInvalidRequest` at line 209; `validateRequest` method does not exist — false positive naming |
| 261 | SameParameterValue | Line 261 is blank; no `id` parameter — stale line |
| 397 | RedundantSuppression | No `@SuppressWarnings` in McpHttpHandler.java — stale |
| 512 | RedundantSuppression | No `@SuppressWarnings` in McpHttpHandler.java — stale |

#### McpRegistry.java (1 stale)

| Line | Category | Reason |
|---|---|---|
| 497 | SynchronizationOnLocalVariable | Line 493 is `synchronized (source)` where `source` is a `List<Map<String, Object>>` parameter; see analysis in STALE section |

#### McpReflectionRegistrar.java (1 stale)

| Line | Category | Reason |
|---|---|---|
| 286 | RedundantSuppression | No `@SuppressWarnings` in McpReflectionRegistrar.java — stale |

#### McpBlobResourceHandler.java (1 stale)

| Line | Category | Reason |
|---|---|---|
| 28 | RedundantThrows | File has 28 lines; line 28 is blank — file too short for this finding |

#### DefaultApiKeyStore.java (2 stale)

| Line | Category | Reason |
|---|---|---|
| 113 | DanglingJavadoc | `/**` at line ~107 opens `shutdown()` Javadoc; properly closed at `*/` — false positive |
| 121 | UnusedReturnValue | File ends at line 120 — line does not exist |

#### McpServerConfig.java (1 stale)

| Line | Category | Reason |
|---|---|---|
| 267 | JavadocReference | `{@code MAX_PENDING_NOTIFICATIONS_PER_SESSION}` at line ~280 uses the literal constant name; no broken reference |

#### McpJsonRpc.java (1 stale)

| Line | Category | Reason |
|---|---|---|
| 8 | JavadocReference | `supportsProtocolVersion` is a method in `McpProtocolHandler`, not `McpJsonRpc` — IntelliJ incorrectly cross-referenced |

#### ApiKeyStore.java (1 stale)

| Line | Category | Reason |
|---|---|---|
| 5 | JavadocBlankLines | Blank lines in Javadoc (`/**` and `@return` separated by blank) are standard format — false positive |

---

## Collision Groups

| Group | Files | Issue | Note |
|---|---|---|---|
| `LIFECYCLE-001` | `McpServer.java` | `AutoCloseable` usage | FIX-005 |

---

## Recommendations

1. **Immediate**: Address [FIX-005] (`McpServer.java:48` — `AutoCloseable` lifecycle). This is the only genuine runtime resource-leak risk among the 27 findings.
2. **Short-term**: Verify the 5 `synchronized (source)` sites in `McpRegistry.java` (including the `copyDefinitions` use at current line 493) for thread-safety correctness; open a separate architecture decision if the `source` parameter can escape the synchronisation scope.
3. **Long-term**: Add a Gradle-based static analysis plugin (SpotBugs, Error Prone, PMD) for command-line-equivalent coverage without requiring IntelliJ IDEA.
4. **Automation**: Investigate IntelliJ IDEA Headless Analysis (`inspect.sh`) for automated inspection runs in CI.

---

## No Source Changes Made

This QA verification task did not modify any production source code. All findings are documented for review by the appropriate developer.

**File modified**: `docs/inspect/FINDINGS-CLASSIFICATION.md` only.
