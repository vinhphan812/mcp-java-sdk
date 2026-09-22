# Documentation Completeness and Accuracy Audit

**Date:** 2026-09-22  
**Task:** t_f98f902c  
**Auditor:** dev-pm  
**Scope:** MCP Java SDK documentation

---

## Executive Summary

The MCP Java SDK has **84 documentation files** with good overall coverage. A new user can understand and deploy the SDK
without assistance, but there are **3 accuracy issues** and **4 broken internal links** that should be fixed. No
deprecated APIs were found referenced in documentation.

**Overall Grade: B+** (Good, with minor fixes needed)

---

## 1. Documentation Inventory

Total: **84 Markdown files**

| Category     | Count | Files                                                                                                                                                                                 |
|--------------|-------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Guides       | 7     | USER_GUIDE.md, API-REFERENCE.md, PROJECT-GUIDE.md, GRIZZLY-EXAMPLE.md, TRANSPORT-SSE.md, IMPLEMENTATION-STATUS.md, CI-PORTABILITY-JAVA8-RELEASE-GATE-SPEC.md                          |
| Architecture | 6     | MCP-COMPATIBILITY-2026.md, MCP-PORTING-PLAN.md, mcp-registry-design-specification.md, blob-resource-spi-triage-spec.md, listener-semantics-spec.md, BLOB-RESOURCE-SPI-TRIAGE-TIER2.md |
| ADR          | 19    | ADR-0001 through ADR-0018 + README.md                                                                                                                                                 |
| Audits       | 33    | Current + historical/ (12 files in historical/)                                                                                                                                       |
| Authz        | 2     | SCOPES-AUTHORIZATION-SPEC.md, RATE-LIMIT-TRIAGE-SPEC.md                                                                                                                               |
| Testing      | 5     | Test specs                                                                                                                                                                            |
| Transport    | 5     | SSE and Streamable HTTP specs                                                                                                                                                         |
| Migration    | 1     | STREAMABLE-HTTP-MIGRATION.md                                                                                                                                                          |

---

## 2. User Journey Analysis

### 2.1 README.md

**Status: GOOD**

- Explains what the SDK is (portable Java 8 library for hosting MCP server)
- Covers all three installation methods: Gradle (GitHub Packages), Local JAR, Clone
- Provides quick start code example
- Links to getting started documentation
- Architecture diagrams included

### 2.2 USER_GUIDE.md

**Status: GOOD**

- Quick setup instructions (5 minutes)
- Basic usage with McpServer.Builder
- Rate limits configuration
- Authentication and authorization (Bearer token, scopes)
- Troubleshooting section
- FAQ

**Note:** Line 14 references `mcp-java-sdk:1.0-SNAPSHOT` but README.md references `1.0.0` — slight version
inconsistency.

### 2.3 API-REFERENCE.md

**Status: ACCURACY ISSUES**

- Comprehensive API surface documented
- Fixed duplicate section issue (previous audit t_6d049a82)
- **Issue:** `@McpResource` mimeType default documented as `"text/plain"` (line 153), but source code shows
  `"application/json"` (McpResource.java line 22)

### 2.4 PROJECT-GUIDE.md

**Status: GOOD**

- Clear directory structure
- Package architecture explained
- Runtime and dependencies listed
- Android hosting section
- Server startup flow with diagrams
- Capability registration details
- Security section complete

### 2.5 GRIZZLY-EXAMPLE.md

**Status: GOOD**

- Standalone example with full code
- Direct parameter binding documented
- POJO complex types with Gson
- Request examples with JSON payloads
- curl bootstrap commands
- Limitations clearly stated

---

## 3. Accuracy Check

Verified 5 random API claims against source code:

| Claim                            | Document             | Source                     | Status   |
|----------------------------------|----------------------|----------------------------|----------|
| `McpServer.builder()` exists     | API-REFERENCE.md     | src/core/McpServer.java:30 | PASS     |
| `bindSessionToIp` builder method | API-REFERENCE.md     | McpServerConfig.java:329   | PASS     |
| `@McpTool(scopes)` annotation    | PROJECT-GUIDE.md     | McpTool.java:30            | PASS     |
| `@McpResource` default mimeType  | API-REFERENCE.md:153 | McpResource.java:22        | **FAIL** |
| `streamableHttp` config option   | Migration guide      | McpServerConfig.java:85    | PASS     |

### 3.1 Detailed Accuracy Issues

**Issue 1: @McpResource mimeType default**

- **Document:** `docs/guides/API-REFERENCE.md` line 153
- **Doc says:** `mimeType` default is `"text/plain"`
- **Source says:** `mimeType` default is `"application/json"` (McpResource.java:22)
- **Severity:** Medium — misleading default value

**Issue 2: Migration guide references non-existent builder patterns**

- **Document:** `docs/migration/STREAMABLE-HTTP-MIGRATION.md`
- **Doc says (line 34-35):** `new StreamableServerTransportProvider(handler).build()`
- **Reality:** `StreamableServerTransportProvider` has no builder() method
- **Severity:** High — code examples won't compile

**Issue 3: Migration guide TransportMode builder params**

- **Document:** `docs/migration/STREAMABLE-HTTP-MIGRATION.md` lines 45-48
- **Doc shows:** `.transportMode(TransportMode.STREAMABLE_HTTP)` as builder chain
- **Reality:** TransportMode is set via McpHttpHandler constructor, not builder
- **Severity:** High — code examples won't compile

**Issue 4: Missing sseIdleTimeoutSeconds**

- **Document:** `docs/migration/STREAMABLE-HTTP-MIGRATION.md` line 73
- **Doc mentions:** `sseIdleTimeoutSeconds` configuration option
- **Reality:** No such configuration in source (grep returned no results)
- **Severity:** Medium — non-existent configuration option

---

## 4. Completeness Check

| Required Topic                       | Covered | Document                                                |
|--------------------------------------|---------|---------------------------------------------------------|
| Add SDK to Gradle/Maven              | Yes     | README.md                                               |
| Create server with custom config     | Yes     | USER_GUIDE.md, API-REFERENCE.md                         |
| Register tools with @McpTool         | Yes     | API-REFERENCE.md                                        |
| Register resources with @McpResource | Yes     | API-REFERENCE.md                                        |
| Register prompts with @McpPrompt     | Yes     | API-REFERENCE.md                                        |
| Configure authentication             | Yes     | USER_GUIDE.md, PROJECT-GUIDE.md                         |
| Configure authorization with scopes  | Yes     | USER_GUIDE.md (lines 86-100), PROJECT-GUIDE.md          |
| Configure rate limits                | Yes     | USER_GUIDE.md (lines 49-67)                             |
| Handle long-running tools            | Partial | IMPLEMENTATION-STATUS.md mentions progress/cancellation |
| Handle cancellations                 | Partial | IMPLEMENTATION-STATUS.md mentions isCancelled           |
| SSE streaming (legacy vs modern)     | Yes     | Migration guide, TRANSPORT-SSE.md                       |
| Debug common issues                  | Yes     | USER_GUIDE.md troubleshooting                           |
| Run tests                            | Yes     | PROJECT-GUIDE.md line 393                               |
| Build and package                    | Yes     | README.md build section                                 |

---

## 5. Staleness Check

**No deprecated API references found.** Searched for:

- `@Deprecated` annotations in docs: 0 results
- "deprecated" keyword in docs: Only used correctly to describe deprecated MCP protocol features (not SDK APIs)

---

## 6. Broken Internal Links

| Document                                 | Broken Link                                       | Should Be                              |
|------------------------------------------|---------------------------------------------------|----------------------------------------|
| docs/adr/ADR-0009-code-audit-findings.md | `adr/ADR-0009-code-audit-2026-09-11.md`           | Non-existent                           |
| docs/README.md                           | `docs/TRANSPORT-SSE.md`                           | `docs/guides/TRANSPORT-SSE.md`         |
| docs/audits/AUDIT_STATUS.md              | `docs/IMPLEMENTATION-STATUS.md` (double-prefixed) | `docs/guides/IMPLEMENTATION-STATUS.md` |
| docs/guides/IMPLEMENTATION-STATUS.md     | `docs/guides/TRANSPORT-SSE.md` (line 18)          | `TRANSPORT-SSE.md` (same directory)    |

---

## 7. Recommendations

### Priority 1 — Fix Immediately (Accuracy)

1. **Fix @McpResource mimeType default** in API-REFERENCE.md line 153
    - Change from `"text/plain"` to `"application/json"`

2. **Fix migration guide code examples** in STREAMABLE-HTTP-MIGRATION.md
    - Remove non-existent `.build()` calls on StreamableServerTransportProvider
    - Fix TransportMode configuration examples to match actual API

3. **Remove sseIdleTimeoutSeconds** from migration guide (line 73)
    - This configuration option doesn't exist

### Priority 2 — Fix Broken Links

4. **Fix docs/README.md line references**
    - Change `docs/TRANSPORT-SSE.md` to `guides/TRANSPORT-SSE.md`

5. **Fix docs/audits/AUDIT_STATUS.md link**
    - Change `docs/IMPLEMENTATION-STATUS.md` to `guides/IMPLEMENTATION-STATUS.md`

6. **Fix docs/guides/IMPLEMENTATION-STATUS.md link**
    - Change `docs/guides/TRANSPORT-SSE.md` to `TRANSPORT-SSE.md`

### Priority 3 — Minor Improvements

7. **Version consistency** — USER_GUIDE.md line 14 says `1.0-SNAPSHOT`, README.md says `1.0.0`

8. **ADR-0009 reference** — Verify correct ADR filename in ADR-0009-code-audit-findings.md

---

## 8. Validation Commands Run

```bash
# Count doc files
find docs -name "*.md" -type f | wc -l  # Result: 84

# Search for deprecated references
grep -r "deprecated\|@Deprecated" docs/  # Result: 0 (only used for MCP protocol, not SDK)

# Verify API-REFERENCE.md builder methods
grep -n "bindSessionToIp\|trustXForwardedFor" src/main/java/.../McpServerConfig.java  # Found

# Verify @McpResource mimeType default
grep "mimeType" src/main/java/.../annotations/McpResource.java  # Shows "application/json"
```

---

## Appendix: Documentation File Tree

```
docs/
├── README.md                    # Docs index
├── adr/                        # 19 ADRs
│   ├── ADR-0001-portable-java8-core.md
│   ├── ADR-0002-grizzly-transport-isolation.md
│   ├── ...
│   ├── ADR-0018-transport-contract-http-sse-vs-streamable-http.md
│   └── README.md
├── architecture/                # 6 files
│   ├── MCP-COMPATIBILITY-2026.md
│   ├── MCP-PORTING-PLAN.md
│   └── ...
├── audits/                     # Current + historical/
│   ├── AUDIT_STATUS.md
│   ├── COMPREHENSIVE-TEST-COVERAGE-AUDIT-2026-09-22.md
│   ├── SECURITY-TRIPLE-TRIAGE-2026-09-21.md
│   └── historical/            # 12 old audits
├── authz/                      # 2 files
│   ├── SCOPES-AUTHORIZATION-SPEC.md
│   └── RATE-LIMIT-TRIAGE-SPEC.md
├── guides/                     # 7 files (primary user-facing)
│   ├── USER_GUIDE.md
│   ├── API-REFERENCE.md
│   ├── PROJECT-GUIDE.md
│   ├── GRIZZLY-EXAMPLE.md
│   ├── TRANSPORT-SSE.md
│   ├── IMPLEMENTATION-STATUS.md
│   └── CI-PORTABILITY-JAVA8-RELEASE-GATE-SPEC.md
├── testing/                    # 5 test specs
├── transport/                  # 5 transport specs
└── migration/
    └── STREAMABLE-HTTP-MIGRATION.md
```

---

*End of audit report*
