# Event Store and Snapshots Contract

**Task:** t_aec04aaa
**Date:** 2026-10-17
**Status:** Final Specification
**Branch:** origin/master

---

## 1. Executive Summary

This document specifies the current event store implementation and defines the snapshot/resilience contract for the MCP Java SDK. It covers three distinct event storage mechanisms:

| Store | Purpose | Bounded | Persistence | Test Coverage |
|-------|---------|---------|-------------|---------------|
| **Session SSE Queue** | Server-initiated notifications via `pendingEvents` | Yes (1000 events) | In-memory only | `McpProtocolHandlerReplayTest` |
| **Listener Queue** | SEP-2575 topic subscriptions via `SubscriptionManager` | Yes (configurable) | In-memory only | `SubscriptionManagerTest` |
| **Task Snapshots** | Task lifecycle state via `McpTask` | No hard limit | In-memory only | `McpTaskExtensionTest` |

---

## 2. Session SSE Event Queue

### 2.1 Core Data Structures

Located in `McpProtocolHandler.java:348-383`:

```java
private static class SessionState {
    final ConcurrentLinkedQueue<SseEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    final AtomicLong nextEventId = new AtomicLong(1L);
    // ...
}

private static class SseEvent {
    final long id;
    final String body;
}
```

### 2.2 Queue Semantics

| Property | Value |
|----------|-------|
| Max events | 1000 (configurable via `McpServerConfig.maxQueuedEvents`) |
| Eviction policy | FIFO — oldest event dropped when capacity exceeded |
| Thread safety | `ConcurrentLinkedQueue` + `synchronized` enqueue for atomic size check |
| ID generation | `AtomicLong.getAndIncrement()` — strictly monotonic per session |

### 2.3 Public API

```java
// Enqueue notification (called from protocol handlers)
void enqueueEvent(String body);  // SessionState.java:377-382

// Poll single event (transport layer drain)
String pollSseEvent(String sessionId);  // McpProtocolHandler.java:3320-3326

// Replay missed events on reconnect
String getMissedEvents(String sessionId, long afterEventId);  // McpProtocolHandler.java:3336-3356
```

### 2.4 Gap Detection

When the first retained event ID is greater than `afterEventId + 1`, a gap event is emitted:

```text
event: gap
data: {"from": N, "to": M}
```

This signals to the client that events N through M were evicted and are permanently lost.

**Location:** `McpProtocolHandler.java:3341-3346`

---

## 3. Listener Subscription Queue (SEP-2575)

### 3.1 Core Data Structures

Located in `SubscriptionManager.java:213-265`:

```java
private static final class ListenerSubscription {
    final String token;
    final Set<String> topics;
    final String subscriptionId;
    private final ConcurrentLinkedQueue<String> queue;
    private final int capacity;
}
```

### 3.2 Queue Semantics

| Property | Value |
|----------|-------|
| Max events | Configurable via `McpServerConfig.maxListenerBufferSize` (default: 100) |
| Eviction policy | FIFO — oldest event dropped when capacity exceeded |
| Thread safety | `synchronized` enqueue/drain for atomicity |
| Per-subscription | Each subscription token has its own bounded queue |

### 3.3 Public API

```java
// Subscribe to topics
String subscribe(Set<String> topics, String subscriptionId, int bufferSizeHint);

// Unsubscribe
void unsubscribe(String token);

// Poll single notification
String pollListenerNotification(String subscriptionToken);  // McpProtocolHandler.java:3370-3375

// Drain all notifications (for replay)
List<String> drainListenerNotifications(String subscriptionToken);  // McpProtocolHandler.java:3386-3389
```

---

## 4. Task Snapshots (MCP 2026 Tasks Extension)

### 4.1 Core Data Structures

Located in `McpTask.java`:

```java
public final class McpTask {
    private final String id;
    private final String status;
    private final Map<String, Object> metadata;
    // Immutable snapshot once created
}
```

### 4.2 Task Lifecycle States

Per SEP-2663:

| State | Terminal | Description |
|-------|----------|-------------|
| `working` | No | Task is in progress |
| `completed` | Yes | Task finished successfully |
| `failed` | Yes | Task encountered an error |
| `canceled` | Yes | Task was cancelled |
| `input-required` | No | Task needs user input |

### 4.3 Task Registry SPI

Located in `McpTaskExtension.java:195-200`:

```java
public interface TaskRegistry {
    /**
     * Registers a task snapshot with the server's task store.
     */
    void register(McpTask task);
    
    McpTask get(String taskId);
    
    List<McpTask> list(int limit, String after);
    
    McpTask complete(String taskId, Map<String, Object> result);
    
    McpTask fail(String taskId, Map<String, Object> error);
    
    McpTask cancel(String taskId);
    
    McpTask inputRequired(String taskId, Map<String, Object> prompt);
}
```

### 4.4 Snapshot Guarantees

1. **Immutability:** Once created, `McpTask` instances are immutable
2. **Atomic updates:** State transitions are atomic via registry methods
3. **No persistence:** Tasks exist only in-memory; lost on server restart
4. **Ordered list:** `list()` returns tasks in reverse chronological order

---

## 5. Resilience Contract

### 5.1 Event Loss Scenarios

| Scenario | Detection | Mitigation |
|----------|-----------|------------|
| Queue overflow (SSE) | Gap event emitted | Client reconnects with `Last-Event-ID` to trigger replay |
| Queue overflow (Listener) | None | Use larger buffer size if needed |
| Server restart | None | Events lost; client must re-establish subscriptions |
| Concurrent poll + replay | Possible silent skip | Gap event informs client of loss |

### 5.2 Ordering Guarantees

| Operation | Guarantee |
|-----------|-----------|
| SSE replay | Strict monotonic-ID order = insertion order |
| Listener drain | FIFO per subscription token |
| Task list | Reverse chronological (newest first) |

### 5.3 Concurrency Safety

| Store | Concurrent Access | Notes |
|-------|-------------------|-------|
| Session SSE | Safe | `ConcurrentLinkedQueue` provides lock-free iteration |
| Listener queue | Safe | `synchronized` on enqueue/drain |
| Task registry | Safe | `ConcurrentHashMap` for task storage |

### 5.4 Idempotency

- `getMissedEvents()` is read-only — calling multiple times returns the same events
- Clients can safely retry reconnections with the same `Last-Event-ID`
- No duplicate delivery occurs unless client explicitly polls after replay

---

## 6. Test Coverage

### 6.1 Session SSE Queue

- `McpProtocolHandlerReplayTest` — Validates:
  - Strictly increasing event IDs
  - Gap detection on eviction
  - Concurrent producer/consumer correctness
  - Snapshot immutability

### 6.2 Listener Queue

- `SubscriptionManagerTest` — Validates:
  - FIFO ordering
  - Buffer overflow behavior
  - Topic matching

### 6.3 Task Snapshots

- `McpTaskExtensionTest` — Validates:
  - Task lifecycle transitions
  - Immutable snapshot creation
  - Input-required state handling

---

## 7. Known Limitations

1. **No persistence:** All three stores are in-memory only; events/tasks are lost on server restart
2. **Silent loss:** Listener queue overflow provides no signal to client
3. **Gap detection only for SSE:** Listener queue has no gap event mechanism
4. **Session-bound:** Events are tied to session IDs; no cross-session delivery

---

## 8. Related Documentation

| Document | Description |
|----------|-------------|
| `ADR-0005` | SSE notifications event queue design decision |
| `SSE-LAST-EVENT-ID-REPLAY.md` | Detailed replay consistency analysis |
| `ADR-0022` | MRTR/Elicitation foundation |
| `mcp-registry-design-specification.md` | Registry snapshot semantics |

---

## 9. Source References

| Component | File | Lines |
|-----------|------|-------|
| SessionState | `McpProtocolHandler.java` | 348-383 |
| SseEvent | `McpProtocolHandler.java` | 317-325 |
| getMissedEvents | `McpProtocolHandler.java` | 3336-3356 |
| SubscriptionManager | `SubscriptionManager.java` | 1-266 |
| McpTask | `McpTask.java` | 1-450 |
| TaskRegistry SPI | `McpTaskExtension.java` | 195-200 |
