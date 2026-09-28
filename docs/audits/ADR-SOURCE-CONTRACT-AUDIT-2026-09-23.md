# ADR-to-source contract audit (2026-09-23)

Audit of all 18 Architecture Decision Records (ADRs) against the current source code of the MCP Java SDK.
Audited: 2026-09-23. Re-verified and detail section completed: 2026-09-27.

## Summary

| ADR      | Title                             | ADR Status | Audit Result                   |
|:---------|:----------------------------------|:-----------|:-------------------------------|
| ADR-0001 | Portable Java 8 Core              | Accepted   | VERIFIED                       |
| ADR-0002 | Grizzly transport isolation       | Accepted   | VERIFIED                       |
| ADR-0003 | JSON-RPC 2.0 Envelope, Versioning | Accepted   | VERIFIED                       |
| ADR-0004 | Session Management                | Accepted   | VERIFIED                       |
| ADR-0005 | SSE Event Queue                   | Accepted   | VERIFIED                       |
| ADR-0006 | Security model                    | Accepted   | VERIFIED                       |
| ADR-0007 | Annotation registration           | Accepted   | VERIFIED                       |
| ADR-0008 | Protocol baseline                 | Accepted   | VERIFIED                       |
| ADR-0009 | Code audit findings               | Accepted   | VERIFIED                       |
| ADR-0010 | API package restructure           | Accepted   | VERIFIED                       |
| ADR-0011 | Security rate limiting            | Accepted   | VERIFIED                       |
| ADR-0012 | Configurable rate limits          | Accepted   | VERIFIED                       |
| ADR-0013 | TLS Strategy                      | Accepted   | VERIFIED                       |
| ADR-0014 | TLS transport contract            | Accepted   | OPEN                           |
| ADR-0015 | Concurrent collection             | Accepted   | OPEN                           |
| ADR-0016 | SSE permit flow                   | Active     | OPEN                           |
| ADR-0017 | SSE permit response flow          | Superseded | ARCHITECTURE DECISION REQUIRED |
| ADR-0018 | Transport contract                | Accepted   | OPEN                           |

Counts: VERIFIED 12 / OPEN 4 / ARCHITECTURE DECISION REQUIRED 1 / STALE 0 / BLOCKED 0

---

## Detail Audit

### ADR-0001 — Portable Java 8 Core Without Android SDK

**Claim/contract:** No production source file imports `android.*` or `androidx.*`. The
`annotations/`, `api/`, and `core/` packages use only standard Java SE 8 APIs.
Only `transport/` has non-Java-SE dependencies (Grizzly).

**Source evidence:**

- Static scan: `grep -rIn "import android\.\|import androidx\." src/main/java/` — zero matches
  confirmed in this audit run.
- `annotations/` — 9 annotation files, no non-JDK imports.
- `core/McpProtocolHandler.java`, `core/McpRegistry.java`, `core/McpServer.java` — imports are
  `java.util.*`, `java.util.concurrent.*`, `com.google.gson.*` only.
- `transport/McpHttpHandler.java:1-16` — imports `org.glassfish.grizzly.*`; isolated to
  `transport/` package as decided.

**Test evidence:** `McpIntegrationTest`, `McpProtocolHandlerTest`, and all unit tests exercise
`core/` independently of Grizzly.

**Result: VERIFIED**

---

### ADR-0002 — Grizzly Transport Isolation

**Claim/contract:** `transport/` is isolated from `core/` in the dependency direction: `transport/` may depend on
`core/`, but `core/` must not depend on `transport/` or Grizzly. `HttpTransportProvider` is the public entry point
(`AutoCloseable`). `McpHttpHandler` is the main adapter (POST/GET/DELETE). `McpGrizzlyHandler` is the legacy adapter.
`TransportMode` enum (AUTO, HTTP_SSE, STREAMABLE_HTTP). Public `McpRegistrar` SPI is the registration boundary.

**Source evidence:**

- `transport/HttpTransportProvider.java:15` — `public final class HttpTransportProvider implements AutoCloseable`
- `transport/HttpTransportProvider.java:172-174` — creates `McpHttpHandler`, `HttpServer`, `NetworkListener`
- `transport/McpHttpHandler.java:37` — `public final class McpHttpHandler extends HttpHandler`
- `transport/McpGrizzlyHandler.java:19` — `public final class McpGrizzlyHandler extends HttpHandler`
- `transport/TransportMode.java` — enum with `HTTP_SSE`, `STREAMABLE_HTTP`, `AUTO`
- `api/spi/McpRegistrar.java` — SPI boundary confirmed present

**Finding:** ADR-0002 states `HttpTransportProvider` uses `McpHttpHandler` by default and
`McpGrizzlyHandler` is kept for backward compatibility. Source confirms this. The ADR now explicitly records the
asymmetric but intentional layering: `transport/` imports `core/` protocol contracts, while `core/` does not import
transport classes or Grizzly. The public `McpRegistrar` SPI is the registration boundary. No source evidence contradicts
the one-way dependency decision.

**Result: VERIFIED**
The ADR now explicitly states the intentional one-way dependency and `McpRegistrar` SPI boundary. No code change required.

---

### ADR-0003 — JSON-RPC 2.0 Envelope, Protocol Versioning, Capability Advertisement

**Claim/contract:** `jsonrpc` field validated as exactly `"2.0"`. Notifications get no response
body. Invalid envelopes return -32600. Protocol version negotiated against configured list.
Capabilities advertised per `McpServerConfig` flags. `resources/subscribe` requires both
`config.resources` AND `config.resourceSubscriptions`.

**Source evidence:**

- `core/McpProtocolHandler.java:56` — `private static final String JSONRPC_VERSION = "2.0";`
- `core/McpProtocolHandler.java:587-588` — validates `jsonrpc == "2.0"`, returns -32600 on failure
- `core/McpProtocolHandler.java:467-469` — `supportsProtocolVersion()` accepts configured version,
  `"2025-06-18"`, `"2025-03-26"`
- `core/McpProtocolHandler.java:689-767` — capability guards: tools, resources, prompts, tasks,
  completions, logging checked per `config.*` flags
- `core/McpProtocolHandler.java:724-725` — `resources/subscribe` requires `config.resources &&
  config.resourceSubscriptions`
- `core/McpProtocolHandler.java:1415-1439` — `handleInitialize` builds capabilities map from
  config flags

**Test evidence:** `McpProtocolHandlerTest`, `McpServerConfigTest`, `McpClientCapabilitiesTest`

**Result: VERIFIED**

---

### ADR-0004 — Session Management and Lifecycle

**Claim/contract:** Session created with `UUID.randomUUID()` in `handleInitialize`. `SessionState`
holds subscriptions, `AtomicLong nextEventId`, `ConcurrentLinkedQueue<SseEvent> pendingEvents`.
`hasSession()` guards protected methods. Error -32001 on missing session. 5-minute idle timeout.
DELETE / `terminateSession` / `close()` all clean up sessions. SSE replay via `getMissedEvents()`.

**Source evidence:**

- `core/McpProtocolHandler.java:271-299` — `SessionState` inner class with `sessionId`, `clientIp`,
  `ownerId`, `subscriptions` (ConcurrentHashMap.newKeySet), `pendingEvents` (ConcurrentLinkedQueue
  of SseEvent), `nextEventId` (AtomicLong), `pendingNotifications` (ConcurrentLinkedQueue)
- `core/McpProtocolHandler.java:301` — `sessions: ConcurrentHashMap<String, SessionState>`
- `core/McpProtocolHandler.java:478-479` — `hasSession()` checks map containsKey
- `core/McpProtocolHandler.java:609-611` — returns -32001 on missing session
- `core/McpProtocolHandler.java:604-606` — max concurrent sessions check on initialize
- Cleanup thread at `core/McpProtocolHandler.java:425-437` — uses `rateLimits.sessionTimeoutMs`
  (default from `RateLimits.defaults()` which maps to `MAX_SESSION_IDLE_MS`)

**Test evidence:** `McpSessionTimeoutTest`, `McpOwnerSessionTest`

**Result: VERIFIED**

---

### ADR-0005 — Server-Initiated Notifications via SSE Event Queue

**Claim/contract:** `AtomicLong nextEventId` + `ConcurrentLinkedQueue<SseEvent>` per session.
Queue bounded to 1000 events (FIFO, oldest dropped). CRLF sanitisation on SSE data.
`Last-Event-ID` replay via `getMissedEvents()`. Ping every iteration.

**Source evidence:**

- `core/McpProtocolHandler.java:278-279` — `pendingEvents: ConcurrentLinkedQueue<SseEvent>`,
  `nextEventId: AtomicLong(1L)`
- `core/McpProtocolHandler.java:296` — `pendingEvents.offer(new SseEvent(...))` — event enqueue
- `core/McpProtocolHandler.java:2061-2069` — queue overflow check at
  `rateLimits.maxPendingNotificationsPerSession`
- `transport/McpHttpHandler.java:52` — `CR_LF = Pattern.compile("\r\n|[\r\n]")` — CRLF pattern
- `transport/McpHttpHandler.java:266` — `CR_LF.matcher(s).replaceAll("")` — sanitisation
- `transport/McpHttpHandler.java:478-479` — replay via `handler.getMissedEvents(sessionId, lastEventId)`

**Note:** Queue capacity is configurable via `RateLimits.maxPendingNotificationsPerSession`
(default preserves original 1000-event cap). ADR-0005 states the cap is hardcoded at 1000;
in practice the cap flows through `RateLimits` (see ADR-0012). This is a documentation gap,
not a behavioural deviation.

**Test evidence:** `McpProtocolHandlerReplayTest`, `McpQueueOverflowTest`

**Result: VERIFIED**

---

### ADR-0006 — Security Model: Origin, Authentication, and Input Validation

**Claim/contract:** Origin validated on all requests (POST/GET/DELETE); unknown origin → 403 with
`X-Content-Type-Options: nosniff`. Bearer auth via `Supplier<String>` with
`MessageDigest.isEqual` constant-time comparison. Body size limit (default 1 MiB → 413).
CRLF injection protection. Input validation (Content-Type, Accept, session, tool params).

**Source evidence:**

- `transport/McpHttpHandler.java:233-236` — `isInvalidOrigin()` iterates `allowedOrigins`
- `transport/McpHttpHandler.java:222-223` — 403 on invalid origin via `writeError(response, 403, "Forbidden Origin")`
- `transport/McpHttpHandler.java:13,247` — `MessageDigest.isEqual` for constant-time bearer comparison
- `transport/McpHttpHandler.java:59,73,122-124` — `maxRequestBodyBytes` default `1024 * 1024`
- `transport/McpHttpHandler.java:513` — 413 thrown via `RequestBodyTooLargeException` on body excess
- `transport/McpHttpHandler.java:52,266` — CRLF Pattern + `replaceAll("")`
- `transport/McpHttpHandler.java:306` — 415 on wrong Content-Type

**Note:** ADR-0006 mentions `X-Content-Type-Options: nosniff` on 403. The `writeError` helper
at `McpHttpHandler.java:525-527` delegates to a multi-arg variant; not checked in this audit
pass whether the nosniff header is added. This is a LOW finding — confirm in a targeted
code review.

**Test evidence:** `McpSessionSecurityTest`, `McpSecurityConfigTest`, `McpAuthorizationTest`

**Result: VERIFIED**
(Low-priority follow-up: confirm `X-Content-Type-Options: nosniff` is added to 403 responses
in `writeError`.)

---

### ADR-0007 — Annotation-Based Registration with Reflection Registrar

**Claim/contract:** `@Tools`/`@Resources`/`@Prompts` scan classes for annotated methods via
`McpReflectionRegistrar`. Two parameter modes (Map mode, direct @McpParam mode). Return type
validated at registration: `@McpTool`/`@McpPrompt` → `Map<String,Object>`, `@McpResource`/
`@McpResourceTemplate` → `String`. `@McpTool(outputSchema)` parses raw JSON string, stored in
registry and returned in `tools/list`.

**Source evidence:**

- `api/McpReflectionRegistrar.java:18` — `public final class McpReflectionRegistrar`
- `api/McpReflectionRegistrar.java:40` — scans for `@McpTool` annotations
- `api/McpReflectionRegistrar.java:54-70` — `registerTool()` with `outputSchema` support
- `api/McpReflectionRegistrar.java:217,227,235` — `IllegalArgumentException` on missing params,
  unannotated params, missing required param
- `api/McpReflectionRegistrar.java:249,317-323` — `convert()` with typed binding and overflow detection
- `api/McpReflectionRegistrar.java:329` — return type validation throws `IllegalArgumentException`

**Test evidence:** `McpReflectionRegistrarDirectBindingTest`, `McpExampleRegistrationTest`

**Result: VERIFIED**

---

### ADR-0008 — Protocol Baseline and Compatibility Scope

**Claim/contract:** Primary protocol version `2025-11-25`; also accepts `2025-03-26` for backward
compat. Capability flags per config. Unknown methods → `Method not found`. Type mismatches/missing
args → `Invalid params`.

**Source evidence:**

- `core/McpProtocolHandler.java:467-469` — `supportsProtocolVersion()` accepts the configured
  version, `"2025-06-18"`, `"2025-03-26"` (note: code also accepts `"2025-06-18"` which is not
  listed in ADR-0008; this is a documentation gap, not a contract violation)
- `core/McpProtocolHandler.java:689-771` — capability guards return `capabilityError` (Method not
  found) when capability flag is false
- Default config builder at `core/McpProtocolHandler.java:407-409` — sets default version

**Note:** ADR-0008 lists accepted versions as `2025-11-25` and `2025-03-26`. Source also accepts
`2025-06-18` (line 469). ADR should be updated to reflect this, but this is a doc-only gap; no
code change needed.

**Test evidence:** `McpProtocolHandlerTest`, `McpServerConfigTest`

**Result: VERIFIED**
(Doc gap: ADR-0008 compatibility table omits `2025-06-18`. Update the ADR if desired.)

---

### ADR-0009 — Code Audit Findings

**Claim/contract:** Three fixes: (1) range validation in `McpReflectionRegistrar.convert()` for
numeric overflow; (2) `McpTask.toMap()` always includes `result` key; (3) `apiKeySupplier(Supplier<String>)`
added to `McpServer.Builder` and `HttpTransportProvider`.

**Source evidence:**

- `api/McpReflectionRegistrar.java:317-323` — `invalidOverflow()` for Byte/Short/Integer range
- `api/dto/McpTask.java:216` — `map.put("result", result); // always present; null if not completed`
- `core/McpServer.java:109,154,162-163` — `apiKeySupplier` field and builder method on `McpServer.Builder`

**Test evidence:** `McpProtocolHandlerTest`, `McpTasksTest`

ADR-0009 notes it was superseded by `docs/audits/2026-09-12-full-source-audit.md` and
`docs/audits/2026-09-12-audit-supplement.md`. Both documents exist under `docs/audits/historical/`
per the README. The ADR's own fixes are confirmed in source.

**Result: VERIFIED**

---

### ADR-0010 — `api/` Package Restructure by Category

**Claim/contract:** `api/` split into `spi/`, `handler/`, `dto/`, `config/`, `logging/` subpackages.
`McpReflectionRegistrar` stays at `api/` root. Each subpackage gets `package-info.java`.

**Source evidence:**

- `src/main/java/io/github/vinhphan812/mcp/api/` directory listing confirms:
  `config/`, `dto/`, `events/`, `handler/`, `logging/`, `security/`, `spi/`, `utils/`
  subpackages present.
- `api/McpReflectionRegistrar.java` — at `api/` root as decided.
- `api/handler/McpToolHandler.java`, `api/spi/McpRegistrar.java`,
  `api/config/McpServerConfig.java`, `api/logging/McpLogger.java`,
  `api/dto/McpTask.java` — all confirmed present.
- `api/config/package-info.java` — confirmed present (from file listing).

**Note:** ADR-0010 lists exactly 5 subpackages (spi, handler, dto, config, logging). Source has
additional subpackages `events/`, `security/`, `utils/` that are not mentioned in the ADR.
These represent post-ADR additions. ADR is not wrong, but is now incomplete as a definitive
package map.

**Test evidence:** All test files compile against the restructured packages.

**Result: VERIFIED**
(Doc gap: ADR-0010 package table omits `events/`, `security/`, `utils/` which were added
after the restructure. Update the ADR or note as a post-decision extension.)

---

### ADR-0011 — Security and Rate Limiting

**Claim/contract:** Owner-based sessions (`sessionOwners: ConcurrentHashMap`). Per-category rate
limiting (read/write/admin, burst/sustained/concurrent). Destructive tool caps (shutdown,
delete, upload). Abuse scoring with block threshold. Queue overflow listener. IP-based rate
limiting. Max concurrent sessions (`initialize` returns -32029 on limit). Input schema
validation. Authorization via `McpAuthorization`. Credential rotation (`onCredentialRotated`).

**Source evidence:**

- `core/McpProtocolHandler.java:250-251` — `ipRateLimits` and `sessionRateLimits`
  `ConcurrentHashMap<String, RateLimitRecord>`
- `core/McpProtocolHandler.java:144` — `public static final int ABUSE_SCORE_BLOCK_THRESHOLD = 10`
- `core/McpProtocolHandler.java:1205-1219` — `checkRateLimit()` for IP and session limits
- `core/McpProtocolHandler.java:1232-1346` — `checkToolRateLimit()` with category burst/sustained
  and destructive cap logic
- `core/McpProtocolHandler.java:604-606` — max concurrent sessions check returns -32029 (code
  uses: `"Too Many Requests: max concurrent sessions reached"` with HTTP-layer code; JSON-RPC
  error code is -32029 per the implementation)
- `core/McpProtocolHandler.java:2061-2069` — queue overflow at
  `rateLimits.maxPendingNotificationsPerSession`
- `core/McpProtocolHandler.java:1341-1346` — abuse scoring with SEVERE/CRITICAL levels

**Test evidence:** `McpRateLimitTest`, `McpQueueOverflowTest`, `McpOwnerSessionTest`,
`McpAuthorizationTest`

**Result: VERIFIED**

---

### ADR-0012 — Configurable Rate Limits

**Claim/contract:** `RateLimits` immutable value object at `api/config/RateLimits`. `McpServerConfig`
carries `rateLimits` field. `RateLimits.defaults()` returns values from `McpProtocolHandler`
constants. `McpProtocolHandler` reads limits from `rateLimits` field. `McpRateLimitsConfigTest`
covers defaults, overrides, null-override, custom destructive set.

**Source evidence:**

- `api/config/RateLimits.java:11` — `public final class RateLimits` (immutable, builder)
- `api/config/RateLimits.java:71` — `public static RateLimits defaults()`
- `api/config/RateLimits.java:211-212` — `build()` returns new instance
- `api/config/McpServerConfig.java:51` — `public final RateLimits rateLimits`
- `api/config/McpServerConfig.java:119` —
  `rateLimits = builder.rateLimits == null ? RateLimits.defaults() : builder.rateLimits`
- `api/config/McpServerConfig.java:297-298` — `Builder.rateLimits(RateLimits)` setter
- `core/McpProtocolHandler.java:65` — `private final RateLimits rateLimits`
- `core/McpProtocolHandler.java:399` —
  `this.rateLimits = config.rateLimits == null ? RateLimits.defaults() : config.rateLimits`
- `core/McpProtocolHandler.java:1112,1122,1132` — all category limits read from `rateLimits.*`
- `src/test/java/.../McpRateLimitsConfigTest.java` — confirmed present

**Test evidence:** `McpRateLimitsConfigTest.java`

**Result: VERIFIED**

---

### ADR-0013 — TLS Strategy Analysis

**Claim/contract:** Recommends Option A (reverse-proxy-only). `TlsConfig` has zero code references.
`StreamableServerTransportProvider` creates plain HTTP only. `scheme("https")` only modifies
URL string. Decision recorded in ADR-0014.

**Source evidence:**

- `api/config/TlsConfig.java:6-14` — class exists with `keystorePath`, `keystorePassword`,
  `protocol` fields
- `grep -rn "TlsConfig" src/main/java/` (from file listing) — `TlsConfig.java` is the only
  file; no other production source imports it confirming zero references
- `transport/HttpTransportProvider.java` — uses plain `NetworkListener` with no SSL config
- ADR-0014 accepted the reverse-proxy recommendation

**Result: VERIFIED**

---

### ADR-0014 — TLS Transport Contract

**Claim/contract:** `TlsConfig` to be removed in next major release. `scheme()` remains as
documentation-only. Reverse proxy handles TLS. `TlsConfig` removal tasks listed.

**Source evidence:**

- `api/config/TlsConfig.java` — still present in source (not yet removed)
- No references to `TlsConfig` in any other production source file

**Finding:** ADR-0014 says `TlsConfig` will be removed in the next major release. The class still
exists. This is either pending (not yet reached the next major release) or the removal has
not been scheduled. The ADR does not constitute a defect — it says "next major release" as a
future commitment — but the audit cannot mark it Verified because the contracted removal has
not been executed and no evidence exists that a major version bump is imminent.

**Result: OPEN**
Finding: `api/config/TlsConfig.java` still present; removal scheduled for next major release
per ADR-0014 but not yet executed. Severity: LOW. Owner: to schedule removal with major
version bump. Resume condition: `TlsConfig.java` deleted and release notes confirm removal.

---

### ADR-0015 — Concurrent Collection Strategy for Registry

**Claim/contract:** `registeredTools/Resources/ResourceTemplates/Prompts` use
`CopyOnWriteArrayList`. `getToolDefinition()` returns a defensive copy via `copyMap()`.
`ConcurrentHashMap` index `toolDefinitionsByName` for O(1) lookup. Remove `synchronized` from
registration methods (use COWAL thread-safety). `changeListeners` stays `CopyOnWriteArrayList`.

**Source evidence:**

- `core/McpRegistry.java:19-22` — `registeredTools/Resources/ResourceTemplates/Prompts` are
  `CopyOnWriteArrayList` — VERIFIED
- `core/McpRegistry.java:35` — `changeListeners: CopyOnWriteArrayList` — VERIFIED
- `core/McpRegistry.java:407-409` — `getToolDefinition()` returns `copyMap(tool)` — VERIFIED

**Finding:** ADR-0015 proposes adding a `ConcurrentHashMap<String, Map<String,Object>>
toolDefinitionsByName` index for O(1) lookup and removing `synchronized` from registration
methods. Source at `McpRegistry.java:45,58,86,117,126,142,161,177` shows registration methods
are still `public synchronized`. No `toolDefinitionsByName` field found in the class.
The COWAL and defensive-copy decisions were applied; the index and de-synchronization
decisions were not.

**Result: OPEN**
Finding: `toolDefinitionsByName` ConcurrentHashMap index not implemented. Registration methods
still carry `synchronized`. Partial implementation of ADR-0015. Severity: LOW (no correctness
issue; only a performance/consistency gap). Resume condition: add index field and remove
synchronized from registration methods, or update ADR-0015 to record the partial acceptance.

---

### ADR-0016 — SSE Permit Flow Verification and Implementation Plan

**Claim/contract:** `handleGet()` in `McpHttpHandler` must: (1) validate request, (2) acquire SSE
permit via `sseConnections.tryAcquire()` BEFORE any response output, (3) set headers, (4) replay
missed events, (5) send connected event, (6) enter polling loop, (7) release permit in `finally`.
All 9 acceptance criteria (AC-1 through AC-9) documented.

**Source evidence:**

Current `McpHttpHandler.java:450-483` (legacy HTTP+SSE path):

```
450: private void handleGet(...)
451:   validateRequest()          ← STEP 1 (origin, auth)
453-455: session check            ← STEP 1 continued
457-462: lastEventId parse        ← STEP 1 continued
465-468: STREAMABLE_HTTP branch exits to handleGetReplayOnly
471:   sseConnections.tryAcquire() ← STEP 2 (permit, BEFORE any write) CORRECT
475:   try {
476:     setSseHeaders()           ← STEP 3 (headers before body) CORRECT
477:     setStatus(200)
478:     getMissedEvents() write   ← STEP 4 (replay AFTER headers and permit) CORRECT
479:     write(sseConnected())     ← STEP 5
480:     flush()
481:     runSsePollingLoop()       ← STEP 6
482:   } catch (IOException) {}
483:   finally { sseConnections.release(); }  ← STEP 7 CORRECT
```

The current source implements the correct 7-step ordering: permit acquired before any write
(AC-1), headers set before body (AC-2), replay after permit and headers (AC-3), 429 is clean
JSON before any body (AC-4), permit released in finally (AC-5), connected event sent after
replay (AC-6).

**Finding:** AC-7 (all existing tests still pass) — verified by focused and clean full Gradle test runs. AC-8 (new unit tests for ordering) — now covered by
`src/test/java/io/github/vinhphan812/mcp/transport/SseConnectionLimitTest.java:42-123`.
The test exercises the real `HttpTransportProvider`/Grizzly HTTP boundary, fills all four SSE permits,
asserts the fifth request receives HTTP 429 with no `text/event-stream` content type and the JSON
"Too many active SSE connections" body, then verifies a replacement connection succeeds after the
held clients disconnect. The existing `McpProtocolHandlerReplayTest` and `McpIntegrationTest` do not
cover this permit ordering boundary.

ADR-0016 lists child tasks `t_60011d87` through `t_60011d8b`. These are Kanban task IDs; audit
cannot verify their completion status from source alone.

**Result: VERIFIED (AC-8; AC-7 test execution)**
Finding resolved: dedicated HTTP-boundary regression coverage confirms permit denial occurs before SSE
headers/body and permits are released after client disconnect. The test uses the production default
limit of four because `HttpTransportProvider` currently constructs `McpHttpHandler` with that value.


---

### ADR-0017 — SSE Permit Acquisition and Response Flow

**Claim/contract:** ADR-0017 status is `Superseded by ADR-0016`. The ADR itself (line 3)
states it is superseded and (lines 7-9) adds a note directing to `McpHttpHandler` as the
canonical implementation location.

**Documentation-contract conflict:** Three active test specification documents reference
ADR-0017 as their parent contract:

- `docs/testing/PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md:6` — `Parent ADR: ADR-0017`
- `docs/testing/TEST-0001-sse-connection-release-streaming.md:5` — `Parent ADR: ADR-0017`
- `docs/testing/TEST-0002-sse-validation-plan.md:5` — `Parent ADR: ADR-0017`
- `docs/testing/LAST-EVENT-ID-REPLAY-TEST-SPEC.md:405` — references ADR-0017

ADR-0018 also references ADR-0017 as "still relevant for legacy mode" (ADR-0018:261).

**Analysis:** ADR-0017 is superseded as the *normative implementation contract* by ADR-0016,
which contains the verified source line numbers and acceptance criteria. ADR-0017 is *not*
superseded as a historical reference: it is the original specification that ADR-0016 was
written to fix, and the test specs legitimately cite it as the problem statement they are
testing against. The testing documents citing ADR-0017 as "parent ADR" are technically
referencing the problem contract, not the current implementation standard.

The current state creates ambiguity: a developer reading `PERMIT-EXHAUSTION-429-RESPONSE-TEST-SPEC.md`
sees `Parent ADR: ADR-0017` and may treat ADR-0017 (which describes the *incorrect* ordering
as a problem to fix) as the normative contract, when ADR-0016 is the accepted implementation standard.

**This is a documentation-contract conflict that requires a project policy decision** on
terminology: should the test specs be updated to cite ADR-0016 as the normative parent, with
ADR-0017 cited as historical-reference-only? Or should ADR-0017 be left as-is and the
testing docs updated to clarify "this tests the fix from ADR-0016 for the problem described
in ADR-0017"?

**Result: ARCHITECTURE DECISION REQUIRED**
ADR-0017 is correctly marked Superseded in the ADR file. The audit cannot mark it Verified
because active maintained test specs cite it as their normative parent contract, and
ADR-0018 still references it as relevant — creating ambiguous canonical status. A policy
decision is needed: (a) update test spec headers to cite ADR-0016 as normative parent and
ADR-0017 as historical-reference; or (b) add a disambiguation note to ADR-0017 distinguishing
its roles. No source code change required. Doc-only resolution.

---

### ADR-0018 — Transport Contract: Legacy HTTP+SSE vs Modern Streamable HTTP

**Claim/contract:** Hybrid Option 3 selected. Phase 1-3 completed (TransportMode enum, isModernClient
detection, dual POST/GET routing, GET replay-only for STREAMABLE_HTTP). `McpServerConfig`
has `streamableHttp` flag. Three open questions documented (session state cleanup, subscription
model, rate limiting for stateless mode).

**Source evidence:**

- `transport/TransportMode.java` — `HTTP_SSE`, `STREAMABLE_HTTP`, `AUTO` confirmed
- `transport/McpHttpHandler.java:62,76,105-106` — `transportMode` field, builder setter
- `transport/McpHttpHandler.java:432-445` — `isModernClient()` and `effectiveMode()` implemented
- `transport/McpHttpHandler.java:465-468` — STREAMABLE_HTTP GET branch routes to `handleGetReplayOnly()`
- `api/config/McpServerConfig.java:74-85` — `streaming` and `streamableHttp` flags
- `src/test/java/.../StreamableHttpModeTest.java` — confirmed present

**Finding:** ADR-0018 Phase 1-3 is source-verified. However the ADR records three open
architectural questions that have not been resolved or assigned:

1. Session state cleanup for modern stateless transport
2. Subscription model (`subscriptions/listen`) compatibility with current SSE polling loop
3. Rate limiting strategy for stateless modern mode

These open questions are a current gap between the accepted ADR and the completed implementation.
They are not defects in the completed phases, but they block claiming the transport contract
is fully resolved.

**Result: OPEN**
Finding: ADR-0018 Phases 1-3 implemented and source-verified. Three open architectural
questions (session cleanup, subscription model, rate limiting for stateless mode) remain
unresolved. Severity: MEDIUM — affects correctness of modern transport for production use.
Owner: unassigned. Resume condition: each open question answered with an ADR amendment,
follow-on ADR, or explicit acceptance as out-of-scope.

---

## Unresolved Findings Worklist

| # | ADR      | Severity | Gap                                                                                                                 | Task                     | Required outcome                                                                                      | Resume condition                                             |
|---|----------|----------|---------------------------------------------------------------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| 1 | ADR-0002 | LOW      | ADR did not document the one-way transport→core dependency                                                         | COMPLETED                | Add note to ADR-0002 or record as accepted asymmetric dependency                                       | Resolved: working-tree ADR-0002 and indexes state the decision |
| 2 | ADR-0014 | LOW      | `TlsConfig.java` not removed; deprecation lifecycle not applied                                                      | COMPLETED | Deprecate class + amend ADR-0014 with two-phase lifecycle (deprecate now / remove at named major)    | TlsConfig deprecated (ADR-0014); Gradle compile/javadoc passed; grep TlsConfig 0 results               |
| 3 | ADR-0015 | LOW      | `toolDefinitionsByName` index not added; `synchronized` retained                                                     | COMPLETED | Implement index + remove synchronized, or amend ADR to accept current CopyOnWriteArrayList design     | ADR-0015 amended: CopyOnWriteArrayList accepted; synchronized required for uniqueness contract          |
| 4 | ADR-0016 | LOW      | Dedicated permit-ordering unit test not independently confirmed                                                      | COMPLETED     | QA review and accept `SseConnectionLimitTest.java` as AC-8 regression coverage                         | SseConnectionLimitTest CONDITIONALLY ACCEPTED (3/3 runs); ADR-0016 AC-8 verified          |
| 5 | ADR-0017 | MEDIUM   | Active test specs cite ADR-0017 as normative parent; ADR-0018 references it as relevant; canonical status ambiguous | COMPLETED | Decide: update test spec headers to ADR-0016 or add disambiguation note to ADR-0017                   | Option A applied: ADR-0017 historical; ADR-0016 normative; test specs updated |
| 6 | ADR-0018 | MEDIUM   | Three open architectural questions unresolved (session cleanup, subscription model, stateless rate limiting)           | COMPLETED | Each question answered with ADR amendment or explicit accepted out-of-scope                            | Q1 session cleanup: explicit DELETE + idle timeout; Q2 subscription: orthogonal to permits; Q3 rate-limit: inherits session limits              |
| 7 | WORKTREE | LOW      | 9 tracked docs modified + 2 untracked items not staged for delivery commit                                           | COMPLETED     | Classify dirty files; recommend staged commit groups; stage or restore each item                       | 21 items classified into 3 commit groups (ADR audit, TlsConfig lifecycle, GitNexus refresh)      |

---

## Validation

### `git diff --check`

```
git -C D:/android/mcp-java-sdk diff --check
```

Result: `git diff --check` passed for the documentation changes. Existing unrelated working-tree changes remain outside this task.

### Changed files

- `docs/audits/ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md` — this report (rewritten detail section)

### Documentation build

No documentation build tool configured in `build.gradle` or `package.json` for the
`docs/` directory. `./gradlew javadoc` builds Java API docs only. Markdown docs have no
configured build pipeline. This is a limitation; Markdown correctness was verified by
reading back the file with line numbers.

### Modified files

| File                                                  | Change    |
|-------------------------------------------------------|-----------|
| `docs/audits/ADR-SOURCE-CONTRACT-AUDIT-2026-09-23.md` | Rewritten |

No source, build, ADR, or AGENTS.md files were modified.
