# Javadoc Warnings & Docs Governance — Triage Report

**Task:** t_437808e1  
**Build:** `./gradlew clean javadoc` (2026-09-20)  
**Warnings:** 71 active (clean root build, no stale build artifacts)  
**Examples module:** 0 warnings (clean)  
**Scope:** `src/main/java` — 35 source files, 5,136 LOC

---

## 1. Categorized Warning Report by Public API Severity

### CRITICAL — Public API, missing class-level Javadoc (2 warnings)

Class-level docstring absent; these public top-level types have no class documentation.

| File                     | Line | Element                                 | Note                                         |
|--------------------------|------|-----------------------------------------|----------------------------------------------|
| `McpGrizzlyHandler.java` | 19   | `public final class McpGrizzlyHandler`  | Transport handler — high-profile entry point |
| `McpGrizzlyHandler.java` | 72   | `McpGrizzlyHandler(McpProtocolHandler)` | Primary 1-arg public constructor             |

### MAJOR — Public API, missing method-level Javadoc (6 warnings)

SDK-exposed public methods on documented interfaces/classes.

| File                         | Line | Element               | Note                          |
|------------------------------|------|-----------------------|-------------------------------|
| `AuthenticationContext.java` | 9    | `getApiKey()`         | Security SPI interface method |
| `AuthenticationContext.java` | 10   | `getHeaders()`        | Security SPI interface method |
| `AuthenticationContext.java` | 11   | `getRemoteAddress()`  | Security SPI interface method |
| `AuthenticationContext.java` | 12   | `getRequestPath()`    | Security SPI interface method |
| `AuthenticationContext.java` | 13   | `getMethod()`         | Security SPI interface method |
| `Clock.java`                 | 7    | `currentTimeMillis()` | SPI interface method          |

### MINOR — Public API, missing `@param`/`@return` on documented Builder methods (6 warnings)

Methods already have class-level docstrings; parameter/return tags are absent.

| File                   | Line | Element                                     | Issue                             |
|------------------------|------|---------------------------------------------|-----------------------------------|
| `McpServerConfig.java` | 291  | `Builder.trustXForwardedFor(boolean value)` | Missing `@param value`, `@return` |
| `McpServerConfig.java` | 309  | `Builder.bindSessionToIp(boolean value)`    | Missing `@param value`, `@return` |
| `RateLimits.java`      | 62   | `defaults()`                                | Missing `@return`                 |
| `RateLimits.java`      | 67   | `builder()`                                 | Missing `@return`                 |
| `RateLimits.java`      | 72   | `toBuilder()`                               | Missing `@return`                 |

### INTERNAL — Non-public or implementation-only (47 warnings)

Not surfaced in generated Javadoc for external consumers. No user-visible API impact.

| Category                                                 | Count | Files                                   |
|----------------------------------------------------------|-------|-----------------------------------------|
| `McpSecurityDefaults` constants (class-level doc exists) | 24    | 24 fields, each Javadoc-on-field absent |
| `RateLimits.Builder` methods (class-level doc exists)    | 16    | All Builder methods                     |
| `McpProtocolHandler.RateLimitStatus` inner class         | 4     | Inner class + 3 fields + constructor    |
| `DefaultApiKeyStore.shutdown()`                          | 1     | Package-private lifecycle method        |
| `DestructiveToolPolicy` constructors                     | 2     | Package-private (same-package)          |
| `QueueOverflowListener.onOverflow()`                     | 1     | Interface method already has class doc  |

---

## 2. Docs Audit Findings

### 2a. Public API Docs vs Source Code — Confirmed Accurate

| Source element                                  | Docs claim                                                                                          | Verdict                                                                          |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| `AuthenticationContext` interface (5 methods)   | Interface-level doc only; method docs missing                                                       | MATCHES — no class-level mismatch; methods lack docs (in scope of Batch 1 above) |
| `Clock` interface (`currentTimeMillis`)         | Class-level doc: "Interface to abstract time-based operations, allowing for deterministic testing." | MATCHES                                                                          |
| `McpServerConfig.Builder.trustXForwardedFor`    | Lines 286–294: full prose, 5 sentences, covers proxy, default, behaviour                            | MATCHES — @param/@return missing (minor gap)                                     |
| `McpServerConfig.Builder.bindSessionToIp`       | Lines 296–312: full prose with AUTH-03 reference and reverse-proxy note                             | MATCHES — @param/@return missing (minor gap)                                     |
| `RateLimits` class (5 factory/instance methods) | Class-level doc "Immutable server security and rate-limit configuration."                           | MATCHES — @return missing on 3 of 3                                              |
| `RateLimits.Builder`                            | No class-level doc; 16 Builder methods lack doc                                                     | CONSISTENT — class doc is absent, methods inherit nothing                        |

### 2b. Java 8 Compatibility

All Javadoc is plain HTML (no `@index`, no `{@value}`, no `{@code ...}` needing special encoding). No
`{@systemProperty}`, `{@exec}`, or other post-Java-8 tags observed. **Compliant.**

### 2c. Historical Audit Evidence vs Current Documents

All audit documents in `docs/audits/historical/` are accurately preserved and clearly labeled as superseded:

| Historical doc                                      | Current status                                     |
|-----------------------------------------------------|----------------------------------------------------|
| `2026-09-01-full-source-audit.md`                   | Historical (pre-ADR-0011)                          |
| `2026-09-12-audit-supplement.md`                    | Historical                                         |
| `2026-09-12-full-source-audit.md`                   | Historical                                         |
| `2026-09-14-mcp-rate-limit-security-audit.md`       | Historical                                         |
| `2026-09-15-rate-limit-remediation-verification.md` | Historical                                         |
| `2026-09-15-verification-addendum.md`               | Historical                                         |
| `LOC-AUDIT.md`                                      | Superseded (2026-09-17 full LOC audit replaces it) |
| `LOC-AUDIT-summary.md`                              | Historical                                         |
| `remediation-mapping.md`                            | Historical                                         |
| `OPEN_ITEMS.md`                                     | Historical                                         |
| `EVIDENCE_VERIFICATION_ADDENDUM.md`                 | Historical                                         |

Current authoritative audit record: `docs/audits/AUDIT_STATUS.md` (30/30 VERIFIED).  
Current authoritative LOC record: `docs/audits/LOC-AUDIT.md` (2026-09-17 baseline).

---

## 3. Remediation Batch Plan

### Batch 1 — Critical Public API Fixes (2 tasks)

**Goal:** Eliminate class-level Javadoc gaps on public entry points.  
**Files:** `McpGrizzlyHandler.java` (class + primary constructor)  
**Risk:** Low — add only doc comments, no behaviour change.  
**Test:** `./gradlew clean javadoc` passes.

### Batch 2 — Major Public API Fixes (2 tasks)

**Goal:** Document 6 methods on SDK SPI interfaces.  
**Files:** `AuthenticationContext.java` (5 method docs), `Clock.java` (1 method doc)  
**Risk:** Low — add only doc comments.  
**Test:** `./gradlew clean javadoc` passes.

### Batch 3 — Minor Public API Fixes (1 task)

**Goal:** Add `@param`/`@return` to documented Builder methods.  
**Files:** `McpServerConfig.java` (4 tags on 2 methods), `RateLimits.java` (3 tags on 3 methods)  
**Risk:** Minimal.  
**Test:** `./gradlew clean javadoc` passes.

### Batch 4 — DocLint Enforcement Gate (1 task)

**Goal:** Add doclint to the Gradle javadoc task to prevent regression.  
**File:** `build.gradle`

```
javadoc {
    options.addStringOption('Xdoclint', 'all,-missing')
    // or: options.doclint = 'all'
}
```

**Risk:** Low — CI gate only, no source changes.  
**Test:** `./gradlew clean javadoc` continues to pass; warnings become errors.

### Batch 5 — Optional Enhancements (1 task)

**Goal:** Address internal-only warnings if desired (not required for release).  
**Files:** `McpSecurityDefaults` (24), `RateLimits.Builder` (16), `McpProtocolHandler.RateLimitStatus` (4),
`DefaultApiKeyStore.shutdown()` (1), `DestructiveToolPolicy` (2), `QueueOverflowListener` (1).  
**Decision:** Deprioritize unless per-method Javadoc is a project standard.

---

## 4. Governance Gap Analysis

### Gap 1 — Stale Javadoc Warnings Inventory

**File:** `javadoc-warnings-inventory.md` (root, 7,897 bytes, 211 lines)  
**Issue:** Documents 76 warnings from an earlier build. Current clean build shows **71 warnings**.  
**Cause:** TlsConfig.java was removed (CHANGELOG.md: Unreleased / Removed) but the inventory still lists 5 TlsConfig
entries (lines 141–148).  
**Action:** Archive or delete `javadoc-warnings-inventory.md` — it is superseded by this report.

### Gap 2 — ADR Naming Inconsistency

**Issue:** `docs/adr/ADR-000X-sse-permit-response-flow.md` uses placeholder `X` instead of the next sequential number.  
**Active ADRs:** ADR-0001 through ADR-0016 are numbered; ADR-000X is a draft. ADR-0016's README entry is absent.  
**Impact:** Low (ADR-000X is draft status, ADR-0016 is not in README)  
**Action:** Assign sequential number to ADR-000X when promoted from Draft. Add ADR-0015 and ADR-0016 to
`docs/adr/README.md` (currently lists only through ADR-0014).

### Gap 3 — Duplicate Historical Audit Content

**Issue:** `docs/audits/historical/` contains 11 documents including duplicates:

- `2026-09-12-audit-supplement.md` and `2026-09-12-full-source-audit.md` overlap
- `LOC-AUDIT-summary.md` duplicates summary content from `LOC-AUDIT.md`  
  **Action:** No deletion — user-owned documents; maintain but reduce redundancy at next doc cleanup.

### Gap 4 — ADR Number Gap (0013 vs 0014)

**Issue:** ADR-0013 and ADR-0014 are both dated 2026-09-20 and sequential. ADR-0013 is TLS strategy analysis; ADR-0014
is TLS transport contract (decision). ADR-0015 and ADR-0016 exist in filesystem but not in README.  
**Action:** Add ADR-0015 and ADR-0016 to `docs/adr/README.md`.

### Gap 5 — Stale LOC Audit References

**Issue:** `docs/audits/LOC-AUDIT.md` (lines 101–111) shows `io.github.vinhphan812.io.github.vinhphan812.mcp` (doubled
package prefix) in per-package summary — a copy-paste error from the doc generator.  
**Issue:** Line 195 references "ADR-0011 added ~600 LOC" — accurate but stale.  
**Impact:** Cosmetic; does not affect source.  
**Action:** Fix in next LOC re-audit.

### Gap 6 — RateLimits.java Duplicate Class-Level Docstring

**File:** `RateLimits.java` lines 60–61 have two consecutive class-level Javadoc blocks:

```java
/** Returns defaults identical to the public McpProtocolHandler constants. */
/** Returns the default values matching McpProtocolHandler. */
```

The first is orphaned (Java 8 doclint does not flag this as an error, but it is invalid). Only the second is parsed.  
**Action:** Remove the duplicate docstring block (1-line fix).

---

## 5. Summary

| Category                     | Count | Action Required                                              |
|------------------------------|-------|--------------------------------------------------------------|
| **Critical public API gaps** | 2     | Batch 1: Add class Javadoc to McpGrizzlyHandler              |
| **Major public API gaps**    | 6     | Batch 2: Add method Javadoc to AuthenticationContext + Clock |
| **Minor public API gaps**    | 6     | Batch 3: Add @param/@return to Builder methods               |
| **Internal only**            | 47    | Optional Batch 5                                             |
| **Governance issues**        | 6     | Action items below                                           |

### Action Items for Follow-Up Tasks

1. **`javadoc-gaps-batch-1`** (dev): Add class-level Javadoc to `McpGrizzlyHandler` and its primary constructor.
2. **`javadoc-gaps-batch-2`** (dev): Add method Javadoc to
   `AuthenticationContext.getApiKey/getHeaders/getRemoteAddress/getRequestPath/getMethod` and `Clock.currentTimeMillis`.
3. **`javadoc-gaps-batch-3`** (dev): Add `@param`/`@return` to `McpServerConfig.Builder` and `RateLimits` factory
   methods.
4. **`javadoc-doclint-enforcement`** (dev): Add doclint to `build.gradle`.
5. **`docs-adr-readme-sync`** (dev-pm): Add ADR-0015 and ADR-0016 to `docs/adr/README.md`; assign sequential number to
   `ADR-000X`.
6. **`docs-archive-stale-inventory`** (dev-pm): Delete or archive `javadoc-warnings-inventory.md`.
7. **`rateLimits-duplicate-docs`** (dev): Remove duplicate class-level docstring block in `RateLimits.java` line 60.
8. **`loc-audit-fix`** (optional): Fix per-package table in `docs/audits/LOC-AUDIT.md`.

---

*Generated by t_437808e1 triage worker (dev-pm) — 2026-09-20. Source: `./gradlew clean javadoc` (71 warnings).*
