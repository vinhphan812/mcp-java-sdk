# Java-Native MCP Conformance Alternatives

**Task:** t_04340916 — Identify Java-only conformance alternatives
**Parent:** t_6092eae7 (Node.js dependency confirmed: `@modelcontextprotocol/conformance` is Node.js-only, no viable alternative in the official suite)
**Status:** Complete
**Date:** 2026-10-05
**Workspace:** `D:\android\mcp-java-sdk\.worktrees\t_04340916`

---

## Executive Summary

The official MCP conformance suite (`@modelcontextprotocol/conformance`) is **Node.js-only** with no non-Node.js entry point. This task evaluated four Java-native alternatives. **Verdict: a custom JUnit 5 suite using the existing live-server testing pattern is the most viable approach.** A third-party `mcp-java-testkit` library also exists but requires evaluation before adoption. `MockWebServer` and `WireMock` are **not recommended** as primary tools because the MCP server's SSE streaming and session lifecycle are not easily mocked at the HTTP-layer.

---

## 1. Existing Java MCP Test Harnesses

### 1.1 `senor14/mcp-java-testkit` (GitHub, open source)

**URL:** https://github.com/senor14/mcp-java-testkit

**Description:** JUnit 5 extension for in-process MCP server conformance and contract testing on the JVM. SDK-independent; speaks the wire protocol directly.

**Key capabilities:**
- `@McpServerTest` JUnit 5 extension annotation — spins up server, injects test client
- Fluent assertions: `McpAssertions.assertThat(client).initializesSuccessfully().hasTools().toolSchemasAreValid()`
- 26 assertions across initialize handshake, capabilities, tools, resources, prompts, error paths
- Snapshot regression testing: `McpSnapshot.matches("tools", client.listTools())`
- Token-budget CI gates: fail build when tool list exceeds N tokens
- Notification capture on stdio and Streamable HTTP (JSON + SSE + session)
- Spring Boot test support via `spring:` URL scheme (reflective, no Spring dependency in library)
- **2025-11-25 wire protocol only** (stateless 2026 not yet documented)

**Strengths:**
- JUnit 5 native; runs in `mvn test` / `gradlew test` without external tooling
- SDK-agnostic; tests any MCP server over stdio or HTTP
- Fluent assertions designed for MCP semantics (not generic HTTP assertions)
- License: open source (verify before adoption)

**Weaknesses:**
- 2025-11-25 only; no documented 2026-07-28 support (stateless wire)
- Newer project (verify maturity and maintenance)
- Adds a third-party dependency that must be evaluated for compatibility with Java 11 and project Guava/Gson stack
- No STDIO support needed for this project (HTTP-only), but the library bundles stdio support which adds complexity
- Snapshot testing may introduce fragility if tool schemas change frequently

**Recommendation:** Evaluate `mcp-java-testkit` as a supplement to the existing JUnit suite. Confirm 2026-07-28 coverage before adoption. Request a quick integration probe: add as `testImplementation` and run a single assertion to confirm compatibility with Java 11 and the current Grizzly transport.

**Gradle dependency pattern (if adopted):**
```groovy
testImplementation 'com.github.senor14:mcp-java-testkit:0.1.x'  // verify current version
```

---

## 2. JUnit-Based Conformance Test Patterns

### 2.1 Existing Pattern in This Project

The project already uses the **live-server integration test** pattern throughout its test suite. This is the most mature and appropriate pattern for this codebase.

**Pattern characteristics:**
1. Start `HttpTransportProvider` (Grizzly-backed) on ephemeral port (`port(0)`)
2. Use `HttpURLConnection` or raw socket to send real HTTP requests
3. Assert on HTTP status codes, response bodies, and headers
4. Tear down the server in `@AfterEach`

**Evidence from existing tests:**

**`McpCorsIntegrationTest.java` (376 lines)** — Uses raw sockets to bypass HttpURLConnection's Origin header filtering:
```java
try (Socket sock = new Socket()) {
    sock.connect(new InetSocketAddress(host, port), 5000);
    OutputStream out = sock.getOutputStream();
    out.write(req.toString().getBytes(StandardCharsets.UTF_8));
    // ... read status line
    return parseStatusCode(firstLine);
}
```

**`Mcp2026WireContractTest.java` (822 lines)** — Full wire contract validation using HttpURLConnection:
```java
HttpURLConnection conn = (HttpURLConnection) u.openConnection();
conn.setRequestProperty("Mcp-Session-Id", sessionId);
conn.setRequestProperty("Mcp-Protocol-Version", protocolVersion);
conn.setRequestProperty("Mcp-Method", rpcMethod);
```

**`StreamableHttpModeTest.java` (541 lines)** — SSE streaming + replay with live server.

**`McpCorsIntegrationTest.java` (376 lines)** — CORS policy enforcement with raw socket requests.

**Current test coverage (40+ test files):**

| Category | Coverage |
|---|---|
| JSON-RPC envelope validation | `McpProtocolHandlerTest`, `Mcp2026WireContractTest` |
| Initialize handshake | `Mcp2026WireContractTest` (2025 backward compat) |
| 2026 stateless wire | `Mcp2026WireContractTest` (server/discover, ping, no-session) |
| Tool registration/call | `McpExampleRegistrationTest`, `McpProtocolHandlerTest` |
| Resource/prompt handlers | `McpProtocolHandlerTest` |
| CORS origin enforcement | `McpCorsIntegrationTest`, `CorsOriginPolicyTest` |
| SSE streaming + replay | `McpProtocolHandlerReplayTest`, `StreamableHttpModeTest` |
| Session lifecycle | `McpOwnerSessionTest`, `McpSessionTimeoutTest` |
| Rate limiting | `McpRateLimitTest` |
| Authentication | `McpIntegrationTest`, `McpSecurityConfigTest` |
| Progress/cancellation | `McpProgressAndCancellationTest` |
| Tasks | `McpTasksTest` |
| Elicitation models | `ElicitationModelTest` |
| Connection limits | `SseConnectionLimitTest` |
| Protocol version negotiation | `Mcp2026WireContractTest` |

**Strengths of this pattern:**
- Tests the actual server as a black box — no mocking of internal state
- Reproduces real HTTP interactions exactly as a client would send them
- No additional test-only dependencies
- Tests run as part of `./gradlew test` with no external infrastructure
- Covers both happy path and error paths (malformed JSON, wrong session, etc.)

**Weaknesses:**
- No automatic protocol compliance certification — tests verify behavior, not spec compliance
- Test authorship requires understanding of MCP wire protocol
- SSE streaming tests require careful handling of connection lifecycle

**Recommendation:** Continue extending the existing JUnit live-server pattern. This is the recommended approach. Do not add a mocking layer on top of it.

---

## 3. HTTP Client Test Libraries (MockWebServer, WireMock)

### 3.1 MockWebServer (square/okhttp)

**Maven:** `com.squareup.okhttp3:mockwebserver` (part of OkHttp, also available standalone)

MockWebServer is an in-process HTTP server for testing HTTP clients. It records requests and emits canned responses.

**Applicability to this project:**

| Scenario | MockWebServer fit | Notes |
|---|---|---|
| Testing an MCP *client* library | **High** | Can mock MCP server responses |
| Testing an MCP *server* library | **Low** | Would mock the *client side*, not the server under test |
| Wire protocol validation | **Low** | Does not easily reproduce SSE streaming behavior |
| Session lifecycle testing | **Low** | MockWebServer does not track per-connection session state |

**Critical gap for this project:** This is a **server SDK**. The tests need to start a real MCP server and send it real HTTP requests. MockWebServer would mock the server, not the client — the inverse of what is needed.

Even if MockWebServer were used to mock a client, it would require simulating the MCP client wire protocol (JSON-RPC, SSE parsing, session handling), which is essentially recreating the MCP client. This is not practical.

**Not recommended** as a primary conformance testing tool for this server SDK.

### 3.2 WireMock

**Maven:** `com.github.tomakehurst:wiremock` (standalone) or `io.rest-assured:rest-assured` for higher-level assertions.

WireMock is a more sophisticated HTTP mocking tool with request matching, response stubbing, and stateful simulation.

**Same fundamental limitation as MockWebServer:** It mocks HTTP servers, but this project needs to test its own HTTP server from the outside. WireMock would be useful for testing MCP *client* code, not server code.

**Additional concern:** WireMock's stateful simulation is not designed for MCP session semantics. SSE streaming would require complex wiremock setup.

**Not recommended** as a primary conformance testing tool for this server SDK.

### 3.3 When to Use MockWebServer / WireMock

These tools ARE appropriate for:
- Testing the `McpClient` class (if added in the future)
- Integration testing of a client that uses the MCP SDK
- Simulating a remote MCP server in client-side tests

They are NOT appropriate for:
- Validating the MCP server wire protocol against the spec
- SSE streaming tests
- Session lifecycle validation

**Gradle pattern (for future client tests):**
```groovy
testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'
// OR
testImplementation 'com.github.tomakehurst:wiremock:3.0.1'
```

### 3.4 REST-assured

**Maven:** `io.rest-assured:rest-assured:5.4.0`

REST-assured provides a DSL for testing REST APIs. It is designed for testing HTTP *servers* from the client side — exactly what this project needs for HTTP transport tests.

**Potential use:** Replace raw socket / HttpURLConnection code in `McpCorsIntegrationTest` and other HTTP transport tests with a more readable DSL:

```java
given()
    .header("Origin", "http://localhost")
    .header("Mcp-Session-Id", sessionId)
    .header("Mcp-Protocol-Version", "2025-11-25")
    .contentType("application/json")
    .accept("application/json, text/event-stream")
    .body(initRequest())
.when()
    .post(url)
.then()
    .statusCode(200)
    .header("Mcp-Session-Id", notNullValue())
    .body("result.protocolVersion", equalTo("2025-11-25"));
```

**Strengths:**
- More readable than raw `HttpURLConnection` / socket code
- Built-in JSON path assertions
- Well-maintained, widely used

**Weaknesses:**
- Adds another test-only dependency
- The existing raw socket code in `McpCorsIntegrationTest` works correctly
- SSE streaming assertions are not REST-assured's strong suit
- Would require refactoring existing tests

**Recommendation:** Consider REST-assured for new HTTP transport tests, but do not retroactively refactor existing working tests. The raw socket approach in `McpCorsIntegrationTest` is already correct and well-tested.

---

## 4. Custom Minimal Conformance Suite — Viability Assessment

### 4.1 Is a Custom Suite Viable?

**YES, with conditions.** The existing test suite already demonstrates the viability of this approach. A "custom minimal suite" would formalize and extend what already exists.

**What already works:**
- `./gradlew test` passes with 338+ tests
- Live Grizzly server on ephemeral port (`port(0)`)
- Real HTTP requests (raw socket, HttpURLConnection)
- Full JSON-RPC wire contract validation (`Mcp2026WireContractTest`, 822 lines)
- SSE streaming and replay (`McpProtocolHandlerReplayTest`, 525 lines)
- CORS policy enforcement (`McpCorsIntegrationTest`, 376 lines)
- Session lifecycle (`McpOwnerSessionTest`, 214 lines)

**What a minimal conformance suite would add:**

A structured document mapping MCP spec requirements to JUnit test cases, organized by conformance scenario. This is already implicitly done in the existing test suite — formalizing it as a living spec document would provide:

1. A coverage matrix (spec requirement → test method)
2. Clear pass/fail criteria per scenario
3. A basis for future extensions as the spec evolves

### 4.2 Scenario-to-Test Mapping

Building on the HTTP contract documented in `docs/transport/HTTP-SERVER-CONFORMANCE.md`, the following mapping shows how existing tests cover the scored conformance scenarios:

| Conformance Scenario | 2025-11-25 | 2026-07-28 | Test Class(es) |
|---|---|---|---|
| `server-initialize` | ✅ | N/A (no init in stateless) | `Mcp2026WireContractTest` |
| `ping` | ✅ | ✅ | `Mcp2026WireContractTest` |
| `logging-set-level` | ✅ | N/A | `McpProtocolHandlerTest` |
| `completion-complete` | Partial | N/A | `McpCompletionProvider` |
| `tools-list` | ✅ | ✅ | `Mcp2026WireContractTest`, `McpProtocolHandlerTest` |
| `tools-call-*` | Partial | Partial | `McpProtocolHandlerTest`, `McpIntegrationTest` |
| `resources-list` | ✅ | ✅ | `Mcp2026WireContractTest` |
| `resources-read-*` | ✅ | ✅ | `McpProtocolHandlerTest` |
| `resources-subscribe` | ✅ | ❌ | `McpProtocolHandlerTest` |
| `prompts-list` | ✅ | ✅ | `Mcp2026WireContractTest` |
| `prompts-get-*` | ✅ | ✅ | `McpProtocolHandlerTest` |
| `server/discover` | ✅ | ✅ | `Mcp2026WireContractTest` |
| `server-sse-multiple-streams` | ✅ | N/A | `SseConnectionLimitTest`, `McpProtocolHandlerReplayTest` |
| `dns-rebinding-protection` | ✅ | N/A | `McpCorsIntegrationTest` |
| CORS origin enforcement | ✅ | ✅ | `McpCorsIntegrationTest` |
| `elicitation-sep1034-*` | N/A | Partial | `ElicitationModelTest` (models only) |
| `input-required-result-*` | N/A | Partial | Covered by elicitation model |

**Legend:**
- ✅ = Covered by existing tests
- Partial = Partially covered; gaps exist
- ❌ = Known gap (e.g., resources/subscribe in stateless 2026)
- N/A = Not applicable to this spec version

### 4.3 Recommended Custom Suite Scope

Do NOT build a custom suite that duplicates what already exists. Instead, formalize the existing coverage:

**Phase 1 (Documentation):** Create a conformance coverage matrix document
- Map each MCP spec requirement to an existing test method
- Identify gaps with specific test method stubs
- This is a docs task, not a code task

**Phase 2 (Gap Filling):** Write targeted JUnit tests for identified gaps
- Elicitation end-to-end (not just model validation)
- Sampling integration (not just stubs)
- 2026 stateless wire complete coverage

**Phase 3 (Automation):** Optional CI-friendly report
- Generate JUnit XML report from `./gradlew test`
- Parse to produce a conformance coverage summary
- No new tooling required — Gradle + JUnit already produce XML reports

### 4.4 Why Not MockWebServer/WireMock for a Custom Suite

The live-server pattern tests the complete HTTP stack:
- Grizzly HTTP server (actual transport layer)
- McpHttpHandler (actual routing)
- McpProtocolHandler (actual JSON-RPC handling)
- McpRegistry (actual tool/resource/prompt registration)

Mocking any of these layers would reduce confidence. The HTTP contract must be validated against a real server, not a mock.

---

## 5. Summary: Recommended Approach

| Alternative | Recommendation | Rationale |
|---|---|---|
| `senor14/mcp-java-testkit` | **Evaluate** (test adoption) | JUnit 5 native, SDK-agnostic, 2025-11-25 coverage confirmed, 2026-07-28 unconfirmed. Low risk probe: add dependency, run one test. |
| Existing JUnit live-server pattern | **Continue and extend** | Already proven, 338+ tests, `./gradlew test` passes. Most appropriate for this codebase. |
| MockWebServer | **Not recommended** | Wrong direction — mocks the server, but this project tests its own server. |
| WireMock | **Not recommended** | Same fundamental limitation as MockWebServer. Better for client SDK tests. |
| REST-assured | **Consider for new tests only** | More readable DSL than raw socket code; do not refactor working tests. |
| Custom minimal suite | **YES — Phase 1 docs, Phase 2 code** | Formalize existing coverage as a conformance matrix. Fill gaps with targeted JUnit tests. |

### Key Constraints

- **No Node.js / npm** in runtime, build, or CI (per `MCP-COMPATIBILITY-2026.md`)
- **HTTP/Streamable HTTP/SSE only** — STDIO intentionally unsupported
- **Java 11 compatibility** required (project `sourceCompatibility`)
- **No new runtime dependencies** — test-only dependencies acceptable
- **Gradle-based CI** — must run as part of `./gradlew test`

### Next Steps (for child task t_6ee0cf10 synthesis)

The decision memo should recommend:
1. **GO for continued JUnit live-server testing** — the existing suite is the most viable Java-only path
2. **Evaluate `mcp-java-testkit`** as a supplement, with a bounded probe card
3. **Formalize conformance coverage** as a Phase 1 docs task (conformance matrix)
4. **Fill targeted gaps** in Phase 2 (elicitation E2E, sampling integration, 2026 stateless complete)
5. **Do NOT plan conformance suite CI gates** against `@modelcontextprotocol/conformance` — confirmed infeasible per t_6092eae7

---

## References

- Parent task: `t_6092eae7` — Node.js dependency confirmed
- HTTP contract: `docs/transport/HTTP-SERVER-CONFORMANCE.md` (t_8608b906)
- Official suite: `@modelcontextprotocol/conformance` npm package
- Third-party kit: https://github.com/senor14/mcp-java-testkit
- Official java-sdk conformance: https://github.com/modelcontextprotocol/java-sdk/blob/main/conformance-tests/VALIDATION_RESULTS.md
- Existing test suite: `src/test/java/io/github/vinhphan812/mcp/` (40+ files, ~8,000 lines)
- Compatibility doc: `docs/architecture/MCP-COMPATIBILITY-2026.md`
