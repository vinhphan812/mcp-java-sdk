# Documentation Inventory — 2026-09-29

**Workspace:** `D:\android\mcp-java-sdk`
**Task:** t_18704e97
**Auditor:** dev-pm
**Scope:** All maintained documentation, README, CHANGELOG, CONTRIBUTING, and MkDocs config (if present).

---

## 1. Inventory — All Maintained Markdown Files

| # | File (relative to repo root) | Last modified | Size (bytes) | Canonical? | Status |
|---|------------------------------|--------------|-------------|-----------|--------|
| 1 | `README.md` | 2026-09-24 | 17,262 | YES | current — root entry point |
| 2 | `CHANGELOG.md` | 2026-09-28 | 890 | YES | current |
| 3 | `CONTRIBUTING.md` | 2026-09-22 | 1,830 | YES | current |
| 4 | `CLAUDE.md` | 2026-09-24 | 3,595 | YES | current |
| 5 | `AGENTS.md` | 2026-09-24 | 4,082 | YES | current |
| 6 | `docs/README.md` | 2026-09-23 | 1,546 | YES | canonical docs entry point (links to subdirectories) |
| 7 | `docs/API-REFERENCE.md` | 2026-09-24 | 51,600 | **DUPLICATE** | root; superseded by `docs/guides/API-REFERENCE.md` |
| 8 | `docs/PROJECT-GUIDE.md` | 2026-09-28 | 18,089 | **DUPLICATE** | root; superseded by `docs/guides/PROJECT-GUIDE.md` |
| 9 | `docs/USER_GUIDE.md` | 2026-09-23 | 4,637 | **DUPLICATE** | root; superseded by `docs/guides/USER_GUIDE.md` |
| 10 | `docs/IMPLEMENTATION-STATUS.md` | 2026-09-28 | 14,126 | **DUPLICATE** | root; superseded by `docs/guides/IMPLEMENTATION-STATUS.md` |
| 11 | `docs/MCP-COMPATIBILITY-2026.md` | 2026-09-23 | 4,512 | **DUPLICATE** | root; superseded by `docs/architecture/MCP-COMPATIBILITY-2026.md` |
| 12 | `docs/MCP-PORTING-PLAN.md` | 2026-09-23 | 3,585 | **DUPLICATE** | root; superseded by `docs/architecture/MCP-PORTING-PLAN.md` |
| 13 | `docs/guides/API-REFERENCE.md` | 2026-09-24 | 25,264 | **CANONICAL** | current |
| 14 | `docs/guides/PROJECT-GUIDE.md` | 2026-09-28 | 18,659 | **CANONICAL** | current |
| 15 | `docs/guides/USER_GUIDE.md` | 2026-09-23 | 4,637 | **CANONICAL** | current (identical to root duplicate) |
| 16 | `docs/guides/IMPLEMENTATION-STATUS.md` | 2026-09-24 | 39,431 | **CANONICAL** | current (much more complete than root duplicate) |
| 17 | `docs/guides/CI-PORTABILITY-JAVA8-RELEASE-GATE-SPEC.md` | 2026-09-23 | 18,569 | **UNIQUE** | standalone spec; no duplicate |
| 18 | `docs/guides/HTTP-TRANSPORT-EXAMPLE.md` | 2026-09-24 | 14,189 | **CANONICAL** | replaces GRIZZLY-EXAMPLE.md (renamed 2026-09-23) |
| 19 | `docs/guides/TRANSPORT-SSE.md` | 2026-09-23 | 21,168 | **CANONICAL** | current |
| 20 | `docs/architecture/MCP-COMPATIBILITY-2026.md` | 2026-09-23 | 4,512 | **CANONICAL** | current (links updated to guides/) |
| 21 | `docs/architecture/MCP-PORTING-PLAN.md` | 2026-09-23 | 3,585 | **CANONICAL** | current (identical to root duplicate) |
| 22 | `docs/architecture/mcp-registry-design-specification.md` | — | — | **CANONICAL** | current |
| 23 | `docs/architecture/blob-resource-spi-triage-spec.md` | — | — | **CANONICAL** | current |
| 24 | `docs/architecture/listener-semantics-spec.md` | — | — | **CANONICAL** | current |
| 25 | `docs/architecture/guava-dependency-policy.md` | — | — | **CANONICAL** | current |
| 26 | `docs/architecture/MCP-SECURITY-SYNTHESIS-TIER2.md` | — | — | **ACTIVE** | detailed security design spec; not superseded |
| 27 | `docs/architecture/TLSCONFIG-LIFECYCLE-DECISION.md` | — | — | **ACTIVE** | ADR-0014 companion; decision tree |
| 28 | `docs/authz/SCOPES-AUTHORIZATION-SPEC.md` | 2026-09-23 | — | **CANONICAL** | current |
| 29 | `docs/authz/RATE-LIMIT-TRIAGE-SPEC.md` | 2026-09-23 | — | **CANONICAL** | current |
| 30 | `docs/authz/RATE-LIMIT-429-BEHAVIOR-SPEC.md` | 2026-09-23 | — | **CANONICAL** | current |
| 31 | `docs/transport/SSE-LAST-EVENT-ID-REPLAY.md` | — | — | **CANONICAL** | current |
| 32 | `docs/transport/TRANSPORT-STREAMABLE-HTTP.md` | — | — | **CANONICAL** | current |
| 33 | `docs/testing/TEST-0001-sse-connection-release-streaming.md` | — | — | **CANONICAL** | current |
| 34 | `docs/testing/TEST-0002-sse-validation-plan.md` | — | — | **CANONICAL** | current |
| 35 | `docs/testing/AUTH-SCOPES-CONFIRMATION-TEST-SPEC.md` | — | — | **CANONICAL** | current |
| 36 | `docs/testing/LAST-EVENT-ID-REPLAY-TEST-SPEC.md` | — | — | **CANONICAL** | current |
| 37 | `docs/testing/PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md` | — | — | **CANONICAL** | current |
| 38 | `docs/adr/README.md` | 2026-09-28 | — | **CANONICAL** | ADR index; current |
| 39–56 | `docs/adr/ADR-0001.md` through `ADR-0018.md` | various | — | **CANONICAL** | all 18 ADRs; ADR-0017 historical; see ADR README |
| 57 | `docs/audits/README.md` | 2026-09-28 | 2,277 | **CANONICAL** | canonical current-worklist entry point for audits |
| 58 | `docs/audits/AUDIT_STATUS.md` | 2026-09-23 | 13,284 | **DUPLICATE** | superseded; superseded by ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md |
| 59 | `docs/audits/audit_report.md` | 2026-09-23 | 2,882 | **STALE** | completion/notification system audit; date 2026-09-17; no living references found |
| 60 | `docs/audits/config-audit-2026-09-17.md` | 2026-09-23 | 10,314 | **HISTORICAL** | config field audit; superseded by ADR-SOURCE-CONTRACT-AUDIT |
| 61 | `docs/audits/FINAL-VERIFICATION-REPORT.md` | 2026-09-23 | 5,678 | **HISTORICAL** | superseded by ADR-SOURCE-CONTRACT-AUDIT |
| 62 | `docs/audits/LOC-AUDIT.md` | 2026-09-24 | 16,460 | **DUPLICATE** | LOC-AUDIT-summary.md in historical/ is current summary |
| 63 | `docs/audits/2026-09-21-DOCS-HYGIENE-AUDIT.md` | 2026-09-23 | 11,244 | **HISTORICAL** | superseded by consolidation work (t_660cd9b6) |
| 64 | `docs/audits/ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md` | 2026-09-28 | 34,064 | **CANONICAL** | current authoritative audit; all 18 ADRs verified |
| 65 | `docs/audits/historical/` | — | — | **ARCHIVED** | 45 files; preserved for traceability; see `docs/audits/README.md` §Historical archive |
| 66 | `docs/audits/evidence/inspect/` | — | — | **EVIDENCE** | IntelliJ named XML exports moved from docs/inspect/ per DOCS-REORGANISATION-PLAN |
| 67 | `docs/inspect/FINDINGS-CLASSIFICATION.md` | 2026-09-29 | 14,460 | **CANONICAL** | current source-revalidated classification; has discoverable location in docs/inspect/ |
| 68 | `docs/inspect/*.xml` (machine-generated) | — | — | **GENERATED** | IntelliJ export; should be in `.gitignore` |

---

## 2. Java Compatibility Claims — Source of Truth

### Build configuration (`build.gradle`)

```
java {
    sourceCompatibility = JavaVersion.VERSION_1_8   // compile target
    targetCompatibility = JavaVersion.VERSION_1_8    // compile target
}
```

`sourceCompatibility = JavaVersion.VERSION_1_8` means the compiler is told to emit Java 8 bytecode. This does **not** guarantee the runtime environment is Java 8.

### CI evidence (`.github/workflows/ci.yml`)

```yaml
matrix:
  java-version: ['11', '17']   # CI runs on Java 11 and 17, NOT Java 8
```

### Release evidence (`.github/workflows/release.yml`)

```yaml
java-version: '17'   # Release is built and published with Java 17
```

### Published artifact evidence

- `grizzly-http-server:4.0.2` dependency produces Java 11 bytecode (per `build.gradle` comment line 33: "Grizzly 4.0.2 is retained for Java 8/JVM compatibility")
- The `components.java` publication via Gradle Maven plugin emits whatever bytecode the toolchain produced
- ADR-0001 documents "Portable Java 8 core" — but the core uses no Java 9+ APIs; the Grizzly transport may impose a higher minimum

### Root README claims

```
Line 3:  "Portable Java 8 library for hosting an [MCP]..."
Line 9:  "[![Java](https://img.shields.io/badge/Java-8+-orange)]..."
Line 123: "A minimal example that starts an MCP server on Android or any Java 8+ runtime"
```

### Proposed factual wording

> **Root README line 3:** "Portable Java 8-compatible library for hosting an MCP server inside any Java application"
> **Badge line 9:** Keep "Java 8+" but add a footnote: "Compiled for Java 8 bytecode; CI tests on Java 11 and 17; release built with Java 17."
> **Example caption line 123:** "A minimal example that starts an MCP server on Android (API 22+) or any Java 8+ runtime"

Rationale: `sourceCompatibility = JavaVersion.VERSION_1_8` is a compile-time contract stating the output is Java 8 bytecode. The runtime contract is "any JVM that can execute Java 8 bytecode." CI testing Java 11/17 validates that the Java 8 bytecode runs correctly on newer JVMs. The Grizzly transport may raise the practical floor above Java 8 in practice, but this is an implementation detail, not a stated contract.

---

## 3. Duplicate Canonical Documents — Detailed Findings

### 3.1 `docs/` root vs `docs/guides/`

| Root file | guides/ canonical | Diff summary |
|-----------|-------------------|--------------|
| `docs/API-REFERENCE.md` | `docs/guides/API-REFERENCE.md` | Minor: guides/ has `GRIZZLY-EXAMPLE.md` → `GRIZZLY-EXAMPLE.md` (broken link); guides/ has corrected `.tools(true).resources(true).prompts(true)` removed from builder example |
| `docs/PROJECT-GUIDE.md` | `docs/guides/PROJECT-GUIDE.md` | Minimal: guides/ has updated docs tree in §2 (reflects current subdirectories) |
| `docs/USER_GUIDE.md` | `docs/guides/USER_GUIDE.md` | **Identical** (diff returns empty) |
| `docs/IMPLEMENTATION-STATUS.md` | `docs/guides/IMPLEMENTATION-STATUS.md` | **Significant**: guides/ is 39,431 bytes vs root 14,126 bytes; guides/ has complete mermaid diagrams, session lifecycle, authorization sections; root is a stub |
| `docs/MCP-COMPATIBILITY-2026.md` | `docs/architecture/MCP-COMPATIBILITY-2026.md` | Minor: architecture/ has updated internal cross-links pointing to `../guides/`; root has stale `../` links |
| `docs/MCP-PORTING-PLAN.md` | `docs/architecture/MCP-PORTING-PLAN.md` | **Identical** (diff returns empty) |

### 3.2 `docs/guides/CI-PORTABILITY-JAVA8-RELEASE-GATE-SPEC.md`

This file (18,569 bytes) is unique to `docs/guides/`. It is NOT a duplicate of `MCP-COMPATIBILITY-2026.md` — they are distinct documents covering different scopes. No action needed.

### 3.3 `GRIZZLY-EXAMPLE.md` → `HTTP-TRANSPORT-EXAMPLE.md`

The file was renamed from `GRIZZLY-EXAMPLE.md` to `HTTP-TRANSPORT-EXAMPLE.md` in commit `79666df docs(guides): rename GRIZZLY-EXAMPLE to HTTP-TRANSPORT-EXAMPLE` (per `docs/audits/historical/MERGE-PLAN-2026-09-23.md`). However, the rename was **incomplete**:

- `docs/guides/HTTP-TRANSPORT-EXAMPLE.md` exists and is current
- `docs/guides/GRIZZLY-EXAMPLE.md` does NOT exist (confirmed: `ls` returns "No such file or directory")
- **Broken links** to `GRIZZLY-EXAMPLE.md` remain in:
  - `docs/guides/API-REFERENCE.md:133` — `See GRIZZLY-EXAMPLE.md for the full...`
  - `docs/guides/API-REFERENCE.md:631` — table link `[Grizzly Example](GRIZZLY-EXAMPLE.md)`
- `docs/API-REFERENCE.md` also has broken links to `GRIZZLY-EXAMPLE.md` and `docs/GRIZZLY-EXAMPLE.md`

The canonical file is now `docs/guides/HTTP-TRANSPORT-EXAMPLE.md`. All references must be updated.

---

## 4. `docs/inspect/` — Classification and Discoverability

### Current state

`docs/inspect/FINDINGS-CLASSIFICATION.md` (14,460 bytes, updated 2026-09-29) is a **current, maintained document** — it was revalidated against HEAD and contains actionable findings for `dev-architect`. It belongs in `docs/` (discoverable), not buried in `docs/inspect/`.

The machine-generated `.xml` and `.descriptions.xml` files are IntelliJ export artifacts and should not be tracked in git (see `.gitignore` gap below).

### `.gitignore` gap

`docs/inspect/.xml` and `docs/inspect/.descriptions.xml` are not excluded by the current `.gitignore`. The DOCS-REORGANISATION-PLAN-2026-09-21.md (now archived in historical/) recommended excluding these. See §7, item REORG-3.

### Recommended location for FINDINGS-CLASSIFICATION.md

The DOCS-REORGANISATION-PLAN specified moving it to `docs/audits/evidence/FINDINGS-CLASSIFICATION.md`. However, the 2026-09-29 revalidation updated it in place at `docs/inspect/FINDINGS-CLASSIFICATION.md`. Since the task is QA-run and the document is maintainer-owned, the location should be settled by the team. Two options:

- **Option A** (per DOCS-REORGANISATION-PLAN): move to `docs/audits/FINDINGS-CLASSIFICATION.md` (next to the canonical AUDIT_STATUS replacement)
- **Option B**: keep in `docs/inspect/` but add a link from `docs/audits/README.md` for discoverability

---

## 5. Competing `docs/audits/` Current-Status Artifacts

| File | Date | Contents | Status |
|------|------|----------|--------|
| `docs/audits/README.md` | 2026-09-28 | Points to AUDIT_STATUS.md; lists 4 key documents; historical archive index | **CANONICAL** (updated by t_660cd9b6) |
| `docs/audits/AUDIT_STATUS.md` | 2026-09-23 | 30 findings (15 SEC, 13 DOC, 2 VER); all VERIFIED | **Superseded** by ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md |
| `docs/audits/audit_report.md` | 2026-09-23 | Completion/notification system audit; no living cross-references | **Stale** — no doc links to it |
| `docs/audits/config-audit-2026-09-17.md` | 2026-09-23 | McpServerConfig field audit | **Historical** — superseded by ADR-SOURCE-CONTRACT-AUDIT |
| `docs/audits/FINAL-VERIFICATION-REPORT.md` | 2026-09-23 | Synthesis of parallel work streams | **Historical** — superseded by ADR-SOURCE-CONTRACT-AUDIT |
| `docs/audits/LOC-AUDIT.md` | 2026-09-24 | Full LOC data (14,196 bytes); `LOC-AUDIT-summary.md` in historical/ is the curated summary | **Duplicate of summary** — keep full data; confirm historical/ summary is sufficient |
| `docs/audits/ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md` | 2026-09-28 | 18 ADR contracts verified against source; current authoritative audit | **CANONICAL** |

### Proposed canonical audit entry point

`docs/audits/README.md` is already the designated entry point. It should be updated to reference `ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md` as the current authoritative source, and note that `AUDIT_STATUS.md` is historical (superseded).

---

## 6. Broken Internal Links

| File | Line | Broken link | Target status |
|------|------|-------------|---------------|
| `docs/guides/API-REFERENCE.md` | 133 | `See GRIZZLY-EXAMPLE.md for the full...` | **MISSING** — renamed to `HTTP-TRANSPORT-EXAMPLE.md` |
| `docs/guides/API-REFERENCE.md` | 631 | `[Grizzly Example](GRIZZLY-EXAMPLE.md)` | **MISSING** — renamed to `HTTP-TRANSPORT-EXAMPLE.md` |
| `docs/API-REFERENCE.md` | 133 | `See docs/GRIZZLY-EXAMPLE.md for the full...` | **MISSING** — renamed to `HTTP-TRANSPORT-EXAMPLE.md` |
| `docs/API-REFERENCE.md` | 690 | `[GRIZZLY-EXAMPLE.md](GRIZZLY-EXAMPLE.md)` | **MISSING** |
| `docs/API-REFERENCE.md` | 788 | `See docs/GRIZZLY-EXAMPLE.md for the full...` | **MISSING** |
| `docs/adr/ADR-0009-code-audit-findings.md` | 98 | `../audits/2026-09-12-full-source-audit.md` | **ARCHIVED** — moved to `historical/2026-09-12-full-source-audit.md` |
| `docs/adr/ADR-0009-code-audit-findings.md` | 99 | `../audits/2026-09-12-audit-supplement.md` | **ARCHIVED** — moved to `historical/` |

### Link check via `git diff --check`

```
git diff --check  (no staged changes; this is a read-only audit)
```

No whitespace errors in the working tree. All broken links above are content-level (target file missing or moved), not whitespace issues.

---

## 7. Unsafe Credential / Secret Examples

### Findings

| File | Line | Issue | Severity |
|------|------|-------|----------|
| `docs/transport/TRANSPORT-STREAMABLE-HTTP.md` | 329 | `() -> "secret-api-key"` — literal secret in example | MEDIUM |
| `docs/guides/TRANSPORT-SSE.md` | 329 | `() -> "secret-api-key"` — literal secret in example | MEDIUM |

### Correct examples (already compliant)

The following use `System.getenv()` suppliers — correct:

- `docs/guides/USER_GUIDE.md:77` — `() -> System.getenv("MCP_API_KEY")`
- `docs/guides/PROJECT-GUIDE.md:115` — "Do not put credentials, API keys, tokens, passwords..."
- `README.md` — GitHub token examples all use `System.getenv("GITHUB_TOKEN")`
- `docs/adr/ADR-0006-security-model.md:34` — `() -> System.getenv("MCP_API_KEY")`
- `docs/guides/USER_GUIDE.md:71-77` — explains Bearer auth with env var supplier

### Action required

The two literal `"secret-api-key"` strings in `TRANSPORT-STREAMABLE-HTTP.md` and `TRANSPORT-SSE.md` are placeholder examples in API documentation. They are in explanatory context (not in actual code), but should be replaced with `"${MCP_API_KEY}"` or `System.getenv("MCP_API_KEY")` for consistency and to avoid giving the impression that hardcoding secrets is acceptable. This is a LOW severity issue (documentation example, not production code), but should be remediated.

---

## 8. MkDocs / CI Documentation Configuration

No `mkdocs.yml` or `mkdocs.yaml` found anywhere in the repository. There is no MkDocs CI deployment. The documentation is served directly from the `docs/` directory on GitHub (via the repo's file tree) and via `docs/README.md` as the human navigation index.

---

## 9. Summary of Required Actions

### HIGH — Must fix before next release

| # | Action | Owner |
|---|--------|-------|
| H-1 | Fix all 6 broken `GRIZZLY-EXAMPLE.md` → `HTTP-TRANSPORT-EXAMPLE.md` links | dev-backend |
| H-2 | Update `docs/audits/README.md` to reference `ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md` as canonical; note `AUDIT_STATUS.md` is superseded | dev-pm |

### MEDIUM — Fix before next milestone

| # | Action | Owner |
|---|--------|-------|
| M-1 | Update root `docs/API-REFERENCE.md`, `docs/PROJECT-GUIDE.md`, `docs/IMPLEMENTATION-STATUS.md` cross-links to point to canonical `docs/guides/` copies; or delete the root duplicates | dev-backend |
| M-2 | Replace `"secret-api-key"` literal in `docs/transport/TRANSPORT-STREAMABLE-HTTP.md` and `docs/guides/TRANSPORT-SSE.md` with `System.getenv("MCP_API_KEY")` | dev-backend |
| M-3 | Settle `FINDINGS-CLASSIFICATION.md` location: either move to `docs/audits/` (per DOCS-REORGANISATION-PLAN) or add a discoverable link from `docs/audits/README.md` | dev-pm |

### LOW — Roadmap

| # | Action | Owner |
|---|--------|-------|
| L-1 | Add `docs/inspect/*.xml` and `docs/inspect/*.descriptions.xml` to `.gitignore` (machine-generated IntelliJ exports) | dev-ops |
| L-2 | Update root README Java badge to include factual footnote about CI/runtimes (see §2) | dev-backend |
| L-3 | Archive `docs/audits/audit_report.md` to `docs/audits/historical/` (no living references; stale date 2026-09-17) | dev-pm |
| L-4 | Review whether `docs/audits/LOC-AUDIT.md` (full data) should supersede `docs/audits/historical/LOC-AUDIT-summary.md` (summary only) — current: both exist | dev-pm |

---

## 10. Proposed Target Structure

```
docs/
├── README.md                          # canonical navigation index
├── API-REFERENCE.md                   # DELETE (duplicate; canonical is guides/)
├── PROJECT-GUIDE.md                   # DELETE (duplicate)
├── USER_GUIDE.md                     # DELETE (duplicate)
├── IMPLEMENTATION-STATUS.md          # DELETE (duplicate; guides/ is much fuller)
├── MCP-COMPATIBILITY-2026.md         # DELETE (duplicate)
├── MCP-PORTING-PLAN.md               # DELETE (duplicate)
│
├── guides/                            # canonical user-facing docs
│   ├── API-REFERENCE.md               # canonical
│   ├── PROJECT-GUIDE.md               # canonical
│   ├── USER_GUIDE.md                  # canonical
│   ├── IMPLEMENTATION-STATUS.md      # canonical
│   ├── HTTP-TRANSPORT-EXAMPLE.md     # canonical (renamed from GRIZZLY-EXAMPLE)
│   ├── TRANSPORT-SSE.md               # canonical
│   └── CI-PORTABILITY-JAVA8-RELEASE-GATE-SPEC.md  # canonical (unique)
│
├── architecture/                      # canonical design specs
│   ├── MCP-COMPATIBILITY-2026.md     # canonical
│   ├── MCP-PORTING-PLAN.md           # canonical
│   ├── mcp-registry-design-specification.md
│   ├── blob-resource-spi-triage-spec.md
│   ├── listener-semantics-spec.md
│   ├── guava-dependency-policy.md
│   ├── MCP-SECURITY-SYNTHESIS-TIER2.md
│   └── TLSCONFIG-LIFECYCLE-DECISION.md
│
├── authz/                             # canonical authorization specs
│   ├── SCOPES-AUTHORIZATION-SPEC.md
│   ├── RATE-LIMIT-TRIAGE-SPEC.md
│   └── RATE-LIMIT-429-BEHAVIOR-SPEC.md
│
├── transport/                         # canonical transport specs
│   ├── SSE-LAST-EVENT-ID-REPLAY.md
│   └── TRANSPORT-STREAMABLE-HTTP.md
│
├── testing/                           # canonical test specs
│   ├── TEST-0001-sse-connection-release-streaming.md
│   ├── TEST-0002-sse-validation-plan.md
│   ├── AUTH-SCOPES-CONFIRMATION-TEST-SPEC.md
│   ├── LAST-EVENT-ID-REPLAY-TEST-SPEC.md
│   └── PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md
│
├── adr/                               # canonical ADRs (never move/delete)
│   ├── README.md
│   └── ADR-0001.md … ADR-0018.md
│
├── audits/                            # canonical audit index
│   ├── README.md                      # canonical entry point (updated)
│   ├── ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md  # canonical authoritative audit
│   ├── LOC-AUDIT.md                  # canonical (review vs historical/ summary)
│   ├── 2026-09-21-DOCS-HYGIENE-AUDIT.md  # historical (keep)
│   ├── AUDIT_STATUS.md               # DEPRECATE (add note: superseded)
│   ├── audit_report.md               # STALE → move to historical/
│   ├── config-audit-2026-09-17.md   # historical (keep)
│   ├── FINAL-VERIFICATION-REPORT.md  # historical (keep)
│   ├── evidence/
│   │   └── inspect/                  # moved from docs/inspect/ per reorg plan
│   └── historical/                    # archived superseded docs
│       ├── LOC-AUDIT-summary.md
│       ├── DOCS-REORGANISATION-PLAN-2026-09-21.md
│       ├── 2026-09-12-full-source-audit.md
│       ├── 2026-09-12-audit-supplement.md
│       └── (41 more archived files)
│
└── inspect/                           # DEPRECATED location
    ├── FINDINGS-CLASSIFICATION.md   # MOVE to docs/audits/ (see M-3)
    └── *.xml                          # GENERATED — should be in .gitignore

root/
├── README.md                          # canonical root entry point
├── CHANGELOG.md                       # canonical
├── CONTRIBUTING.md                    # canonical
├── CLAUDE.md                          # canonical
├── AGENTS.md                          # canonical
└── build.gradle                       # source of truth for Java compatibility
```

---

## 11. Permanent ADR Preservation

All 18 ADRs in `docs/adr/` are permanent records and must never be deleted. ADR-0017 (`ADR-0017-sse-permit-response-flow.md`) is correctly marked "Historical" in the ADR index — it is preserved but noted as superseded by ADR-0016. The ADR index (`docs/adr/README.md`) is up to date as of 2026-09-28.

---

## 12. Validation

```bash
cd D:/android/mcp-java-sdk
git diff --check
# Expected: no output (no whitespace errors in working tree)

# Verify no GRIZZLY-EXAMPLE.md exists anywhere
find . -name "GRIZZLY-EXAMPLE.md" 2>/dev/null
# Expected: no output (file was renamed)

# Verify all guides/ canonical files exist
ls docs/guides/API-REFERENCE.md
ls docs/guides/PROJECT-GUIDE.md
ls docs/guides/USER_GUIDE.md
ls docs/guides/IMPLEMENTATION-STATUS.md
ls docs/guides/HTTP-TRANSPORT-EXAMPLE.md
ls docs/guides/TRANSPORT-SSE.md
ls docs/guides/CI-PORTABILITY-JAVA8-RELEASE-GATE-SPEC.md
# All should return 0 exit code

# Verify ADR count
ls docs/adr/ADR-*.md | wc -l
# Expected: 18
```
