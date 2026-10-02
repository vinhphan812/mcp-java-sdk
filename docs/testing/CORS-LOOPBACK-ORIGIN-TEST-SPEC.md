# CORS Loopback-Origin — Integration Test Specification

**Task:**       t_ca84cb11
**Author:**     dev-qa
**Date:**       2026-09-30
**Status:**     Specification only — implementation follows
**Parent ADR:**  ADR-0021 (CORS loopback-origin policy)
**Test target:** `McpCorsOriginTest.java` (new), `McpCorsPreflightTest.java` (new)

---

## 1. Overview

This document defines the integration test matrix and fixture strategy for CORS /
Origin behaviour in the MCP Java SDK HTTP/SSE transport
(`HttpTransportProvider` + `McpHttpHandler`).

Tests are **black-box**: they drive real HTTP requests against a live embedded
Grizzly server and assert on status codes and response headers. No production
source is modified.

**Baseline evidence (pre-implementation, worktree wt/t_ca84cb11):**

| Behaviour                               | Source                                                             | Status               |
|-----------------------------------------|--------------------------------------------------------------------|----------------------|
| Absent `Origin` → allowed               | `McpHttpHandler:221-226` `isInvalidOrigin` returns `false`         | Confirmed            |
| Exact-match loopback origin accepted    | `McpHttpHandler:224` `a.equalsIgnoreCase(origin.trim())`           | Confirmed            |
| Port-bearing loopback origin rejected   | `McpHttpHandler:224` exact-match only; no wildcard                 | Confirmed            |
| OPTIONS returns 405 (no CORS arm)       | `McpHttpHandler:200-202` `default` arm → 405                       | Confirmed            |
| No CORS response headers emitted        | No `Access-Control-*` / `Vary` code anywhere in handler            | Confirmed            |
| Bearer auth checked after origin        | `McpHttpHandler:209-219` `isInvalidOrigin` before `isUnauthorized` | Confirmed            |
| Duplicate origin defaults in two places | `HttpTransportProvider:23` and `McpHttpHandler.Builder:59`         | Confirmed (ADR-0021) |

These are the gaps the implementation (`t_af3f88ab`) must close.

---

## 2. Test Fixture Strategy

### 2.1 Test class placement

```
src/test/java/io/github/vinhphan812/mcp/transport/
  McpCorsOriginTest.java       # Simple CORS + pre-implementation baseline
  McpCorsPreflightTest.java    # OPTIONS preflight matrix
```

Both classes live in the `transport` package alongside `SseConnectionLimitTest`
and follow the same `@BeforeEach`/`@AfterEach` server lifecycle pattern.

### 2.2 Shared request helper

Each test class defines a local `HttpResult` record and a private `doRequest`
method. The helper is intentionally minimal — no test-scoped HTTP client
framework is introduced. Copy-paste into each class is acceptable; a shared
base class is **not** required for two classes.

```java
// ── Shared HTTP result record ─────────────────────────────────────────────
private record HttpResult(int status, String body,
                          String sessionId,
                          Map<String, List<String>> headers) {}

private HttpResult doRequest(String method, String url,
                             String body,
                             String sessionId,
                             String token,
                             String origin,
                             String accessControlRequestMethod,
                             String accessControlRequestHeaders,
                             String accept) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod(method);
    conn.setDoOutput(body != null);
    if (origin        != null) conn.setRequestProperty("Origin", origin);
    if (accept        != null) conn.setRequestProperty("Accept", accept);
    if (sessionId     != null) conn.setRequestProperty("Mcp-Session-Id", sessionId);
    if (token        != null) conn.setRequestProperty("Authorization", "Bearer " + token);
    if (accessControlRequestMethod   != null)
        conn.setRequestProperty("Access-Control-Request-Method", accessControlRequestMethod);
    if (accessControlRequestHeaders  != null)
        conn.setRequestProperty("Access-Control-Request-Headers", accessControlRequestHeaders);
    if (contentType   != null) conn.setRequestProperty("Content-Type", contentType);
    if (body         != null) conn.getOutputStream().write(body.getBytes(UTF_8));

    int status = conn.getResponseCode();
    InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
    String respBody = in == null ? "" : new String(in.readAllBytes(), UTF_8);

    Map<String, List<String>> hdrs = conn.getHeaderFields();
    return new HttpResult(status, respBody,
                          conn.getHeaderField("Mcp-Session-Id"), hdrs);
}
```

**Constants** (static final in each class):

```java
private static final Charset UTF_8 = StandardCharsets.UTF_8;
private static final String ACCEPT_JSON_SSE = "application/json, text/event-stream";
private static final String CONTENT_JSON   = "application/json";
private static final String INIT_JSON =
    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
    "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}," +
    "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
private static final String PING_JSON =
    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
```

### 2.3 Server setup variants

The two test classes cover three server configurations:

| Configuration | Auth              | `allowedOrigins`                    | When used                  |
|---------------|-------------------|-------------------------------------|----------------------------|
| A             | none              | default (loopback wildcard)         | Baseline + preflight cases |
| B             | Bearer `"secret"` | default (loopback wildcard)         | Auth × CORS interaction    |
| C             | none              | `Set.of("https://app.example.com")` | Explicit allowlist         |

Configuration C maps to the `HttpTransportProvider` API; the
`McpServer.Builder.allowedOrigins(Set)` forwarding is tested separately in
a unit-test for `McpServer.Builder` (not in this spec — covered by ADR-0021
implementation handoff).

### 2.4 Initialization helper

Both classes share an `initializeSession()` helper that performs a POST
`initialize` and returns the `Mcp-Session-Id` header.

```java
private String initializeSession(HttpTransportProvider transport, String token) throws Exception {
    HttpResult r = doRequest("POST", transport.getUrl(), INIT_JSON,
                             null, token, null, null, null, null, CONTENT_JSON, ACCEPT_JSON_SSE);
    assertEquals(200, r.status, "initialize: " + r.body);
    assertNotNull(r.sessionId, "session missing: " + r.body);
    return r.sessionId;
}
```

`initializeSession` is duplicated in each class (no shared base).

---

## 3. Request Construction

### 3.1 HTTP methods

| Method         | Purpose                                                       |
|----------------|---------------------------------------------------------------|
| `POST /mcp`    | JSON-RPC request/response — primary CORS surface              |
| `GET /mcp`     | SSE streaming — needs `Mcp-Session-Id`; CORS headers applied  |
| `DELETE /mcp`  | Session teardown — CORS headers applied                       |
| `OPTIONS /mcp` | Preflight — no body, requires `Access-Control-Request-Method` |

### 3.2 Origin header variants

Tests use these Origin values as inputs:

| Label                     | Value                     | Policy classification                     |
|---------------------------|---------------------------|-------------------------------------------|
| `ORIGIN_LOCALHOST_PORT`   | `http://localhost:3000`   | Loopback wildcard — default policy        |
| `ORIGIN_127_PORT`         | `http://127.0.0.1:8080`   | Loopback wildcard — default policy        |
| `ORIGIN_IPV6_PORT`        | `http://[::1]:5173`       | Loopback wildcard — default policy        |
| `ORIGIN_HTTPS_LOCALHOST`  | `https://localhost:443`   | Loopback wildcard — default policy        |
| `ORIGIN_LOCALHOST_NOPORT` | `http://localhost`        | Loopback exact — default policy           |
| `ORIGIN_127_NOPORT`       | `http://127.0.0.1`        | Loopback exact — default policy           |
| `ORIGIN_EVIL`             | `http://evil.com`         | Non-loopback — rejected by default        |
| `ORIGIN_ALLOWLISTED`      | `https://app.example.com` | Explicit allowlist — accepted by config C |

### 3.3 Preflight request headers

For OPTIONS requests the following `Access-Control-Request-*` permutations are used:

| Header                           | Value                                         |
|----------------------------------|-----------------------------------------------|
| `Access-Control-Request-Method`  | `POST`                                        |
| `Access-Control-Request-Headers` | `Content-Type, Authorization, Mcp-Session-Id` |
| `Access-Control-Request-Headers` | `X-Forwarded-For`                             |

---

## 4. Assertion Strategy

### 4.1 Status assertions

Each test case declares an expected HTTP status as the primary signal.

### 4.2 CORS header assertions

Helper methods on the `HttpResult` record reduce repetition:

```java
// Null-safe single-value header lookup
private String header(HttpResult r, String name) {
    var vals = r.headers().get(name);
    return (vals == null || vals.isEmpty()) ? null : vals.get(0);
}

private void assertCORSAllowedOrigin(HttpResult r, String expectedOrigin) {
    assertEquals(expectedOrigin, header(r, "Access-Control-Allow-Origin"),
        "ACAO must reflect validated origin");
}

private void assertCORSNoAllowOrigin(HttpResult r) {
    assertNull(header(r, "Access-Control-Allow-Origin"),
        "Disallowed origin must not emit ACAO");
}

private void assertVaryOrigin(HttpResult r) {
    assertEquals("Origin", header(r, "Vary"),
        "Vary: Origin is required to prevent cross-origin cache poisoning");
}

private void assertNoVaryOrigin(HttpResult r) {
    // When no Origin header in request, Vary: Origin is optional
    // (no CORS contract to vary on)
}

private void assertACACredentialsTrue(HttpResult r) {
    assertEquals("true", header(r, "Access-Control-Allow-Credentials"),
        "ACAC required when auth is configured and origin is allowed");
}

private void assertPreflightMethods(HttpResult r) {
    assertEquals("POST, GET, DELETE, OPTIONS",
        header(r, "Access-Control-Allow-Methods"));
}

private void assertPreflightHeaders(HttpResult r) {
    String h = header(r, "Access-Control-Allow-Headers");
    assertNotNull(h, "ACAH required on preflight");
    assertTrue(h.toLowerCase().contains("content-type"), "ACAH must list Content-Type");
    assertTrue(h.toLowerCase().contains("authorization"), "ACAH must list Authorization");
    assertTrue(h.toLowerCase().contains("mcp-session-id"), "ACAH must list Mcp-Session-Id");
}

private void assertPreflightMaxAge(HttpResult r) {
    assertEquals("86400", header(r, "Access-Control-Max-Age"),
        "ACMAXAge must be 86400 (24 hours)");
}
```

### 4.3 Auth assertions (bearer token independence)

For tests exercising auth × CORS interaction:

```java
// Accepted origin with invalid bearer → 401 (NOT 403)
assertEquals(401, r.status, "Origin gate passed; auth gate must fail with 401");
assertNotNull(header(r, "Access-Control-Allow-Origin"),
    "401 on bad token must still emit ACAO for the browser to read");

// Disallowed origin with valid bearer → 403 (NOT 401)
assertEquals(403, r.status, "Origin gate must reject before auth is evaluated");
```

---

## 5. IPv6 Portable CI Strategy

**Problem.** CI runners (GitHub Actions `ubuntu-latest`, `windows-latest`) may not
reliably bind to `::1` if IPv6 is disabled or the loopback interface is not
configured.

**Strategy: `@DisabledIf` with runtime detection.**

```java
import org.junit.jupiter.api.condition.EnabledIf;

@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@EnabledIf("hasReachableIPv6Loopback")
@interface SkipIfNoIPv6 {}

static boolean hasReachableIPv6Loopback() {
    try (var sock = new ServerSocket()) {
        sock.bind(new InetSocketAddress("::1", 0));
        return true;
    } catch (IOException e) {
        return false;
    }
}
```

Apply `@SkipIfNoIPv6` only to the IPv6 test cases (`ORIGIN_IPV6_PORT`).
All other tests run unconditionally.

The detection is performed once per class via a `static` field cached at load time
so it does not affect test execution time.

---

## 6. Test Case Matrix

### 6.1 `McpCorsOriginTest` — Simple CORS (no preflight)

| #  | Description                                          | Config | Method | Origin                    | Auth | Expected status | ACAO                      | ACAC   | Vary     |
|----|------------------------------------------------------|--------|--------|---------------------------|------|-----------------|---------------------------|--------|----------|
| 1  | Loopback port wildcard — `http://localhost:3000`     | A      | POST   | `http://localhost:3000`   | none | 200             | `http://localhost:3000`   | absent | `Origin` |
| 2  | Loopback port wildcard — `http://127.0.0.1:8080`     | A      | POST   | `http://127.0.0.1:8080`   | none | 200             | `http://127.0.0.1:8080`   | absent | `Origin` |
| 3  | Loopback port wildcard — IPv6 `http://[::1]:5173`    | A      | POST   | `http://[::1]:5173`       | none | 200             | `http://[::1]:5173`       | absent | `Origin` |
| 4  | Loopback port wildcard — `https://localhost:443`     | A      | POST   | `https://localhost:443`   | none | 200             | `https://localhost:443`   | absent | `Origin` |
| 5  | Loopback exact — `http://localhost` (no port)        | A      | POST   | `http://localhost`        | none | 200             | `http://localhost`        | absent | `Origin` |
| 6  | No Origin header (non-browser client)                | A      | POST   | absent                    | none | 200             | absent                    | absent | absent   |
| 7  | Non-loopback — `http://evil.com`                     | A      | POST   | `http://evil.com`         | none | 403             | absent                    | absent | absent   |
| 8  | Explicit allowlist — `https://app.example.com`       | C      | POST   | `https://app.example.com` | none | 200             | `https://app.example.com` | absent | `Origin` |
| 9  | Allowlist not configured — `https://app.example.com` | A      | POST   | `https://app.example.com` | none | 403             | absent                    | absent | absent   |
| 10 | Empty allowlist `Set.of()` — `http://localhost:3000` | D      | POST   | `http://localhost:3000`   | none | 403             | absent                    | absent | absent   |

Config D (empty allowlist — no origins accepted) is set via:
`new HttpTransportProvider(handler).allowedOrigins(Set.of())`.

**Auth × CORS interaction** (config B — Bearer `"secret"`):

| #  | Description                     | Origin                  | Bearer   | Expected status | ACAO                    | ACAC   |
|----|---------------------------------|-------------------------|----------|-----------------|-------------------------|--------|
| 11 | Allowed origin + valid token    | `http://localhost:3000` | `secret` | 200             | `http://localhost:3000` | `true` |
| 12 | Allowed origin + invalid token  | `http://localhost:3000` | `wrong`  | 401             | `http://localhost:3000` | `true` |
| 13 | Disallowed origin + valid token | `http://evil.com`       | `secret` | 403             | absent                  | absent |
| 14 | No Origin + valid token         | absent                  | `secret` | 200             | absent                  | absent |

**Additional method coverage** (config A):

| #  | Description                   | Method | Origin                  | Expected status     | ACAO                    | Notes            |
|----|-------------------------------|--------|-------------------------|---------------------|-------------------------|------------------|
| 15 | GET with allowed origin       | GET    | `http://localhost:3000` | 200 (needs session) | `http://localhost:3000` | Initialize first |
| 16 | DELETE with allowed origin    | DELETE | `http://localhost:3000` | 204 (needs session) | `http://localhost:3000` | Initialize first |
| 17 | GET with disallowed origin    | GET    | `http://evil.com`       | 403                 | absent                  | No session check |
| 18 | DELETE with disallowed origin | DELETE | `http://evil.com`       | 403                 | absent                  | No session check |

### 6.2 `McpCorsPreflightTest` — OPTIONS preflight

All tests use `OPTIONS /mcp` with `Access-Control-Request-Method: POST`.

| #  | Description                               | Config | Origin                    | Expected status | ACAO                      | ACAC   | ACAM                         | ACAH    | ACMA   |
|----|-------------------------------------------|--------|---------------------------|-----------------|---------------------------|--------|------------------------------|---------|--------|
| 19 | Preflight — allowed origin, no auth       | A      | `http://localhost:3000`   | 200             | `http://localhost:3000`   | absent | `POST, GET, DELETE, OPTIONS` | present | 86400  |
| 20 | Preflight — allowed origin, with auth     | B      | `http://localhost:3000`   | 200             | `http://localhost:3000`   | `true` | `POST, GET, DELETE, OPTIONS` | present | 86400  |
| 21 | Preflight — disallowed origin             | A      | `http://evil.com`         | 403             | absent                    | absent | absent                       | absent  | absent |
| 22 | Preflight — no Origin header              | A      | absent                    | 403             | absent                    | absent | absent                       | absent  | absent |
| 23 | Preflight — allowlisted origin (config C) | C      | `https://app.example.com` | 200             | `https://app.example.com` | absent | `POST, GET, DELETE, OPTIONS` | present | 86400  |
| 24 | Preflight — empty allowlist (config D)    | D      | `http://localhost:3000`   | 403             | absent                    | absent | absent                       | absent  | absent |
| 25 | Preflight — IPv6 loopback                 | A      | `http://[::1]:5173`       | 200             | `http://[::1]:5173`       | absent | `POST, GET, DELETE, OPTIONS` | present | 86400  |

---

## 7. Target Test Classes and Methods

### 7.1 `McpCorsOriginTest.java`

```java
package io.github.vinhphan812.mcp.transport;

// --- TC 1-5: Loopback wildcard (default policy) ---
@Test void corsOrigin_localhostWithPort_isAllowed()       // TC 1
@Test void corsOrigin_127WithPort_isAllowed()             // TC 2
@Test void corsOrigin_ipv6WithPort_isAllowed()            // TC 3  @SkipIfNoIPv6
@Test void corsOrigin_httpsLocalhostWithPort_isAllowed()  // TC 4
@Test void corsOrigin_localhostNoPort_isAllowed()        // TC 5

// --- TC 6: Absent Origin ---
@Test void corsOrigin_noOriginHeader_isAllowed()          // TC 6

// --- TC 7: Non-loopback rejection ---
@Test void corsOrigin_nonLoopback_isRejected403()        // TC 7

// --- TC 8-9: Explicit allowlist ---
@Test void corsOrigin_allowlistedOrigin_isAllowed()      // TC 8
@Test void corsOrigin_unconfiguredAllowlist_isRejected() // TC 9

// --- TC 10: Empty allowlist ---
@Test void corsOrigin_emptyAllowlist_rejectsAll()       // TC 10

// --- TC 11-14: Auth × CORS ---
@Test void corsAuth_allowedOrigin_validToken_200WithACAC()   // TC 11
@Test void corsAuth_allowedOrigin_invalidToken_401WithACAC() // TC 12
@Test void corsAuth_disallowedOrigin_validToken_403NoCORS()  // TC 13
@Test void corsAuth_noOrigin_validToken_200NoCORS()          // TC 14

// --- TC 15-18: GET / DELETE methods ---
@Test void corsMethod_getAllowedOrigin_200WithACAO()    // TC 15
@Test void corsMethod_deleteAllowedOrigin_204WithACAO() // TC 16
@Test void corsMethod_getDisallowedOrigin_403NoACAO()   // TC 17
@Test void corsMethod_deleteDisallowedOrigin_403NoACAO() // TC 18
```

### 7.2 `McpCorsPreflightTest.java`

```java
package io.github.vinhphan812.mcp.transport;

// --- TC 19-20: Preflight allowed origin ---
@Test void preflight_allowedOrigin_noAuth_200WithACAO_ACAM_ACAH_ACMAXAge() // TC 19
@Test void preflight_allowedOrigin_withAuth_200WithACAC()                  // TC 20

// --- TC 21-22: Preflight rejection ---
@Test void preflight_disallowedOrigin_403NoCORSHeaders()  // TC 21
@Test void preflight_noOrigin_403NoCORSHeaders()          // TC 22

// --- TC 23-24: Preflight with allowlist configs ---
@Test void preflight_explicitAllowlist_200WithACAO()     // TC 23
@Test void preflight_emptyAllowlist_403NoCORSHeaders()  // TC 24

// --- TC 25: Preflight IPv6 ---
@Test void preflight_ipv6Loopback_200WithACAO() @SkipIfNoIPv6 // TC 25
```

---

## 8. Implementation Requirements for Tests

### 8.1 What the tests assume about production

| Assumption                                             | ADR-0021 section | Notes                                |
|--------------------------------------------------------|------------------|--------------------------------------|
| `http://localhost:PORT` accepted                       | Decision 1-2     | Loopback wildcard semantics          |
| `http://127.0.0.1:PORT` accepted                       | Decision 1-2     | Same                                 |
| `http://[::1]:PORT` accepted                           | Decision 1-2     | IPv6 loopback                        |
| `http://evil.com` rejected 403                         | Decision 6       | Non-loopback, no allowlist           |
| `Access-Control-Allow-Origin: <validated>`             | Decision 4       | Never `*`                            |
| `Vary: Origin` on all responses                        | Decision 4       | With Origin header present           |
| `Access-Control-Allow-Credentials: true` when auth set | Decision 4       | Only on allowed origins              |
| OPTIONS handled (not 405)                              | Decision 5       | New arm in `service()`               |
| OPTIONS → 403 without Origin                           | Decision 5       | No CORS contract                     |
| OPTIONS → 403 with disallowed Origin                   | Decision 5       | Origin gate before preflight         |
| `ACMAXAge: 86400` on preflight                         | Decision 5       | 24-hour cache                        |
| Bearer auth evaluated after origin check               | Decision 4, 6    | Independent gates                    |
| `McpServer.Builder.allowedOrigins(Set)` forwarding     | Decision 3       | Not tested here — separate unit test |

### 8.2 Test infrastructure prerequisites

Before these tests can be written, the implementation task (`t_af3f88ab`) must
provide:

1. **Origin validation with port wildcard** — `isInvalidOrigin` must match
   `http://localhost:3000` against `http://localhost` (any port), not require
   exact equality.
2. **CORS response header injection** — `Access-Control-Allow-Origin`,
   `Access-Control-Allow-Credentials`, `Vary: Origin` written to the response
   in `handlePost`, `handleGet`, `handleDelete`.
3. **OPTIONS arm in `service()`** — `OPTIONS` route that:
    - Returns 403 if no `Origin` header.
    - Returns 403 if `Origin` fails `isInvalidOrigin`.
    - Otherwise returns 200 with all preflight headers.
4. **`McpServer.Builder.allowedOrigins(Set)`** — forwarded to
   `HttpTransportProvider.allowedOrigins(Set)`. Test TC 8 / TC 23 use the
   `HttpTransportProvider` API directly (lower-level); a unit test for the
   `McpServer` forwarding is out of scope for this spec but noted in ADR-0021.

### 8.3 Regression guard

These tests must continue to pass if:

- `allowedOrigins` default set changes (must be updated in test setup comments).
- New CORS headers are added (tests asserting specific values may need updating).
- HTTP method routing changes (OPTIONS arm added/modified).

The `Vary: Origin` assertion is the highest-risk regression point: every
non-403 response with a present `Origin` header must include it.

---

## 9. CI Considerations

- **Port binding:** `HttpTransportProvider.port(0)` selects an ephemeral port;
  `transport.getUrl()` returns the concrete URL after `start()`.
- **IPv6:** Detected and skipped via `@SkipIfNoIPv6` at runtime — no CI matrix
  change needed.
- **Test ordering:** No test depends on state from another; all tests are
  independent and may run in any order or in parallel.
- **Timeout:** Default JUnit 5 timeout (none set) is sufficient; each test
  performs at most 2 HTTP round-trips.
- **Resource cleanup:** `@AfterEach transport.stop()` in both classes releases
  the Grizzly server socket.

---

## 10. References

- ADR-0021: CORS Loopback-Origin Policy — canonical implementation contract
- `McpHttpHandler.java` — production source under test (lines 221-226, 184-204)
- `HttpTransportProvider.java` — transport setup (line 23: default origins)
- `McpCorsOriginTest.java` — target test class (new)
- `McpCorsPreflightTest.java` — target test class (new)
- `McpIntegrationTest.java` — existing integration test pattern reference
- `SseConnectionLimitTest.java` — existing transport test pattern reference
- Parent task: `t_e553c79f` (ADR-0021 commit b3527ad)
- Implementation task: `t_af3f88ab`
