# IDE Inspection Findings — Current Source Classification

Date: 2026-09-29
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
| ACTIONABLE | 27 | High/medium findings requiring review or remediation |

**Total: 299 findings across 33 XML categories**

---

## Evidence Table — Unresolved High/Medium Findings

### ACTIONABLE: Correctness / Security / Concurrency

| File | Line | Category | Description |
|---|---|---|---|
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 78 | MismatchedCollectionQueryUpdate | Contents of collection `DESTRUCTIVE_TOOLS` are updated but never queried — **verify intent or suppress** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 344 | BusyWait | `Thread.sleep()` in loop — **intentional retry backoff or test only?** |
| `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java` | 400 | BusyWait | `Thread.sleep()` in loop — **intentional retry backoff** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 539 | DataFlowIssue | Variable already assigned to this value — **verify logic** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java` | 497 | SynchronizationOnLocalVariable | Synchronization on method parameter `source` — **verify thread safety** |

### ACTIONABLE: Code Quality / Refactor Opportunities

| File | Line | Category | Description |
|---|---|---|---|
| `src/main/java/io/github/vinhphan812/mcp/core/McpServer.java` | 48 | AutoCloseableResource | `McpServer` used without try-with-resources — **ensure lifecycle management** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 128 | BooleanMethodIsAlwaysInverted | `allowRequest()` called with inverted logic — **verify boolean contract** |
| `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java` | 206 | BooleanMethodIsAlwaysInverted | `validateRequest()` called with inverted logic — **verify boolean contract** |
| `src/main/java/io/github/vinhphan812/mcp/api/config/RateLimits.java` | 207 | Convert2Diamond | `Collections.<String>emptyList()` can be `<>` — minor style |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 110 | SameParameterValue | Parameter `score` always equals `ABUSE_SCORE` — **verify if param is needed** |
| `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java` | 261 | SameParameterValue | Parameter `id` always equals `"0"` — **verify if param is needed** |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/McpBlobResourceHandler.java` | 28 | RedundantThrows | Declared `Exception` never thrown — remove or narrow declaration |

### ACTIONABLE: Redundant Code / Suppressions

| File | Line | Category | Description |
|---|---|---|---|
| `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java` | 286 | RedundantSuppression | Suppression no longer needed — **remove or verify** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 581 | RedundantSuppression | Suppression no longer needed — **remove or verify** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 1136 | RedundantSuppression | Suppression no longer needed — **remove or verify** |
| `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java` | 397 | RedundantSuppression | Suppression no longer needed — **remove or verify** |
| `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java` | 512 | RedundantSuppression | Suppression no longer needed — **remove or verify** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 501 | UnusedAssignment | Null initializer redundant — **remove redundant null** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 502 | UnusedAssignment | Null initializer redundant — **remove redundant null** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 849 | UnusedReturnValue | Return value unused — **verify if return is intentional** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 861 | UnusedReturnValue | Return value unused — **verify if return is intentional** |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 1474 | RedundantCast | Cast to `long` redundant — **remove cast** |

### ACTIONABLE: Documentation Issues

| File | Line | Category | Description |
|---|---|---|---|
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 271 | DanglingJavadoc | Dangling Javadoc comment — **add `@throws`/`@return` or close properly** |
| `src/main/java/io/github/vinhphan812/mcp/api/security/DefaultApiKeyStore.java` | 113 | DanglingJavadoc | Dangling Javadoc comment — **fix or remove** |
| `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` | 267 | JavadocReference | Cannot resolve symbol `MAX_PENDING_NOTIFICATIONS_PER_SESSION` — **update or remove reference** |
| `src/main/java/io/github/vinhphan812/mcp/api/utils/McpJsonRpc.java` | 8 | JavadocReference | Cannot resolve symbol `supportsProtocolVersion` — **update or remove reference** |
| `src/main/java/io/github/vinhphan812/mcp/api/spi/ApiKeyStore.java` | 5 | JavadocBlankLines | Blank line in Javadoc will be ignored — **fix formatting** |

---

## Stale / Archived Findings

These findings reference code that was removed or refactored since the original inspection export.

### REFACTOR_STALE (5 findings)

| File | Line | Category | Description |
|---|---|---|---|
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 435 | ClassEscapesItsScope | `CategoryRateLimitState` removed — class no longer exists |
| `src/test/java/io/github/vinhphan812/mcp/core/McpCategoryReservationTest.java` | 418 | RedundantCast | `getSessionCategoryLimits()` removed — method no longer exists |
| `src/test/java/io/github/vinhphan812/mcp/core/McpCategoryReservationTest.java` | 492 | RedundantCast | `getSessionCategoryLimits()` removed — method no longer exists |
| `src/test/java/io/github/vinhphan812/mcp/core/McpCategoryReservationTest.java` | 529 | RedundantCast | `getSessionCategoryLimits()` removed — method no longer exists |
| `src/test/java/io/github/vinhphan812/mcp/core/McpCategoryReservationTest.java` | 566 | RedundantCast | `getSessionCategoryLimits()` removed — method no longer exists |

### STALE_LINE (6 findings)

Lines exceed current file length (refactor removed lines):

| File | Line | Category |
|---|---|---|
| `src/main/java/io/github/vinhphan812/mcp/api/security/DefaultApiKeyStore.java` | 121 | UnusedReturnValue |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 1844 | GrazieInspection |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 1995 | SpellCheckingInspection |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 2120 | SpellCheckingInspection |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 2136 | SpellCheckingInspection |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 2158 | SpellCheckingInspection |

---

## Accepted Findings

### ACCEPTED_TEST_DISCOVERY (110 findings)

JUnit 5 `@Test` methods are discovered at runtime via reflection by the test framework. IntelliJ IDEA's static analysis incorrectly marks these as "unused" because it doesn't recognize framework-based test discovery patterns. These are **false positives** and do not require action.

### STYLE (151 findings)

Style and preference issues including:
- `Convert2Diamond` (16): Minor diamond operator suggestion
- `RedundantCast` (17): Redundant type casts in production code
- `GrazieInspection` (9): Grammar/style suggestions
- `SpellCheckingInspection` (14): Spelling suggestions
- `Convert2Lambda` (8): Lambda expression suggestions
- `SameReturnValue` (5): Methods always returning same value
- `DuplicateBranchesInSwitch` (1): Duplicate switch branches
- `EmptyMethod` (2): Empty method bodies (may be intentional)
- `SimplifiableAssertion` (1): Assertion simplification
- `Since15` (14): Java version annotation suggestions
- `ClassEscapesItsScope` (1): Minor scope issue
- `CodeBlock2Expr` (2): Code style suggestions
- `CommentedOutCode` (1): Commented code suggestion
- `ExtractMethodRecommender` (1): Method extraction suggestion
- `UnusedReturnValue` (2): Return value not used in tests
- `unused` (19): Non-test unused declarations in production code

---

## Recommendations

1. **Immediate**: Fix the 5 **Correctness/Security/Concurrency** findings (BusyWait, DataFlowIssue, SynchronizationOnLocalVariable, MismatchedCollectionQueryUpdate) — these represent potential runtime issues.

2. **Short-term**: Address the 11 **Documentation** and **Redundant Code** findings — these are low-effort cleanup items.

3. **Long-term**: Consider adding a Gradle-based static analysis plugin (SpotBugs, Error Prone, PMD) to automate inspection runs in CI without requiring IntelliJ IDEA.

4. **Automation**: Investigate IntelliJ IDEA Headless Analysis (`inspect.sh`) for automated inspection runs, or add a Gradle plugin like `spotbugs` or `pmd` for command-line equivalent coverage.

---

## No Source Changes Made

This QA verification task did not modify any production source code. All findings are documented for review by the appropriate developer.
