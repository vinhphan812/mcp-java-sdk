# Release Checklist: MCP 2026 post-v1.0.1 Gate

Issue: GitHub #18
Status: Draft — awaiting operational dependencies (#3, #7, #17) and pre-release review.

---

## 1. Version and SemVer Recommendation

### SemVer: Patch or Minor?

**Recommendation: Minor bump to `1.1.0`.**

Rationale: the release delivers new MCP 2026-07-28 (SEP-2243 routing headers, stateless mode,
`server/discover`) as a supported capability alongside existing 2025-11-25 sessioned mode.
No existing API is removed or changed in an incompatible way. The 2026 protocol is additive
and isolated by the `Mcp-Protocol-Version` header — consumers opting into 2026 get new
behaviour, consumers staying on 2025 see no change.

### Source of Truth: Library Artifact Version vs User-Configured serverVersion

These are two independent, non-conflatable values:

| Name | Set by | Purpose | Default | Where declared |
|---|---|---|---|---|
| **Artifact version** (library JAR) | `git tag v{version}` → `GITHUB_REF_NAME` → `sdkVersion` Gradle property | Names the published JAR file (`mcp-java-sdk-{version}.jar`) and GitHub release | `1.0-SNAPSHOT` (local dev) | `build.gradle:13` |
| **serverVersion** (user-configured) | Consumer code: `McpServerConfig.builder().serverVersion("...")` | Advertised to MCP clients as `serverInfo.version` in the `initialize` / `server/discover` response | `"1.0.0"` (hard-coded in `McpServerConfig.Builder`) | `McpServerConfig.java:223` |

**Critical: they must never be conflated.** The artifact version drives CI/CD artifact naming
and GitHub release tagging. The `serverVersion` is entirely under consumer control. A release
does NOT automatically update `serverVersion` — consumers choose when to opt into a new version
string in their `McpServerConfig`. The README Quick Start example currently hard-codes
`serverVersion("1.0.0")`; after this release it should document the recommendation to align
it with the library artifact version for clarity, but that is a consumer-side change.

Evidence: `build.gradle:10-13` reads `GITHUB_REF_NAME` (the tag, e.g. `v1.1.0`) and strips
the `v` prefix. `McpServerConfig.java:223` independently defaults `"1.0.0"` and is never
derived from the build version. The two are resolved in different build phases and different
runtime contexts.

---

## 2. Pre-Release Operational Gate

All of the following must be resolved before the release tag is pushed:

### 2a. Sibling Card Dependencies

| Issue | Title | Status | Blocking reason |
|---|---|---|---|
| [#3](https://github.com/vinhphan812/mcp-java-sdk/issues/3) | [P0] Migrate Tasks to MCP extensions framework | OPEN — owner: vinhphan812 | If Tasks are restructured under SEP-2663 before release, release notes must describe the migration path. Open Tasks: a breaking change after release creates a churn event. |
| [#7](https://github.com/vinhphan812/mcp-java-sdk/issues/7) | [P1] MRTR / elicitation foundation | OPEN — owner: vinhphan812; PR [#22](https://github.com/vinhphan812/mcp-java-sdk/pull/22) (`feat/mrtr-elicitation-2026`) | `ElicitationMessage`, `ElicitRequest`, `ElicitationCallback` added in PR #22. If PR #22 merges before the release, release notes must cover elicitation foundation. If not, gap must be stated. |
| [#10](https://github.com/vinhphan812/mcp-java-sdk/issues/10) | [P1] Reconcile README/build/docs with v1.0.1 | **CLOSED** | Not blocking. |
| [#17](https://github.com/vinhphan812/mcp-java-sdk/issues/17) | [P1] Add official MCP 2026-07-28 conformance gate to CI | OPEN — owner: vinhphan812; PR [#23](https://github.com/vinhphan812/mcp-java-sdk/pull/23) (`feat/spike-17-conformance-feasibility`) | The conformance feasibility spike is in PR #23. If the decision is "defer with plan", the release notes should state the conformance CI timeline. If "do it now", it is a release blocker. |

**Decision gate:** Before tagging, the release owner must confirm which of #3, #7, #17 are closing
or remaining open, and ensure release notes reflect that state accurately.

### 2b. Library Artifact Version Alignment

- [ ] Confirm `McpServerConfig.Builder.serverVersion` default is updated from `"1.0.0"` to
  `"1.1.0"` (or whatever the release version is) in the same commit that updates the tag.
  This is a documentation/build hygiene item, not a functional requirement.
  - Evidence: `McpServerConfig.java:223` — `private String serverVersion = "1.0.0";`
  - Note: this default is the *example* default in documentation, not the JAR version.
  - If updated, update `README.md` Quick Start example `serverVersion("1.0.0")` → `"1.1.0"`.

---

## 3. Tag / Release Artifact Verification

### 3a. Before tagging

```bash
# Verify clean build state on origin/master
git fetch origin
git checkout origin/master
./gradlew clean test --console=plain
# Expected: BUILD SUCCESSFUL, all tests pass
```

### 3b. Tag procedure (do not push directly to master)

```bash
# 1. Create release branch from origin/master
git checkout -b release/v1.1.0 origin/master

# 2. Update McpServerConfig.java serverVersion default (optional — see §2b)
# Patch only if aligning the example default

# 3. Commit
git add -A
git commit -m "chore: bump serverVersion example default to 1.1.0"

# 4. Push release branch
git push origin release/v1.1.0

# 5. Open PR against master, get review

# 6. After PR approval and merge, tag from master
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
| Grype MEDIUM report | `grype sbom.json --fail-on=high -o table` | MEDIUM findings non-blocking; table logged |
| Grype HIGH/CRITICAL gate | `grype sbom.json --fail-on=high` | exit 0 (clean) or exit 1 (block); CI uses exit 1 to fail |
| SARIF generation | `grype sbom.json -o sarif --file grype-results.sarif` | Always exits 0; SARIF uploaded to Code Scanning |
| GitHub Packages publish | `./gradlew publish` | `GITHUB_TOKEN` env var; package published to `io.github.vinhphan812.mcp` |
| GitHub Release | `softprops/action-gh-release@v2` | Generates release notes from conventional commits; uploads JARs + SBOM |

**Verification commands post-release:**

```bash
# Check GitHub Packages artifact
gh api repos/vinhphan812/mcp-java-sdk/packages/maven/io.github.vinhphan812.mcp/mcp-java-sdk/versions \
  --jq '.[0] | {version: .name, id: .id}'

# Check release assets
gh release view v1.1.0 --json assets --jq '.assets[] | {name: .name, size: .size, url: .browser_download_url}'

# Check SBOM presence
gh release view v1.1.0 --json assets --jq '.assets[] | select(.name | contains("bom"))'
```

Expected release assets: `mcp-java-sdk-1.1.0.jar`, `mcp-java-sdk-1.1.0-sources.jar`,
`mcp-java-sdk-1.1.0-javadoc.jar`, `bom.json` (CycloneDX SBOM).

---

## 4. SBOM and Grype Verification

- SBOM generated by: `cyclonedxBom` Gradle task → `build/reports/bom.json`
- Scanned by: [anchore/scan-action@v6](.github/workflows/release.yml:32) with `sbom=build/reports/bom.json`
- MEDIUM findings: reported, non-blocking (`fail-build: false`)
- HIGH/CRITICAL findings: block release (`fail-build: true`, `--fail-on=high`)
- Grype SARIF uploaded to GitHub Code Scanning on every run

**Evidence from v1.0.1:** 3 JAR assets (main, sources, javadoc) published at
https://github.com/vinhphan812/mcp-java-sdk/releases/tag/v1.0.1 (2026-09-29).

---

## 5. Release Notes: Distinguishing 2025 Sessioned vs 2026 Stateless Behaviour

The release notes **must** clearly distinguish the two protocol modes to prevent consumer
confusion.

### Proposed release note structure

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

### MCP 2026-07-28 — Stateless (opt-in via `streamableHttpMode(ProtocolMode.STATELESS)`)

Clients route requests directly to method handlers without establishing a server-managed
session. No `Mcp-Session-Id` header is produced.

Behaviour:
- `initialize` is **not valid**; use `server/discover` to retrieve server metadata
- `Mcp-Method` HTTP header required on POST requests for routing
- `Mcp-Protocol-Version: 2026-07-28` required on all requests
- `Mcp-Name` header required on tool/call requests (must match body params.name)
- No session state; each request is independently authenticated and authorised
- SSE not used in stateless mode

See [docs/architecture/MCP-COMPATIBILITY-2026.md](docs/architecture/MCP-COMPATIBILITY-2026.md) for
the full compatibility matrix.
```

### What changed in PR #20 (merged, in master)

PR [#20](https://github.com/vinhphan812/mcp-java-sdk/pull/20) (`feat/strict-mcp-2026-wire`,
merged 2026-10-05) landed the following protocol changes — these must be in the release notes:

| Change | Detail |
|---|---|
| `initialize` rejected in 2026 mode | Returns `-32601 methodNotFound`; `server/discover` replaces it |
| `notifications/initialized` rejected in 2026 mode | Returns silent HTTP 202; no JSON-RPC response |
| `Mcp-Session-Id` never produced in 2026 mode | All 2026 responses have `null` session ID |
| SEP-2243 routing headers enforced | `Mcp-Method` + `Mcp-Protocol-Version` required; `Mcp-Name` on tool calls |
| `Mcp2026RequestContext` | Typed request-scoped 2026 `_meta` context; extracts only `progressToken` |
| `cancelStream()` limitation | SSE stream close signalling not reliable under Grizzly NIO; documented in Javadoc |
| Mixed-era isolation | 2026-mode requests bypass HTTP session guards; 2025 mode unaffected |
| 53 new HTTP-level wire contract tests | `Mcp2026WireContractTest`; full 2026-07-28 coverage |

---

## 6. README Dependency Update Workflow

The README currently pins `io.github.vinhphan812.mcp:mcp-java-sdk:1.0.1`. After this release:

### 6a. Files to update

| File | Change |
|---|---|
| `README.md:69` | `implementation("io.github.vinhphan812.mcp:mcp-java-sdk:1.0.1")` → `1.1.0` |
| `README.md:112` | `files("path/to/mcp-java-sdk-1.0.1.jar")` → `1.1.0` |
| `README.md:448-450` | Build output table: `1.0.1` → `1.1.0` (three occurrences) |
| `README.md:141` | Quick-start example `serverVersion("1.0.0")` → `serverVersion("1.1.0")` |
| `README.md:483-484` | Release tag example: `v1.0.1` → `v1.1.0` |

### 6b. Validation

```bash
# Verify no stale version references remain
grep -rn "1.0.1" README.md docs/  # should return only changelog/history entries

# Verify version in README matches the tag you are about to push
./gradlew -PsdkVersion=1.1.0 jar --console=plain
# Confirm: build/libs/mcp-java-sdk-1.1.0.jar exists
```

### 6c. Doc-hygiene CI gate

The `ci.yml` Validate Documentation job runs:
```bash
python validate_docs.py
python docs_check_stale_classes.py
```

Both must pass. The stale-classes checker compares doc references against the compiled class
list — it will catch a README that still names `McpGrizzlyHandler` (fixed in #19) and any
other stale class reference.

---

## 7. STDIO Out-of-Scope Statement

The following text should appear verbatim in the Scope section of release notes and README:

> **STDIO transport is intentionally unsupported.**
>
> The SDK is a Java server library for hosting an MCP server inside a JVM application over
> HTTP/Streamable HTTP/SSE. STDIO (stdin/stdout process communication) is a client-side
> transport defined in the MCP specification and is implemented by MCP client SDKs. It is
> outside the scope of this server-only library.
>
> Consumers who need STDIO support should use an external bridge process that translates
> STDIO messages to HTTP calls against this SDK's HTTP endpoint.
>
> Relevant issues: STDIO is listed as P2 deferred in
> [docs/architecture/MCP-COMPATIBILITY-2026.md](docs/architecture/MCP-COMPATIBILITY-2026.md)
> and as excluded in the README Scope section. No STDIO handler or transport class exists
> in the codebase (`transport/` package contains only `McpHttpHandler` and Grizzly providers).

Evidence: `docs/guides/IMPLEMENTATION-STATUS.md:60` — "STDIO — Not implemented".
`README.md:528` — listed under "### Excluded".

---

## 8. Objective Release Blockers (Checklist)

Complete this checklist before pushing the release tag:

| # | Blocker | Evidence / Verification | Status |
|---|---|---|---|
| B01 | `./gradlew clean test` fails | Run locally; CI must pass on PR | OPEN |
| B02 | `./gradlew --no-daemon -p examples compileJava` fails | Run locally; gate in `ci.yml:40` | OPEN |
| B03 | Grype HIGH/CRITICAL vulnerabilities in SBOM | `grype build/reports/bom.json --fail-on=high` returns exit 1 | OPEN |
| B04 | `validate_docs.py` fails | Run locally; gate in `ci.yml:18` | OPEN |
| B05 | `docs_check_stale_classes.py` fails | Run locally; gate in `ci.yml:20` | OPEN |
| B06 | `softprops/action-gh-release` asset upload fails | Check release workflow run | OPEN |
| B07 | GitHub Packages publish fails | Check workflow `publish` step exit code | OPEN |
| B08 | #3 (Tasks extension) is OPEN at tag time | Run `gh issue view 3 --json state` — must be CLOSED or explicitly deferred in notes | OPEN |
| B09 | #7 (MRTR/elicitation) is OPEN at tag time | Run `gh issue view 7 --json state` — same gate | OPEN |
| B10 | #17 (conformance CI) resolution not documented | Run `gh issue view 17 --json state` — gap must appear in release notes | OPEN |
| B11 | README not updated to new version | `grep -c "1.0.1" README.md` after update; should be 0 | OPEN |
| B12 | Artifact JAR name mismatch | `build/libs/mcp-java-sdk-{version}.jar` must match `GITHUB_REF_NAME#v` | OPEN |
| B13 | SBOM not attached to GitHub Release | `gh release view v{version} --json assets` must include `bom.json` | OPEN |
| B14 | Javadoc/sources JARs missing from release | Three JAR assets required (main, sources, javadoc) | OPEN |
| B15 | `McpServerConfig.serverVersion` default not updated | Only if aligning example default (optional hygiene item) | OPEN |

---

## 9. Pre-Merge Checklist (on the release PR)

Before the release branch PR is merged:

- [ ] All CI jobs pass (docs validation + build on Java 11 + build on Java 17 + dependency security)
- [ ] Release notes draft reviewed and approved
- [ ] #3, #7, #17 disposition confirmed (closed, deferred, or documented in notes)
- [ ] README version updated to new version
- [ ] `docs/release/RELEASE-CHECKLIST-2026.md` committed to the release PR
- [ ] Reviewer sign-off on the release PR

---

## 10. Post-Release Actions (after tag push)

After the tag fires the release workflow and assets are published:

```bash
# Verify all expected assets
gh release view v1.1.0 --json assets,tarballUrl,zipballUrl \
  --jq '{assets: [.assets[] | {name, size}], tag: .tag_name, tarball: .tarball_url}'

# Verify GitHub Packages version
gh api repos/vinhphan812/mcp-java-sdk/packages/maven/io.github.vinhphan812.mcp/mcp-java-sdk/versions \
  --jq '.[0].name'

# Verify Grype SARIF uploaded to Code Scanning
gh api repos/vinhphan812/mcp-java-sdk/code-scanning/alerts?state=open \
  --jq 'length'  # should be 0 open alerts if scan passed

# Verify CI passed on the release commit
gh run list --workflow=ci.yml --event=push --json status,conclusion \
  --jq '.[] | select(.headBranch == "master") | {status, conclusion}'
```

---

## Appendix A: Key Source Locations

| Item | File | Line |
|---|---|---|
| Artifact version derivation | `build.gradle` | 10-13 |
| SBOM generation | `build.gradle` (cyclonedxBom task) | |
| Release workflow trigger | `.github/workflows/release.yml` | 4-6 |
| Grype MEDIUM report | `.github/workflows/release.yml` | 32-36 |
| Grype HIGH gate | `.github/workflows/release.yml` | 37-44 |
| Publish step | `.github/workflows/release.yml` | 51-54 |
| Release asset upload | `.github/workflows/release.yml` | 56-61 |
| `serverVersion` Builder default | `src/main/java/.../api/config/McpServerConfig.java` | 223 |
| `serverVersion` advertised in protocol | `McpServerConfig.java` | 79, 171 |
| `protocolVersion` Builder default | `McpServerConfig.java` | 220 |
| 2026 wire contract implementation | `McpProtocolHandler.java` | 580-595 |
| 2026 no-session implementation | `McpProtocolHandler.java` (null `responseSessionId`) | |
| SEP-2243 header routing | `McpHttpHandler.java` | 441-447 |
| `Mcp2026RequestContext` | `src/main/java/.../api/dto/Mcp2026RequestContext.java` | |
| Protocol version constants | `McpJsonRpc.PROTOCOL_VERSION_STATELESS = "2026-07-28"` | |
| STDIO absence | `transport/` package — no Stdio* files exist | |

---

## Appendix B: Artifact Inventory (v1.0.1 Reference)

v1.0.1 (released 2026-09-29) published to GitHub Packages:

| Asset | SHA256 | Size |
|---|---|---|
| `mcp-java-sdk-1.0.1.jar` | `81588e7c...` | 124,094 bytes |
| `mcp-java-sdk-1.0.1-sources.jar` | `021cf07e...` | 80,408 bytes |
| `mcp-java-sdk-1.0.1-javadoc.jar` | `b15e8bde...` | 355,556 bytes |
| `bom.json` (CycloneDX) | (attached to release) | (in release assets) |

Grype scan results: v1.0.1 had no HIGH/CRITICAL findings at release time.
