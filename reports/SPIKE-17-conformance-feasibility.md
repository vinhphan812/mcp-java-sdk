# Spike #17 — MCP Conformance Suite Feasibility: Decision Memo

**Author:** dev-ops (Hermes)
**Date:** 2026-10-05
**Task:** t_04579609
**Workspace:** `D:\android\mcp-java-sdk\.worktrees\t_04579609` (branch `wt/t_04579609`)
**Baseline HEAD:** `963b69c` feat: MRTR/elicitation foundation
**origin/master:** `4d9fb83` feat(protocol): strict MCP 2026-07-28 wire contract (#20)
**Gradle test evidence:** `./gradlew test --console=plain` → 338 tests, 5 FAILED, 27 skipped

---

## 1. Official Suite — Package, Version, Licence

| Field | Value |
|---|---|
| Package | `@modelcontextprotocol/conformance` |
| Latest stable (2026-10-05) | `0.1.16` |
| npm published versions | `0.1.11` through `0.1.16` + alpha/beta pre-releases |
| License | MIT (Anthropic, PBC) |
| Repository | `github.com/modelcontextprotocol/conformance` |
| GitHub Action | `modelcontextprotocol/conformance@v0.1.11` (pinned; upgrade to `@v0.1.16` for latest) |
| Node.js peer/runtime dep | Node 20+ (built with `tsdown --target node20`; npm script uses `tsx` for inner-loop) |

The runner is distributed as a **pre-built Node ESM package** published to npm. No source compilation is required by consumers.

---

## 2. Node.js — Scope Conflict Analysis

### Required Node components
| Component | Role | Consumed how? |
|---|---|---|
| `@modelcontextprotocol/conformance` (npm) | Test runner (CLI) | `npx` / `npm exec` / GitHub Action |
| `tsx` | Dev dependency only (inner-loop source run) | NOT needed for consumer use |
| TypeScript 5.x | Dev dependency only | NOT needed for consumer use |
| `tsdown` | Build tool only | NOT needed for consumer use |
| `@modelcontextprotocol/sdk ^1.25.2` | Runtime dependency of conformance | Installed automatically by npm |

### Conflict verdict

**Node.js is confined to isolated CI tooling only — NO runtime or build dependency enters the Java SDK.**

The runner is:
- Invoked via `npx` (ephemeral install) or the pinned GitHub Action
- Started only during CI runs, against a pre-built JAR
- NOT imported by any Java code
- NOT present in `build.gradle`, `settings.gradle`, or the production classpath
- NOT vendored into the repository (npm cache only, transient between runs)

The project's **STDIO-intent exclusion** does not conflict: server-mode conformance tests the running server over **Streamable HTTP** — exactly the transport this SDK exposes. STDIO coverage is irrelevant to this SDK's scope.

### GitHub Action invocation pattern (server mode)

```yaml
# .github/workflows/conformance.yml
name: Conformance

on:
  push:
    branches: [ master ]
  pull_request:
    branches: [ master ]

jobs:
  conformance:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      # Build the Java server JAR (no Node involved)
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew jar --console=plain

      # Download the built JAR for the harness
      - name: Download JAR
        uses: actions/upload-artifact@v4
        with:
          name: mcp-server-jar
          path: build/libs/mcp-java-sdk-*.jar

      # Start server on ephemeral port, wait for health, run conformance
      - name: Start MCP server
        run: |
          java -jar build/libs/mcp-java-sdk-*.jar &
          SERVER_PID=$!
          # Poll until ready (simple TCP connect)
          for i in $(seq 1 20); do
            curl -s --fail http://127.0.0.1:3011/mcp && break
            sleep 0.5
          done
          echo "SERVER_PID=$SERVER_PID" >> $GITHUB_ENV

      - name: Run conformance
        uses: modelcontextprotocol/conformance@v0.1.16
        with:
          mode: server
          url: http://127.0.0.1:3011/mcp
          suite: active
          spec-version: 2025-11-25
          # Expected failures baseline (maintain as SDK gaps close)
          # expected-failures: ./conformance-baseline.yml

      - name: Stop server
        if: always()
        run: kill $SERVER_PID 2>/dev/null || true
```

**The Action's `node-version` input defaults to Node 20.** The Java server is managed entirely by `setup-java` / Gradle. Node is scoped to the Action runner's transient environment.

---

## 3. Server HTTP Invocation Contract

### Minimal ephemeral-port fixture

```bash
# Build JAR
./gradlew jar --console=plain

# Start server on OS-assigned port (port 0), capture bound port
java -Dserver.port=0 -jar build/libs/mcp-java-sdk-*.jar &
SERVER_PID=$!

# Wait for readiness (HTTP OPTIONS /health or TCP connect)
for i in $(seq 1 30); do
  curl -s --fail http://127.0.0.1:3011/mcp && break
  sleep 0.5
done

# Run conformance (server must already be listening)
npx @modelcontextprotocol/conformance@0.1.16 server \
  --url http://127.0.0.1:3011/mcp \
  --suite active \
  --spec-version 2025-11-25

# Or with the Action (server manages its own lifecycle):
# uses: modelcontextprotocol/conformance@v0.1.16
#   with:
#     mode: server
#     url: http://127.0.0.1:3011/mcp
```

**No STDIO involved.** The suite connects over HTTP to the already-running Streamable HTTP endpoint.

---

## 4. Core vs Optional/Extension Classification

### `active` suite (default — recommended CI baseline)

| Category | Scenarios | Tier |
|---|---|---|
| **Core** | `server-initialize`, `tools-list`, `tools-call-*`, `resources-*`, `prompts-*` | MUST for MCP 2025-11-25 |
| **Extensions** | `sse-*`, `notifications/*` | SHOULD; SDK supports |
| **Auth** | `auth/basic-*`, `oauth/*` | P2 — out of scope for this SDK |
| **Draft/Pending** | scenarios tagged `draft`, `pending` | Excluded from `active` |

### `all` suite (comprehensive)
Adds `draft` and `pending` scenarios. Requires `--spec-version 2026-07-28` for the stateless wire. More aggressive; suitable for post-P2 tracking.

### Per-revision requirements

```bash
# Exactly what 2025-11-25 required at release (frozen)
npx @modelcontextprotocol/conformance@0.1.16 server \
  --url http://127.0.0.1:3011/mcp \
  --requirements 2025-11-25

# Exactly what 2026-07-28 required at release (frozen)
npx @modelcontextprotocol/conformance@0.1.16 server \
  --url http://127.0.0.1:3011/mcp \
  --requirements 2026-07-28
```

### `expected-failures` baseline

```yaml
# conformance-baseline.yml
server:
  # P2 not yet implemented
  - prompts-list
  - prompts-get
  - sampling-something
  # Draft scenarios (optional)
  - elicitation-*
```

This prevents regressions while allowing CI to pass with documented known gaps.

---

## 5. Reports / Artifact Mechanics

| Output | Location | Contents |
|---|---|---|
| `checks.json` | `results/server-<scenario>-<timestamp>/` | Per-check pass/fail array |
| `stdout.txt` | `results/server-<scenario>-<timestamp>/` | Runner stdout |
| `stderr.txt` | `results/server-<scenario>-<timestamp>/` | Runner stderr |

Exit code semantics:
- `0` = all scenarios passed or all failures are in `expected-failures`
- `1` = unexpected regression (failure not in baseline) OR stale baseline entry (passes when failure is listed)

**No SARIF/JUnit XML output** from the suite itself. Results are JSON + text. Third-party tooling (e.g., `conformance-to-junit`) is community-maintained only.

---

## 6. Java-Only Alternative

### `@hasmcp/mcp-spec-test` (npm, Node-free runtime)

| Field | Value |
|---|---|
| Package | `@hasmcp/mcp-spec-test` |
| Latest | `v0.1.5` |
| License | Apache-2.0 |
| Invocation | `npx @hasmcp/mcp-spec-test@latest -u http://host:port/mcp` |
| Node dependency | **Only at invocation** — same `npx` / CI Action pattern |

**Capabilities:**
- Black-box HTTP only — no instrumentation, no plugin, no stdio
- Speaks Streamable HTTP directly
- Tests against **both** 2026-07-28 and 2025-11-25 revisions
- Output: structured report with PASSED/FAILED/NOT VERIFIED counts
- Exit code 0 when nothing failed

**Significant gaps vs official suite:**
- No per-scenario granular results — single aggregated report
- No `expected-failures` baseline mechanism
- No GitHub Action integration
- No `--suite` / `--requirements` per-revision freeze
- No SDK tier assessment support
- Small community (4 GitHub stars; active maintenance uncertain)

**Verdict:** `@hasmcp/mcp-spec-test` is a lightweight supplement but does not replace `@modelcontextprotocol/conformance` for a structured CI gate.

---

## 7. Decision

### GO for `@modelcontextprotocol/conformance`

**The official suite is viable for this SDK.** The Node.js requirement is strictly confined to the CI runner invocation and does not infiltrate the Java SDK's runtime, build graph, or product scope.

### Constraints and Mitigations

| Concern | Mitigation |
|---|---|
| Node.js in CI | GitHub Action handles setup; Java server runs independently |
| `npx` ephemeral installs | Pin `@modelcontextprotocol/conformance@v0.1.16` in CI Action |
| STDIO scope not applicable | Server-mode tests HTTP only; stdio not tested |
| Auth scenarios out of scope | Exclude `auth/*` via `active` suite (or `--suite core`) |
| Expected failures unknown | Maintain `conformance-baseline.yml` updated as P2 work closes gaps |
| No JUnit/SARIF output | Consume `checks.json` + custom post-processor if needed |
| npm package supply chain | Pin exact version; lockfile via npm CI Action or `npm ci` |

### Gradle Test Evidence (baseline)

```
./gradlew test --console=plain
338 tests completed, 5 FAILED, 27 skipped
```

**5 pre-existing failures** in `Mcp2026HandlerDiagTest` and `Mcp2026WireContractTest`:
- `initializeRejectsIn2026Mode` — handler accepts `initialize` in 2026 mode; test expects rejection
- `sessionIdPreservedOnToolsListWithSessionIn2025` — related 2026/2025 wire contract edge case
- 3× `initializeRejectedWith*` — header validation for 2026 protocol version

These are **pre-existing failures from HEAD `963b69c` (MRTR/elicitation on top of PR #20)**. They are out of scope for this spike. The conformance CI job should **not block on these failures** — they require a separate protocol fix card.

---

## 8. Implementation Card (if approved)

**Title:** [CI] Add MCP Conformance Suite as GitHub Actions gate
**Assignee:** dev-ops
**Parents:** t_04579609 (this spike)
**Priority:** P2

**Scope:**
- Add `.github/workflows/conformance.yml` with `modelcontextprotocol/conformance@v0.1.16` (server mode, `active` suite, `--spec-version 2025-11-25`)
- Add `conformance-baseline.yml` documenting current known-failure list
- Add `reports/conformance/` to `.gitignore`
- Add `docs/architecture/CONFORMANCE-EVIDENCE.md` documenting the baseline pass/fail snapshot and maintenance protocol
- Build JAR from Gradle CI step, start server on ephemeral port, run conformance
- **Exclude from runtime dependency graph:** no `package.json`, no `node_modules` in repo root, no `npm install`
- Pin `@modelcontextprotocol/conformance@0.1.16` in CI Action (not in any project package manager)
- Do NOT open PR from `wt/...` — transfer to `feat/conformance-ci` branch per delivery policy

**Pre-existing test failures are excluded from the conformance gate scope.** The conformance job targets the production JAR; those 5 JUnit failures are tracked separately.

---

*This memo is the spike deliverable. No code or CI changes were made in this task.*
