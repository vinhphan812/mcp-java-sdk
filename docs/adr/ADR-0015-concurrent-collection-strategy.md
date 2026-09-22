# ADR-0015: Concurrent Collection Strategy for Registry

**Status:** Accepted
**Date:** 2026-09-20
**Task:** t_c41f5db0

## Summary

Replace ArrayList-based metadata storage with thread-safe collections (CopyOnWriteArrayList), fix the mutable reference
leak in getToolDefinition(), and standardize locking hierarchy to eliminate contention and improve concurrent read
performance.

## Context

The McpRegistry class currently mixes three different concurrency strategies:

1. **Synchronized methods** on instance for registration (ArrayList metadata)
2. **ConcurrentHashMap** for handler storage (already thread-safe)
3. **CopyOnWriteArrayList** for change listeners (already thread-safe)

This creates several issues:

- Registration methods hold instance lock during all operations, blocking concurrent reads
- getToolDefinition() returns mutable internal Map, allowing unsynchronized mutation
- Inconsistent locking strategies (instance, class, and list-level) create maintenance burden

## Decision

We will implement a unified concurrent collection strategy with the following changes:

### 1. Replace ArrayList with CopyOnWriteArrayList for Metadata Storage

**Current:**

```java
private final List<Map<String, Object>> registeredTools = new ArrayList<>();
private final List<Map<String, Object>> registeredResources = new ArrayList<>();
private final List<Map<String, Object>> registeredResourceTemplates = new ArrayList<>();
private final List<Map<String, Object>> registeredPrompts = new ArrayList<>();
```

**Proposed:**

```java
private final List<Map<String, Object>> registeredTools = new CopyOnWriteArrayList<>();
private final List<Map<String, Object>> registeredResources = new CopyOnWriteArrayList<>();
private final List<Map<String, Object>> registeredResourceTemplates = new CopyOnWriteArrayList<>();
private final List<Map<String, Object>> registeredPrompts = new CopyOnWriteArrayList<>();
```

**Rationale:**

- CopyOnWriteArrayList provides thread-safe iteration without external synchronization
- Optimized for read-heavy workloads (MCP tools/resources are registered once at startup)
- Copy-on-write semantics are acceptable since registry modifications are rare after initialization
- Eliminates synchronized keyword from registration methods, improving concurrent read performance

### 2. Fix getToolDefinition() Mutable Reference Leak

**Current (INSECURE):**

```java
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {
        if (name.equals(tool.get("name"))) return tool; // Returns live object!
    }
    return null;
}
```

**Proposed (SECURE):**

```java
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {
        if (name.equals(tool.get("name"))) return copyMap(tool);
    }
    return null;
}
```

**Rationale:**

- Prevents external code from mutating security-sensitive fields (requiredScopes, confirmationRequired)
- Maintains defensive programming principle
- Already has copyMap() utility method available

### 3. Add ConcurrentHashMap Index for Tool Definitions

**Proposed new field:**

```java
private final ConcurrentHashMap<String, Map<String, Object>> toolDefinitionsByName = new ConcurrentHashMap<>();
```

**Registration update:**

```java
public synchronized void registerTool(...) {
    // ... existing logic ...
    registeredTools.add(tool);
    toolDefinitionsByName.put(name, tool);  // Add index
    toolHandlers.put(name, handler);
    notifyRegistryChanged("tools");
}
```

**Optimized getter:**

```java
public Map<String, Object> getToolDefinition(String name) {
    Map<String, Object> tool = toolDefinitionsByName.get(name);
    return tool != null ? copyMap(tool) : null;
}
```

**Rationale:**

- O(1) lookup instead of O(n) iteration
- Maintains defensive copy on read
- ConcurrentHashMap provides thread-safe putIfAbsent semantics

### 4. Remove Redundant Synchronization from Registration Methods

**Current:**

```java
public synchronized void registerTool(String name, String description, ...) {
    // synchronized on instance
}
```

**Proposed:**

```java
public void registerTool(String name, String description, ...) {
    // No synchronized needed - CopyOnWriteArrayList is thread-safe
    requireUnique(name, toolHandlers, "tool");
    // ... rest of implementation
}
```

**Rationale:**

- CopyOnWriteArrayList.add() is thread-safe
- ConcurrentHashMap.put() is thread-safe
- Removes lock contention between registration and concurrent readers
  -唯一需要同步的是requireUnique检查，但这可以在ConcurrentHashMap上使用原子操作

### 5. Keep Existing Thread-Safe Components

| Component                | Current Type         | Decision | Rationale                            |
|--------------------------|----------------------|----------|--------------------------------------|
| toolHandlers             | ConcurrentHashMap    | Keep     | O(1) lookup, thread-safe             |
| resourceHandlers         | ConcurrentHashMap    | Keep     | O(1) lookup, thread-safe             |
| resourceTemplateHandlers | ConcurrentHashMap    | Keep     | O(1) lookup, thread-safe             |
| promptHandlers           | ConcurrentHashMap    | Keep     | O(1) lookup, thread-safe             |
| tasks                    | ConcurrentHashMap    | Keep     | CAS-based state transitions          |
| completionProviders      | ConcurrentHashMap    | Keep     | Thread-safe putIfAbsent              |
| changeListeners          | CopyOnWriteArrayList | Keep     | Already optimal for listener pattern |
| notificationTarget       | volatile             | Keep     | Single-writer pattern is safe        |

### 6. Listener List Management

The current CopyOnWriteArrayList implementation for changeListeners is already optimal:

```java
private final CopyOnWriteArrayList<McpRegistryChangeListener> changeListeners = new CopyOnWriteArrayList<>();

public void addRegistryChangeListener(McpRegistryChangeListener listener) {
    if (listener != null) changeListeners.addIfAbsent(listener);
}

public void removeRegistryChangeListener(McpRegistryChangeListener listener) {
    if (listener != null) changeListeners.remove(listener);
}
```

**Rationale:**

- Add-if-absent and remove are O(n) but infrequent (happens at startup/shutdown)
- Iteration during notification is O(n) with no concurrent modification issues
- No locking needed during iteration

### 7. Read-Write Lock Analysis for Tool Definitions

**Access Patterns:**

1. **Write-heavy during initialization:** registerTool(), registerResource(), etc. - single-threaded bootstrap
2. **Read-heavy during runtime:** getToolDefinition(), getToolHandler() - concurrent per-request

**Analysis:**
| Operation | Current | Proposed | Benefit |
|-----------|---------|----------|---------|
| Registration | synchronized method | Unsynchronized | No lock contention |
| getToolHandler() | None (CHM) | None | Lock-free |
| getToolDefinition() | None | None (copy on read) | Lock-free |
| getRegisteredTools() | synchronized(list) | None (COWAL) | Lock-free |
| getResourceMimeType() | synchronized(class) | None (COWAL) | Lock-free |

**Conclusion:** No explicit ReadWriteLock needed. CopyOnWriteArrayList + ConcurrentHashMap provide better concurrency
for this read-heavy workload.

## Consequences

### Positive

- Eliminated lock contention between registration and concurrent reads
- Fixed security vulnerability (mutable reference leak)
- O(1) tool definition lookup with index
- Simplified locking model (fewer synchronization strategies)
- Better performance for high-concurrency scenarios

### Negative

- CopyOnWriteArrayList has slightly higher memory overhead for large registries
- Registration operations create full array copy (acceptable for startup-only pattern)
- Migration effort for existing code using synchronized registration

### Migration Path

1. Add toolDefinitionsByName index field
2. Update registerTool() to populate index (keep synchronized for requireUnique)
3. Update getToolDefinition() to use index and return copy
4. Replace ArrayList with CopyOnWriteArrayList
5. Remove synchronized from registration methods
6. Update getRegisteredTools() to remove synchronized(source) - COWAL handles it

## References

- McpRegistry-Technical-Analysis.md - Detailed current state analysis
- ADR-0011-security-rate-limiting.md - Rate limiting context
- Java Concurrency in Practice - CopyOnWriteArrayList guidelines
