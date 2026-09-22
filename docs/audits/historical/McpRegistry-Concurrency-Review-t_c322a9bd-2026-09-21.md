# McpRegistry Concurrency/Immutability Review

## Task t_c322a9bd — Tier 2 Specification Review

**Reviewer:** dev-backend
**Date:** 2026-09-20
**Status:** Review complete
**Scope:** McpRegistry.java diff, ADR-0015, registry design docs, concurrency test, listener semantics

---

## 1. ADR-0015 Compliance Assessment

### 1.1 Decisions Implemented Correctly

| ADR-0015 Decision                                 | Implementation                                | Status |
|---------------------------------------------------|-----------------------------------------------|--------|
| Replace ArrayList with CopyOnWriteArrayList       | Lines 24-27: four metadata lists              | ✅ DONE |
| Add ConcurrentHashMap toolDefinitionsByName index | Line 30                                       | ✅ DONE |
| getToolDefinition() returns copyMap()             | Lines 460-463                                 | ✅ DONE |
| Keep ConcurrentHashMap for handler maps           | Lines 32-41                                   | ✅ DONE |
| Keep CopyOnWriteArrayList for changeListeners     | Line 43                                       | ✅ DONE |
| Keep volatile for notificationTarget              | Line 42                                       | ✅ DONE |
| Listener exception does not propagate             | Lines 400-406: try-catch around listener call | ✅ DONE |

### 1.2 ADR-0015 Deviation: registrationLock Is Not Unsynchronized

**ADR-0015 Section 4 states:**
> Remove `synchronized` from registration methods — no explicit lock needed; COWAL + CHM provide thread-safety.

**Actual implementation (e.g., registerTool lines 69-92):**

```java
public void registerTool(...) {
    registrationLock.lock();
    try {
        requireUnique(name, toolHandlers, "tool");
        // ... build tool ...
        registeredTools.add(tool);          // COWAL — thread-safe without lock
        toolDefinitionsByName.put(name, tool); // CHM — thread-safe without lock
        toolHandlers.put(name, handler);    // CHM — thread-safe without lock
    } finally {
        registrationLock.unlock();
    }
    notifyRegistryChanged("tools");  // outside lock
}
```

**Analysis:** The ADR explicitly proposed removing all synchronization because COWAL.add() and CHM.put() are
individually atomic. The implementation uses a ReentrantLock instead, which is **more conservative** and not incorrect —
the lock serializes registration, preventing two threads from racing the `requireUnique` check simultaneously. However,
this deviates from the ADR's stated design and introduces a potential performance bottleneck under concurrent multi-tool
registration that the ADR was designed to avoid.

**Severity:** MEDIUM. The lock is correct (prevents the requireUnique race condition) but contradicts the ADR's
read-heavy optimization rationale.

---

## 2. Risk Matrix

### 2.1 CRITICAL — Handler Published Before Registration Complete (under lock)

**Location:** `registerTool()` lines 84-87 and 126-129

```java
registrationLock.lock();
try {
    // ...
    registeredTools.add(tool);
    toolDefinitionsByName.put(name, tool);   // metadata indexed
    toolHandlers.put(name, handler);           // handler published
} finally {
    registrationLock.unlock();
}
notifyRegistryChanged("tools");
```

**Risk:** After `registrationLock.unlock()` but before `notifyRegistryChanged()`, a concurrent reader thread can observe
the handler in `toolHandlers` via `getToolHandler(name)`. At this point:

- The tool IS in `registeredTools` (COWAL is updated)
- The tool IS in `toolDefinitionsByName`
- The handler IS in `toolHandlers`
- BUT `notifyRegistryChanged("tools")` has not fired yet

This means a client that has just called `getRegisteredTools()` (or received a list_changed notification) will see a
tool definition that has its handler already callable. This is actually **correct** — the tool is fully registered
before listeners are notified. However, there is a subtle ordering risk documented below.

**Severity:** MEDIUM (not an immediate bug, but a semantic hazard if the registration contract ever changes)

---

### 2.2 CRITICAL — Non-Atomic Metadata + Handler Publication for Resources/Templates/Prompts

**Location:** `registerResource()`, `registerBlobResource()`, `registerResourceTemplate()`,
`registerBlobResourceTemplate()`, `registerPrompt()` — lines 145-238

Unlike `registerTool()` which publishes metadata (to COWAL list) and handler (to CHM) atomically under the lock, the
other registration methods publish both **within** the same lock scope:

```java
registrationLock.lock();
try {
    // ...
    registeredResources.add(resource);  // COWAL write
    resourceHandlers.put(uri, handler);  // CHM write
} finally {
    registrationLock.unlock();
}
notifyRegistryChanged("resources");
```

This is **internally consistent** but **not the same pattern as tool registration**. The tool registration publishes
metadata to COWAL, then to the CHM index, then to the CHM handler map — all within the lock. Resources/templates/prompts
publish to both COWAL and CHM within the lock. No inconsistency in practice, but inconsistent with the documented
registration contract.

**Severity:** LOW (the lock makes both correct; but inconsistent with registerTool's three-phase publication)

---

### 2.3 HIGH — toolDefinitionsByName Index Holds Live Reference to Mutable Tool Map

**Location:** Lines 85, 127: `toolDefinitionsByName.put(name, tool)` stores the original mutable map.

The `tool` map is built and mutated inside the lock, then published to:

1. `registeredTools` (COWAL) — COWAL stores a reference; iterators may see the map before it's "complete"
2. `toolDefinitionsByName` (CHM) — CHM stores a reference to the same map
3. `getToolDefinition()` (line 462): `copyMap(toolDefinitionsByName.get(name))` — returns a defensive copy

The index stores the live map, not a copy. While `getToolDefinition()` returns a copy, any future internal use of
`toolDefinitionsByName` that bypasses `copyMap()` could observe partially-constructed maps.

More importantly: COWAL iterators (from `getRegisteredTools()` via `copyDefinitions()`) iterate over the same live map
references that are in `toolDefinitionsByName`. If `copyMap` is deep enough, this is safe — and it is deep (handles
nested Maps/Lists recursively). However, if `copyMap` ever misses a nested type (e.g., a custom collection), the live
reference leaks.

**Severity:** MEDIUM. The deep-copy chain covers all currently known types. Risk is latent if new field types are added
without updating `copyMap`.

---

### 2.4 MEDIUM — Registration Lock Creates a Serialisation Bottleneck

**Location:** `registrationLock` at line 45, used in all registration methods.

The ADR-0015 rationale explicitly stated:
> Removes lock contention between registration and concurrent readers

With `registrationLock`, concurrent registration calls are fully serialised. For registries with many simultaneous
registration calls (e.g., dynamic plugin loading), this creates a bottleneck that COWAL + CHM alone would not.

**Severity:** LOW for typical MCP bootstrap pattern (single-threaded registration at startup). MEDIUM if the registry is
used for dynamic tool addition at runtime under load.

---

### 2.5 MEDIUM — Inconsistent Publication Ordering: registerTool Three-Phase vs Others

**Location:** `registerTool()` lines 83-87 vs resource/prompt methods lines 150-151, 229-234

Tool registration uses a documented three-phase publication under the lock:

1. `registeredTools.add(tool)` — COWAL
2. `toolDefinitionsByName.put(name, tool)` — CHM index
3. `toolHandlers.put(name, handler)` — CHM

Resource/template/prompt registration uses a two-phase publication:

1. `registeredXxx.add(def)` — COWAL
2. `xxxHandlers.put(key, handler)` — CHM

Both are correct under the lock, but the inconsistency makes the contract harder to reason about.

**Severity:** LOW (correctness), MEDIUM (maintainability)

---

### 2.6 LOW — Listener Notification Happens Outside the Registration Lock

**Location:** All registration methods: `notifyRegistryChanged(...)` called after `registrationLock.unlock()`

```java
registrationLock.lock();
try {
    // all writes
} finally {
    registrationLock.unlock();
}
notifyRegistryChanged("tools");  // lock already released
```

A reader that calls `getRegisteredTools()` between `unlock()` and `notifyRegistryChanged()` will see the new tool (
COWAL/CHM are updated), but no listener has been notified yet. The tool is visible to reads but listeners have not
fired. This is acceptable for a "notify after publish" contract.

**Severity:** LOW — this is the intended contract.

---

### 2.7 LOW — `findMimeType()` No Longer Synchronized (ADR Improvement)

**Location:** Lines 519-526 (previously used `synchronized(McpRegistry.class)`)

The class-level lock in `findMimeType` has been removed. The method now iterates the COWAL directly. This is correct:
COWAL iteration requires no external synchronization and provides a consistent snapshot. The old
`synchronized(McpRegistry.class)` lock was inconsistent with the instance-level locks and has been correctly eliminated.

**Severity:** N/A — this is an improvement, not a risk.

---

### 2.8 LOW — `copyDefinitions()` No Longer Synchronized (ADR Improvement)

**Location:** Lines 541-547 (previously used `synchronized(source)`)

The list-level lock has been removed. COWAL provides its own snapshot semantics. Correct improvement.

**Severity:** N/A — improvement.

---

## 3. Test Suite Quality Assessment

The test file `McpRegistryConcurrencyTest.java` is comprehensive. Assessment per test:

| Test                                                          | What It Verifies                                                             | Coverage | Gaps                                                                                                       |
|---------------------------------------------------------------|------------------------------------------------------------------------------|----------|------------------------------------------------------------------------------------------------------------|
| `getToolDefinitionReturnsDetachedCopy`                        | Deep copy: top-level, nested schema, properties, required list, outputSchema | ✅ Full   | None                                                                                                       |
| `getRegisteredDefinitionsReturnDetachedCopies`                | All four list types: tools, resources, templates, prompts                    | ✅ Full   | None                                                                                                       |
| `concurrentUniqueToolRegistrationIsSafe`                      | 20 concurrent unique tools, all succeed, all retrievable                     | ✅ Full   | None                                                                                                       |
| `concurrentSameNameToolRegistrationProducesExactlyOneSuccess` | Race produces exactly 1 success + N-1 exceptions                             | ✅ Full   | None                                                                                                       |
| `concurrentReadsWhileWritingNeverThrow`                       | 5 writers + 5 readers, 100 reads each                                        | ✅ No CME | **Missing:** does not verify returned definitions are immutable copies vs live refs under concurrent write |
| `listenerNotificationOccursOnceAfterRegistration`             | Notification fires once per registration                                     | ✅ Full   | None                                                                                                       |
| `listenerExceptionDoesNotRollbackRegistration`                | Listener exception does not roll back completed registration                 | ✅ Full   | None                                                                                                       |
| `concurrentRegistrationWithAuthorizationMetadata`             | 10 tools with scopes + confirmationRequired, all succeed                     | ✅ Full   | None                                                                                                       |

**Gap:** `concurrentReadsWhileWritingNeverThrow` only asserts `assertNotNull(tool.get("name"))` and
`assertNotNull(tool.get("inputSchema"))` — it does not verify immutability under concurrent mutation. A concurrent
reader could see a partially-written map if the lock-based ordering ever regresses. Recommend adding mutation assertions
inside the reader loop.

---

## 4. Acceptance Criteria for Concurrency/Immutability Verification

### 4.1 Immutability Criteria

| Criterion                                                | Verification Method                                      | Pass Condition                               |
|----------------------------------------------------------|----------------------------------------------------------|----------------------------------------------|
| `getToolDefinition(name)` returns deep copy              | Mutate returned map, re-fetch, assert original unchanged | Nested maps, lists, primitives all unchanged |
| `getRegisteredTools()` returns deep copies               | Mutate returned list + nested maps, re-fetch             | Internal list size and contents unchanged    |
| Same for resources, templates, prompts                   | Same pattern                                             | Same pass condition                          |
| `toolDefinitionsByName` index never leaks live reference | Internal use of index always wrapped in `copyMap()`      | No raw map exposure from index               |

### 4.2 Concurrency Criteria

| Criterion                                                 | Verification Method                              | Pass Condition                                                       |
|-----------------------------------------------------------|--------------------------------------------------|----------------------------------------------------------------------|
| Concurrent unique registrations all succeed               | N threads register distinct tools                | All N tools present, all handlers retrievable                        |
| Concurrent duplicate-name registration: exactly 1 success | N threads register same name                     | 1 IllegalArgumentException, 1 tool present, handler + def consistent |
| Concurrent reads during concurrent writes: no CME         | 5 writers + 5 readers × 100 reads each           | Zero exceptions; each read sees fully-constructed definitions        |
| Listener exception does not roll back registration        | Register listener that throws                    | Registration completes; tool visible; exception not propagated       |
| Listener notification order: occurs after publication     | Add listener + register + verify notification    | Notification fires after getter would return new tool                |
| Handler + definition become visible atomically            | Concurrent reader checks both after registration | Never see handler without definition, or vice versa                  |

### 4.3 Lock Model Criteria

| Criterion                                                     | Verification Method                                   | Pass Condition                                       |
|---------------------------------------------------------------|-------------------------------------------------------|------------------------------------------------------|
| Registration lock prevents requireUnique race                 | Concurrent duplicate-name → consistent single success | Same as concurrency duplicate test                   |
| Reads do not block under concurrent registration              | Timed concurrent read + write                         | Read latency unaffected by write lock                |
| Lock type matches ADR contract (if ADR deviation is accepted) | N/A (deviation noted)                                 | Lock present; not removed without COWAL+CHM analysis |

---

## 5. Compatibility and Migration Notes

### 5.1 Breaking Change Assessment: NONE

| Change                                                        | Breaking?        | Rationale                                                    |
|---------------------------------------------------------------|------------------|--------------------------------------------------------------|
| ArrayList → CopyOnWriteArrayList                              | No               | Same `List<Map>` return type; COWAL is a drop-in             |
| `synchronized` removed from registration                      | No               | External callers do not synchronize on registry instance     |
| `synchronized(McpRegistry.class)` removed from `findMimeType` | No               | Private static method; callers pass list reference only      |
| `synchronized(source)` removed from `copyDefinitions`         | No               | Private static method                                        |
| `getToolDefinition()` now uses index + `copyMap()`            | No (improvement) | Returns same data shape; defensive copy is a hardening       |
| `requireUnique` now under `ReentrantLock`                     | No               | Same IllegalArgumentException contract                       |
| `notifyRegistryChanged` try-catch added                       | No               | Listener exception now swallowed; same notification contract |

### 5.2 Caller Migration Implications

**For `McpProtocolHandler` (primary consumer):**

- `registry.getToolDefinition(name)` — now returns a defensive copy; no caller changes needed. If callers cached the
  result for repeated access, they should call `getToolDefinition()` fresh each time (new copy per call).
- `registry.getRegisteredTools()` — already returned a copy; unchanged.
- `registry.getToolHandler(name)` — unchanged (CHM reference).
- Listener callback `onRegistryChanged(listType)` — now receives try-catch protection; a throwing listener no longer
  aborts subsequent listeners in the loop.

**For any other callers:**

- No source changes required.
- No behavioral changes observed by callers.
- The hardening (defensive copy) makes the API safer, not different.

### 5.3 Javadoc Compliance Gap

The ADR and design spec call for updating Javadocs to document defensive copy contracts. Current state:

- `getToolDefinition()` javadoc (lines 454-459): ✅ Documents "defensive copy"
- `getRegisteredTools()` javadoc (lines 418-420): ✅ Documents "defensive copies"
- `getRegisteredResources()` etc.: ✅ Consistent

**Compliance:** Javadoc is correctly updated.

---

## 6. Summary

### ADR-0015 Compliance: SUBSTANTIALLY COMPLIANT (1 deviation)

The implementation correctly achieves all four technical goals of ADR-0015:

1. ✅ CopyOnWriteArrayList for all four metadata lists
2. ✅ ConcurrentHashMap O(1) index for tool definitions
3. ✅ getToolDefinition() returns deep copy (fixes the critical security vulnerability)
4. ✅ Listener exception isolation

**Deviation:** The ADR explicitly proposed leaving registration methods unsynchronized (relying on COWAL + CHM
atomicity). The implementation uses `ReentrantLock`. The lock is not incorrect — it prevents the `requireUnique` race
condition cleanly — but it contradicts the ADR's stated rationale (eliminating lock contention for read-heavy
workloads). This should be documented as a spec deviation and either the ADR updated or the lock removed (with
`requireUnique` replaced by CHM `putIfAbsent`).

### Risk Summary

| ID  | Risk                                                                        | Severity | Likelihood | Action                                                                |
|-----|-----------------------------------------------------------------------------|----------|------------|-----------------------------------------------------------------------|
| R-1 | registrationLock contradicts ADR read-heavy optimization intent             | MEDIUM   | Low        | Update ADR or remove lock, replace requireUnique with putIfAbsent     |
| R-2 | toolDefinitionsByName holds live reference to mutable map                   | MEDIUM   | Low        | Accept if copyMap coverage is verified; add test for new field types  |
| R-3 | Concurrent reads test doesn't verify immutability under concurrent mutation | MEDIUM   | Medium     | Extend concurrentReadsWhileWritingNeverThrow with mutation assertions |
| R-4 | Three-phase vs two-phase publication inconsistency                          | LOW      | Low        | Document registration contract; no code change needed                 |

### Recommendation

The refactor is **ready for acceptance** with the following conditions:

1. Resolve the `registrationLock` / ADR-0015 deviation: either update ADR-0015 to document the conservative locking
   approach, or refactor `requireUnique` to use CHM `putIfAbsent` and remove the lock.
2. Add mutation-assertion loop to `concurrentReadsWhileWritingNeverThrow` reader threads.
3. Document the registration atomicity contract (metadata and handler published together under lock, listener notified
   after) in the class Javadoc.

---

*Review produced by t_c322a9bd — dev-backend — 2026-09-20*
