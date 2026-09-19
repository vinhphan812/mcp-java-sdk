# Trace of failing assertions in McpRateLimitTest to audit findings

| Test Case                     | Failing Assertion (Line) | Audit Finding | Root Cause Summary                                                         |
|:------------------------------|:-------------------------|:--------------|:---------------------------------------------------------------------------|
| `testDestructiveShutdownCap`  | 271                      | SEC-004       | Hardcoded cap check logic does not support configured tool names.          |
| `testDestructiveDeleteCap`    | 293                      | SEC-004       | Hardcoded cap check logic does not support configured tool names.          |
| `testDestructiveUploadCap`    | 315                      | SEC-004       | Hardcoded cap check logic does not support configured tool names.          |
| `testWriteCategoryBurstLimit` | 138                      | SEC-006       | Non-atomic category concurrency: limit exceeded before check.              |
| `testAdminCategoryBurstLimit` | 160                      | SEC-006       | Non-atomic category concurrency: limit exceeded before check.              |
| `testWriteConcurrentCap`      | 218                      | SEC-006/007   | Concurrency in-flight checks and atomic state transitions are missing.     |
| `testAdminConcurrentCap`      | 247                      | SEC-006       | Non-atomic category concurrency: in-flight limits not respected.           |
| `testAbuseScoreBlocksSession` | 349                      | SEC-008       | Quota is consumed before authorisation, impacting abuse score calculation. |

This analysis covers the 7 explicitly failing tests observed in the build. The remaining failing assertions/tests likely
stem from similar non-atomic or hardcoded logic across the related `McpProtocolHandler` methods, as detailed in the
referenced audit findings (SEC-004 - 008, SEC-014).
