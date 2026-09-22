# TEST-0002 — SSE Fix Compatibility and Validation Plan

**Status:** Draft
**Date:** 2026-09-20
**Parent ADR:** ADR-0017 — SSE Permit Acquisition and Response Flow
**Related Test Spec:** TEST-0001 — SSE Connection Release and Streaming

---

## Overview

This document provides a comprehensive validation plan to verify the SSE fix for `McpGrizzlyHandler.handleGet()`. The
fix corrects the ordering of:

1. Permit acquisition
2. Response headers setting
3. Missed events replay

The validation plan covers test impact analysis, manual test scenarios, client compatibility verification, and backward
compatibility assessment.

---

## 1. Existing Tests Potentially Affected by Ordering Change

### 1.1 Unit Tests (Direct Impact)

| Test File                         | Test Method                               | Impact   | Notes                                           |
|-----------------------------------|-------------------------------------------|----------|-------------------------------------------------|
| `McpGrizzlyResumabilityTest.java` | `parsesAbsentAndNonNegativeLastEventId()` | **Low**  | Tests parsing only, no ordering dependency      |
| `McpGrizzlyResumabilityTest.java` | `rejectsMalformedLastEventId()`           | **Low**  | Tests parsing validation only                   |
| `McpGrizzlyResumabilityTest.java` | `emitsSseEventIdBeforeEventAndData()`     | **None** | Tests static formatting, no ordering dependency |

**Assessment:** Existing unit tests are unaffected as they test isolated components.

### 1.2 Integration Tests (Potential Impact)

| Test File                           | Test Method | Potential Impact | Notes                                         |
|-------------------------------------|-------------|------------------|-----------------------------------------------|
| `McpIntegrationTest.java`           | All 9 tests | **Medium**       | May create SSE connections; need verification |
| `McpGrizzlyLiveTest.java`           | Unknown     | **High**         | Likely tests SSE streaming behavior           |
| `McpGrizzlySecurityMatrixTest.java` | Unknown     | **Medium**       | Security tests may involve SSE auth           |

### 1.3 Tests to Verify After Fix

```
Recommended verification command after fix:
./gradlew test --tests "McpGrizzlyResumabilityTest" --tests "McpIntegrationTest" --tests "McpGrizzlyLiveTest"
```

### 1.4 Test Files Requiring Review

| Priority | File                      | Action Required                 |
|----------|---------------------------|---------------------------------|
| HIGH     | `McpGrizzlyLiveTest.java` | Review for SSE connection tests |
| MEDIUM   | `McpIntegrationTest.java` | Review for SSE initialization   |
| LOW      | All other test files      | Verify no SSE handleGet calls   |

---

## 2. Manual Test Scenarios for Corrected Flow

### 2.1 Scenario 1: Basic SSE Connection

**Purpose:** Verify headers are set before any body content

**Steps:**

1. Start MCP server with Grizzly transport
2. Initialize session via POST /mcp
3. Open SSE connection via GET /mcp with valid session
4. Capture response headers
5. Verify headers BEFORE reading body:
    - `Content-Type: text/event-stream`
    - `Cache-Control: no-cache, no-transform`
    - `Connection: keep-alive`
    - Status: 200

**Expected:** Headers present and correct; first event is `connected`

### 2.2 Scenario 2: Replay with Last-Event-ID

**Purpose:** Verify replay happens after headers are set

**Steps:**

1. Create session and open SSE connection
2. Send 3 notifications via protocol handler
3. Close SSE connection
4. Reopen SSE with header `Last-Event-ID: 1`
5. Verify response:
    - Headers are SSE (text/event-stream)
    - Body contains missed events (IDs 2, 3)
    - Then `connected` event
    - No mixing of JSON error with SSE

**Expected:** Clean SSE response with replay followed by connected event

### 2.3 Scenario 3: Permit Exhaustion (429 Error)

**Purpose:** Verify clean error when permit fails

**Steps:**

1. Set maxSseConnections to 2 (low limit)
2. Open 2 SSE connections (fills permits)
3. Attempt 3rd connection
4. Read error response

**Expected:**

- Response is valid JSON (not corrupted)
- Content-Type: application/json
- Status: 429
- No SSE body content mixed in

### 2.4 Scenario 4: Connection Release on Disconnect

**Purpose:** Verify permit released when client disconnects

**Steps:**

1. Open SSE connection
2. Read connected event
3. Abruptly close (kill client, not graceful)
4. Immediately open new connection

**Expected:** Second connection succeeds (permit was released)

### 2.5 Scenario 5: Concurrent Multi-Client

**Purpose:** Verify permit counting works correctly

**Steps:**

1. Create 3 different sessions
2. Open SSE on session 1, session 2 (2 permits used)
3. Try session 3 → should fail 429
4. Close session 1
5. Try session 3 → should succeed

**Expected:** Strict permit counting, no race conditions

---

## 3. Breaking Changes Verification for Existing Clients

### 3.1 Protocol Compatibility

| Aspect                 | Old Behavior            | New Behavior             | Breaking?                    |
|------------------------|-------------------------|--------------------------|------------------------------|
| Header order           | Body written first      | Headers set first        | No - clients don't see order |
| Content-Type           | May be wrong on replay  | Always text/event-stream | **Yes - potential fix**      |
| 429 response           | May contain partial SSE | Clean JSON error         | **Fixes corruption**         |
| Connected event timing | After replay            | After replay             | No                           |
| Event IDs              | Sequential              | Sequential               | No                           |

### 3.2 Client Impact Assessment

| Client Type                           | Impact              | Mitigation                         |
|---------------------------------------|---------------------|------------------------------------|
| MCP SDK clients (official)            | None                | Standard behavior                  |
| Custom HTTP clients                   | None if robust      | N/A                                |
| Clients relying on wrong Content-Type | **Behavior change** | Consider adding header before fix? |

### 3.3 Verification Steps for Client Compatibility

```bash
# Test 1: Verify SSE response format unchanged
curl -v -H "Accept: text/event-stream" \
     -H "Mcp-Session-Id: <session>" \
     http://localhost:8080/mcp

# Test 2: Verify JSON error format unchanged (429 case)
# Should return:
# {"jsonrpc":"2.0","id":null,"error":{"code":429,"message":"Too many active SSE connections"}}
```

### 3.4 Backward Compatibility Matrix

| Scenario                            | Old Code                | New Code    | Compatible?  |
|-------------------------------------|-------------------------|-------------|--------------|
| First connection (no Last-Event-ID) | Works                   | Works       | Yes          |
| Reconnection (with Last-Event-ID)   | Bug: wrong Content-Type | Fixed       | Yes          |
| Permit exhausted                    | Possible corruption     | Clean error | **Improved** |
| Headers before body                 | No                      | Yes         | Yes          |
| Connected event format              | Same                    | Same        | Yes          |

---

## 4. Backward Compatibility Concerns

### 4.1 Identified Concerns

| Concern                | Severity | Resolution                                            |
|------------------------|----------|-------------------------------------------------------|
| Content-Type on replay | Medium   | Fixes bug; clients should not rely on broken behavior |
| Response order         | Low      | Internal implementation detail                        |
| Error response format  | Low      | Already JSON-RPC 2.0 compliant                        |

### 4.2 Client-Side Considerations

Clients that MAY be affected:

1. **Clients parsing response based on timing:**
    - If client checks Content-Type AFTER reading body
    - Mitigation: None needed (broken behavior)

2. **Clients expecting connected event timing:**
    - Old: connected after replay events
    - New: same (connected after replay)
    - No change

3. **Clients handling 429 errors:**
    - Old: May receive mixed SSE + JSON
    - New: Clean JSON only
    - **Positive change** (fixes bug)

### 4.3 Migration Guidance

For existing MCP server deployments:

1. **No configuration changes required** - The fix is internal
2. **No protocol version changes** - Same MCP protocol
3. **Drop-in replacement** - Same API surface

---

## 5. Test Execution Matrix

### 5.1 Pre-Fix Tests (Baseline)

| Test        | Command                                               | Expected Before Fix |
|-------------|-------------------------------------------------------|---------------------|
| Unit tests  | `./gradlew test --tests "McpGrizzlyResumabilityTest"` | Pass                |
| Integration | `./gradlew test --tests "McpIntegrationTest"`         | Pass                |

### 5.2 Post-Fix Validation

| Test                  | Command                                                | Expected After Fix |
|-----------------------|--------------------------------------------------------|--------------------|
| Unit tests            | `./gradlew test --tests "McpGrizzlyResumabilityTest"`  | Pass               |
| Integration           | `./gradlew test --tests "McpIntegrationTest"`          | Pass               |
| Live SSE              | `./gradlew test --tests "McpGrizzlyLiveTest"`          | Pass               |
| New tests (TEST-0001) | `./gradlew test --tests "McpSseConnectionReleaseTest"` | Pass               |

### 5.3 Manual Verification Checklist

- [ ] Headers set before body in all SSE responses
- [ ] 429 errors return clean JSON
- [ ] Permit correctly counted
- [ ] Client disconnect releases permit
- [ ] Replay works after headers set

---

## 6. Risk Assessment

| Risk                       | Likelihood | Impact | Mitigation                       |
|----------------------------|------------|--------|----------------------------------|
| New behavior breaks client | Low        | Medium | Limited to buggy clients         |
| Test suite fails           | Low        | Low    | Existing tests unaffected        |
| Performance regression     | Low        | Low    | No logic change, just reordering |

---

## 7. References

- ADR-0017: SSE Permit Acquisition and Response Flow
- TEST-0001: SSE Connection Release and Streaming Test Specifications
- Source: `McpGrizzlyHandler.java` (lines 301-372)

---

## 8. Child Tasks

- `t_ec0e9fbb` — Review and validate this validation plan
- Implement TEST-0001 test cases
- Execute validation tests post-fix
