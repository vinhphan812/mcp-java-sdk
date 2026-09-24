# TEST-0001 — SSE Connection Release and Streaming Test Specifications

**Status:** Draft
**Date:** 2026-09-20
**Parent ADR:** ADR-0017 — SSE Permit Acquisition and Response Flow

## Overview

This document specifies integration tests for validating the correct behavior of SSE connection permit management and
streaming in `McpHttpHandler.handleGet()`. The tests verify the 7-step flow defined in ADR-0017.

---

## Test Suite Architecture

### Test Class Location

```
src/test/java/io/github/vinhphan812/mcp/transport/
  └── McpSseConnectionReleaseTest.java
```

### Dependencies

- JUnit 5
- Grizzly HTTP Server (for test transport)
- McpHttpHandler (under test)
- McpProtocolHandler + McpRegistry

---

## Test Case 1: Permit Released on Normal Connection Close

### Description

Verify that when a client closes an SSE connection normally (completes the request), the semaphore permit is properly
released.

### Test Method

```java
@Test
void permitReleasedOnNormalConnectionClose() throws Exception
```

### Setup

1. Create McpProtocolHandler with McpRegistry
2. Create McpHttpHandler with custom maxSseConnections=2
3. Start Grizzly transport on available port
4. Initialize a session via POST /mcp with initialize request

### Test Steps

1. Open SSE connection via GET /mcp with valid session ID
2. Verify HTTP 200 and Content-Type: text/event-stream
3. Read a few events from the stream (connected event, some pings)
4. Close the connection cleanly (client calls connection.close())
5. Wait for server to detect closure (short sleep, e.g., 500ms)
6. Open a NEW SSE connection with the same session
7. Verify: NEW connection succeeds (gets 200) — proves permit was released

### Assertions

- First connection returns 200 with SSE content-type
- First connection receives connected event
- Second connection succeeds (permit was released)
- No 429 "Too many active SSE connections" error

### Alternative Verification

Use reflection to check `sseConnections.availablePermits()` after closing:

```java
Field field = McpHttpHandler.class.getDeclaredField("sseConnections");
field.setAccessible(true);
Semaphore semaphore = (Semaphore) field.get(handler);
assertEquals(2, semaphore.availablePermits()); // Should be 2 (max - used)
```

---

## Test Case 2: Permit Released on Client Disconnect

### Description

Verify that when a client disconnects abruptly (network failure, client crash), the permit is still released via the
finally block.

### Test Method

```java
@Test
void permitReleasedOnClientDisconnect() throws Exception
```

### Setup

Same as Test 1

### Test Steps

1. Open SSE connection and verify successful (200 OK)
2. Simulate abrupt disconnect by closing the input stream or socket
3. Wait for server to detect disconnect (IOException in handler)
4. Immediately attempt a new SSE connection with same session
5. Verify: New connection succeeds (permit was released in finally block)

### Assertions

- First connection opened successfully
- Second connection succeeds (proves permit was released)
- No leaked permits

### Edge Case: Test with tryAcquire() verification

```java
// Before any connections
assertEquals(2, semaphore.availablePermits());

// Open one connection
openSseConnection();

// Should be 1 permit available
assertEquals(1, semaphore.availablePermits());

// Disconnect abruptly
closeAbruptly();

// After short delay, should be back to 2
assertEquals(2, semaphore.availablePermits());
```

---

## Test Case 3: Streaming Works After Permit Acquisition and Header Setting

### Description

Verify that events are correctly streamed after the headers are set and permit is acquired, matching the correct flow
defined in ADR-0017.

### Test Method

```java
@Test
void streamingWorksAfterPermitAndHeaders() throws Exception
```

### Setup

Same as Test 1

### Test Steps

1. Initialize session via POST
2. Send a notification to the session via the protocol handler
3. Open SSE connection
4. Read events from stream

### Assertions

- Response has correct headers:
    - Content-Type: text/event-stream
    - Cache-Control: no-cache, no-transform
    - Connection: keep-alive
    - Status: 200
- Connected event is sent with correct format: `id: 1\nevent: connected\ndata: {...}\n\n`
- Sent notification appears as SSE message event
- Events have correct incremental IDs

### Verification of Event Format

```java
// Read connected event
String connectedEvent = readEvent(); // Should be "id: 1\nevent: connected\ndata: {...}\n\n"
assertTrue(connectedEvent.startsWith("id: "));
assertTrue(connectedEvent.contains("event: connected"));
assertTrue(connectedEvent.contains("data: "));

// After sending notification
String messageEvent = readEvent();
assertTrue(messageEvent.contains("event: message"));
```

---

## Test Case 4: Concurrent Connection Handling with Multiple Clients

### Description

Verify that the semaphore correctly limits concurrent SSE connections and that permits are properly managed across
multiple simultaneous clients.

### Test Method

```java
@Test
void concurrentConnectionHandlingWithMultipleClients() throws Exception
```

### Setup

- Create McpHttpHandler with maxSseConnections=2 (small limit for testing)
- Create multiple session IDs (one per client)

### Test Steps

1. Create 2 sessions (session1, session2)
2. Open 2 concurrent SSE connections (one per session)
3. Verify both succeed (200 OK)
4. Attempt to open a 3rd concurrent SSE connection (different session)
5. Verify: 3rd connection fails with 429

### Assertions

- First 2 connections succeed (200, valid SSE)
- Third connection fails with 429 "Too many active SSE connections"
- After closing one connection, 4th connection succeeds

### Concurrent Test Code Pattern

```java
@Test
void concurrentConnectionHandlingWithMultipleClients() throws Exception {
    // Create 3 sessions
    String session1 = createSession();
    String session2 = createSession();
    String session3 = createSession();

    // Open 2 connections
    SseClient client1 = openSseConnection(session1);
    SseClient client2 = openSseConnection(session2);
    assertTrue(client1.isConnected());
    assertTrue(client2.isConnected());

    // Third should fail
    try {
        SseClient client3 = openSseConnection(session3);
        fail("Expected 429 but got connection");
    } catch (HttpException e) {
        assertEquals(429, e.getStatusCode());
    }

    // Release one and try again
    client2.close();
    SseClient client3Retry = openSseConnection(session3);
    assertTrue(client3Retry.isConnected());
}
```

---

## Test Case 5: Permit Released on Session Termination

### Description

Verify that when a session is terminated (DELETE /mcp), the associated SSE connection permit is released.

### Test Method

```java
@Test
void permitReleasedOnSessionTermination() throws Exception
```

### Test Steps

1. Create session and open SSE connection
2. Verify connection works
3. Send DELETE /mcp to terminate session
4. Wait for server to process termination
5. Attempt to open new SSE connection with the deleted session
6. Verify: New connection fails with 400 (invalid session)

### Assertions

- DELETE returns 204 No Content
- Subsequent SSE connection returns 400 "Missing or invalid Mcp-Session-Id header"
- Permit count returns to max

---

## Test Case 6: Error Response Clean When Permit Fails

### Description

Verify that when permit acquisition fails (429), the error response is clean JSON and not corrupted with SSE data.

### Test Method

```java
@Test
void errorResponseCleanWhenPermitFails() throws Exception
```

### Test Steps

1. Create session
2. Open max connections (fill the semaphore)
3. Attempt to open one more connection
4. Read error response

### Assertions

- Response is valid JSON (not SSE + JSON mix)
- Response has correct error format:
  `{"jsonrpc":"2.0","id":null,"error":{"code":429,"message":"Too many active SSE connections"}}`
- Response Content-Type is application/json (not text/event-stream)

---

## Test Case 7: Full Connection Lifecycle

### Description

Integration test covering the complete connection lifecycle from creation to release.

### Test Method

```java
@Test
void fullConnectionLifecycle() throws Exception
```

### Lifecycle Steps

1. **Initialize**: POST /mcp with initialize request → receive session ID
2. **Connect**: GET /mcp with session → receive connected event
3. **Stream**: Receive pings and notifications
4. **Notify**: Send notification via protocol handler → appears in stream
5. **Reconnect**: Close and reopen with Last-Event-ID → receives missed events
6. **Terminate**: DELETE /mcp → session ends
7. **Cleanup**: Verify permits released

### Assertions at Each Stage

- Initialize: 200 + session ID header
- Connect: 200 + Content-Type:text/event-stream + connected event
- Stream: Pings arrive every ~1 second
- Notify: Notification appears as message event
- Reconnect: Missed events replayed, then new events
- Terminate: 204, session invalid
- Cleanup: Semaphore at full capacity

---

## Test Utilities

### Helper: Create Session

```java
private String createSession(HttpTransportProvider transport) throws Exception {
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    HttpResult result = post(transport.getUrl(), body, null, null);
    return result.sessionId;
}
```

### Helper: Open SSE Connection

```java
private SseClient openSseConnection(String sessionId) {
    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
    conn.setRequestMethod("GET");
    conn.setRequestProperty("Accept", "text/event-stream");
    conn.setRequestProperty("Mcp-Session-Id", sessionId);
    return new SseClient(conn);
}
```

### Helper: Read SSE Event

```java
private String readEvent(SseClient client) throws Exception {
    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
    StringBuilder event = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
        event.append(line).append("\n");
    }
    event.append("\n"); // Trailing blank line
    return event.toString();
}
```

---

## Notes

- Use `@Timeout` from JUnit 5 to prevent hanging tests
- Use `CountDownLatch` for coordinating concurrent client tests
- Consider `@Disabled` for tests requiring the fix from ADR-0017
- Test class should be in `src/test/java/io/github/vinhphan812/mcp/transport/`

---

## Child Tasks

- `t_ec0e9fbb` — Review and validate this test specification
