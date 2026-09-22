# Test Specification: Permit Exhaustion and 429 Response Behavior

**Reference:** `McpGrizzlyHandler.handleGet()` (lines 301-372)
**Date:** 2026-09-20
**Status:** Draft
**Parent ADR:** ADR-0017 — SSE Permit Acquisition and Response Flow

## Overview

This document defines test specifications for validating the correct behavior when SSE connection permits are exhausted.
The tests verify that the 429 response follows the contract defined in ADR-0017: permits are acquired BEFORE any
response output, ensuring clean error responses without body corruption.

## Background

The SSE endpoint at `McpGrizzlyHandler.handleGet()` uses a `Semaphore` to limit concurrent connections (
`DEFAULT_MAX_SSE_CONNECTIONS = 4`). According to ADR-0017:

1. Permits MUST be acquired BEFORE any response output
2. If permit acquisition fails (429), a clean JSON error is returned with NO body corruption
3. Replay (Last-Event-ID) should NEVER happen when permits are exhausted
4. Permits must be released on connection close (normal or abrupt)

---

## Test Case 1: 429 Response Sent BEFORE Any Body Output

### Description

Verify that when permits are exhausted, the HTTP 429 response is sent with headers and status BEFORE any body content is
written. This ensures the error response is properly formatted.

### Test Setup

```java
@Test
void429ResponseSentBeforeAnyBodyOutput() throws Exception {
    // 1. Setup server with max 1 concurrent SSE connection
    int maxConnections = 1;
    GrizzlyStreamableServerTransportProvider transport = createTransport(maxConnections);
    transport.start();

    // 2. Initialize session
    String sessionId = createSession(transport);

    // 3. Fill the single permit slot
    SseClient firstClient = openSseConnection(transport, sessionId);
    assertTrue(firstClient.isConnected(), "First connection should succeed");

    // 4. Attempt second connection (should fail with 429)
    HttpURLConnection secondConn = createGetConnection(transport.getUrl(), sessionId);
    secondConn.setRequestProperty("Accept", "text/event-stream");
    secondConn.setRequestProperty("Mcp-Session-Id", sessionId);

    // 5. Capture response BEFORE reading body
    int status = secondConn.getResponseCode();
    String contentType = secondConn.getContentType();
    Map<String, List<String>> headers = secondConn.getHeaderFields();

    // 6. Read body (should be clean JSON, not corrupted)
    InputStream errorStream = (status >= 400) 
        ? secondConn.getErrorStream() 
        : secondConn.getInputStream();
    String body = readStream(errorStream);

    // Assertions
    assertEquals(429, status, "Second connection should get 429");
    assertTrue(contentType != null, "Content-Type header must be present");
    assertFalse(body.contains("id:"), "Body must NOT contain SSE event format");
    assertFalse(body.contains("event:"), "Body must NOT contain SSE event format");
}
```

### Assertions

| Assertion                               | Expected Behavior                                           |
|-----------------------------------------|-------------------------------------------------------------|
| `response.getResponseCode()`            | `429`                                                       |
| `response.getContentType()`             | `application/json; charset=UTF-8` (not `text/event-stream`) |
| Response body does NOT start with `id:` | No SSE event data in error response                         |
| Response body is valid JSON             | Parses without error                                        |

### Expected Behavior

1. Server responds with HTTP 429 (not 200, not 500)
2. `Content-Type: application/json` is set (not `text/event-stream`)
3. No SSE event format appears in the error body
4. Error body is valid JSON with proper error structure

---

## Test Case 2: 429 Response Includes Correct Headers

### Description

Verify that when permits are exhausted, the 429 response includes all required headers for a proper JSON error response.

### Test Setup

```java
@Test
void429ResponseIncludesCorrectHeaders() throws Exception {
    // 1. Setup with max 2 connections
    GrizzlyStreamableServerTransportProvider transport = createTransport(2);
    transport.start();
    String sessionId = createSession(transport);

    // 2. Fill both permits
    SseClient client1 = openSseConnection(transport, sessionId);
    SseClient client2 = openSseConnection(transport, sessionId);
    assertTrue(client1.isConnected());
    assertTrue(client2.isConnected());

    // 3. Attempt third connection
    HttpURLConnection thirdConn = createGetConnection(transport.getUrl(), sessionId);
    int status = thirdConn.getResponseCode();

    // 4. Verify headers
    assertEquals(429, status);
    
    // Content-Type header
    String contentType = thirdConn.getContentType();
    assertTrue(contentType.contains("application/json"), 
        "Content-Type should be application/json, got: " + contentType);
    
    // Character encoding
    assertTrue(contentType.contains("UTF-8"), 
        "Content-Type should include UTF-8 encoding");
    
    // Status is 429
    assertEquals(429, thirdConn.getResponseCode());
    
    // No SSE-specific headers
    assertNull(thirdConn.getHeaderField("Cache-Control"), 
        "SSE Cache-Control should NOT be present in 429");
    assertNull(thirdConn.getHeaderField("Connection"), 
        "SSE Connection header should NOT be present in 429");
}
```

### Assertions

| Assertion              | Expected Behavior                 |
|------------------------|-----------------------------------|
| `Content-Type`         | `application/json; charset=UTF-8` |
| `Status`               | `429`                             |
| `Cache-Control` header | NOT present (SSE-only header)     |
| `Connection` header    | NOT present (SSE-only header)     |
| Body is valid JSON     | Parses as JSON object             |

### Response Body Format

Expected JSON structure:

```json
{
  "jsonrpc": "2.0",
  "id": null,
  "error": {
    "code": 429,
    "message": "Too many active SSE connections"
  }
}
```

### Verification Code

```java
// Parse and verify error body structure
JsonObject errorBody = JsonParser.parseString(body).getAsJsonObject();
assertEquals("2.0", errorBody.get("jsonrpc").getAsString());
assertTrue(errorBody.has("error"));

JsonObject error = errorBody.getAsJsonObject("error");
assertEquals(429, error.get("code").getAsInt());
assertTrue(error.get("message").getAsString().contains("SSE connection"));
```

---

## Test Case 3: Replay Does NOT Happen When Permits Are Exhausted

### Description

Verify that when a client reconnects with `Last-Event-ID` but permits are exhausted, the server returns 429 WITHOUT
attempting to replay missed events. This ensures:

1. No replay occurs before permit check
2. No body corruption from partial replay + error

### Test Setup

```java
@Test
void replayDoesNotHappenWhenPermitsExhausted() throws Exception {
    // 1. Setup with max 1 connection
    GrizzlyStreamableServerTransportProvider transport = createTransport(1);
    transport.start();
    String sessionId = createSession(transport);

    // 2. Send notifications to build event history
    sendNotifications(transport, sessionId, 5); // Events 1-5

    // 3. Fill the single permit
    SseClient client = openSseConnection(transport, sessionId);
    assertTrue(client.isConnected());

    // 4. Attempt replay connection with Last-Event-ID (should fail 429)
    HttpURLConnection replayConn = createGetConnection(transport.getUrl(), sessionId);
    replayConn.setRequestProperty("Accept", "text/event-stream");
    replayConn.setRequestProperty("Mcp-Session-Id", sessionId);
    replayConn.setRequestProperty("Last-Event-ID", "3");

    // 5. Capture response
    int status = replayConn.getResponseCode();
    String contentType = replayConn.getContentType();
    String body = readStream(replayConn.getErrorStream());

    // Assertions: 429 without any replay
    assertEquals(429, status, "Should return 429 when permits exhausted");
    assertTrue(contentType.contains("application/json"), 
        "Should be JSON error, not SSE");
    
    // Verify NO replay data in body
    assertFalse(body.contains("id: 4"), "Should NOT contain event id 4");
    assertFalse(body.contains("id: 5"), "Should NOT contain event id 5");
    assertFalse(body.contains("event: message"), "Should NOT contain message events");
    
    // Verify clean error JSON
    JsonObject errorBody = JsonParser.parseString(body).getAsJsonObject();
    assertTrue(errorBody.has("error"));
    assertEquals(429, errorBody.getAsJsonObject("error").get("code").getAsInt());
}
```

### Critical Assertion: Header Ordering

This test verifies the fix from ADR-0017. The INCORRECT implementation:

1. Replays events (writes body)
2. Then tries to acquire permit
3. Fails and writes 429 over corrupted body

The CORRECT implementation:

1. Tries to acquire permit FIRST
2. If fails, writes clean 429 (no body written yet)

### Assertions

| Assertion                              | Expected Behavior       |
|----------------------------------------|-------------------------|
| Status is 429                          | Not 200                 |
| Content-Type is `application/json`     | Not `text/event-stream` |
| Body does NOT contain `id: `           | No replayed events      |
| Body does NOT contain `event: message` | No replayed events      |
| Body is valid error JSON               | Clean error structure   |

### Edge Case: Rapid Reconnection

```java
@Test
void replayDoesNotHappenWithRapidReconnection() throws Exception {
    // Test that even with rapid retry, replay never happens
    // when permits are exhausted
    GrizzlyStreamableServerTransportProvider transport = createTransport(1);
    transport.start();
    String sessionId = createSession(transport);
    sendNotifications(transport, sessionId, 3);

    // Fill permit
    SseClient client = openSseConnection(transport, sessionId);

    // Rapid retry attempts - all should get 429 without replay
    for (int i = 0; i < 5; i++) {
        HttpURLConnection conn = createGetConnection(transport.getUrl(), sessionId);
        conn.setRequestProperty("Last-Event-ID", "1");
        
        assertEquals(429, conn.getResponseCode());
        
        String body = readStream(conn.getErrorStream());
        assertFalse(body.contains("id:"), 
            "Attempt " + i + ": Body should not contain SSE events");
    }
}
```

---

## Test Case 4: Proper Permit Release on Connection Close

### Description

Verify that when an SSE connection closes (normal or abrupt), the permit is properly released, allowing new connections
to be accepted.

### Test Setup

```java
@Test
void permitReleasedOnConnectionClose() throws Exception {
    // 1. Setup with max 2 connections
    GrizzlyStreamableServerTransportProvider transport = createTransport(2);
    transport.start();
    String sessionId = createSession(transport);

    // 2. Get reference to semaphore via reflection
    Semaphore semaphore = getSseSemaphore(transport.getHandler());

    // 3. Verify initial state
    assertEquals(2, semaphore.availablePermits(), "Should have 2 permits initially");

    // 4. Open first connection
    SseClient client1 = openSseConnection(transport, sessionId);
    assertTrue(client1.isConnected());
    assertEquals(1, semaphore.availablePermits(), "One permit should be held");

    // 5. Open second connection
    SseClient client2 = openSseConnection(transport, sessionId);
    assertTrue(client2.isConnected());
    assertEquals(0, semaphore.availablePermits(), "All permits should be held");

    // 6. Close first connection
    client1.close();
    Thread.sleep(100); // Allow server to detect close and release permit

    // 7. Verify permit released
    assertEquals(1, semaphore.availablePermits(), 
        "One permit should be released after close");

    // 8. Verify new connection works
    SseClient client3 = openSseConnection(transport, sessionId);
    assertTrue(client3.isConnected());
    assertEquals(0, semaphore.availablePermits(), "All permits should be held again");

    // Cleanup
    client2.close();
    client3.close();
}
```

### Helper: Get Semaphore via Reflection

```java
private Semaphore getSseSemaphore(McpGrizzlyHandler handler) throws Exception {
    Field field = McpGrizzlyHandler.class.getDeclaredField("sseConnections");
    field.setAccessible(true);
    return (Semaphore) field.get(handler);
}
```

### Test: Permit Released on Abrupt Disconnect

```java
@Test
void permitReleasedOnAbruptDisconnect() throws Exception {
    GrizzlyStreamableServerTransportProvider transport = createTransport(2);
    transport.start();
    String sessionId = createSession(transport);
    Semaphore semaphore = getSseSemaphore(transport.getHandler());

    // Open and hold connection
    SseClient client = openSseConnection(transport, sessionId);
    assertEquals(1, semaphore.availablePermits());

    // Simulate abrupt disconnect (close socket without proper SSE close)
    client.closeAbruptly();

    // Wait for server to detect and release
    Thread.sleep(500);

    // Verify permit released
    assertEquals(2, semaphore.availablePermits(), 
        "Permit should be released after abrupt disconnect");

    // Verify new connection works
    SseClient newClient = openSseConnection(transport, sessionId);
    assertTrue(newClient.isConnected());
}
```

### Test: Permit Released on Session Termination

```java
@Test
void permitReleasedOnSessionTermination() throws Exception {
    GrizzlyStreamableServerTransportProvider transport = createTransport(2);
    transport.start();
    String sessionId = createSession(transport);
    Semaphore semaphore = getSseSemaphore(transport.getHandler());

    // Open SSE connection
    SseClient client = openSseConnection(transport, sessionId);
    assertEquals(1, semaphore.availablePermits());

    // Terminate session via DELETE
    deleteSession(transport, sessionId);

    // Wait for termination processing
    Thread.sleep(200);

    // Permit should still be held (session termination doesn't auto-close SSE)
    // But subsequent connection attempt should fail (invalid session)
    HttpURLConnection conn = createGetConnection(transport.getUrl(), sessionId);
    assertEquals(400, conn.getResponseCode());

    // Manually close the connection
    client.close();

    // Now permit should be released
    assertEquals(2, semaphore.availablePermits());
}
```

### Assertions

| Assertion                                    | Expected Behavior                          |
|----------------------------------------------|--------------------------------------------|
| `availablePermits()` after normal close      | Increments by 1                            |
| `availablePermits()` after abrupt disconnect | Increments by 1                            |
| New connection after close                   | Succeeds (200)                             |
| Concurrent close + new connection            | New connection waits briefly then succeeds |

---

## Test Case 5: No Race Condition Between Permit Check and Response

### Description

Verify there is no race condition where:

1. Thread A checks permit (available)
2. Thread B checks permit (available)
3. Thread A acquires permit
4. Thread B tries to acquire but fails AFTER writing body

This is ensured by the atomic `tryAcquire()` call happening BEFORE any response output.

### Test Setup

```java
@Test
void noRaceConditionBetweenPermitCheckAndResponse() throws Exception {
    // Use 2 permits
    GrizzlyStreamableServerTransportProvider transport = createTransport(2);
    transport.start();
    String sessionId = createSession(transport);

    // Track responses from concurrent connection attempts
    List<ConnectionResult> results = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch startLatch = new CountDownLatch(1);
    int concurrentAttempts = 10;

    // Submit concurrent connection attempts
    ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
    for (int i = 0; i < concurrentAttempts; i++) {
        final int attempt = i;
        executor.submit(() -> {
            try {
                startLatch.await(); // Wait for all threads to be ready
                
                HttpURLConnection conn = createGetConnection(transport.getUrl(), sessionId);
                int status = conn.getResponseCode();
                String body = readStream(status < 400 
                    ? conn.getInputStream() 
                    : conn.getErrorStream());
                
                results.add(new ConnectionResult(attempt, status, body));
            } catch (Exception e) {
                results.add(new ConnectionResult(attempt, -1, e.getMessage()));
            }
        });
    }

    // Release all threads simultaneously
    startLatch.countDown();
    executor.shutdown();
    assertTrue(executor.await(10, TimeUnit.SECONDS));

    // Verify: Exactly 2 succeeded (200), rest got 429
    List<ConnectionResult> successes = results.stream()
        .filter(r -> r.status == 200)
        .collect(Collectors.toList());
    List<ConnectionResult> rateLimited = results.stream()
        .filter(r -> r.status == 429)
        .collect(Collectors.toList());

    assertEquals(2, successes.size(), "Exactly 2 should succeed");
    assertEquals(8, rateLimited.size(), "Rest should get 429");

    // Verify all 429 responses are clean (no body corruption)
    for (ConnectionResult result : rateLimited) {
        assertFalse(result.body.contains("id:"), 
            "429 body should not contain SSE events");
        assertFalse(result.body.contains("event:"), 
            "429 body should not contain SSE events");
        
        // Verify valid JSON
        JsonObject.parse(result.body); // Should not throw
    }
}

private static class ConnectionResult {
    final int attempt;
    final int status;
    final String body;
    
    ConnectionResult(int attempt, int status, String body) {
        this.attempt = attempt;
        this.status = status;
        this.body = body;
    }
}
```

---

## Test Utilities

### Helper: Create Transport with Custom Limit

```java
private GrizzlyStreamableServerTransportProvider createTransport(int maxConnections) {
    McpProtocolHandler handler = new McpProtocolHandler(new McpRegistry());
    McpGrizzlyHandler grizzlyHandler = new McpGrizzlyHandler(
        handler,
        "/mcp",
        null, // no API key
        DEFAULT_ALLOWED_ORIGINS,
        DEFAULT_MAX_REQUEST_BODY_BYTES,
        maxConnections
    );
    return new GrizzlyStreamableServerTransportProvider(grizzlyHandler)
        .port(0)
        .endpoint("/mcp");
}
```

### Helper: Get Handler from Transport

```java
private McpGrizzlyHandler getHandler(GrizzlyStreamableServerTransportProvider transport) {
    // Use reflection or add getter to transport class
    Field field = GrizzlyStreamableServerTransportProvider.class.getDeclaredField("handler");
    field.setAccessible(true);
    return (McpGrizzlyHandler) field.get(transport);
}
```

### Test Configuration Constants

| Constant                      | Value   | Purpose                                   |
|-------------------------------|---------|-------------------------------------------|
| `MAX_SSE_CONNECTIONS_DEFAULT` | 4       | Default connection limit                  |
| `MAX_SSE_CONNECTIONS_TEST`    | 1-2     | Test-specific limits                      |
| `SSE_PERMIT_RELEASE_DELAY_MS` | 100-500 | Delay for permit release detection        |
| `CONCURRENT_ATTEMPTS`         | 10      | Number of threads for race condition test |

---

## Edge Cases

1. **Permit release during 429 response write**: Server should complete writing clean 429
2. **Client cancels during 429 response**: Permit still released in finally block
3. **Multiple rapid 429 requests**: Each should get clean response
4. **Permit release + new request timing**: New request may get 429 or succeed depending on timing
5. **Connection limit = 1**: Single connection behavior

---

## References

- ADR-0017: SSE Permit Acquisition and Response Flow
- `McpGrizzlyHandler.handleGet()` (lines 301-372)
- `McpGrizzlyHandler.writeError()` (lines 421-448)
- Test Spec: LAST-EVENT-ID-REPLAY-TEST-SPEC.md
- Test: TEST-0001-sse-connection-release-streaming.md

---

## Child Tasks

- `t_ec0e9fbb` — Review and validate this test specification
