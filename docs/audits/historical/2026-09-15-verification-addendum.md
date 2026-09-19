# Verification Addendum — MCP Rate-Limit Security Audit 2026-09-14

## Audit Summary Status

- Status: PARTIAL / ACTION REQUIRED
- Verification Date: 2026-09-15
- Assessor: dev-qa (Hermes Agent)

## Findings Status

| Finding | Status  | Note                                                                                     |
|:--------|:--------|:-----------------------------------------------------------------------------------------|
| SEC-001 | PARTIAL | RateLimits no longer imports core, but McpServerConfig still imports McpProtocolHandler. |
| SEC-014 | OPEN    | Security tests (@Disabled) remain disabled in source.                                    |
| SEC-002 | OPEN    | Not yet verified.                                                                        |
| SEC-003 | OPEN    | Not yet verified.                                                                        |
| SEC-004 | OPEN    | Not yet verified.                                                                        |
| SEC-005 | OPEN    | Not yet verified.                                                                        |
| SEC-006 | OPEN    | Not yet verified.                                                                        |
| SEC-007 | OPEN    | Not yet verified.                                                                        |
| SEC-008 | OPEN    | Not yet verified.                                                                        |
| SEC-009 | OPEN    | Not yet verified.                                                                        |
| SEC-010 | OPEN    | Not yet verified.                                                                        |
| SEC-011 | OPEN    | Not yet verified.                                                                        |
| SEC-012 | OPEN    | Not yet verified.                                                                        |
| SEC-013 | OPEN    | Not yet verified.                                                                        |
| SEC-015 | OPEN    | Not yet verified.                                                                        |
| DOC-001 | OPEN    | Not yet verified.                                                                        |
| DOC-002 | OPEN    | Not yet verified.                                                                        |
| DOC-003 | OPEN    | Not yet verified.                                                                        |
| DOC-004 | OPEN    | Not yet verified.                                                                        |
| DOC-005 | OPEN    | Not yet verified.                                                                        |

See findings details below.

## Details

### SEC-001 — API-to-core dependency inversion

- Status: PARTIAL
- Verification: `RateLimits.java` no longer imports `io.github.vinhphan812.mcp.core`. However, `McpServerConfig.java`
  still imports `McpProtocolHandler` (core) for `QueueOverflowListener`. This violates the dependency rule
  `api.config does not depend on core`.

### SEC-014 — Security tests are disabled

- Status: OPEN
- Verification: `McpRateLimitTest.java`, `McpOwnerSessionTest.java`, and `McpSessionTimeoutTest.java` remain marked with
  `@Disabled`. They are not passing and are not active.

## Required Actions

1. Remove dependency of `McpServerConfig` on `McpProtocolHandler`.
2. Enable and pass security tests.
