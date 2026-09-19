# Evidence Verification Addendum — 2026-09-15

## Audit Status: Open

Independently verified audit findings from `docs/audits/2026-09-14-mcp-rate-limit-security-audit.md` against canonical
workspace `D:\android\mcp-java-sdk`.

### Findings Verification Summary

| Finding | Status  | Evidence/Notes                                                                    |
|---------|---------|-----------------------------------------------------------------------------------|
| SEC-001 | PARTIAL | Import dependency resolved in `RateLimits.java`.                                  |
| SEC-002 | OPEN    | Policy ownership still needs consolidation.                                       |
| SEC-003 | OPEN    | Application-specific names still exist.                                           |
| SEC-004 | OPEN    | Custom destructive tool enforcement still broken.                                 |
| SEC-005 | OPEN    | Non-atomic session admission.                                                     |
| SEC-006 | OPEN    | Non-atomic category concurrency.                                                  |
| SEC-007 | OPEN    | Non-atomic destructive state.                                                     |
| SEC-008 | OPEN    | Quota consumed before authorization.                                              |
| SEC-009 | OPEN    | Notification queues still incompatible.                                           |
| SEC-010 | OPEN    | Owner replacement still problematic.                                              |
| SEC-011 | OPEN    | Direct shutdown functionality.                                                    |
| SEC-012 | OPEN    | Sliding-window boundary definition.                                               |
| SEC-013 | OPEN    | IP/Session admission atomicity.                                                   |
| SEC-014 | OPEN    | Security tests (RateLimits, OwnerSession, SessionTimeout) are failing/incomplete. |
| SEC-015 | OPEN    | Configuration validation incomplete.                                              |

### Test Failures Evidence

Running `./gradlew.bat --no-daemon clean test` results in 10 test failures:

- `testDestructiveShutdownCap`: Assertion failed at McpRateLimitTest.java:321
- `testDestructiveDeleteCap`: Assertion failed at McpRateLimitTest.java:346
- `testAbuseScoreBlocksSession`: Assertion failed at McpRateLimitTest.java:415
- `testAdminCategoryBurstLimit`: Assertion failed at McpRateLimitTest.java:198
- `testWriteConcurrentCap`: Assertion failed at McpRateLimitTest.java:272
- `testWriteCategoryBurstLimit`: Assertion failed at McpRateLimitTest.java:173
- `testDestructiveUploadCap`: Assertion failed at McpRateLimitTest.java:371
- `testAdminConcurrentCap`: Assertion failed at McpRateLimitTest.java:295
- `testReadConcurrentCap`: Assertion failed at McpRateLimitTest.java:243
- `testReadCategoryBurstLimit`: Assertion failed at McpRateLimitTest.java:148

These failures confirm that the security mechanisms (SEC-004, SEC-006, SEC-007, SEC-014) are not correctly enforced
under test conditions, and rate limits are not triggering as expected in multiple categories.

## Blockers

Rate-limiting enforcement logic in `McpProtocolHandler` is fundamentally failing under test conditions, specifically for
category burst limits, concurrent caps, and destructive tool caps. 
