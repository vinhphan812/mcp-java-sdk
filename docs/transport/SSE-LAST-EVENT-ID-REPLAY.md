# SSE Last-Event-ID Replay Consistency Guarantees

**Analysis date:** 2026-09-21
**Source:** `McpProtocolHandler.java` L2018-2031, `McpGrizzlyHandler.java` L322-348
**Spec reference:** RFC 8890 (Server-Sent Events)

---

## 1. Replay Ordering Semantics

### What the code does

`getMissedEvents` iterates `pendingEvents` (a `ConcurrentLinkedQueue<SseEvent>`) in head-to-tail insertion order and
selects events where `event.id > afterEventId`. The returned SSE blocks are concatenated in that same iteration order.

```java
// McpProtocolHandler.java:2021-2030
public String getMissedEvents(String sessionId, long afterEventId) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return "";
    StringBuilder sb = new StringBuilder();
    for (SseEvent event : state.pendingEvents) {
        if (event.id > afterEventId) {
            sb.append("id: ").append(event.id)
                    .append("\nevent: message\ndata: ")
                    .append(escapeSseData(event.body))
                    .append("\n\n");
        }
    }
    return sb.toString();
}
```

### What RFC 8890 requires

RFC 8890 Section 2 states: *"The `Last-Event-ID` header field ... SHOULD be used by the server to replay any missed
events."* It also requires the server to *"ignore events with an `id` less than or equal to the client's
Last-Event-ID"*. The spec does not define a required ordering of the replay batch beyond the monotonic ID constraint.

### Ordering guarantee: PARTIAL

The replay batch is ordered by **insertion order into `pendingEvents`**, which equals the order that callers invoked
`state.enqueueEvent(...)`. Each call to `enqueueEvent` increments `nextEventId` atomically before writing:

```java
// L283-286
void enqueueEvent(String body) {
    if (pendingEvents.size() >= MAX_QUEUED_EVENTS) pendingEvents.poll();
    pendingEvents.offer(new SseEvent(nextEventId.getAndIncrement(), body));
}
```

Since `AtomicLong.getAndIncrement()` is totally ordered, event IDs are strictly monotonically increasing within a
session. Therefore, for any two events e1 and e2 with e1.id < e2.id, e1 was enqueued before e2. The for-each iteration
preserves insertion order, so the replay batch is strictly monotonic-ID order. This satisfies RFC 8890's ordering
intent.

**Gap risk (see Section 3):** If events were evicted from the bounded buffer before the client reconnected, those gaps
are not reflected in the batch — the remaining events are still emitted in correct relative order.

---

## 2. Duplicate Event Handling

### Idempotency analysis

Events enqueued for a session remain in `pendingEvents` until explicitly removed via `pollSseEvent`:

```java
// L2002-2008 — pollSseEvent is the ONLY removal path
public String pollSseEvent(String sessionId) {
    SessionState state = sessions.get(sessionId);
    if (state == null) return null;
    SseEvent event = state.pendingEvents.poll();
    if (event == null) return null;
    return "id: " + event.id + "\nevent: message\ndata: " + event.body + "\n\n";
}
```

`getMissedEvents` is **read-only** — it does not call `poll()`. Therefore:

- **Reconnecting multiple times with the same `Last-Event-ID` is idempotent** — the same events are returned every time,
  and none are removed from the queue until the client polls them via `pollSseEvent`.
- **No deduplication is needed** — since events are never duplicated in the queue, and `getMissedEvents` is read-only,
  the only way to get the same event twice is a client-side retry of the same HTTP GET, which is safe (idempotent read).

This matches RFC 8890: *"The client MUST be able to handle duplicates of events."* The implementation is implicitly
idempotent because events are not consumed by `getMissedEvents`.

---

## 3. Miss Semantics if Queue Mutates During Snapshot

### The mutation window

`getMissedEvents` does not hold any lock. The for-each iteration over `ConcurrentLinkedQueue` produces a
weakly-consistent iterator: it will not throw `ConcurrentModificationException`, but it may observe elements that were
added after the iteration began (never, since additions go to the tail), and may miss elements that were polled between
the start of iteration and a given element's visit.

**Three concurrent mutation scenarios:**

**Scenario A: An event is polled by `pollSseEvent` during `getMissedEvents` iteration**

- `poll()` removes the head element.
- The iterator for the remaining elements is unaffected; the already-visited portion is stable.
- **Effect:** If the polled event had `id > afterEventId` and hadn't been visited yet, it is silently skipped. If it was
  already visited, it was already in the output batch.
- **Severity:** MEDIUM. The client receives fewer events than it should, with no signal. The gap in the ID sequence is
  detectable (e.g., client sees IDs 1, 2, 5 — missing 3, 4).

**Scenario B: `enqueueEvent` adds events during iteration**

- Additions go to the tail. The iterator walks from head to tail.
- **Effect:** Newly added events (which necessarily have higher IDs than any existing event) may appear in the iteration
  output if they satisfy `id > afterEventId`. This is benign — the client receives more events than it strictly "
  missed," but all are valid, unprocessed events.
- **Severity:** LOW. Extra events are better than missing ones; the client processes them normally.

**Scenario C: `MAX_QUEUED_EVENTS` overflow evicts the head during `enqueueEvent`**

```java
void enqueueEvent(String body) {
    if (pendingEvents.size() >= MAX_QUEUED_EVENTS) pendingEvents.poll();
    pendingEvents.offer(new SseEvent(nextEventId.getAndIncrement(), body));
}
```

This runs concurrently with `getMissedEvents`. If an event is evicted between the `getMissedEvents` call and the
client's subsequent `pollSseEvent` calls, that event is permanently lost.

**Effect:** A permanent, undetectable gap. The client sees ID sequence jumps with no error signal.
**Severity:** HIGH. The spec does not define a gap-notification mechanism. The client has no way to distinguish "server
had no events" from "server had events but they were evicted."

---

## 4. Is an Atomic Snapshot/Log Required for Correctness?

### What "atomic snapshot" means here

Two possible interpretations:

1. **Frozen queue:** No concurrent mutation allowed during the snapshot — equivalent to
   `synchronized(pendingEvents) { ... }` or taking a defensive copy (`new ArrayList<>(pendingEvents)`).
2. **Atomic drain-and-snapshot:** The set of events to replay is captured, then a cursor marks them as "replay in
   progress" so `pollSseEvent` does not consume them.

### Correctness criteria

- **No lost events** during concurrent `pollSseEvent` consumption.
- **No silent gaps** — if an event is dropped, the client is informed.
- **Monotonic delivery** — once an event with ID N is delivered, no event with ID < N will follow.

### Verdict: NOT strictly required, but current design is UNSOUND for lost-event scenario

**The current design does not require a frozen snapshot for basic correctness**, because:

- `ConcurrentLinkedQueue` is lock-free; its iterator is weakly consistent.
- `getMissedEvents` is read-only and idempotent.
- The window of inconsistency is bounded to the duration of one HTTP request.

**However, the design is UNSOUND for production use** because of Scenario C above: the unbounded concurrency between
`enqueueEvent` (which can evict via `poll()`) and the client's post-replay `pollSseEvent` calls means events can be
silently lost with no gap signal. RFC 8890 provides no gap-notification mechanism, so this is a spec-level gap, not just
an implementation gap.

**Required for production correctness (in order of invasiveness):**

| Option                       | Mechanism                                                                                    | Pros                                         | Cons                                          |
|------------------------------|----------------------------------------------------------------------------------------------|----------------------------------------------|-----------------------------------------------|
| A. Defensive copy            | `new ArrayList<>(pendingEvents)` in `getMissedEvents`                                        | Eliminates Scenario A; simple                | Extra allocation per replay; doesn't fix C    |
| B. Synchronized drain window | Mark session as "replaying" during HTTP GET; `pollSseEvent` returns null                     | Prevents A+C during replay                   | Blocks normal polling during reconnect window |
| C. Sequence number log       | Persistent append-only log of all events; replay from log                                    | No eviction, no loss                         | Significant complexity; persistence needed    |
| D. Gap event                 | Emit a synthetic `event: gap\ndata: {from: N, to: M}\n\n` event when IDs are non-consecutive | Informs client of loss without preventing it | Client must handle gap events; spec extension |

**Minimum viable fix:** Option B (synchronized drain window) or Option A + documented eviction behavior (the server MUST
emit a gap event or return a sentinel HTTP header when the queue has overflowed since the client's `Last-Event-ID`).

---

## Summary Table

| Question                     | Answer                                                                                | RFC 8890 Alignment            |
|------------------------------|---------------------------------------------------------------------------------------|-------------------------------|
| (1) Replay ordering          | Strict monotonic-ID order = insertion order                                           | Satisfies MUST                |
| (2) Duplicate handling       | Idempotent read; `getMissedEvents` does not consume                                   | Implicitly compliant          |
| (3) Mutation during snapshot | Silent event loss possible via overflow (HIGH) + concurrent poll (MEDIUM)             | Non-compliant — no gap signal |
| (4) Atomic snapshot required | Not strictly required; defensive copy or drain window needed for production soundness | —                             |

---

## Related: `pollPendingNotification` Contrast

`notifyPendingNotification` (L1937-1951) uses `synchronized(state.pendingNotifications)` for the size-check-and-offer
pair, but does NOT protect the corresponding `pollPendingNotification` read. The SSE event queue (`pendingEvents`) has
no equivalent synchronization. This asymmetry is a latent concurrency bug — the notification queue is slightly safer,
but neither is fully sound for concurrent read-during-write.
