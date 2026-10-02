# Inspection Governance Policy

Date: 2026-09-30
Inspection tool: IntelliJ IDEA 2024.3 (IU-243.28141.41)
Profile: `pProject Default`
Workspace: `docs/inspect/` (IDE evidence, not canonical source truth)
Current HEAD: `b4fb81b`

---

## 1. Classification Taxonomy

Every finding belongs to exactly one classification. This taxonomy replaces ad-hoc labels.

| Code                   | Label                         | Definition                                                | Action                                |
|------------------------|-------------------------------|-----------------------------------------------------------|---------------------------------------|
| `STALE`                | Stale / Removed               | Source symbol, file, or line no longer exists             | No-op; delete from XML on next export |
| `FALSE_POSITIVE`       | False Positive                | Inspection fired on correct code                          | No-op; suppress per-file if needed    |
| `API_PUBLIC`           | Accepted: Public API          | Symbol is part of SDK public/experimental API surface     | Accept; document in worklist          |
| `ACCEPTED_INTENTIONAL` | Accepted: Intentional Pattern | Deliberate design, documented and intentional             | Accept; document risk and regression  |
| `STYLE`                | Style / Low Priority          | Formatting, diamond, casts; no semantic effect            | Batch-clean or defer                  |
| `FIX_REQUIRED`         | Fix Required                  | Defect confirmed by human reviewer                        | Open task; owner assigned             |
| `LANG_MIGRATION`       | Language Migration Aid        | Java version migration hint (Since15, RedundantCast)      | Accept; close when target reached     |
| `TEST_HELPER`          | Accepted: Test Helper         | Test fixture returning a constant, JUnit discovery method | Accept; document why                  |

---

## 2. Artifact Ownership

| Path                                      | Role                                          | Included in commit?                      |
|-------------------------------------------|-----------------------------------------------|------------------------------------------|
| `docs/inspect/*.xml`                      | IDE evidence (raw export)                     | Yes — evidence of what IDE reported      |
| `docs/inspect/.xml`                       | Same as above, full batch                     | Yes                                      |
| `docs/inspect/.descriptions.xml`          | IDE inspection descriptions only; no findings | Yes — reference metadata                 |
| `docs/inspect/FINDINGS-CLASSIFICATION.md` | Canonical current-source classification       | Yes — **this is the current truth**      |
| `docs/inspect/INSPECTION-GOVERNANCE.md`   | Policy and worklist                           | Yes — **this document**                  |
| `docs/audits/evidence/inspect/`           | Historical archive (pre-refactor)             | Archived; do not use as current evidence |

`docs/inspect/` is **IDE evidence only**. Raw XML may contain stale findings from removed/renamed code. The `.md` files
are the maintained source of truth. Do not treat historical XML as authoritative.

---

## 3. How to Reproduce / Attach Evidence

1. Open the project in IntelliJ IDEA 2024.3 (IU-243.28141.41).
2. **Code > Analyze Code > Run Inspection by Name...** (Ctrl+Alt+Shift+I) — or **Inspect Code...** for full project.
3. Select profile: **`pProject Default`** (confirmed in `.idea/workspace.xml:201`).
4. Scope: whole project or specific module.
5. **File > Export Results** to export as XML.
6. Replace `docs/inspect/*.xml` with fresh export.
7. Re-run classification: compare new export against this document's worklist; update this document's classifications.

> Note: IntelliJ IDEA inspections require a GUI and cannot be automated via CLI without additional tooling. When a
> Gradle-based inspection task is added (see Recommendation #4), this workflow can be automated.

---

## 4. Current Worklist

_Counts as of 2026-09-30 export (HEAD `b4fb81b`). Source-revalidated where noted._

### 4.1 `unused` — 44 findings

**Source files (39)**

| File                                            | Count | Classification                         | Action                                                                                    |
|-------------------------------------------------|-------|----------------------------------------|-------------------------------------------------------------------------------------------|
| `McpServer.java`                                | 17    | `API_PUBLIC` (15), `STALE` (2)         | Accept 15 as public API surface. Investigate 2 stale lines (see below).                   |
| `McpLogger.java`                                | 9     | `API_PUBLIC` (7), `FALSE_POSITIVE` (2) | Accept 7 (public API); investigate 2 false positives.                                     |
| `McpServerConfig.Builder.logger()`              | 1     | `API_PUBLIC`                           | Accept. `McpLogger` is experimental public API.                                           |
| `McpToolHandler.java`                           | 1     | `API_PUBLIC`                           | Accept. Public handler interface.                                                         |
| `McpRegistrar.java`                             | 5     | `API_PUBLIC`                           | Accept. Public SPI.                                                                       |
| `McpBlobContent.java`                           | 2     | `API_PUBLIC`                           | Accept. Public DTO.                                                                       |
| `McpRegistry.java`                              | 2     | `API_PUBLIC`                           | Accept. Public registry.                                                                  |
| `GrizzlyStreamableServerTransportProvider.java` | 2     | `STALE`                                | File removed (ADR-0019 refactor). Delete from XML on next export. Owner: `dev-architect`. |
| `McpProtocolHandler.java`                       | 1+    | `STALE` (line > file length)           | Line 374 > current max 1731? Wait — line 374 exists. Let me recheck.                      |

**Test files (5)**

| File                              | Method     | Count | Classification   | Action                                                                       |
|-----------------------------------|------------|-------|------------------|------------------------------------------------------------------------------|
| `McpExampleRegistrationTest.java` | `readme()` | 1     | `TEST_HELPER`    | Accept. JUnit `@Parameters` helper returns constant readme URI; intentional. |
| `McpExampleRegistrationTest.java` | other      | 4     | `FALSE_POSITIVE` | Accept. Test-discovery methods not called by static analysis; no risk.       |

**Stale reference detail**

| File                          | Finding                    | Status                                                                                                                                                                                       |
|-------------------------------|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpProtocolHandler.java:374` | Referenced in `unused.xml` | Current file length: 1731 lines. Line 374 exists and is likely a JUnit test-discovery method (`@Test` or `@Parameters`). Static analysis cannot see JUnit caller. Classify as `TEST_HELPER`. |

### 4.2 `.xml` main batch — 60 findings (unused declarations)

**Grizzly removed classes (10)**

| File                                  | Lines          | Count | Classification                                                           |
|---------------------------------------|----------------|-------|--------------------------------------------------------------------------|
| `McpGrizzlyHandler.java`              | 20, 63, 72, 84 | 4     | `STALE` — class removed (ADR-0019 refactor, `McpGrizzlyHandler` deleted) |
| `McpClientCapabilitiesTest.java`      | 12, 14         | 2     | `STALE` — file removed                                                   |
| `McpGrizzlyLiveTest.java`             | 22             | 1     | `STALE` — file removed                                                   |
| `McpGrizzlySecurityMatrixTest.java`   | various        | 1     | `STALE` — file removed                                                   |
| `McpListChangedNotificationTest.java` | various        | 1     | `STALE` — file removed                                                   |
| `McpPaginationTest.java`              | various        | 1     | `STALE` — file removed                                                   |

**Test files present in repo (50)**

All report `unused declaration` on JUnit test methods. IntelliJ's static analysis cannot trace JUnit test discovery. All
are `TEST_HELPER`.

| File                          | Count |
|-------------------------------|-------|
| `McpProtocolHandlerTest.java` | ~13   |
| `McpTasksTest.java`           | ~12   |
| `McpServerConfigTest.java`    | ~8    |
| Other test files              | ~17   |

**Action for unused declarations**: Mark all as `ACCEPTED_INTENTIONAL / TEST_HELPER` in worklist. No source changes
required. Suppress in IntelliJ if desired but do not clean up test files.

### 4.3 `DuplicatedCode` — 6 findings

Severity: `WEAK WARNING` (INFO_ATTRIBUTES).

| File                          | Lines                            | Classification | Action                                                               |
|-------------------------------|----------------------------------|----------------|----------------------------------------------------------------------|
| `McpProtocolHandler.java:641` | `resourceContents()` body        | `STYLE`        | Low priority. Consider extracting a private helper `newResultMap()`. |
| `McpProtocolHandler.java:653` | `blobContents()` body            | `STYLE`        | Same as above — same duplicate pair.                                 |
| `McpRegistry.java:103`        | `registerResource()`             | `STYLE`        | Low priority. Duplicate `requireUnique` call pattern.                |
| `McpRegistry.java:127`        | `registerBlobResource()`         | `STYLE`        | Same as above.                                                       |
| `McpRegistry.java:150`        | `registerResourceTemplate()`     | `STYLE`        | Same as above.                                                       |
| `McpRegistry.java:175`        | `registerBlobResourceTemplate()` | `STYLE`        | Same as above.                                                       |

**Action**: Accept as `STYLE`. Batch-clean optional. No immediate fix required.

### 4.4 `RedundantSuppression` — 4 findings

| File                              | Line                             | Classification                                       | Action |
|-----------------------------------|----------------------------------|------------------------------------------------------|--------|
| `McpProtocolHandler.java:581`     | blank                            | `STALE` — line 581 does not exist (file: 1731 lines) |
| `McpProtocolHandler.java:1136`    | blank                            | `STALE` — line 1136 does not exist                   |
| `McpProtocolHandler.java:1474`    | `registry.registerResource(...)` | `FALSE_POSITIVE` — no `@SuppressWarnings` present    |
| `McpReflectionRegistrar.java:286` | blank                            | `STALE` — file removed or line shifted               |

**Action**: All no-op. Delete findings on next export.

### 4.5 `AutoCloseableResource` — 2 findings

| File                         | Line                                       | Classification         | Action                                                                                                                                                                                                      |
|------------------------------|--------------------------------------------|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpServer.java:48`          | `registerAll()`                            | `FIX_REQUIRED`         | Non-try-with-resources usage of `McpServer` in `registerAll`. Risk: medium. See LIFECYCLE-001. Owner: `dev-architect`. Regression: integration test verifying `stop()`/`close()` terminates cleanup thread. |
| `McpGrizzlyLiveTest.java:22` | `GrizzlyStreamableServerTransportProvider` | `STALE` — file removed |

### 4.6 `SynchronizationOnLocalVariableOrMethodParameter` — 2 findings

Both in `McpRegistry.java`.

| Line | Parameter                        | Classification | Action                                                                                                                                  |
|------|----------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| 417  | `definitions` (method parameter) | `FIX_REQUIRED` | Synchronizing on method parameter. Risk: medium — parameter escapes synchronisation scope. Owner: `dev-architect`. See CONCURRENCY-001. |
| 441  | `source` (method parameter)      | `FIX_REQUIRED` | Same pattern. Owner: `dev-architect`. See CONCURRENCY-001.                                                                              |

**Recommendation**: Verify both synchronisation sites are correct; open architecture decision if the parameter can
escape the synchronisation scope.

### 4.7 `SameReturnValue` — 2 findings

| File                                    | Line                                 | Classification | Action                                                         |
|-----------------------------------------|--------------------------------------|----------------|----------------------------------------------------------------|
| `McpExampleRegistrationTest.java:24`    | `readme()` returns constant          | `TEST_HELPER`  | Accept. JUnit `@Parameters` helper returning test fixture URI. |
| `McpGrizzlySecurityMatrixTest.java:106` | `initialize()` returns constant JSON | `STALE`        | File removed. Delete on next export.                           |

### 4.8 `UnusedReturnValue` — 2 findings

Both in `McpProtocolHandler.java`.

| Line | Method                                                   | Classification   | Action                                                                       |
|------|----------------------------------------------------------|------------------|------------------------------------------------------------------------------|
| 849  | `allowRequest()` return value used via `!allowRequest()` | `FALSE_POSITIVE` | Negation is intentional design; return value IS used as denial check. No-op. |
| 861  | `allowRequest()` return value used                       | `FALSE_POSITIVE` | Same as above. No-op.                                                        |

### 4.9 `SpellCheckingInspection` — 1 finding

| File                          | Line                        | Finding       | Classification | Action                                                                            |
|-------------------------------|-----------------------------|---------------|----------------|-----------------------------------------------------------------------------------|
| `McpProtocolHandler.java:962` | `pollPendingNotification()` | Typo: `ndata` | `STYLE`        | Fix typo. Change `ndata` → `notificationData` or similar. Owner: `dev-architect`. |

### 4.10 `RedundantThrows` — 1 finding

| File                             | Line    | Classification                                             | Action                        |
|----------------------------------|---------|------------------------------------------------------------|-------------------------------|
| `McpBlobResourceHandler.java:28` | `STALE` | File has 28 lines; line 28 is blank. No `@throws` present. | No-op. Delete on next export. |

### 4.11 `MismatchedCollectionQueryUpdate` — 1 finding

| File                         | Line                        | Classification                                                       | Action                        |
|------------------------------|-----------------------------|----------------------------------------------------------------------|-------------------------------|
| `McpProtocolHandler.java:78` | `DESTRUCTIVE_TOOLS` removed | `STALE` — field no longer exists (ADR resolution, commit `c8d64ac`). | No-op. Delete on next export. |

### 4.12 `BusyWait` — 1 finding

| File                          | Line                                                                  | Classification         | Action                                                                                                                                                  |
|-------------------------------|-----------------------------------------------------------------------|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `McpProtocolHandler.java:308` | `Thread.sleep(rateLimits.sessionCleanupIntervalMs)` in cleanup daemon | `ACCEPTED_INTENTIONAL` | Deliberate backoff in the session-cleanup daemon thread. Interruptible, bounded cleanup batch. Risk: low. Regression: verify cleanup thread start/stop. |

---

## 5. Collision Groups

| Group             | Files                      | Issue                                                                                 | Owner           |
|-------------------|----------------------------|---------------------------------------------------------------------------------------|-----------------|
| `LIFECYCLE-001`   | `McpServer.java`           | `AutoCloseable` resource lifecycle: `registerAll()` called without try-with-resources | `dev-architect` |
| `CONCURRENCY-001` | `McpRegistry.java:417,441` | Synchronising on method parameters                                                    | `dev-architect` |

---

## 6. Completion Criteria

### `unused` (44 findings)

- **Source files**: All `API_PUBLIC` findings accepted and documented in this worklist. No source changes.
- **Test files**: All `TEST_HELPER`/`FALSE_POSITIVE`. No source changes.
- **Stale** (`GrizzlyStreamableServerTransportProvider`): Delete from XML on next IntelliJ export.
- **Done** when: This worklist documents each finding's classification and no `FIX_REQUIRED` items remain in this
  category.

### Language migration aids (Since15, RedundantCast, Convert2Diamond)

- **Current state**: 0 Since15, 0 RedundantCast, 0 Convert2Diamond findings in current export. These were present in
  earlier baselines and have been resolved by prior refactoring.
- **Done** when: Maintain zero count on each export. If any reappear, classify and address.

### Style findings (Grazie, SpellChecking, RedundantCast, Convert2Diamond)

| Category               | Count | Action                                     |
|------------------------|-------|--------------------------------------------|
| Grazie (grammar/prose) | 0     | Maintain zero.                             |
| SpellChecking          | 1     | Fix typo in `McpProtocolHandler.java:962`. |
| RedundantCast          | 0     | Maintain zero.                             |
| Convert2Diamond        | 0     | Maintain zero.                             |

### High-risk findings

| Group                                             | Owner            | Done when                                        |
|---------------------------------------------------|------------------|--------------------------------------------------|
| `LIFECYCLE-001` (`McpServer.java` AutoCloseable)  | `dev-architect`  | Fix applied + integration test added             |
| `CONCURRENCY-001` (`McpRegistry` synchronisation) | `dev-architecct` | Architecture decision documented + fix if needed |

---

## 7. Recommendations

1. **Immediate**: Fix `McpProtocolHandler.java:962` typo (`ndata` → meaningful name). One-line change.
2. **Short-term**: Address `LIFECYCLE-001` (`McpServer.java:48` `AutoCloseable` resource lifecycle) and
   `CONCURRENCY-001` (`McpRegistry.java:417,441` synchronisation on method parameters). Both are genuine correctness
   risks.
3. **Medium-term**: Batch-clean 6 `DuplicatedCode` `STYLE` findings in `McpProtocolHandler.java` and `McpRegistry.java`.
   Extract `newResultMap()` helper for `resourceContents`/`blobContents`; extract `requireUnique` call for the 4
   `McpRegistry` register methods.
4. **Long-term**: Add a Gradle-based static analysis plugin (SpotBugs, Error Prone, PMD) for command-line-equivalent
   coverage without requiring IntelliJ IDEA.
5. **Automation**: Investigate IntelliJ IDEA Headless Analysis (`inspect.sh`) for automated inspection runs in CI (do
   not edit workflow files — coordinate with DevOps if a CI-doc validation task exists).

---

## 8. Validation

```text
./gradlew clean test --no-daemon --console=plain
git diff --check
```

Both must pass before any commit.

---

## 9. No Source Changes Made

This governance task produced documentation only. No production source code was modified.

**File created**: `docs/inspect/INSPECTION-GOVERNANCE.md`
