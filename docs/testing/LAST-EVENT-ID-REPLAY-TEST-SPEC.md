# Test Specification: Last-Event-ID Replay Behavior

**Reference:** `McpGrizzlyHandler.handleGet()` (lines 301-372)
**Date:** 2026-09-20
**Status:** Draft

## Overview

This document defines test specifications for validating the correct behavior of Server-Sent Events (SSE) replay
functionality when a client reconnects with a `Last-Event-ID` header.

## Background

The SSE endpoint at `McpGrizzlyHandler.handleGet()` supports client reconnection with event replay via the
`Last-Event-ID` header. The implementation must ensure:

1. Replay occurs AFTER response headers are properly set
2. Replay occurs AFTER the SSE connection permit is acquired
3. Only events with IDs greater than the provided `Last-Event-ID` are replayed
4. Duplicate events are not sent on reconnect

---

## Test Case 1: Replay Happens AFTER Headers Are Set

### Description

Verify that when a client reconnects with `Last-Event-ID`, the missed events are replayed ONLY after the SSE response
headers (`Content-Type: text/event-stream`, `Cache-Control`, `Connection`, status 200) have been set.

### Test Setup

```java
@Test
void replayOccursAfterHeadersAreSet() throws Exception {
    // 1. Start MCP server with custom handler that tracks header state
    McpProtocolHandler handler = createMockHandler();

    // 2. Create handler wrapper that captures header state during replay
    HeaderTrackingHandler trackingHandler = new HeaderTrackingHandler(handler);
    GrizzlyStreamableServerTransportProvider transport = new GrizzlyStreamableServerTransportProvider(trackingHandler)
            .port(0).endpoint("/mcp");
    transport.start();

    // 3. Initialize session and send notifications to build event history
    String sessionId = initializeSession(transport);
    sendNotifications(transport, sessionId, 5); // Events with IDs 1-5

    // 4. Close the SSE connection
    // (Simulate client disconnect)

    // 5. Reconnect with Last-Event-ID=3
    HttpURLConnection reconnect = createGetConnection(transport.getUrl(), sessionId);
    reconnect.setRequestProperty("Last-Event-ID", "3");
    reconnect.setRequestProperty("Accept", "text/event-stream");

    // 6. Capture the response and verify header state
    InputStream input = reconnect.getInputStream();
    String response = readStream(input);

    // 7. Verify: Check that Content-Type was set BEFORE any event data
    assertTrue(trackingHandler.wereHeadersSetBeforeReplay(),
            "Headers must be set before replay begins. " +
            "Content-Type should be 'text/event-stream', not default 'text/html'");
}
```

### Assertions

| Assertion                                  | Expected Behavior                  |
|--------------------------------------------|------------------------------------|
| `response.getContentType()`                | `text/event-stream; charset=UTF-8` |
| `response.getStatus()`                     | `200`                              |
| `response.getHeader("Cache-Control")`      | `no-cache, no-transform`           |
| `response.getHeader("Connection")`         | `keep-alive`                       |
| Response body starts with SSE event format | `id: X\nevent: ...\ndata: ...\n\n` |

### Expected Behavior

1. Server responds with HTTP 200 (not 415 or other error)
2. `Content-Type: text/event-stream` is set BEFORE any body content
3. The replayed events appear in correct SSE format
4. Client can parse the response as valid SSE

---

## Test Case 2: Replay Happens AFTER Permit Is Acquired

### Description

Verify that replayed events are sent ONLY after the SSE connection permit (semaphore) has been acquired. This ensures
the connection limit is enforced during replay.

### Test Setup

```java
@Test
void replayOccursAfterPermitAcquired() throws Exception {
    // 1. Configure server with max 2 concurrent SSE connections
    int maxConnections = 2;
    McpProtocolHandler handler = createMockHandler();

    GrizzlyStreamableServerTransportProvider transport = new GrizzlyStreamableServerTransportProvider(handler)
            .port(0)
            .endpoint("/mcp")
            .maxSseConnections(maxConnections);
    transport.start();

    // 2. Initialize session and send events
    String sessionId = initializeSession(transport);
    sendNotifications(transport, sessionId, 3);

    // 3. Fill all connection slots
    ExecutorService executor = Executors.newFixedThreadPool(maxConnections);
    CountDownLatch connectionsEstablished = new CountDownLatch(maxConnections);
    List<ConnectionHolder> holders = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < maxConnections; i++) {
        executor.submit(() -> {
            try {
                HttpURLConnection conn = createGetConnection(transport.getUrl(), sessionId);
                InputStream in = conn.getInputStream();
                // Read first event to confirm connection
                byte[] buf = new byte[1024];
                in.read(buf);
                connectionsEstablished.countDown();
                // Keep connection open
                holders.add(new ConnectionHolder(conn, in));
                Thread.sleep(2000);
            } catch (Exception e) { /* ignore */ }
        });
    }
    assertTrue(connectionsEstablished.await(5, TimeUnit.SECONDS));

    // 4. Attempt replay connection (should fail with 429)
    // The permit should be acquired BEFORE any replay occurs
    HttpURLConnection replayConnection = createGetConnection(transport.getUrl(), sessionId);
    replayConnection.setRequestProperty("Last-Event-ID", "1");

    int status = replayConnection.getResponseCode();

    // 5. Verify: Connection rejected with 429 (not 200 with replay data)
    assertEquals(429, status, "Connection should be rejected when permit limit reached");
    assertTrue(holders.stream().allMatch(ConnectionHolder::isPermitHeld),
            "All existing connections should hold permits");

    // Cleanup
    holders.forEach(h -> h.close());
    executor.shutdown();
}
```

### Assertions

| Assertion                                    | Expected Behavior                   |
|----------------------------------------------|-------------------------------------|
| Permit count during replay                   | Equals number of active connections |
| When permits exhausted, new request gets 429 | Before any body written             |
| Permit released on disconnect                | Other connections can proceed       |

### Expected Behavior

1. When connection limit is reached, new requests receive HTTP 429
2. The 429 error response has clean JSON body (no SSE data mixed in)
3. Existing connections continue to function normally
4. When a connection closes, its permit is released for reuse

---

## Test Case 3: Events with IDs Greater Than Last-Event-ID Are Replayed

### Description

Verify that when a client reconnects with `Last-Event-ID=N`, only events with IDs greater than N are replayed.

### Test Setup

```java
@Test
void onlyEventsAfterLastEventIdAreReplayed() throws Exception {
    // 1. Setup server with mock handler that records sent events
    RecordingMcpProtocolHandler handler = new RecordingMcpProtocolHandler();

    GrizzlyStreamableServerTransportProvider transport = new GrizzlyStreamableServerTransportProvider(handler)
            .port(0).endpoint("/mcp");
    transport.start();

    // 2. Initialize session
    String sessionId = initializeSession(transport);

    // 3. Send 10 notification events (IDs 1-10)
    // Note: IDs are assigned by the handler, we track what gets replayed
    for (int i = 0; i < 10; i++) {
        sendNotification(transport, sessionId, "event-" + i);
    }

    // 4. Disconnect (simulate client disconnect)
    // Close the SSE connection...

    // 5. Reconnect with Last-Event-ID=5
    HttpURLConnection reconnect = createGetConnection(transport.getUrl(), sessionId);
    reconnect.setRequestProperty("Last-Event-ID", "5");
    reconnect.setRequestProperty("Accept", "text/event-stream");

    InputStream input = reconnect.getInputStream();
    String response = readStream(input);

    // 6. Parse response and verify replay content
    List<SseEvent> replayedEvents = parseSseResponse(response);

    // Assertions
    assertFalse(replayedEvents.isEmpty(), "Events after ID 5 should be replayed");

    // Verify: No event with ID <= 5 in replay
    for (SseEvent event : replayedEvents) {
        assertTrue(event.id > 5,
                "Event ID " + event.id + " should be > Last-Event-ID (5)");
    }

    // Verify: Events 6, 7, 8, 9, 10 are all present
    List<Long> replayedIds = replayedEvents.stream()
            .map(e -> e.id)
            .sorted()
            .collect(Collectors.toList());
    assertEquals(Arrays.asList(6L, 7L, 8L, 9L, 10L), replayedIds,
            "Events 6-10 should be replayed (5 events)");
}
```

### Assertions

| Assertion                                        | Expected Behavior              |
|--------------------------------------------------|--------------------------------|
| `event.id > lastEventId` for all replayed events | Event IDs are strictly greater |
| Count of replayed events                         | `totalEvents - lastEventId`    |
| Event data integrity                             | Data content matches original  |

### Expected Behavior

1. Events with ID <= Last-Event-ID are NOT replayed
2. Events with ID > Last-Event-ID ARE replayed
3. Event IDs are in ascending order
4. Event data is preserved correctly

---

## Test Case 4: Duplicate Events Are Not Sent on Reconnect

### Description

Verify that when a client reconnects WITHOUT providing a `Last-Event-ID`, or with an invalid value, they do NOT receive
duplicate events that were already delivered in previous connections.

### Test Setup

```java
@Test
void noDuplicateEventsOnReconnect() throws Exception {
    // 1. Setup server with event tracking per session
    TrackingMcpProtocolHandler handler = new TrackingMcpProtocolHandler();

    GrizzlyStreamableServerTransportProvider transport = new GrizzlyStreamableServerTransportProvider(handler)
            .port(0).endpoint("/mcp");
    transport.start();

    // 2. Initialize session and send events
    String sessionId = initializeSession(transport);
    sendNotifications(transport, sessionId, 3); // Send events 1-3

    // 3. First connection: connect and receive events
    SSEConnection firstConn = connectSSE(transport.getUrl(), sessionId);
    List<SseEvent> firstEvents = firstConn.readEvents(3);
    assertEquals(3, firstEvents.size());

    // 4. First connection closes (graceful disconnect)
    firstConn.close();

    // 5. Second connection: connect with Last-Event-ID=3
    // Should receive events 4, 5, 6 (not 1-3 again)
    sendNotifications(transport, sessionId, 3); // Send events 4-6 while disconnected

    SSEConnection secondConn = connectSSE(transport.getUrl(), sessionId);
    secondConn.setLastEventId(3);

    List<SseEvent> secondEvents = secondConn.readEvents(3);
    assertEquals(3, secondEvents.size());

    // 6. Verify no duplicates
    Set<String> allEventData = new HashSet<>();
    for (SseEvent event : firstEvents) {
        allEventData.add(event.data);
    }
    for (SseEvent event : secondEvents) {
        assertFalse(allEventData.contains(event.data),
                "Event data should not duplicate previous connection's events");
        allEventData.add(event.data);
    }
}
```

### Additional Test: Invalid Last-Event-ID

```java
@Test
void noDuplicatesWithInvalidLastEventId() throws Exception {
    // If client sends invalid Last-Event-ID (e.g., "abc"), treat as fresh connection
    // Server should either reject (400) or treat as no replay
    HttpURLConnection conn = createGetConnection(transport.getUrl(), sessionId);
    conn.setRequestProperty("Last-Event-ID", "invalid");

    int status = conn.getResponseCode();
    // Either 400 (bad request) or 200 with no replay are acceptable
    assertTrue(status == 400 || status == 200);
}
```

### Assertions

| Assertion                                      | Expected Behavior                 |
|------------------------------------------------|-----------------------------------|
| Events previously delivered NOT replayed       | After `Last-Event-ID` is provided |
| New events (ID > Last-Event-ID) ARE replayed   | Fresh events only                 |
| No event data duplication across reconnections | Unique event delivery             |

### Expected Behavior

1. Client receives events 1-3 in first connection
2. After disconnect, client reconnects with `Last-Event-ID=3`
3. Client receives events 4, 5, 6 (NOT 1-3 again)
4. Each event is delivered exactly once to this client

---

## Test Utilities and Fixtures

### Helper Classes

```java
// SSE Event parser
class SseEvent {
    long id;
    String event;
    String data;
}

// SSE Connection wrapper for testing
class SSEConnection {
    private final HttpURLConnection connection;
    private final InputStream input;

    void setLastEventId(String id) {
        connection.setRequestProperty("Last-Event-ID", id);
    }

    List<SseEvent> readEvents(int expectedCount) {
        // Parse SSE format: id: X\nevent: Y\ndata: Z\n\n
    }

    void close() {
        connection.disconnect();
    }
}

// Handler that tracks header state during replay
class HeaderTrackingHandler extends McpProtocolHandler {
    private boolean headersSetBeforeReplay = false;

    @Override
    public synchronized String getMissedEvents(String sessionId, long lastEventId) {
        // Check if headers were already set
        headersSetBeforeReplay = isSseHeadersCommitted();
        return super.getMissedEvents(sessionId, lastEventId);
    }

    boolean wereHeadersSetBeforeReplay() {
        return headersSetBeforeReplay;
    }
}
```

### Test Configuration Constants

| Constant               | Value           | Purpose                  |
|------------------------|-----------------|--------------------------|
| `MAX_SSE_CONNECTIONS`  | 4 (default)     | Default connection limit |
| `SSE_TIMEOUT_MS`       | 300000          | 5 minute idle timeout    |
| `POLL_INTERVAL_MS`     | 1000            | Event polling interval   |
| `LAST_EVENT_ID_HEADER` | `Last-Event-ID` | SSE replay header        |

---

## Edge Cases to Consider

1. **Empty event history**: Client connects with `Last-Event-ID` but no events exist
2. **Event history too small**: `Last-Event-ID` exceeds max event ID in history
3. **Concurrent reconnects**: Multiple clients reconnect simultaneously
4. **Rapid connect/disconnect**: Client repeatedly connects and disconnects
5. **Permit release on error**: Permit released when exception occurs during replay
6. **Session invalidation**: Session ends while replay is in progress

---

## References

- ADR-0017: SSE Permit Acquisition and Response Flow
- `McpGrizzlyHandler.handleGet()` (lines 301-372)
- SSE Specification: https://html.spec.whatwg.org/multipage/server-sent-events.html
