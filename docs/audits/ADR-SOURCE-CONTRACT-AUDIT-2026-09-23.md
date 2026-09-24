# ADR-to-source contract audit (2026-09-23)

Audit of all 18 Architecture Decision Records (ADRs) against the current source code of the MCP Java SDK.

## Summary

| ADR | Title | Status | Verification Result |
| :-- | :--- | :--- | :--- |
| ADR-0001 | Portable Java 8 Core | Accepted | Pending |
| ADR-0002 | Grizzly transport isolation | Accepted | Pending |
| ADR-0003 | JSON-RPC 2.0 Envelope, Versioning | Accepted | Pending |
| ADR-0004 | Session Management | Accepted | Pending |
| ADR-0005 | SSE Event Queue | Accepted | Pending |
| ADR-0006 | Security model | Accepted | Pending |
| ADR-0007 | Annotation registration | Accepted | Pending |
| ADR-0008 | Protocol baseline | Accepted | Pending |
| ADR-0009 | Code audit findings | Accepted | Pending |
| ADR-0010 | API package restructure | Accepted | Pending |
| ADR-0011 | Security rate limiting | Accepted | Pending |
| ADR-0012 | Configurable rate limits | Accepted | Verified |
| ADR-0013 | TLS Strategy | Accepted | Pending |
| ADR-0014 | TLS transport contract | Accepted | Pending |
| ADR-0015 | Concurrent collection | Accepted | Pending |
| ADR-0016 | SSE permit flow | Active | Pending |
| ADR-0017 | SSE permit response flow | Draft | Pending |
| ADR-0018 | Transport contract | Accepted | Pending |

## Detail Audit

### ADR-0012 — Configurable Rate Limits

**Status:** Verified

| Claim | Source Check | Result |
| :--- | :--- | :--- |
| `io.github.vinhphan812.mcp.api.config.RateLimits` exists | Verified `/src/main/java/io/github/vinhphan812/mcp/api/config/RateLimits.java` | OK |
| `McpServerConfig` contains `rateLimits` | Verified `/src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` | OK |
| `McpProtocolHandler` uses `rateLimits` internally | Verified `/src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | OK |
| `McpRateLimitsConfigTest` exists | Verified `/src/test/java/io/github/vinhphan812/mcp/McpRateLimitsConfigTest.java` | OK |
| `RateLimits.defaults()` matches constants | OK | OK |
