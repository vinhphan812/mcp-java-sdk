# CI Portability, Java 8 Compatibility Evidence, and Release Gate Specification

**Task:** t_94c8b631  
**Author:** dev-ops triage audit  
**Date:** 2026-09-21  
**Status:** Specification — for downstream implementation task consumption  
**Workspace evidence:** 22 JUnit XML test suites, 0 failures, 0 errors, 12 skipped (McpRateLimitTest); working tree: 33
staged / 38 modified / 29 untracked

---

## 1. GRADLE INVOCATION PORTABILITY

### 1.1 Current State

Two files use the Gradle wrapper, but neither is self-consistent across platforms:

| File                            | Step                       | Invocation                                                | Runner OS     | Result                                                                                    |
|---------------------------------|----------------------------|-----------------------------------------------------------|---------------|-------------------------------------------------------------------------------------------|
| `.github/workflows/ci.yml`      | Build SDK classes          | `./gradlew clean classes`                                 | ubuntu-latest | OK (Unix shell script)                                                                    |
| `.github/workflows/ci.yml`      | Compile examples (CI gate) | `./gradlew.bat --no-daemon -p examples clean compileJava` | ubuntu-latest | **BROKEN** — `.bat` is Windows-only; fails silently or errors on ubuntu-latest bash shell |
| `.github/workflows/ci.yml`      | Test                       | `./gradlew test`                                          | ubuntu-latest | OK (Unix shell script)                                                                    |
| `.github/workflows/release.yml` | Test and build             | `./gradlew clean test build`                              | ubuntu-latest | OK (Unix shell script)                                                                    |
| `.github/workflows/release.yml` | Publish                    | `./gradlew publish`                                       | ubuntu-latest | OK (Unix shell script)                                                                    |

**Root cause:** `./gradlew.bat` is a Windows batch file. On `ubuntu-latest` (GitHub Actions Linux runner), the shell is
bash — it cannot execute `.bat` files. The step silently fails or errors because the shebang-based `gradlew` (Unix shell
script, same directory) is the correct binary for Linux/macOS runners.

### 1.2 Recommended Fix

1. Replace `./gradlew.bat` with `./gradlew` in ci.yml line 25.
2. Add `--no-daemon` and `-p examples` flags to maintain equivalent behaviour.
3. Add the same examples compilation step to `release.yml` (see Section 1.3).
4. No platform-conditional logic is needed if runners are always `ubuntu-latest`. If Windows runner support is desired
   in future, use `runner.os` matrix variable and branch:
   `run: ${{ runner.os == 'Windows' && './gradlew.bat' || './gradlew' }}`.

### 1.3 Examples Compilation Gate

The examples subproject (`examples/build.gradle`) compiles against `build/classes/java/main` from the root project. A
successful SDK `classes` task is a prerequisite. The CI gate must run:

```bash
# In ci.yml (after Build SDK classes step):
./gradlew -p examples compileJava --console=plain

# In release.yml (after Test and build step):
./gradlew -p examples compileJava --console=plain
```

If `examples` compilation is added as a Gradle subproject (`include 'examples'` in settings.gradle), this can be folded
into the standard `./gradlew classes` call via task dependency. The current approach (separate `-p examples` invocation)
is valid and used successfully in CI.

### 1.4 Files to Modify

- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

### 1.5 Acceptance Criteria

- [ ] ci.yml line 25: `./gradlew.bat` replaced with `./gradlew`
- [ ] ci.yml: examples compilation step present and uses `./gradlew -p examples compileJava`
- [ ] release.yml: examples compilation step added after `./gradlew clean test build`
- [ ] Both workflows pass `./gradlew --version` validation on ubuntu-latest (shebang invocation)
- [ ] No `.bat` references remain in any `.github/workflows/*.yml` file

### 1.6 Rollback / Compatibility Notes

- No rollback risk: correcting `.bat` -> `./gradlew` is a pure workflow fix with no build file change.
- If Windows runners are added in future, restore conditional logic.
- The `gradle/actions/setup-gradle@v4` action already handles wrapper download for non-checkout scenarios; no separate
  `uses: gradle/wrapper-action` needed.

---

## 2. JAVA 8 COMPATIBILITY EVIDENCE STRATEGY

### 2.1 Current State

Both Gradle build files declare Java 8 compatibility:

**`build.gradle` (root, lines 14–16):**

```groovy
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
```

**`examples/build.gradle` (lines 27–29):**

```groovy
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
```

However, the CI runner (`ubuntu-latest`) installs **JDK 17** (ci.yml line 19: `java-version: '17'`). The
`sourceCompatibility`/`targetCompatibility` settings are **compile-time constraints only** — they do not prevent the
code from using JDK 17 APIs at compile time. If the codebase accidentally uses a JDK 9+ API (e.g., `List.of()`,
`Stream.takeWhile()`, `var` keyword), the compiler will accept it (since the toolchain is JDK 17) but the resulting
bytecode will fail at runtime on a JDK 8 JVM.

No toolchain provision is present in either build file.

### 2.2 Recommended Fix — Gradle Toolchain (Preferred)

Gradle's Java toolchain support can enforce that compilation uses a specific JDK:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}
```

When `gradle/actions/setup-gradle@v4` is used, the GitHub Actions runner auto-detects and provisions the correct JDK
toolchain version via ` JDK auto-detection`. However, a JDK 8 toolchain requires a JDK 8 installation on the runner —
currently not present.

**Option A — Toolchain download (recommended):**  
Install JDK 8 alongside JDK 17 in the workflow using `actions/setup-java@v4` with a matrix, or add a separate job that
runs with JDK 8:

```yaml
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: '8'
    cache: 'gradle'
```

**Option B — animal-sniffer-maven-plugin equivalent (alternative):**  
The Maven `animal-sniffer-maven-plugin` has no direct Gradle equivalent, but the `net.zzonk` Gradle plugin
`gradle Animal Sniffer` or manual `-Danimal.sniffer.skip=false` with a signature file can enforce API signatures.
However, the toolchain approach is cleaner.

**Option C — bytecode inspection post-compile (supplementary):**  
After compilation, run a class file inspector to confirm the bytecode major version is 52 (Java 8):

```bash
javap -v build/classes/java/main/io/github/vinhphan812/mcp/.../McpServer.class | grep 'major version'
# Expected: major version 52
```

This is a non-blocking evidence-gathering step — it produces an artifact the release reviewer can inspect.

### 2.3 Evidence Collection

The following files/logs prove Java 8 compatibility:

| Evidence                        | Location                                | Purpose                                              |
|---------------------------------|-----------------------------------------|------------------------------------------------------|
| JUnit test XML results          | `build/test-results/test/*.xml`         | Tests ran and passed on JDK 8                        |
| Bytecode major version          | `javap -v` output artifact              | Confirms class files are Java 8 (major=52)           |
| Animal sniffer report           | `build/reports/animal-sniffer/`         | API compatibility report (if toolchain plugin added) |
| Gradle toolchain resolution log | CI logs (search "Using Java toolchain") | Confirms JDK 8 was used to compile                   |

Current state: 22 test suites, 0 failures, 0 errors — confirms SDK classes compile and tests pass. No bytecode version
evidence or toolchain enforcement exists yet.

### 2.4 Files to Modify

- `.github/workflows/ci.yml` — add JDK 8 installation step
- `.github/workflows/release.yml` — add JDK 8 installation step
- `build.gradle` — add `java.toolchain` block
- `examples/build.gradle` — add `java.toolchain` block

### 2.5 Acceptance Criteria

- [ ] JDK 8 toolchain configured in `build.gradle` and `examples/build.gradle`
- [ ] CI runs on JDK 8 (install via `actions/setup-java` with `java-version: '8'`, or matrix with both JDK versions)
- [ ] Bytecode major version 52 artifact collected in CI (javap step)
- [ ] All existing tests pass under JDK 8 toolchain
- [ ] Examples compilation succeeds under JDK 8 toolchain

### 2.6 Rollback / Compatibility Notes

- Gradle toolchain is non-invasive: if JDK 8 is not installed, Gradle downloads it automatically (`ToolchainManagement`
  auto-provisioning). This requires network access in CI.
- If network access is restricted, install JDK 8 explicitly in the workflow.
- `sourceCompatibility`/`targetCompatibility` can be removed once toolchain is set (toolchain implies them), but
  retaining both is harmless.
- JDK 8 toolchain + JDK 17 host is supported: Gradle can cross-compile when toolchain is set.

---

## 3. CLEAN-CHECKOUT RELEASE GATE

### 3.1 Current State

`release.yml` triggers on any tag matching `v*`. It performs no git status check. The current working tree has:

```
33 staged changes
38 modified files
29 untracked files
```

If a release is cut from the current state, the published JAR and GitHub Release will include artifacts built from
uncommitted source — breaking reproducibility and auditability.

No test XML artifact upload exists in either workflow.

### 3.2 Recommended Fix

**A. Clean Working Tree Gate:**

Add a `permissions: contents: read` scoped job that verifies the working tree before the release job runs:

```yaml
jobs:
  pre-flight:
    runs-on: ubuntu-latest
    outputs:
      clean: ${{ steps.check.outputs.clean }}
    steps:
      - uses: actions/checkout@v4
      - id: check
        run: |
          git fetch origin ${{ github.ref_name }}
          if [ "$(git rev-list HEAD...origin/${{ github.ref_name }}" != "0" ] || \
              [ "$(git status --porcelain)" != "" ]; then
            echo "clean=false" >> $GITHUB_OUTPUT
            echo "WORKING TREE IS NOT CLEAN — aborting release"
            exit 1
          fi
          echo "clean=true" >> $GITHUB_OUTPUT
```

Alternative (simpler): `if [ -n "$(git status --porcelain)" ]; then exit 1; fi`

**B. Test XML Evidence Handling:**

In both workflows, after the test step:

```yaml
- name: Upload test results
  uses: actions/upload-artifact@v4
  if: always()  # Upload even on failure
  with:
    name: test-results
    path: build/test-results/test/
```

JUnit XML files are at `build/test-results/test/TEST-*.xml`. Currently 22 suites exist. Retention: GitHub artifact
default is 90 days; configure retention explicitly if long-term evidence is needed.

**C. No-Secret Artifact Policy:**

The release workflow currently uploads only the JAR file:

```yaml
files: build/libs/mcp-java-sdk-*.jar
```

This is compliant: JAR files contain no secrets. However, `build/reports/tests/test/` (HTML test reports) should NOT be
published as release artifacts — they contain build metadata but are not reproducibility-critical. Any artifact
containing credentials, tokens, or environment variables must be excluded.

**Artifact policy matrix:**

| Artifact                           | CI upload    | Release upload    | Reason                  |
|------------------------------------|--------------|-------------------|-------------------------|
| `build/libs/mcp-java-sdk-*.jar`    | Yes (ci.yml) | Yes (release.yml) | Deliverable             |
| `build/test-results/test/*.xml`    | Yes          | Yes               | Evidence                |
| `build/reports/tests/test/` (HTML) | No           | No                | Non-essential           |
| `examples/build/libs/`             | No           | No                | Internal build artifact |

### 3.3 Files to Modify

- `.github/workflows/release.yml` — add clean checkout gate job and test artifact upload
- `.github/workflows/ci.yml` — add test artifact upload step

### 3.4 Acceptance Criteria

- [ ] release.yml: `pre-flight` job verifies `git status --porcelain` is empty before release proceeds
- [ ] release.yml: fails (non-zero exit) if uncommitted changes exist
- [ ] release.yml: releases only on annotated tags (already configured via `tags: ['v*']` without `--force`)
- [ ] Both workflows upload JUnit XML test results as artifacts
- [ ] No credentials, tokens, or `.env` files are included in any artifact
- [ ] Artifacts are retained for minimum 90 days (GitHub default)

### 3.5 Rollback / Compatibility Notes

- The pre-flight gate requires the release branch to be up-to-date with remote. If `origin/main` has commits not yet
  pushed, the gate will fail — this is intentional.
- Use `github.event_name == 'push' && github.ref_type == 'tag'` guard to ensure the gate only runs for tag events.
- If a hotfix release is needed from a dirty branch, a separate `release-dirty.yml` workflow can be created with
  explicit manual override; this should be documented in the release runbook.

---

## 4. IMPLEMENTATION TRACKING

### 4.1 Summary of Files Requiring Changes

| # | File                            | Change Type                                                                          | Section |
|---|---------------------------------|--------------------------------------------------------------------------------------|---------|
| 1 | `.github/workflows/ci.yml`      | Bug fix (Gradle invocation) + New (test artifact upload)                             | 1, 3    |
| 2 | `.github/workflows/release.yml` | Bug fix (Gradle invocation) + New (examples gate + clean checkout + artifact upload) | 1, 2, 3 |
| 3 | `build.gradle`                  | New (Java toolchain)                                                                 | 2       |
| 4 | `examples/build.gradle`         | New (Java toolchain)                                                                 | 2       |

### 4.2 Implementation Order

**Phase 1 (Low risk, immediate):**  
Fix `./gradlew.bat` in ci.yml. This is a one-line correction with no side effects.

**Phase 2 (CI hardening):**  
Add examples compilation to release.yml; add test artifact upload to both workflows.

**Phase 3 (Java 8 enforcement):**  
Configure toolchain in build.gradle + examples/build.gradle; install JDK 8 in CI runner; add bytecode inspection step.

**Phase 4 (Release governance):**  
Add clean-checkout pre-flight gate to release.yml; configure artifact retention policy.

### 4.3 Acceptance Criteria Summary

| Change                          | Criteria                                                                     |
|---------------------------------|------------------------------------------------------------------------------|
| ci.yml gradlew.bat fix          | `./gradlew.bat` absent; `./gradlew -p examples compileJava` present          |
| ci.yml test artifacts           | `actions/upload-artifact@v4` step uploads `build/test-results/test/`         |
| release.yml examples gate       | `examples/compileJava` runs before publish step                              |
| release.yml gradlew.bat fix     | Same as ci.yml                                                               |
| release.yml test artifacts      | Same as ci.yml                                                               |
| release.yml clean gate          | Pre-flight job blocks release if `git status --porcelain` is non-empty       |
| build.gradle toolchain          | `java.toolchain.languageVersion = JavaLanguageVersion.of(8)` present         |
| examples/build.gradle toolchain | Same as root                                                                 |
| CI JDK 8                        | `actions/setup-java` with `java-version: '8'` or toolchain auto-provisioning |
| Bytecode evidence               | CI logs or artifact show `major version 52` for compiled classes             |

### 4.4 Rollback Considerations

| Change                                | Rollback Action                                                                         |
|---------------------------------------|-----------------------------------------------------------------------------------------|
| ci.yml `./gradlew.bat` -> `./gradlew` | Revert to `./gradlew.bat` (regression to broken state, but rollbackable)                |
| Add JDK 8 toolchain                   | Remove `java.toolchain {}` block from both build files; remove JDK 8 setup step from CI |
| Add clean checkout gate               | Remove `pre-flight` job from release.yml                                                |
| Add test artifact upload              | Remove upload-artifact steps; no data loss (artifacts are copies)                       |

### 4.5 Compatibility Concerns with Existing CI Infrastructure

- **Gradle version:** Toolchain support requires Gradle 6.6+. The project uses `gradle/actions/setup-gradle@v4` which
  provisions Gradle 8.x — fully compatible.
- **actions/upload-artifact@v4:** Creates artifact uploads with unique names; concurrent runs of the same workflow may
  conflict. Use unique artifact names per run (`test-results-${{ github.run_id }}`).
- **JDK 8 on ubuntu-latest runners:** GitHub's `ubuntu-latest` runner does not include JDK 8 by default. Must install
  via `actions/setup-java` or rely on Gradle toolchain auto-download (requires outbound network).
- **animal-sniffer alternative:** If toolchain approach is blocked by network restrictions, the
  `net.zzonk:gradle-animal-sniffer-plugin` or manual `-Dsig-file` approach requires maintaining a JDK 8 API signature
  file — additional maintenance burden.

---

*End of specification. Downstream implementation task should use this document as a checklist.*
