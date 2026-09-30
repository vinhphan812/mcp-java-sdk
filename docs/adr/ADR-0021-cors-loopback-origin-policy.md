# ADR-0021 — CORS Loopback Origin Policy

**Status:** Accepted
**Date:** 2026-09-30
**Authors:** MCP Java SDK team
**Supersedes:** ADR-0006 § Origin allowlisting (partial)

## Context

The current origin allowlisting in `HttpTransportProvider` and `McpHttpHandler.Builder` duplicates an identical
hard-coded default set. Origin validation uses only exact case-insensitive string matching, which:

1. Rejects loopback origins with ports (`http://localhost:5173`, `http://127.0.0.1:3000`) that browsers
   naturally send from local development servers.
2. Rejects IPv6 loopback (`http://[::1]:8080`).
3. Provides no CORS preflight (`OPTIONS`) handling required by browser-based clients.
4. Does not emit `Access-Control-*` response headers, preventing browser clients from inspecting responses.

These gaps block legitimate browser-hosting use-cases while leaving non-loopback deployments to manually
configure every allowed origin. ADR-0006 explicitly deferred full CORS; this ADR resolves it.

## Decisions

### 1. Canonical policy owner

Introduce `CorsOriginPolicy` as the single source of truth for origin evaluation. Both
`HttpTransportProvider` and `McpHttpHandler` receive the same policy; no duplicate defaults remain.

### 2. Default loopback rule

`CorsOriginPolicy` ships with a built-in **loopback rule**: any `Origin` whose host portion resolves to
a loopback address is accepted, regardless of port. This covers:

| Origin pattern | Matched by loopback rule |
|---|---|
| `http://localhost[:<port>]` | yes |
| `http://127.0.0.1[:<port>]` | yes |
| `https://localhost[:<port>]` | yes |
| `https://127.0.0.1[:<port>]` | yes |
| `http://[::1][:port]` | yes |
| `https://[::1][:port]` | yes |
| `http://localhost` (IPv4 mapped IPv6) | yes |
| `http://192.168.1.1:8080` | no |
| `https://example.com` | no |

The rule does **not** treat `0.0.0.0` as loopback — it is not a valid `Host` target.

**Port acceptance rationale:** Browser requests from local dev servers carry a non-trivial port (Vite/Next.js/Webpack
defaults). Forcing users to enumerate every port in the allowlist is an unreasonable burden. Loopback traffic
is inherently local; accepting it with any port is safe.

**Scheme (http/https):** Both `http` and `https` loopback origins are accepted. `http` on loopback presents
no additional attack surface over the existing unauthenticated loopback default. `https` loopback is
expected when TLS is terminated by a local reverse proxy (ADR-0014).

### 3. Explicit non-loopback allowlist

Origins that do **not** match the loopback rule are validated against an explicit, user-configured
allowlist. The allowlist is set via:

- `HttpTransportProvider.allowedOrigins(Set<String>)` — lower-level transport API
- `McpHttpHandler.Builder.allowedOrigins(Set<String>)` — direct handler construction
- `McpServer.Builder.allowedOrigins(Set<String>)` — high-level public API

Validation semantics:

- Each value in the set is stored **immutably** after normalisation (lowercased, trimmed).
- Null or blank values throw `IllegalArgumentException` at configuration time.
- The set itself is wrapped in `Collections.unmodifiableSet`.
- **Exact match only** for non-loopback origins — no wildcards, no pattern matching.

### 4. Malformed origin treatment

If the `Origin` header value cannot be parsed as `scheme://host[:port]`:

- The malformed value is treated as **not matching** the loopback rule.
- It is then tested against the explicit allowlist (exact match).
- If neither succeeds, it is rejected with HTTP 403 and response header `Access-Control-Allow-Origin: null`.

### 5. CORS preflight (OPTIONS)

When an incoming request uses the `OPTIONS` method:

1. **Origin check** is performed as for any other method (loopback rule or allowlist).
2. If origin is rejected → HTTP 403 with CORS headers (`Access-Control-Allow-Origin: null`).
3. If origin is accepted → HTTP 200 with preflight response headers:

   ```
   Access-Control-Allow-Origin: <value matching the request Origin>
   Access-Control-Allow-Methods: POST, GET, DELETE, OPTIONS
   Access-Control-Allow-Headers: Content-Type, Accept, Authorization, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID
   Access-Control-Max-Age: 86400
   Vary: Origin
   ```

   No `Access-Control-Allow-Credentials: true` is sent (credentials are handled by Bearer auth, not CORS).

4. `Vary: Origin` is added to all responses when the request carried an `Origin` header, ensuring
   downstream caches understand that responses vary by origin.

### 6. Non-browser / Origin-absent requests

When the `Origin` header is absent or blank, **no CORS headers are emitted** and origin validation is
skipped entirely. The request proceeds to authentication and handler dispatch normally. This preserves
non-browser MCP clients (direct HTTP, curl, server-to-server) which never send an `Origin` header.

### 7. Auth independence

**Origin acceptance does not bypass authentication.** The auth check (`Authorization: Bearer`) runs
independently of origin checking. A request with a valid origin but invalid/missing bearer token
still returns 401. A request with an invalid origin but valid bearer token still returns 403.

```
isInvalidOrigin → 403  (auth not evaluated)
isInvalidOrigin → false, isUnauthorized → 401  (origin valid, auth fails)
isInvalidOrigin → false, isUnauthorized → false → dispatch
```

### 8. Public API ownership

| API surface | Configuration path | Notes |
|---|---|---|
| `McpServer.Builder.allowedOrigins(Set)` | → `HttpTransportProvider.allowedOrigins` | Primary public API |
| `HttpTransportProvider.allowedOrigins(Set)` | Direct | Lower-level transport API |
| `McpHttpHandler.Builder.allowedOrigins(Set)` | Direct | Handler-level API |

All three normalise identically. `McpServer.Builder` is the recommended entry point for new consumers.

**Compatibility note:** If an existing application already uses `HttpTransportProvider.allowedOrigins(Set)`
with non-loopback origins, that configuration is unchanged. Adding `allowedOrigins` to
`McpServer.Builder` is a pure additive change.

### 9. Bind-host independence

The loopback rule is evaluated against the `Origin` header's host, **not** the server's bind address.
Binding to `0.0.0.0` or `127.0.0.1` does not change origin evaluation. This allows a server bound to
`0.0.0.0:8080` to correctly serve browser clients from `localhost:5173`.

## Consequences

**Positive:**

- Browser-based MCP clients in local development "just work" without manual origin configuration.
- OPTIONS preflight is handled correctly, satisfying browser security checks.
- CORS response headers inform browsers of the server's cross-origin policy.
- `Vary: Origin` prevents caching of origin-specific responses.
- Auth remains orthogonal to origin — no CORS bypass of bearer authentication.
- Existing non-browser clients are unaffected (no Origin header = no CORS processing).

**Negative:**

- Accepting any port on loopback origins marginally widens the attack surface for loopback-adjacent
  threats (other local processes). This is an existing characteristic of loopback-bound servers and
  is mitigated by not running untrusted local processes.
- Applications that intentionally reject loopback ports must configure an empty allowlist and
  explicitly list only their non-loopback origins.

**Migration:**

- Applications using `HttpTransportProvider` directly: add `.allowedOrigins(Collections.emptySet())`
  to switch from loopback-default to explicit-only mode.
- Applications using `McpServer.Builder`: no migration needed; loopback defaults are preserved.
- Applications that relied on the previous exact-match-only behaviour: add affected origins to the
  explicit allowlist via `.allowedOrigins(Set)`.

**Rollback:**

- Revert to the commit before this ADR (pre-CORS headers, exact-match-only).
- Re-introduce `HttpTransportProvider.allowedOrigins` and `McpHttpHandler.Builder.allowedOrigins`
  defaults without `CorsOriginPolicy`.

## Required test matrix

| # | Origin | Expected HTTP |
|---|---|---|
| 1 | *(absent)* | 200 / 401 / dispatch as normal |
| 2 | `http://localhost` | 200 |
| 3 | `http://localhost:3000` | 200 |
| 4 | `http://127.0.0.1` | 200 |
| 5 | `http://127.0.0.1:5173` | 200 |
| 6 | `http://[::1]` | 200 |
| 7 | `http://[::1]:8080` | 200 |
| 8 | `https://localhost` | 200 |
| 9 | `https://localhost:443` | 200 |
| 10 | `https://127.0.0.1` | 200 |
| 11 | `http://192.168.1.1:8080` | 403 |
| 12 | `http://example.com` | 403 |
| 13 | OPTIONS preflight, allowed origin | 200 + CORS headers |
| 14 | OPTIONS preflight, rejected origin | 403 + CORS headers |
| 15 | Allowed origin + invalid bearer | 401 (not 403) |
| 16 | Rejected origin + valid bearer | 403 (auth not evaluated) |

## Types / methods expected to change

- **New:** `CorsOriginPolicy` class in `io.github.vinhphan812.mcp.transport`
- **Modified:** `HttpTransportProvider` — replace raw `Set<String>` field with `CorsOriginPolicy`
- **Modified:** `McpHttpHandler` — add OPTIONS routing, CORS headers, `Vary: Origin`, updated `isInvalidOrigin`
- **Modified:** `McpHttpHandler.Builder` — update `allowedOrigins` to delegate to `CorsOriginPolicy`
- **Modified:** `McpServer.Builder` — add `allowedOrigins(Set<String>)` forwarding to transport
- **New:** `McpCorsIntegrationTest` in `src/test/java/.../transport/`
