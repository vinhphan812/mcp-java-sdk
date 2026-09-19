# Documentation Gaps Synthesis — 2026-09-17

**Synthesised from:** `t_c380b9d6` (code audit) and `t_efbf6629` (documentation audit).
**Verification:** cross-checked against `src/main/java/io/github/vinhphan812/mcp/api/config/RateLimits.java`,
`McpServerConfig.java`, `api/security/DefaultApiKeyStore.java`, `api/spi/ApiKeyStore.java`,
and `api/config/DestructiveToolPolicy.java`.

---

## Category A — Missing `RateLimits.Builder` Builder Methods in API-REFERENCE.md

All six methods below are present in `RateLimits.Builder` (confirmed by source inspection) but absent from the
`RateLimits` section of `docs/API-REFERENCE.md`.

| # | Missing Builder method                          | Source location (RateLimits.java) | Field stored                                      |
|---|-------------------------------------------------|-----------------------------------|---------------------------------------------------|
| 1 | `sessionTimeoutMs(long)`                        | line 115                          | `sessionTimeoutMs`                                |
| 2 | `sessionCleanupIntervalMs(long)`                | line 116                          | `sessionCleanupIntervalMs`                        |
| 3 | `maxPendingNotificationsPerSession(int)`        | line 117                          | `maxPendingNotificationsPerSession`               |
| 4 | `maxRequestsPerSessionPerMinute(int)`           | line 119                          | `maxRequestsPerSessionPerMinute`                  |
| 5 | `rateLimitWindows(long window, long sustained)` | line 124                          | `rateLimitWindowMs`, `rateLimitSustainedWindowMs` |
| 6 | `overflowPolicy(QueueOverflowPolicy)`           | line 130                          | `overflowPolicy`                                  |

**Evidence:** `RateLimits.java:115-130` shows each builder method directly. The `RateLimits` constructor at lines 39-51
confirms all six fields are assigned from builder values.

---

## Category B — Missing `McpServerConfig.Builder` Builder Methods in API-REFERENCE.md

Both methods are present in `McpServerConfig.Builder` (confirmed at lines 292-304) but absent from the
`McpServerConfig` section of `docs/API-REFERENCE.md`.

| # | Missing Builder method | Source location (McpServerConfig.java) | Description |
|---|------------------------|----------------------------------------|-------------|
| 7 | (Removed)              | N/A                                    | N/A         |
| 8 | (Removed)              | N/A                                    | N/A         |

**Evidence:** `McpServerConfig.java` does not contain `apiKeyStore` or `apiKeyMiddleware` fields.
lines 292-304 define the builder setters.

---

## Category C — `ApiKeyStore` SPI Thread-Safety Contract Not Documented

`ApiKeyStore.java` has no Javadoc and no thread-safety declaration. `DefaultApiKeyStore` uses
`AtomicReference`, `ReentrantLock`, and a `ScheduledExecutorService` internally, suggesting the
implementation is thread-safe, but this is not guaranteed by the contract.

**Impact:** Library consumers cannot safely use custom `ApiKeyStore` implementations in concurrent
request paths without an explicit contract.

**Recommendation:** Add Javadoc to `ApiKeyStore` declaring whether implementations must be thread-safe,
and whether concurrent calls to `isValid`, `rotateKey`, and `addKey` are supported.

**Evidence:** `DefaultApiKeyStore.java:13-14` (AtomicReference), `ReentrantLock` (line 13),
`Executors.newSingleThreadScheduledExecutor` (line 34) — implementation is thread-safe but the SPI contract does not
state this.

---

## Category D — `DefaultApiKeyStore` Constructor Parameters Not Documented

`DefaultApiKeyStore` has a public constructor:

```java
public DefaultApiKeyStore(String masterKey, String persistenceFile, int rotationIntervalSeconds)
```

None of the three parameters are documented in any user-facing doc. The fields `masterKey`,
`persistenceFile`, and `rotationIntervalSeconds` are used internally but not described in Javadoc.

**Recommendation:** Add Javadoc to the constructor and/or to the class overview explaining:

- `masterKey`: the secret used to sign/validate rotating keys;
- `persistenceFile`: path to the file-backed key store (may be `null` for in-memory-only);
- `rotationIntervalSeconds`: auto-rotation interval (0 or negative disables rotation).

**Evidence:** `DefaultApiKeyStore.java` — constructor parameters present in source, no Javadoc.

---

## Category E — Request-Processing Impact of `apiKeyStore` / `apiKeyMiddleware` Not Documented

`docs/PROJECT-GUIDE.md` section 9a ("API Key Authentication") shows a usage example but does not
document when and how these fields affect the HTTP request lifecycle (before/after rate limiting,
before/after authorization, interaction with Bearer token auth, etc.).

**Recommendation:** Add a subsection under the "API Key Authentication" heading in PROJECT-GUIDE.md
explaining:

- `apiKeyStore` vs `apiKeyMiddleware` distinction;
- order of evaluation in the request pipeline;
- interaction with `McpServer.Builder.apiKeySupplier(Bearer)`;
- error codes returned on invalid/missing keys.

**Evidence:** `McpServerConfig.java:56-58` — fields present; `PROJECT-GUIDE.md:403-423` — example present,
lifecycle impact absent.

---

## Category F — `DestructiveToolPolicy` 4-Param Constructor Missing from RateLimits.Builder

ADR-0011-follow-up (line 71) documents a 4-param builder method:

```java
public Builder destructiveTool(String name, int cap, long cooldownMs, int abuseWeight) { ... }
```

`DestructiveToolPolicy.java:16` confirms the 3-param constructor accepting `abuseWeight` exists.
However, `RateLimits.Builder.destructiveTool(String, int, long)` at line 125 only accepts **three**
parameters; there is **no** 4-param overload accepting `abuseWeight` directly in the builder.

**Impact:** ADR-0011 documents a builder method that does not exist. Users cannot set `abuseWeight`
per-tool through the builder API (they must construct `DestructiveToolPolicy` manually and use
`toBuilder()`).

**Recommendation:** Either add the missing 4-param `destructiveTool` overload to `RateLimits.Builder`,
or remove the 4-param overload from ADR-0011 and update the acceptance criteria accordingly.

**Evidence:** `RateLimits.java:125-127` — only 3-param method; `ADR-0011-follow-up-rate-limits-config.md:71`
— 4-param method listed as implemented.

---

## Category G — `QueueOverflowPolicy` Enum Not Documented in API-REFERENCE.md

`QueueOverflowPolicy` is referenced in `RateLimits.overflowPolicy` (field at line 35, builder at line 130)
and defined at `api/events/QueueOverflowPolicy.java`. It is not listed in `API-REFERENCE.md`.

**Recommendation:** Add a `QueueOverflowPolicy` section under `RateLimits` in API-REFERENCE.md, listing
all enum values and their effect on request processing.

**Evidence:** `RateLimits.java:35` — field declared; `api/events/QueueOverflowPolicy.java` — enum
definition exists.

---

## Category H — Default Registered Destructive Tools Not Documented

`RateLimits.Builder` registers six destructive tools by default at lines 106-111:

| Tool name            | Default cap              | Default cooldown                 |
|----------------------|--------------------------|----------------------------------|
| `shutdown`           | DESTRUCTIVE_CAP_SHUTDOWN | DESTRUCTIVE_COOLDOWN_SHUTDOWN_MS |
| `delete_action`      | DESTRUCTIVE_CAP_DELETE   | DESTRUCTIVE_COOLDOWN_DELETE_MS   |
| `delete_prompt`      | DESTRUCTIVE_CAP_DELETE   | DESTRUCTIVE_COOLDOWN_DELETE_MS   |
| `set_mcp_api_key`    | DESTRUCTIVE_CAP_DELETE   | DESTRUCTIVE_COOLDOWN_DELETE_MS   |
| `revoke_mcp_api_key` | DESTRUCTIVE_CAP_DELETE   | DESTRUCTIVE_COOLDOWN_DELETE_MS   |
| `upload_file`        | DESTRUCTIVE_CAP_UPLOAD   | DESTRUCTIVE_COOLDOWN_UPLOAD_MS   |

No doc states that these six tools are registered automatically when no explicit `destructiveTool` call
is made. Users may not realise that `shutdown` is already under a cap/cooldown policy.

**Recommendation:** Add a note in the `RateLimits` section of API-REFERENCE.md explaining the
default destructive-tool registration, or add it to the `McpSecurityDefaults` table.

**Evidence:** `RateLimits.java:106-111`.

---

## Summary Table

| ID  | Gap                                                                                  | File(s) affected                          | Severity |
|-----|--------------------------------------------------------------------------------------|-------------------------------------------|----------|
| A-1 | `RateLimits.Builder.sessionTimeoutMs` missing                                        | API-REFERENCE.md                          | Medium   |
| A-2 | `RateLimits.Builder.sessionCleanupIntervalMs` missing                                | API-REFERENCE.md                          | Medium   |
| A-3 | `RateLimits.Builder.maxPendingNotificationsPerSession` missing                       | API-REFERENCE.md                          | Medium   |
| A-4 | `RateLimits.Builder.maxRequestsPerSessionPerMinute` missing                          | API-REFERENCE.md                          | Medium   |
| A-5 | `RateLimits.Builder.rateLimitWindows` missing                                        | API-REFERENCE.md                          | Medium   |
| A-6 | `RateLimits.Builder.overflowPolicy` missing                                          | API-REFERENCE.md                          | Medium   |
| B-1 | `McpServerConfig.Builder.apiKeyStore` missing                                        | API-REFERENCE.md                          | High     |
| B-2 | `McpServerConfig.Builder.apiKeyMiddleware` missing                                   | API-REFERENCE.md                          | High     |
| C   | `ApiKeyStore` SPI thread-safety contract absent                                      | API-REFERENCE.md, ApiKeyStore.java        | Medium   |
| D   | `DefaultApiKeyStore` constructor parameters undocumented                             | ApiKeyStore.java, DefaultApiKeyStore.java | Low      |
| E   | Request-pipeline impact of apiKeyStore/apiKeyMiddleware undocumented                 | PROJECT-GUIDE.md                          | High     |
| F   | `destructiveTool(String,int,long,int)` builder method missing; ADR-0011 documents it | RateLimits.java, ADR-0011-follow-up       | High     |
| G   | `QueueOverflowPolicy` enum undocumented                                              | API-REFERENCE.md                          | Low      |
| H   | Default destructive tools not listed                                                 | API-REFERENCE.md                          | Low      |

---

## Priority Recommendations

1. **High — Fix F:** The ADR-0011 documents a non-existent builder method. This is a spec/implementation
   discrepancy. Either add the 4-param overload to `RateLimits.Builder` or update ADR-0011 to reflect
   the actual API.

2. **High — Fix B-1, B-2:** `apiKeyStore` and `apiKeyMiddleware` are new high-level security features
   that need full API reference coverage before users can adopt them safely.

3. **High — Fix E:** The "API Key Authentication" section in PROJECT-GUIDE.md must explain the
   request-processing lifecycle to prevent misconfiguration.

4. **Medium — Fix A-1 to A-6:** All six `RateLimits.Builder` builder methods should appear in
   API-REFERENCE.md alongside the existing `read`, `write`, `admin`, and `maxConcurrentSessions`
   methods.

5. **Medium — Fix C:** Add thread-safety Javadoc to `ApiKeyStore` to guide third-party implementers.

6. **Low — Fix G, H, D:** Minor additions to API-REFERENCE.md (`QueueOverflowPolicy`, default
   destructive tools) and Javadoc on `DefaultApiKeyStore`.
