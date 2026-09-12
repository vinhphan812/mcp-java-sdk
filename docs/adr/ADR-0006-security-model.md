# ADR-0006 — Security Model: Origin, Authentication, and Input Validation

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

An MCP server exposes a local HTTP endpoint that could be accessible beyond the developer's machine. Without security controls, arbitrary clients could call tools, read resources, or consume CPU. The SDK targets local-first development and must provide configurable controls that can be hardened for production.

## Decisions

### Origin allowlisting

The transport validates the `Origin` header on all requests (POST, GET, DELETE). The default allowlist accepts only local origins:

```java
// Default: allow only local (file://, http://127.0.0.1, http://localhost, http://[::1])
// Patterns can be added via: .allowedOrigins("https://app.example.com")
```

An unknown or disallowed Origin returns HTTP 403 with `X-Content-Type-Options: nosniff`.

This is not a full CORS implementation. The SDK does not add `Access-Control-*` response headers.

### Bearer authentication

Authentication is provided via an external `Supplier<String>`:

```java
.apiKeySupplier(() -> System.getenv("MCP_API_KEY"))
```

The secret is never written to logs, source, or configuration files. Comparison uses `MessageDigest.isEqual` for constant-time comparison against timing attacks:

```java
MessageDigest.isEqual(
    suppliedToken.getBytes(StandardCharsets.UTF_8),
    secret.getBytes(StandardCharsets.UTF_8)
);
```

Bearer authentication is optional. When `apiKeySupplier` is `null` or returns `null`/empty, authentication is bypassed.

### POST body size limit

The transport limits incoming POST bodies before full parsing:

```java
// Default: 1 MiB
.maxRequestBodySize(1024 * 1024)
```

A body exceeding the limit returns HTTP 413 Payload Too Large before any JSON parsing occurs.

### CRLF injection protection

SSE data fields and HTTP response headers are sanitised before transmission. Any `\r` or `\n` characters in user-controlled strings are stripped:

```java
CR_LF.matcher(value).replaceAll("")
```

This prevents HTTP response splitting via CRLF injection in headers or SSE data.

### Input validation

- `Content-Type` must be `application/json` for POST.
- `Accept` must allow `application/json` or `text/event-stream`.
- `Mcp-Protocol-Version` header is validated against the configured protocol version.
- `Mcp-Session-Id` header is validated against active sessions.
- JSON-RPC `params` fields are type-checked before invocation.
- Tool arguments are validated by `@McpParam` type and required attributes.
- URI template variables are validated against the registered pattern before handler invocation.

## Consequences

**Positive:**

- Defense in depth: origin, authentication, body size, and input validation are layered.
- Authentication is external and secret-free by design.
- Constant-time token comparison prevents timing oracle attacks.
- Default configuration is secure (loopback-only, no auth).

**Negative:**

- Origin checking is not a substitute for TLS. A man-in-the-middle can strip or spoof the Origin header on plain HTTP.
- Bearer token comparison without TLS is vulnerable to replay if the channel is intercepted.
- The security model does not cover rate limiting, IP allowlisting, or intrusion detection.
- Production deployment requires TLS termination at a reverse proxy.
