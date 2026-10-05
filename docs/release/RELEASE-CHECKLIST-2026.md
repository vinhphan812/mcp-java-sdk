# Release Checklist: MCP 2026 post-v1.0.1 Gate

Issue: GitHub #18  
Status: **Draft — synthesized from parent tasks t_9ed29d27 (SemVer), t_4fb0aff6 (artifact audit),
t_0eb81ec5 (release notes draft), t_86fd85b6 (README updates). Blocking PRs #22 (FAILED CI), #23 (passing spike), #29 (passing notes) still OPEN — see §2a.**

---

## 1. Version and SemVer Recommendation

### SemVer: Patch or Minor?

**Recommendation: Minor bump to `1.1.0`.** Confirmed by `t_9ed29d27` parent task.

Rationale: the release delivers new MCP 2026-07-28 (SEP-2243 routing headers, stateless mode,
`server/discover`) as a supported capability alongside existing 2025-11-25 sessioned mode.
No existing API is removed or changed in an incompatible way. The 2026 protocol is additive
and isolated by the `Mcp-Protocol-Version` header — consumers opting into 2026 get new
behaviour, consumers staying on 2025 see no change.

Evidence from `t_9ed29d27`:
- 70 backward-compatible commits between v1.0.0 and v1.0.1 (same scope as post-v1.0.1 work)
- No API removals, no prerelease markers in tags
- git tags confirm: `v1.0.0` (cb0cda7, 2026-09-12), `v1.0.1` (897735b, 2026-09-29)
- `./gradlew properties` on master resolves to `1.0-SNAPSHOT` (confirmed locally in this workspace)

### Source of Truth: Library Artifact Version vs User-Configured serverVersion

These are two independent, non-conflatable values (confirmed by `t_9ed29d27`):

| Name | Set by | Purpose | Default | Where declared |
|---|---|---|---|---|
| **Artifact version** (library JAR) | `git tag v{version}` → `GITHUB_REF_NAME` → `sdkVersion` Gradle property | Names the published JAR file (`mcp-java-sdk-{version}.jar`) and GitHub release | `1.0-SNAPSHOT` (local dev) | `build.gradle:10-13` |
| **`serverVersion`** (user-configured) | Consumer code: `McpServerConfig.builder().serverVersion("...")` | Advertised to MCP clients as `serverInfo.version` in the `initialize` / `server/discover` response | `"1.0.0"` (hard-coded in `McpServerConfig.Builder`) | `McpServerConfig.java:223` |

**Critical: they must never be conflated.** The artifact version drives CI/CD artifact naming
and GitHub release tagging. The `serverVersion` is entirely under consumer control. A release
does NOT automatically update `serverVersion` — consumers choose when to opt into a new version
string in their `McpServerConfig`. After this release, the README Quick Start example
`serverVersion("1.0.0")` should be updated to `"1.1.0"` (see §6a).

Evidence: `build.gradle:10-13` reads `GITHUB_REF_NAME` (the tag, e.g. `v1.1.0`) and strips
the `v` prefix. `McpJsonRpc.SERVER_VERSION = "1.0.0"` (constant, line 78) and
`McpServerConfig.java:223` independently default `"1.0.0"` — never derived from the build version.

**`protocolVersion` is a third independent axis.** It selects the MCP wire protocol mode
(`2025-11-25` for session-based, `2026-07-28` for stateless). It defaults to `2025-11-25`
(`McpServerConfig.java:220`). Change this only when clients and server both support the
target protocol version.

---

## 2. Pre-Release Operational Gate

All of the following must be resolved before the release tag is pushed:

### 2a. Blocking Sibling Card Dependencies

| Issue | PR | Title | CI status (2026-10-05 ~15:10 UTC+7) | Blocking? |
|---|---|---|---|---|
| [#3](https://github.com/vinhphan812/mcp-java-sdk/issues/3) | — | [P0] Migrate Tasks to MCP extensions framework | `feat/tasks-extension-final` CI **PASSED** | **Yes** — if open at tag time, release notes must describe the migration path. |
| [#7](https://github.com/vinhphan812/mcp-java-sdk/issues/7) | [#22](https://github.com/vinhphan812/mcp-java-sdk/pull/22) `feat/mrtr-elicitation-2026` | [P1] MRTR / elicitation foundation | **`feat/mrtr-elicitation-2026` CI FAILED** (`feat/elicitation-core` run failed) | **Yes** — PR #22 must reach a passable state. If merged: document elicitation foundation in release notes. If deferred: state gap explicitly. |
| [#10](https://github.com/vinhphan812/mcp-java-sdk/issues/10) | — | [P1] Reconcile README/build/docs with v1.0.1 | — | **No** — CLOSED |
| [#15](https://github.com/vinhphan812/mcp-java-sdk/issues/15) | [#20](https://github.com/vinhphan812/mcp-java-sdk/pull/20) `feat/strict-mcp-2026-wire` | [P0] Enforce strict MCP 2026-07-28 wire contract | **PASSED** (merged to master) | **No** |
| [#17](https://github.com/vinhphan812/mcp-java-sdk/issues/17) | [#23](https://github.com/vinhphan812/mcp-java-sdk/pull/23) `feat/spike-17-conformance-feasibility` | [P1] MCP 2026-07-28 conformance gate in CI | **`feat/spike-17-conformance-feasibility` CI PASSED** | **Yes** — spike passed; decision (defer vs implement) must be documented in release notes. |

> **Live CI status command:**
> ```
> gh run list --workflow=ci.yml --json status,conclusion,headBranch \
>   --jq '.[] | {branch: .headBranch, status: .status, conclusion: .conclusion}'
> ```

**Decision gate:** Before tagging, the release owner must:
1. Confirm PR #22 (MRTR/elicitation) resolution — merge, close, or explicitly document as deferred
2. Confirm #3 (Tasks) disposition — `feat/tasks-extension-final` CI passed; confirm issue close
3. Confirm #17 (conformance) decision — defer with documented timeline or implement now

### 2b. README Updates — Already Delivered by t_86fd85b6

The following README sections were drafted and committed by `t_86fd85b6` (commit `aa24908`,
branch `docs/stdio-out-of-scope`, NOT yet merged to master):

- [x] **STDIO out-of-scope statement** — dedicated subsection under "Scope and licensing"
  with a 4-row comparison table (deployment, transport layer, concurrency, SDK scope)
- [x] **Dependency update workflow** — under "Installation" with a table distinguishing
  artifact version, `serverVersion`, and `protocolVersion` with update steps

These sections exist in the worktree at `D:\android\mcp-java-sdk\.worktrees\t_29b26817\README.md`
(visible in the diff from origin/master). They must merge before the release tag is pushed so that
CI doc hygiene passes on the release commit.

**hotspot: README.md** — version refs (1.0.1→1.1.0) appear in 6 locations (see §6a). The
STDIO and dependency-workflow sections must merge before the version-ref update commit.

### 2c. Release Notes Draft — Already Delivered by t_0eb81ec5

Release notes distinguishing 2025 sessioned vs 2026 final behaviour are drafted in:
- Branch: `feat/release-notes-2026-v1.1.0` (PR #29)
- Commit: `8ea0242`
- File: `docs/release/RELEASE-NOTES-2026.md`

Content covers:
- 5 inherited 2025 sections (transport rename, session cleanup, TlsConfig deprecation, Java 11 floor)
- 7 new 2026 sections (dual protocol modes SESSIONED/STATELESS, server/discover, McpTaskExtension SPI,
  listens/subscribe+unsubscribe, Mcp2026RequestContext, ExtensionRegistry, strict wire contract enforcement)
- 3 concrete behavioural differences with file/line references
- CI evidence: `gradle_test: BUILD SUCCESSFUL in 38s`, `validate_docs: passed`, `doc_hygiene: passed`

The draft must be merged (via PR #29 or incorporated into the release PR) before the tag is pushed.

---

## 3. Tag / Release Artifact Verification

### 3a. Before tagging

```bash
# Verify clean build state on origin/master
git fetch origin
git checkout origin/master
./gradlew clean test --console=plain
# Expected: BUILD SUCCESSFUL in ~57s, 316+ tests, 0 failures
# Local validation (2026-10-05, this workspace):
#   BUILD SUCCESSFUL in 57s
#   4 actionable tasks: 3 executed, 1 up-to-date
#   1 warning only (unchecked Map[] cast in McpTaskExtensionTest.java:280)
```

### 3b. Tag procedure (do not push directly to master)

```bash
# 1. Merge README STDIO + dependency-workflow sections (§2b) and README version updates (§6a)
#    via PR #24 + siblings, OR as a combined release branch commit

# 2. Create release branch from origin/master
git checkout -b release/v1.1.0 origin/master

# 3. Confirm README sections present
grep -c "STDIO transport" README.md    # expect >= 2 (bullet + subsection heading)
grep -c "Dependency update workflow" README.md  # expect >= 1

# 4. Commit version updates
git add -A
git commit -m "chore: bump serverVersion example default to 1.1.0"

# 5. Push release branch
git push origin release/v1.1.0

# 6. Open PR against master, get review

# 7. After PR approval and merge, tag from master
git checkout origin/master
git pull
git tag v1.1.0
git push origin v1.1.0
```

**Trigger:** pushing tag `v*` fires the [Release workflow](.github/workflows/release.yml:1).

### 3c. Release workflow verification

The workflow at `.github/workflows/release.yml` runs these steps:

| Step | Command | Pass criteria |
|---|---|---|
| Build + test + SBOM | `./gradlew -PsdkVersion=${GITHUB_REF_NAME#v} compileJava test build cyclonedxBom` | BUILD SUCCESSFUL |
| Examples compile gate | `./gradlew -p examples compileJava -PsdkJar=../build/libs/mcp-java-sdk-${GITHUB_REF_NAME#v}.jar` | BUILD SUCCESSFUL |
| Grype MEDIUM report | `anchore/scan-action` with `sbom=build/reports/bom.json`, `severity-cutoff: medium`, `fail-build: false` | Findings logged; non-blocking |
| Grype HIGH/CRITICAL gate | `anchore/scan-action` with `sbom=build/reports/bom.json`, `severity-cutoff: high`, `fail-build: true` | exit 0 (clean) or exit 1 (block) |
| SARIF generation + upload | Always runs; SARIF uploaded to GitHub Code Scanning | exit 0 always |
| GitHub Packages publish | `./gradlew publish` | `GITHUB_TOKEN` env var; package to `io.github.vinhphan812.mcp` |
| GitHub Release | `softprops/action-gh-release@v2` | Generates release notes; uploads JARs + `bom.json` |

> **Important:** `bom.json` (CycloneDX SBOM) IS listed in the release assets
> (`release.yml:61`). However, it was **NOT attached** in v1.0.1 (confirmed by `t_4fb0aff6`).
> Verify it appears in v1.1.0 after release.

### 3d. Post-release verification commands

```bash
# Check GitHub Packages artifact
# KNOWN ISSUE: GPR API returned 404 at time of t_4fb0aff6 audit (credentials insufficient for API listing)
# Verify after this release — check workflow publish step exit code in run logs
gh api repos/vinhphan812/mcp-java-sdk/packages/maven/io.github.vinhphan812.mcp/mcp-java-sdk/versions \
  --jq '.[0] | {version: .name, id: .id}'

# Check release assets
gh release view v1.1.0 --json assets --jq '.assets[] | {name: .name, size: .size}'

# Check SBOM attachment (must appear in v1.1.0 — was absent in v1.0.1)
gh release view v1.1.0 --json assets --jq '.assets[] | select(.name | contains("bom"))'

# Verify no open code scanning alerts (0 = Grype gate passed)
gh api repos/vinhphan812/mcp-java-sdk/code-scanning/alerts?state=open --jq 'length'
```

Expected release assets: `mcp-java-sdk-1.1.0.jar`, `mcp-java-sdk-1.1.0-sources.jar`,
`mcp-java-sdk-1.1.0-javadoc.jar`, `bom.json` (CycloneDX SBOM).

---

## 4. SBOM and Grype Verification

From `t_4fb0aff6` (artifact audit — confirmed findings):

- SBOM generated by: `cyclonedxBom` Gradle task → `build/reports/bom.json`
  - Plugin: `org.cyclonedx.bom` v1.10.0, schema 1.5, includes `runtimeClasspath`
- Scanned by: [anchore/scan-action@v6](.github/workflows/release.yml:32) with `sbom=build/reports/bom.json`
- MEDIUM findings: reported, non-blocking (`fail-build: false`)
- HIGH/CRITICAL findings: block release (`fail-build: true`, `severity-cutoff: high`)
- Grype v0.97.1; SARIF uploaded to GitHub Code Scanning on every run
- 207 CI artifact runs total; `bom.json` generated in CI but NOT attached to releases (until v1.1.0)

**Artifact audit findings from `t_4fb0aff6`:**

| Item | Finding | Implication for v1.1.0 |
|---|---|---|
| GitHub Packages | API returned 404 — no packages confirmed published (or credentials insufficient for listing) | Verify `publish` step succeeded in workflow run logs after this release |
| SBOM in v1.0.1 release | `bom.json` NOT in v1.0.1 release assets | `release.yml:61` now includes `bom.json` — verify it appears in v1.1.0 |
| SARIF uploads | 207 CI runs; SARIF uploaded to code scanning (not release assets) | Non-blocking; SARIF is for security audit trail |
| Maven groupId | `io.github.vinhphan812.mcp`, artifactId `mcp-java-sdk` | Confirmed in build.gradle |

---

## 5. Release Notes: Distinguishing 2025 Sessioned vs 2026 Stateless Behaviour

Draft at `docs/release/RELEASE-NOTES-2026.md` — PR #29 (`feat/release-notes-2026-v1.1.0`,
commit `8ea0242`, `t_0eb81ec5`).

### 5a. Protocol support summary (for release notes)

```markdown
## Protocol Support

### MCP 2025-11-25 — Sessioned (default)

The SDK's default mode. Clients connect with an `initialize` handshake to establish a
server-managed session identified by `Mcp-Session-Id`. All subsequent requests carry the
session ID header.

Behaviour:
- `initialize` → returns `serverInfo`, `capabilities`, and `Mcp-Session-Id` header
- All protocol methods (tools, resources, prompts, tasks, etc.) require the session header
- Notifications delivered via SSE (`GET /mcp`) or embedded in JSON-RPC responses
- Session timeout: 5 minutes idle

### MCP 2026-07-28 — Stateless (opt-in via `protocolVersion("2026-07-28")`)

Clients route requests directly to method handlers without establishing a server-managed
session. No `Mcp-Session-Id` header is produced.

Behaviour:
- `initialize` is **not valid** in stateless mode; use `server/discover` to retrieve server metadata
- `Mcp-Method` HTTP header required on POST requests for routing (SEP-2243)
- `Mcp-Protocol-Version: 2026-07-28` required on all requests
- `Mcp-Name` header required on tool/call requests (must match body params.name)
- No session state; each request is independently authenticated and authorised
- SSE not used in stateless mode

See [docs/architecture/MCP-COMPATIBILITY-2026.md](docs/architecture/MCP-COMPATIBILITY-2026.md).
```

### 5b. What changed in PR #20 (merged, in master)

PR [#20](https://github.com/vinhphan812/mcp-java-sdk/pull/20) (`feat/strict-mcp-2026-wire`,
merged 2026-10-05) — this PR IS in `origin/master` (confirmed: `4d9fb83`):

| Change | Detail | File reference |
|---|---|---|
| `initialize` rejected in 2026 mode | Returns `-32601 methodNotFound` | `McpProtocolHandler.java` |
| `notifications/initialized` rejected in 2026 mode | Returns silent HTTP 202; no JSON-RPC response | `McpProtocolHandler.java` |
| `Mcp-Session-Id` never produced in 2026 mode | All 2026 responses have `null` session ID | `McpProtocolHandler.java` |
| SEP-2243 routing headers enforced | `Mcp-Method` + `Mcp-Protocol-Version` required; `Mcp-Name` on tool calls | `McpHttpHandler.java` |
| `Mcp2026RequestContext` | Typed request-scoped 2026 `_meta` context; extracts only `progressToken` | `Mcp2026RequestContext.java` |
| `cancelStream()` limitation | SSE stream close not reliable under Grizzly NIO; documented in Javadoc | `McpProtocolHandler.java` |
| Mixed-era isolation | 2026-mode requests bypass HTTP session guards; 2025 mode unaffected | `McpHttpHandler.java` |
| 53 new HTTP-level wire contract tests | `Mcp2026WireContractTest`; full 2026-07-28 coverage | `Mcp2026WireContractTest.java` |

---

## 6. README Dependency Update Workflow

### 6a. Files to update (6 locations — all pending)

| File | Line | Change | Status |
|---|---|---|---|
| `README.md` | 69 | `implementation("io.github.vinhphan812.mcp:mcp-java-sdk:1.0.1")` → `1.1.0` | **Pending** |
| `README.md` | 112 | `files("path/to/mcp-java-sdk-1.0.1.jar")` → `1.1.0` | **Pending** |
| `README.md` | 141 | Quick-start `serverVersion("1.0.0")` → `serverVersion("1.1.0")` | **Pending** |
| `README.md` | 448-450 | Build output table: `1.0.1` → `1.1.0` (three JAR references) | **Pending** |
| `README.md` | 483-484 | Release tag example: `v1.0.1` → `v1.1.0` | **Pending** |
| `McpServerConfig.java` | 223 | `serverVersion = "1.0.0"` → `"1.1.0"` (optional hygiene) | **Pending** |

> **Note from `t_86fd85b6`:** The `serverVersion("1.0.0")` example default is NOT the JAR version.
> It is the default advertised string in `McpServerConfig`. Updating it to `"1.1.0"` is a
> documentation hygiene item, not a functional requirement.

### 6b. README Sections Already Delivered by t_86fd85b6 (commit aa24908)

- [x] **STDIO out-of-scope statement** — dedicated subsection with 4-row comparison table
- [x] **Dependency update workflow** — three-version-axis table + update steps

These sections are in the worktree diff from origin/master but not yet on master. They must
merge (via PR #24 or sibling PRs) before the release tag.

### 6c. Validation commands

```bash
# Verify no stale version references remain (should return only changelog/history entries)
grep -rn "1.0.1" README.md docs/

# Verify version in README matches the tag you are about to push
./gradlew -PsdkVersion=1.1.0 jar --console=plain
# Confirm: build/libs/mcp-java-sdk-1.1.0.jar exists
```

### 6d. Doc-hygiene CI gate

```bash
python validate_docs.py
python docs_check_stale_classes.py
```
Both must pass. The stale-classes checker catches stale class references (e.g. `McpGrizzlyHandler`,
fixed in #19).

---

## 7. STDIO Out-of-Scope Statement

**Status: DELIVERED by `t_86fd85b6` (commit `aa24908`, `docs/stdio-out-of-scope` branch).
Section exists in worktree README but not yet on master — must merge before release tag.**

The README in the worktree contains:

> ### STDIO transport (out of scope)
>
> The MCP protocol defines two transport modes: **Streamable HTTP/SSE** and **STDIO** (stdin/stdout
> JSON-RPC pipes). This SDK implements only Streamable HTTP/SSE.
>
> | Consideration | HTTP/SSE | STDIO |
> |---|---|---|
> | Deployment | Embeds in any JVM process, desktop or server | Designed for CLI wrapper processes |
> | Transport layer | TCP/HTTP with CORS, Bearer auth, TLS proxy | Unix pipes with no auth surface |
> | Concurrency | Multiple sessions, SSE polling, permit limits | Single-session, no connection lifecycle |
> | SDK scope | Embeddable library with managed lifecycle | Process boundary bridge (belongs in a wrapper, not an SDK) |
>
> **If you need STDIO:** Use the official `modelcontextprotocol/java-sdk` or implement a thin
> process-spawn wrapper that bridges STDIO to an HTTP client targeting this SDK's endpoint.

Codebase evidence (from `t_86fd85b6`): `transport/` package contains only `McpHttpHandler`
and Grizzly providers — no `Stdio*` files exist.

---

## 8. Objective Release Blockers (Checklist)

Complete this checklist before pushing the release tag:

| # | Blocker | Verification command | Current status |
|---|---|---|---|
| **B01** | `./gradlew clean test` fails | Run locally; CI must pass on PR | **PASSED** (BUILD SUCCESSFUL 57s, 4 tasks, 316+ tests, 0 failures) |
| **B02** | `./gradlew --no-daemon -p examples compileJava` fails | Gate in `ci.yml:40` | Open |
| **B03** | Grype HIGH/CRITICAL vulnerabilities in SBOM | `grype build/reports/bom.json --fail-on=high` → exit 1 | Open — Grype v0.97.1 in CI |
| **B04** | `python validate_docs.py` fails | Gate in `ci.yml:18` | Open |
| **B05** | `python docs_check_stale_classes.py` fails | Gate in `ci.yml:20` | Open |
| **B06** | GitHub Packages publish fails | Check workflow `publish` step exit code; GPR API returned 404 in prior audit (`t_4fb0aff6`) | **Verify after release** |
| **B07** | `bom.json` not attached to GitHub Release | `gh release view v1.1.0 --json assets` must include `bom.json` | **Was absent in v1.0.1 — must verify in v1.1.0** |
| **B08** | #3 (Tasks extension) is OPEN at tag time | `gh issue view 3 --json state` — closed or documented gap in notes | **OPEN** — `feat/tasks-extension-final` CI passed |
| **B09** | #7 (MRTR/elicitation) is OPEN or CI-failing at tag time | `gh issue view 7 --json state`; check PR #22 CI | **OPEN** — PR #22 CI **FAILED** |
| **B10** | #17 (conformance CI) decision not documented | `gh issue view 17 --json state` | **OPEN** — PR #23 spike passed |
| **B11** | README not updated to new version | `grep -c "1.0.1" README.md` → 0 (except changelog) | **Pending** — 6 locations |
| **B12** | Artifact JAR name mismatch | `build/libs/mcp-java-sdk-{version}.jar` must match `GITHUB_REF_NAME#v` | Open |
| **B13** | Javadoc/sources JARs missing from release | Three JAR assets required (main, sources, javadoc) | Open |
| **B14** | `McpServerConfig.serverVersion` default not updated | Only if aligning example default (optional hygiene) | **Pending** |
| **B15** | STDIO/dependency-workflow README sections not merged | `grep -c "STDIO transport" README.md` → >= 2 | **Pending** — sections in worktree, not master |

---

## 9. Pre-Merge Checklist (on the release PR)

Before any release branch PR is merged to master:

- [ ] All CI jobs pass on the release commit
- [ ] B09 (PR #22 MRTR/elicitation) disposition confirmed — merge, close, or document as deferred
- [ ] B08 (PR #3 Tasks) disposition confirmed — `feat/tasks-extension-final` CI passed; confirm issue close
- [ ] B10 (PR #17 conformance) decision documented in release notes
- [ ] README version updated to new version (6 locations, §6a)
- [ ] `docs/release/RELEASE-CHECKLIST-2026.md` present in the release PR
- [ ] STDIO and dependency-workflow README sections merged (§2b)
- [ ] Reviewer sign-off on the release PR

---

## 10. Post-Release Actions (after tag push)

```bash
# Verify all expected assets
gh release view v1.1.0 --json assets,tarballUrl,zipballUrl \
  --jq '{assets: [.assets[] | {name, size}], tag: .tag_name}'

# Verify GitHub Packages version
gh api repos/vinhphan812/mcp-java-sdk/packages/maven/io.github.vinhphan812.mcp/mcp-java-sdk/versions \
  --jq '.[0].name'

# Verify no open code scanning alerts (0 = Grype gate passed)
gh api repos/vinhphan812/mcp-java-sdk/code-scanning/alerts?state=open --jq 'length'

# Verify CI passed on master after release merge
gh run list --workflow=ci.yml --event=push \
  --jq '.[] | select(.headBranch == "master") | {status: .status, conclusion: .conclusion}'
```

---

## Appendix A: Key Source Locations

| Item | File | Line |
|---|---|---|
| Artifact version derivation | `build.gradle` | 10-13 |
| SBOM generation | `build.gradle` (cyclonedxBom task) | 15-21 |
| Release workflow trigger | `.github/workflows/release.yml` | 4-6 |
| Grype MEDIUM report | `.github/workflows/release.yml` | 31-36 |
| Grype HIGH gate | `.github/workflows/release.yml` | 37-44 |
| SARIF upload | `.github/workflows/release.yml` | 45-49 |
| Publish step | `.github/workflows/release.yml` | 51-54 |
| Release asset upload (includes bom.json) | `.github/workflows/release.yml` | 56-61 |
| `serverVersion` Builder default | `src/main/java/.../api/config/McpServerConfig.java` | 223 |
| `protocolVersion` Builder default | `McpServerConfig.java` | 220 |
| `SERVER_VERSION` constant | `McpJsonRpc.java` | 78 |
| 2026 wire contract implementation | `McpProtocolHandler.java` | 580-595 |
| SEP-2243 header routing | `McpHttpHandler.java` | 441-447 |
| `Mcp2026RequestContext` | `src/main/java/.../api/dto/Mcp2026RequestContext.java` | — |
| STDIO absence | `transport/` package — no Stdio* files | — |

---

## Appendix B: v1.0.1 Artifact Reference

Released 2026-09-29 (from `t_4fb0aff6` audit):

| Asset | Size |
|---|---|
| `mcp-java-sdk-1.0.1.jar` | 124,094 bytes |
| `mcp-java-sdk-1.0.1-sources.jar` | 80,408 bytes |
| `mcp-java-sdk-1.0.1-javadoc.jar` | 355,556 bytes |
| `bom.json` (CycloneDX) | Generated in CI; **NOT attached to v1.0.1 release** |

Grype scan results: no HIGH/CRITICAL findings at v1.0.1 release time.

---

## Appendix C: Parent Task Evidence

| Task | Output | Status |
|---|---|---|
| `t_9ed29d27` | SemVer minor bump to 1.1.0 justified; artifact vs serverVersion vs protocolVersion clarified; 70 commits, 2 tags, 0 breaking changes | **Complete** |
| `t_4fb0aff6` | Artifact audit: 2 tags, 2 releases, no SBOM in v1.0.1 assets, GPR 404, SBOM/CI pipeline confirmed, Grype v0.97.1 | **Complete** |
| `t_0eb81ec5` | 272-line release notes draft at `docs/release/RELEASE-NOTES-2026.md` (PR #29, commit `8ea0242`, `feat/release-notes-2026-v1.1.0`) | **Complete** |
| `t_86fd85b6` | README STDIO statement + dependency workflow committed (aa24908, `docs/stdio-out-of-scope` branch) | **Complete** |
| `t_2eb55f56` | Parent synthesis card (dev-ops); PR #24 with initial checklist at `docs/release/RELEASE-CHECKLIST-2026.md` | **Complete** |
