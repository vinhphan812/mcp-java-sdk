# Tier 1 Docs / Repository Hygiene Audit

**Task**: t_7d26d109
**Auditor**: dev-pm
**Date**: 2026-09-21
**Scope**: Read-only. No edits, staging, or commits.
**Workspace**: D:/android/mcp-java-sdk

---

## 1. API-REFERENCE.md Drift

### 1a. Duplicate Document Section

The file `docs/guides/API-REFERENCE.md` (1296 lines, 51 573 bytes) contains **two identical copies of the same document
concatenated**:

| Copy   | Line range | Header                                               |
|--------|------------|------------------------------------------------------|
| First  | 1–673      | `# MCP Java SDK — API Reference`                     |
| Second | 675–1296   | `# MCP Java SDK — API Reference` (repeated verbatim) |

The duplicate begins at line 675 with the identical `# MCP Java SDK — API Reference` title and repeats every section
through to the end of the file.

**Severity**: Cosmetic — no functional impact, but doubles file size and causes confusion.

### 1b. McpServerConfig Builder — Misstated Defaults

Source: `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` (Builder fields, lines 122–146).

**Verification table (first copy, lines 310–323 of API-REFERENCE.md)**:

| Builder method            | Source default        | Docs default | Verdict           |
|---------------------------|-----------------------|--------------|-------------------|
| `tools(boolean)`          | `true`                | `false`      | **MISSTATED**     |
| `resources(boolean)`      | `true`                | `false`      | **MISSTATED**     |
| `prompts(boolean)`        | `true`                | `false`      | **MISSTATED**     |
| `serverName(String)`      | `"mcp-server"`        | `required`   | **MISSTATED**     |
| `serverVersion(String)`   | `"1.0.0"`             | `required`   | **MISSTATED**     |
| `protocolVersion(String)` | `"2025-11-25"`        | `required`   | **MISSTATED**     |
| `logging(boolean)`        | `false`               | `false`      | Correct           |
| `completions(boolean)`    | `false`               | `false`      | Correct           |
| `tasks(boolean)`          | `false`               | `false`      | Correct           |
| `experimental(Map)`       | empty `LinkedHashMap` | empty        | Correct (present) |

**Severity**: **Breaking for API consumers** — callers who rely on the documented defaults will get unexpected
capability advertising. The three capability flags defaulting to `true` means unconfigured servers advertise
tools/resources/prompts even when not set.

**Proposed canonical fix** (targeting first copy, lines 310–323):

- `tools(boolean)` default: `false` → `true`
- `resources(boolean)` default: `false` → `true`
- `prompts(boolean)` default: `false` → `true`
- `serverName(String)` default: `required` → `"mcp-server"`
- `serverVersion(String)` default: `required` → `"1.0.0"`
- `protocolVersion(String)` default: `required` → `"2025-11-25"`

### 1c. McpServerConfig Builder — Missing Methods

Methods present in source but absent from the docs builder table:

| Missing method                            | Source line | Description                             | Default in source                |
|-------------------------------------------|-------------|-----------------------------------------|----------------------------------|
| `resourceSubscriptions(boolean)`          | Builder:208 | Enable resource subscription capability | `true`                           |
| `logger(McpLogger)`                       | Builder:136 | Supply custom logger                    | `new JulMcpLogger("mcp-server")` |
| `pageSize(int)`                           | Builder:152 | Max page size for paginated responses   | `50`                             |
| `overflowListener(QueueOverflowListener)` | Builder:258 | Notification queue overflow handler     | `null`                           |
| `authorization(McpAuthorization)`         | Builder:272 | Tool authorisation handler              | `null`                           |
| `rateLimits(RateLimits)`                  | Builder:281 | Rate-limit configuration                | `RateLimits.defaults()`          |
| `trustXForwardedFor(boolean)`             | Builder:293 | Trust X-Forwarded-For header            | `false`                          |
| `bindSessionToIp(boolean)`                | Builder:313 | Bind session to client IP (AUTH-03)     | `false`                          |

**Severity**: **Breaking** — SDK consumers using these features will not find them in the documentation and may assume
they do not exist.

**Proposed canonical fix**: Add all 8 rows to the builder methods table, after the first copy's table (line ~323). Use
the defaults from the column above.

---

## 2. CHANGELOG.md — Stale Link

**File**: `CHANGELOG.md`, line 18
**Current link**: `[transport documentation](docs/TRANSPORT-SSE.md)`

| Path                           | Exists?                            |
|--------------------------------|------------------------------------|
| `docs/TRANSPORT-SSE.md`        | **NO**                             |
| `docs/guides/TRANSPORT-SSE.md` | **YES** (20 669 bytes, 2026-09-21) |

**Canonical fix**: Change link target to `docs/guides/TRANSPORT-SSE.md`.

**Severity**: **High** — users following the link get a 404.

---

## 3. CONTRIBUTING.md — Stale Link

**File**: `CONTRIBUTING.md`, line 44
**Current link**: `[docs/IMPLEMENTATION-STATUS.md](docs/IMPLEMENTATION-STATUS.md)`

| Path                                   | Exists? |
|----------------------------------------|---------|
| `docs/IMPLEMENTATION-STATUS.md`        | **NO**  |
| `docs/guides/IMPLEMENTATION-STATUS.md` | **YES** |

**Canonical fix**: Change link target to `docs/guides/IMPLEMENTATION-STATUS.md`.

**Severity**: **Medium** — only affects contributors who follow the link.

---

## 4. nul Artifact

**File**: `nul` (168 bytes) at repo root `D:/android/mcp-java-sdk/nul`

```
  File: nul
  Size: 168       Blocks: 1          IO Block: 65536  regular file
Device: b62bfc6fh/3056335983d  Inode: 5066549581551413  Links: 1
Access: (0644/-rw-r--r--)  Uid: (197609/   Admin)  Gid: (197609/ UNKNOWN)
 Birth: 2026-09-21 09:56:06.628438000 +0700
 Modify: 2026-09-21 09:56:37.873080800 +0700
```

**Classification**: Accidental build/IDE artifact. Not the Windows NUL device (which would have 0 bytes and device
type). The 168-byte regular file with 0644 permissions and a timestamp from today indicates a command such as
`dir /b *.java > nul` or `ls *.java > nul` was run, which on this Windows/MSYS environment created a file named `nul`
instead of writing to the NUL device.

**Proposed disposition**:

1. Delete `nul` from the filesystem.
2. Add `nul` to `.gitignore` to prevent re-commit.

**Severity**: **Low** — no source code impact, but clutters the repo and may confuse tools that process the root
directory.

---

## 5. Javadoc Warnings

**Command**: `./gradlew javadoc --rerun-tasks`
**Warning count**: 58 total (57 `no comment` + 1 Oracle redirect)

### 5a. Infrastructure Warning

```
warning: URL https://docs.oracle.com/javase/8/docs/api/element-list was redirected to
https://docs.oracle.com/en/java/javase/27/ -- Update the command-line options to suppress this warning.
```

**Root cause**: `--link` option in build.gradle points to Java 8 docs; Oracle redirects to Java 17+. Trivially fixable
by updating the link to `https://docs.oracle.com/en/java/javase/17/docs/api/`.

**Severity**: Cosmetic — does not affect generated documentation quality.

### 5b. Public-API "no comment" Warnings

All 57 warnings are `warning: no comment` on public class, field, or constructor declarations.

| File                         | Count  | Lines affected              |
|------------------------------|--------|-----------------------------|
| `McpSecurityDefaults.java`   | 19     | 5–31 (class + 18 fields)    |
| `RateLimits.java`            | 14     | 122–137 (class + 13 fields) |
| `McpGrizzlyHandler.java`     | 6      | 98,104,111,137,144 + class  |
| `McpProtocolHandler.java`    | 5      | 67,506,507,508,510          |
| `DestructiveToolPolicy.java` | 3      | class + 2 fields            |
| `DefaultApiKeyStore.java`    | 1      | line 110                    |
| `QueueOverflowListener.java` | 1      | class                       |
| **Total**                    | **57** |                             |

**Root cause family**: Missing Javadoc comments on public API surface. All members are configuration constants,
transport fields, or protocol handler members — all intended for external use.

**Severity**: **Medium** — these warnings appear in every CI build; they degrade signal-to-noise for genuine warnings.
Also affects IDE tooltips and generated API documentation.

**Fix approach**: Add Javadoc to each public member. Most are constant/field declarations requiring only a `@field` tag
or class-level comment. ~17 require method/constructor-level `@param`/`@return` tags.

---

## 6. Proposed Implementation Cards

The following four cards were created from this audit. They do **not** overlap with the in-flight structural docs
reorganisation (t_8bd224b2 and predecessor cards).

| # | Card ID      | Title                                                                                     |
|---|--------------|-------------------------------------------------------------------------------------------|
| 1 | `t_6d049a82` | Fix API-REFERENCE.md: McpServerConfig drift, missing builder methods, duplicate section   |
| 2 | `t_0601dc50` | Fix stale doc links: CHANGELOG.md TRANSPORT-SSE and CONTRIBUTING.md IMPLEMENTATION-STATUS |
| 3 | `t_c225b828` | Javadoc Remediation: address 57 "no comment" public-API warnings                          |
| 4 | `t_1b3c0ed8` | Artifact Removal: delete nul (168 bytes) and add to .gitignore                            |

---

## 7. Validation Checklist

### Link check

- [ ] 
  `grep -rn "docs/[A-Z]" --include="*.md" . | grep -v "docs/guides/" | grep -v "docs/architecture/" | grep -v "docs/adr/" | grep -v "docs/authz/" | grep -v "docs/testing/" | grep -v "docs/audits/"`
  returns zero matches (all stale links patched).
- [ ] `docs/guides/TRANSPORT-SSE.md` is reachable from `CHANGELOG.md`.
- [ ] `docs/guides/IMPLEMENTATION-STATUS.md` is reachable from `CONTRIBUTING.md`.

### Javadoc check

- [ ] `./gradlew javadoc --rerun-tasks 2>&1 | grep -c "warning"` returns `0` or `1` (the Oracle redirect, acceptable if
  suppressed separately).

### Clean tree check

- [ ] `git status --short | grep nul` returns empty.
- [ ] `ls -la nul` returns `No such file or directory`.

### API Reference correctness

- [ ] `docs/guides/API-REFERENCE.md` contains exactly one copy of each section (no duplicate).
- [ ] `McpServerConfig` builder table defaults match source defaults for all 11 documented methods.
- [ ] All 8 missing builder methods appear in the docs with correct signatures and defaults.

---

*Audit completed 2026-09-21. No write actions taken.*
