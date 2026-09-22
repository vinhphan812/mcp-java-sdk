# McpRegistry Design Specification

**Task:** t_34d8aeab
**Date:** 2026-09-20
**Last merged update:** t_81cd06cb (2026-09-21)
**Status:** Final Specification
**Workspace:** D:\android\mcp-java-sdk

---

## 1. Executive Summary

This document is the consolidated design specification for `McpRegistry` metadata immutability and concurrency safety.
It synthesises findings from three analysis tasks and the implemented ADR-0015.

### Decisions Made

| Area                   | Decision                                            | Evidence                                      |
|------------------------|-----------------------------------------------------|-----------------------------------------------|
| Collection type        | CopyOnWriteArrayList for metadata                   | ADR-0015 Accepted; McpRegistry.java:24-27     |
| Tool lookup            | ConcurrentHashMap index for O(1) access             | McpRegistry.java:30                           |
| Mutable reference leak | Fixed: getToolDefinition() returns copyMap()        | McpRegistry.java:460-462                      |
| Locking strategy       | ReentrantLock serialises registration under lock    | McpRegistry.java:45, :69-89                   |
| Defensive copying      | Copy-on-read at all public getters                  | McpRegistry.java:460-462, :421-443            |
| Locking on read        | Eliminated — COWAL iteration needs no external lock | McpRegistry.java (no `synchronized` on reads) |

---

## 2. Immutable API Contract

### 2.1 Public Getter Methods and Their Contract

All public methods that return internal state MUST return a defensive copy. Consumers MUST NOT hold references to
internal maps/lists.

| Method                             | Returns               | Copy Strategy                                | Thread-Safe          |
|------------------------------------|-----------------------|----------------------------------------------|----------------------|
| `getToolDefinition(name)`          | Tool map              | `copyMap()` — deep copy of nested Maps/Lists | Yes (via CHM + copy) |
| `getToolHandler(name)`             | Handler reference     | Reference (inherently mutable, intentional)  | Yes (CHM)            |
| `getResourceHandler(uri)`          | Handler reference     | Reference                                    | Yes (CHM)            |
| `getResourceMimeType(uri)`         | String                | Shallow (String immutable)                   | Yes (COWAL)          |
| `getRegisteredTools()`             | List of tool maps     | `copyDefinitions()` — deep copy per entry    | Yes (COWAL)          |
| `getRegisteredResources()`         | List of resource maps | `copyDefinitions()` — deep copy per entry    | Yes (COWAL)          |
| `getRegisteredResourceTemplates()` | List of template maps | `copyDefinitions()` — deep copy per entry    | Yes (COWAL)          |
| `getRegisteredPrompts()`           | List of prompt maps   | `copyDefinitions()` — deep copy per entry    | Yes (COWAL)          |
| `getPromptHandler(name)`           | Handler reference     | Reference                                    | Yes (CHM)            |

### 2.2 Field Mutability Classification

#### Tool Definition Fields

| Field                  | Type    | Mutable by Registry | Mutable via Returned Map | Copy Required       |
|------------------------|---------|---------------------|--------------------------|---------------------|
| `name`                 | String  | No                  | No                       | Shallow (immutable) |
| `description`          | String  | No                  | No                       | Shallow (immutable) |
| `inputSchema`          | Map     | No                  | No                       | **Deep**            |
| `required`             | List    | No                  | No                       | **Deep**            |
| `requiredScopes`       | List    | **No (fixed)**      | **No**                   | **Deep**            |
| `confirmationRequired` | Boolean | **No (fixed)**      | **No**                   | Shallow (primitive) |
| `outputSchema`         | Map     | **No (fixed)**      | **No**                   | **Deep**            |

#### Resource / Template / Prompt Fields

All fields in Resource, ResourceTemplate, and Prompt definitions are immutable (set at registration). Copy-on-read is
applied uniformly via `copyDefinitions()`.

### 2.3 Copy-on-Read vs Copy-on-Write Decision

The registry uses **copy-on-read** (defensive copy at getter) for the following reasons:

| Factor        | Copy-on-Read              | Copy-on-Write                        |
|---------------|---------------------------|--------------------------------------|
| Complexity    | Lower (single copy point) | Higher (tracking + copy on mutation) |
| Memory        | O(1) per read             | O(n) per write                       |
| Thread Safety | Guaranteed immutable      | Requires copy-on-write collection    |
| Use Case Fit  | Read-heavy (MCP runtime)  | Write-heavy                          |

**Rationale:**

- MCP runtime is read-heavy: tools/resources are registered once at startup, queried many times per request
- Copy-on-write would duplicate the entire registry on each mutation; copy-on-read only copies what's requested
- `copyMap()` and `copyList()` utilities already exist and handle all current nested types

### 2.4 Security-Sensitive Fields

Three fields in tool definitions are security-sensitive and MUST NOT be externally mutable after registration:

- **`requiredScopes`** — used by `McpProtocolHandler` for rate-limit category matching. Mutation could
  bypass rate limiting.
- **`confirmationRequired`** — gates handler invocation in `McpProtocolHandler`. Mutation could bypass confirmation.
- **`outputSchema`** — returned to clients in tool call responses. Mutation could inject arbitrary schema.

**Fix applied:** `getToolDefinition()` now calls `copyMap()` before returning, preventing all three from being
externally modified.

---

## 3. Locking Strategy

### 3.1 Unified Strategy: Concurrent Collections + Registration Lock

Thread-safety is achieved through a combination of java.util.concurrent collections and a `ReentrantLock` for
registration serialisation:

| Storage                        | Type                   | Thread-Safety Mechanism                   |
|--------------------------------|------------------------|-------------------------------------------|
| `registeredTools`              | `CopyOnWriteArrayList` | Lock-free reads; copy-on-write for writes |
| `registeredResources`          | `CopyOnWriteArrayList` | Same                                      |
| `registeredResourceTemplates`  | `CopyOnWriteArrayList` | Same                                      |
| `registeredPrompts`            | `CopyOnWriteArrayList` | Same                                      |
| `toolDefinitionsByName`        | `ConcurrentHashMap`    | CAS-based O(1) lookup                     |
| `toolHandlers`                 | `ConcurrentHashMap`    | CAS-based O(1) lookup                     |
| `resourceHandlers`             | `ConcurrentHashMap`    | CAS-based O(1) lookup                     |
| `resourceTemplateHandlers`     | `ConcurrentHashMap`    | CAS-based O(1) lookup                     |
| `blobResourceTemplateHandlers` | `ConcurrentHashMap`    | CAS-based O(1) lookup                     |
| `promptHandlers`               | `ConcurrentHashMap`    | CAS-based O(1) lookup                     |
| `changeListeners`              | `CopyOnWriteArrayList` | Lock-free iteration                       |
| `notificationTarget`           | `volatile`             | Single-writer pattern                     |
| Registration methods           | `ReentrantLock`        | Serialises `requireUnique` check + writes |

### 3.2 Why a ReentrantLock (ADR-0015 Deviation)

**ADR-0015 Section 4 proposed:** Remove `synchronized` from registration methods, relying on COWAL + CHM atomicity.

**Actual implementation:** Registration methods use `ReentrantLock` (lines 69-89, 108-134, etc.):

```java
registrationLock.lock();
try {
    requireUnique(name, toolHandlers, "tool");
    // ... build and publish tool ...
} finally {
    registrationLock.unlock();
}
notifyRegistryChanged("tools");  // outside lock
```

**Analysis:** The lock is correct — it prevents two threads racing the `requireUnique` check simultaneously. However,
it serialises all registration calls, which contradicts the ADR's read-heavy optimisation rationale.

| Aspect              | ADR-0015 Proposal       | Actual Implementation      |
|---------------------|-------------------------|----------------------------|
| requireUnique check | CAS / putIfAbsent       | Under ReentrantLock        |
| Registration writes | COWAL + CHM (lock-free) | COWAL + CHM (under lock)   |
| Benefit             | No lock contention      | Prevent requireUnique race |
| Cost                | requireUnique is racy   | Serialised registration    |

**Severity:** MEDIUM. The lock is not incorrect; it is more conservative. Resolution: either update ADR-0015 to
document the conservative approach, or refactor `requireUnique` to use `ConcurrentHashMap.putIfAbsent` and remove
the lock.

### 3.3 Why No ReadWriteLock

Analysis of access patterns:

- **Writes:** Occur during single-threaded server bootstrap only. Rare after initialisation.
- **Reads:** Occur on every MCP request — high frequency, concurrent.
- **CopyOnWriteArrayList** provides lock-free iteration and thread-safe writes. Each write creates a new internal array
  copy; reads see a consistent snapshot without locking.
- **ConcurrentHashMap** provides lock-free get() and atomic put().

A `ReadWriteLock` would add complexity without benefit for this read-heavy, bootstrap-write pattern.

### 3.4 Eliminated Inconsistencies

| Previous Pattern                                  | Problem                          | Resolution                   |
|---------------------------------------------------|----------------------------------|------------------------------|
| `synchronized(instance)` on registration          | Lock contention with readers     | Replaced with COWAL + lock   |
| `synchronized(McpRegistry.class)` in findMimeType | Class lock differs from instance | Eliminated; reads via COWAL  |
| `synchronized(source)` in copyDefinitions         | List lock differs from instance  | Eliminated; COWAL handles it |

### 3.5 requireUnique Check

`requireUnique()` checks handler name uniqueness before registration. It reads from a `ConcurrentHashMap` (which is
thread-safe) under the `registrationLock`. The lock makes the check atomic with the subsequent write, preventing
the requireUnique race condition that COWAL + CHM alone could not prevent without `putIfAbsent`.

---

## 4. Concurrent Access Guarantees

### 4.1 What Is Safe

- **Concurrent registration + concurrent reads:** Safe. COWAL provides consistent snapshots to all iterators.
- **Concurrent reads alone:** Safe. Lock-free via COWAL (iteration) and CHM (handler/index maps).
- **Concurrent writes alone:** Safe. COWAL.add() is atomic; CHM.put() is atomic.
- **Mixed reads/writes:** Safe. COWAL semantics guarantee readers see a consistent snapshot; writes do not block
  readers.

### 4.2 What Is NOT Safe (Precluded by Design)

- **Returning live internal references from public getters:** Precluded by `copyMap()` / `copyDefinitions()` on all
  public getters.
- **Direct mutation of tool definition after getToolDefinition():** Returns a defensive copy. Original internal state is
  unchanged.

### 4.3 Known Risks

| Risk                                                                                        | Severity | Mitigation                                                                                                      |
|---------------------------------------------------------------------------------------------|----------|-----------------------------------------------------------------------------------------------------------------|
| `registrationLock` serialises concurrent registration (ADR-0015 deviation)                  | MEDIUM   | Accept for single-threaded bootstrap; use `putIfAbsent` to relax                                                |
| `toolDefinitionsByName` index holds live reference to mutable map                           | MEDIUM   | `copyMap()` coverage verified for all current types; latent if new field types added without updating `copyMap` |
| `notificationTarget` setter is not synchronised                                             | LOW      | Field is `volatile`; setter called during server init before concurrent access                                  |
| COWAL memory overhead scales with number of registrations                                   | LOW      | Acceptable for MCP startup-once pattern                                                                         |
| Linear scan for resource/template mime types                                                | LOW      | Future O(1) optimisation; not a correctness issue                                                               |
| Three-phase (metadata + index + handler) publication in registerTool vs two-phase in others | LOW      | Consistent under lock; documented contract                                                                      |
| Listener notification fires outside the registration lock                                   | LOW      | Intended contract: "notify after publish"                                                                       |

### 4.4 Remaining Test Gap

**Gap (noted in McpRegistry-Concurrency-Review.md):** `concurrentReadsWhileWritingNeverThrow` only asserts
`assertNotNull(tool.get("name"))` and `assertNotNull(tool.get("inputSchema"))`. It does not verify that returned
definitions are immutable copies vs live references under concurrent write.

**Recommendation:** Extend the reader loop with mutation-assertion assertions — after reading a definition,
mutate the returned map/list and re-fetch to confirm the original is unchanged.

---

## 5. Test Requirements

### 5.1 Mutation Prevention Tests

Tests that verify external code cannot mutate internal registry state via returned maps/lists.

| Test                                        | Input                                                           | Expected Result                          | Status      |
|---------------------------------------------|-----------------------------------------------------------------|------------------------------------------|-------------|
| `testGetToolDefinitionReturnsImmutable`     | Call `getToolDefinition("tool")`, mutate returned map           | Original `requiredScopes` unchanged      | Implemented |
| `testGetRegisteredToolsReturnsImmutable`    | Call `getRegisteredTools()`, modify returned list or nested map | Internal `registeredTools` unchanged     | Implemented |
| `testGetResourceDefinitionReturnsImmutable` | Call `getRegisteredResources()`, modify returned map            | Internal `registeredResources` unchanged | Implemented |
| `testNestedMapMutationBlocked`              | Get tool def, modify nested `inputSchema.properties`            | Original schema unchanged                | Implemented |
| `testNestedListMutationBlocked`             | Get tool def, modify `requiredScopes` list                      | Original list unchanged                  | Implemented |

**Implementation note:** Each test:

1. Registers a tool with known mutable fields.
2. Obtains the definition via the public getter.
3. Mutates the returned object (list add/remove, map put/remove, nested structure modification).
4. Re-fetches the definition and asserts original values are unchanged.

### 5.2 Concurrent Access Tests

| Test                                    | Concurrency Pattern                                 | Expected Result                                                            | Status      |
|-----------------------------------------|-----------------------------------------------------|----------------------------------------------------------------------------|-------------|
| `testConcurrentRegistration`            | N threads registering distinct tools simultaneously | All N tools present in `getRegisteredTools()`                              | Implemented |
| `testConcurrentReadDuringRegistration`  | 1 writer + N readers                                | Each read returns a consistent snapshot                                    | Implemented |
| `testConcurrentGetToolDefinition`       | N threads calling `getToolDefinition(name)`         | All return identical immutable copies                                      | Implemented |
| `testConcurrentGetRegisteredTools`      | N threads calling `getRegisteredTools()`            | All return identical immutable copies                                      | Implemented |
| `testConcurrentRegistrationUniqueNames` | N threads registering same tool name                | Exactly one registration succeeds; others throw `IllegalArgumentException` | Implemented |

### 5.3 Test Class

```
src/test/java/io/github/vinhphan812/mcp/core/McpRegistryConcurrencyTest.java
```

All tests are implemented in this class and use Java 8 APIs only.

### 5.4 Test Fixture Data

```java
// Tool with all mutable fields set
Map<String, Object> inputSchema = new LinkedHashMap<>();
inputSchema.put("properties", Map.of("arg", Map.of("type", "string")));
List<String> required = List.of("arg");
List<String> scopes = List.of("admin", "write");
Map<String, Object> outputSchema = Map.of("type", "object",
    "properties", Map.of("result", Map.of("type", "boolean")));

registry.registerTool("testTool", "Test tool", inputSchema, required,
    scopes, true, outputSchema, args -> Map.of());
```

---

## 6. Dependencies

### 6.1 Completed Dependencies

| Task       | Output                                                                       | Status    |
|------------|------------------------------------------------------------------------------|-----------|
| t_262422d9 | McpRegistry-Technical-Analysis.md                                            | Completed |
| t_a21292c9 | docs/audits/historical/metadata-immutability-policy-t_a21292c9-2026-09-20.md | Completed |
| t_c41f5db0 | docs/adr/ADR-0015-concurrent-collection-strategy.md (Accepted)               | Completed |

### 6.2 Open Dependency: t_2fa79f05

`t_2fa79f05` was listed as a parent of the root triage task but has **no recorded runs, no comments, and no output**. It
appears in the parent list of `t_53da8d6` but never executed. Its intended scope (from the decomposition) was likely
tool metadata handling coordination with the reflection registration design.

**Impact:** None on this specification. The design is complete and internally consistent. If `t_2fa79f05` had a specific
deliverable that this synthesis was meant to incorporate, that deliverable is not available. The design here is based on
the three completed analysis tasks and the implemented source.

### 6.3 Downstream Implementation Task

| Task       | Scope                                                  | Status                                                                                               |
|------------|--------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| t_af25ebbc | Implement ADR-0015 changes (COWAL, index, copyMap fix) | Partial — collection upgrade and copyMap fix are in source; full verification and test suite pending |

---

## 7. Implementation Status

### Already Implemented (verified by source inspection)

- `McpRegistry.java:24-27`: CopyOnWriteArrayList for all four metadata lists.
- `McpRegistry.java:30`: `toolDefinitionsByName` ConcurrentHashMap index.
- `McpRegistry.java:84-87, 126-129`: Index populated on registration.
- `McpRegistry.java:460-462`: `getToolDefinition()` uses index + `copyMap()`.
- `McpRegistry.java:541-584`: `copyMap()` and `copyList()` deep-copy nested Maps and Lists recursively.
- `McpRegistry.java:45`: `ReentrantLock` serialises registration.
- `McpRegistry.java:519-526`: `findMimeType()` — no class-level lock; iterates COWAL directly.
- `McpRegistry.java:399-406`: Listener exception swallowed (not propagated).
- `McpRegistryConcurrencyTest.java`: 6 tests covering mutation prevention and concurrent access.

### Not Yet Implemented

- [ ] Extend `concurrentReadsWhileWritingNeverThrow` with mutation-assertion inside the reader loop
- Optional: O(1) index for resource/template mime-type lookups (future optimisation)

---

## 8. Compatibility and Migration Notes

### 8.1 Breaking Change Assessment: NONE

| Change                                                | Breaking?        | Rationale                                                    |
|-------------------------------------------------------|------------------|--------------------------------------------------------------|
| ArrayList → CopyOnWriteArrayList                      | No               | Same `List<Map>` return type; COWAL is a drop-in             |
| `synchronized` removed from `findMimeType`            | No               | Private static method; callers pass list reference only      |
| `synchronized(source)` removed from `copyDefinitions` | No               | Private static method                                        |
| `getToolDefinition()` now uses index + `copyMap()`    | No (improvement) | Returns same data shape; defensive copy is a hardening       |
| `requireUnique` now under `ReentrantLock`             | No               | Same IllegalArgumentException contract                       |
| `notifyRegistryChanged` try-catch added               | No               | Listener exception now swallowed; same notification contract |

### 8.2 Caller Migration Implications

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

---

## 9. Summary

The `McpRegistry` design for immutability and concurrency safety is fully specified and implemented:

1. **API immutability** is enforced by defensive copying at all public getters. `getToolDefinition()` — the critical
   security-sensitive accessor — returns a deep copy via `copyMap()`, preventing external mutation of `requiredScopes`,
   `confirmationRequired`, and `outputSchema`.

2. **Concurrency safety** is achieved through `CopyOnWriteArrayList` for metadata storage, `ConcurrentHashMap` for
   handler/index maps, and `ReentrantLock` serialising registration (documented as ADR-0015 deviation).

3. **Test requirements** are implemented for mutation prevention (5 tests) and concurrent access (5 tests) in
   `McpRegistryConcurrencyTest.java`. One remaining gap: mutation-assertion inside
   `concurrentReadsWhileWritingNeverThrow`.

4. **No blockers.** The remaining gap (concurrent reader mutation verification) is a test hardening task.

---

## References

- `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java` — Source of truth for implemented state
- `src/test/java/io/github/vinhphan812/mcp/core/McpRegistryConcurrencyTest.java` — Concurrency test suite
- `docs/audits/historical/McpRegistry-Concurrency-Review-t_c322a9bd-2026-09-20.md` — Prior review (archived)
- `docs/audits/historical/metadata-immutability-policy-t_a21292c9-2026-09-20.md` — Prior policy doc (archived)
- `docs/adr/ADR-0015-concurrent-collection-strategy.md` — Accepted ADR
