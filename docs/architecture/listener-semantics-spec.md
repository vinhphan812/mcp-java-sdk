# Listener Semantics Specification and Migration Guide

## Executive Summary

This document specifies the listener notification semantics for the MCP Java SDK, documents safe notification patterns,
addresses backward compatibility for existing mutable getter users, and analyzes the performance implications of
immutability on listener callbacks.

---

## 1. How Listeners Receive Metadata Updates

### 1.1 Listener Types

The SDK provides three listener interfaces:

| Listener                    | Purpose                                           | Notification Trigger                                       |
|-----------------------------|---------------------------------------------------|------------------------------------------------------------|
| `McpRegistryChangeListener` | Notifies after tool/resource/prompt registration  | `registerTool()`, `registerResource()`, `registerPrompt()` |
| `McpResourceUpdateListener` | Notifies when a resource changes externally       | `notifyResourceUpdated(uri)` called by application         |
| `QueueOverflowListener`     | Notifies when notification queue exceeds capacity | Bounded queue overflow in `McpProtocolHandler`             |

### 1.2 Registry Change Listener

```java
// Interface (McpRegistryChangeListener.java)
public interface McpRegistryChangeListener {
    void onRegistryChanged(String listType);  // "tools", "resources", or "prompts"
}
```

**Notification flow:**

1. Application calls `registry.registerTool(...)`, `registry.registerResource(...)`, or `registry.registerPrompt(...)`
2. `McpRegistry` calls `notifyRegistryChanged(listType)`
3. All registered listeners receive `onRegistryChanged(listType)`
4. `McpProtocolHandler.onRegistryChanged()` broadcasts `notifications/{listType}/list_changed` to all sessions

**Registration:**

```java
registry.addRegistryChangeListener(listener);
registry.

removeRegistryChangeListener(listener);
```

### 1.3 Resource Update Listener

```java
// Interface (McpResourceUpdateListener.java)
public interface McpResourceUpdateListener {
    void notifyResourceUpdated(String uri);
}
```

**Notification flow:**

1. Application calls `registry.notifyResourceUpdated(uri)`
2. `McpRegistry` forwards to configured `McpResourceUpdateListener` target
3. `McpProtocolHandler.notifyResourceUpdated(uri)` queues `notifications/resources/updated` to subscribed sessions

**Configuration:**

```java
registry.setNotificationTarget(new McpResourceUpdateListener() {
    @Override
    public void notifyResourceUpdated (String uri){
        // Application decides how to handle
    }
});
```

### 1.4 Queue Overflow Listener

```java
// Interface (McpProtocolHandler.java)
public interface QueueOverflowListener {
    void onOverflow(String sessionId);
}
```

**Configuration via `McpServerConfig`:**

```java
McpServerConfig config = McpServerConfig.builder()
        .overflowListener(sessionId -> {
            logger.warn("Notification queue overflow for session: " + sessionId);
        })
        .build();
```

---

## 2. Safe Notification Patterns (Copy Before Notify)

### 2.1 Current Implementation: Defensive Copying

All public getters in `McpRegistry` return **defensive copies**:

```java
// McpRegistry.java lines 371-394
public List<Map<String, Object>> getRegisteredTools() {
    return copyDefinitions(registeredTools);
}

public List<Map<String, Object>> getRegisteredResources() {
    return copyDefinitions(registeredResources);
}

public List<Map<String, Object>> getRegisteredResourceTemplates() {
    return copyDefinitions(registeredResourceTemplates);
}

public List<Map<String, Object>> getRegisteredPrompts() {
    return copyDefinitions(registeredPrompts);
}
```

**Copy utilities:**

- `copyDefinitions(List)` - deep copies list of definitions
- `copyMap(Map)` - deep copies nested maps
- `copyList(List)` - deep copies nested lists

### 2.2 Listener Notification Pattern

**Current (safe):** Listeners receive immutable copies, not internal state.

```java
// McpRegistry.notifyRegistryChanged() - line 355-357
private void notifyRegistryChanged(String listType) {
    for (McpRegistryChangeListener listener : changeListeners)
        listener.onRegistryChanged(listType);
}
```

**Important:** The notification itself passes only a `String` (listType), not the metadata. Callers must use getters to
retrieve current state:

```java
// Listener implementation pattern
public class MyListener implements McpRegistryChangeListener {
    @Override
    public void onRegistryChanged(String listType) {
        // Always fetch fresh copies - never cache the result
        List<Map<String, Object>> tools = registry.getRegisteredTools();
        List<Map<String, Object>> resources = registry.getRegisteredResources();
        // Process...
    }
}
```

### 2.3 Recommended Pattern: Copy Before Notify

When implementing custom notification mechanisms:

```java
// AVOID: Exposing internal state
public void onDataChanged() {
    listener.onChange(internalList);  // BAD - exposes mutable reference
}

// PREFERRED: Pass immutable copy
public void onDataChanged() {
    List<Map<String, Object>> copy = new ArrayList<>(internalList);
    listener.onChange(Collections.unmodifiableList(copy));  // GOOD
}
```

---

## 3. Backward Compatibility for Existing Mutable Getter Users

### 3.1 Current API Guarantees

The existing API already provides **immutable snapshots**:

| Method                              | Return Type                       | Thread-Safe   | Can Caller Modify?      |
|-------------------------------------|-----------------------------------|---------------|-------------------------|
| `getRegisteredTools()`              | `List<Map<String, Object>>`       | Yes (copy)    | No - new copy each call |
| `getRegisteredResources()`          | `List<Map<String, Object>>`       | Yes (copy)    | No - new copy each call |
| `getRegisteredToolDefinition(name)` | `Map<String, Object>`             | Yes (copy)    | No - new copy each call |
| `getToolHandler(name)`              | `McpToolHandler`                  | Yes           | N/A - handler is stable |
| `getResourceTemplateHandlers()`     | `Map<String, McpResourceHandler>` | Yes (new map) | No - new map each call  |

### 3.2 Migration Path for Existing Code

**Before (assuming mutability):**

```java
List<Map<String, Object>> tools = registry.getRegisteredTools();
tools.

add(newTool);  // No effect on registry - just modifies local copy
```

**After (explicit immutable):**

```java
List<Map<String, Object>> tools = registry.getRegisteredTools();
// tools is already a copy; modification has no effect
// To make intent clear:
List<Map<String, Object>> tools = Collections.unmodifiableList(
        registry.getRegisteredTools()
);
```

### 3.3 Breaking Changes Assessment

**No breaking changes** - The current implementation already returns copies. Existing code that:

- Reads from getters: Works unchanged
- Modifies returned collections: Has no effect (was always true)
- Stores reference for later use: Gets stale snapshot (was always true)

---

## 4. Performance Impact of Immutability on Listener Callbacks

### 4.1 Copy Overhead Analysis

| Operation              | Time Complexity | Notes                              |
|------------------------|-----------------|------------------------------------|
| `getRegisteredTools()` | O(n)            | n = number of registered tools     |
| `copyDefinitions()`    | O(n × m)        | n = items, m = avg fields per item |
| `copyMap()`            | O(m)            | m = fields in single definition    |
| `copyList()`           | O(k)            | k = elements in list               |

### 4.2 Benchmark Considerations

**Typical workload:** 10-100 registered tools/resources

- Per-getter overhead: ~0.1-1ms for shallow copies
- Deep copy of complex schemas: ~1-10ms

**Mitigation strategies:**

1. **Cache frequently accessed data:**

```java
// Instead of repeated calls
private volatile List<Map<String, Object>> cachedTools;

public void onRegistryChanged(String listType) {
    if ("tools".equals(listType)) {
        cachedTools = registry.getRegisteredTools();
    }
}
```

2. **Lazy initialization with invalidation:**

```java
private volatile List<Map<String, Object>> toolsCache;

public List<Map<String, Object>> getCachedTools() {
    List<Map<String, Object>> cached = toolsCache;
    if (cached == null) {
        cached = registry.getRegisteredTools();
        toolsCache = cached;
    }
    return cached;
}

@Override
public void onRegistryChanged(String listType) {
    if ("tools".equals(listType)) {
        toolsCache = null;  // Invalidate
    }
}
```

3. **Incremental updates:**

```java
// Instead of full copy, track delta
public void onRegistryChanged(String listType) {
    switch (listType) {
        case "tools":
            notifyToolAdditions(getNewTools());
            break;
    }
}
```

### 4.3 Listener Performance Best Practices

1. **Minimize work in listener callbacks:**

```java
// BAD - heavy processing blocks notification
public void onRegistryChanged(String listType) {
    rebuildEntireIndex();  // Slow!
}

// GOOD - defer work
public void onRegistryChanged(String listType) {
    executor.submit(() -> rebuildIndex(listType));
}
```

2. **Use read-only snapshots:**

```java
public void onRegistryChanged(String listType) {
    List<Map<String, Object>> snapshot = registry.getRegisteredTools();
    // Process snapshot, not registry directly
}
```

3. **Avoid blocking in callbacks:**

```java
public void onRegistryChanged(String listType) {
    // NEVER block here - use async processing
    asyncProcessor.submit(() -> handleChange(listType));
}
```

---

## 5. API Reference

### 5.1 McpRegistry Listener Methods

```java
public class McpRegistry implements McpRegistrar {
    // Registry change notifications
    public void addRegistryChangeListener(McpRegistryChangeListener listener);

    public void removeRegistryChangeListener(McpRegistryChangeListener listener);

    // Resource update notifications  
    public void setNotificationTarget(McpResourceUpdateListener target);

    public void notifyResourceUpdated(String uri);

    // Data access (already returns copies)
    public List<Map<String, Object>> getRegisteredTools();

    public List<Map<String, Object>> getRegisteredResources();

    public List<Map<String, Object>> getRegisteredResourceTemplates();

    public List<Map<String, Object>> getRegisteredPrompts();

    public Map<String, Object> getToolDefinition(String name);
}
```

### 5.2 McpProtocolHandler Listener Methods

```java
public class McpProtocolHandler implements McpRegistryChangeListener {
    // Queue overflow handling
    public interface QueueOverflowListener {
        void onOverflow(String sessionId);
    }

    // Resource update notifications
    public void notifyResourceUpdated(String uri);

    public String pollResourceNotification(String sessionId);
}
```

---

## 6. Migration Checklist

- [ ] Replace any caching of getter results with fresh calls or explicit cache invalidation
- [ ] Move heavy processing out of listener callbacks to async handlers
- [ ] Remove any code that modifies returned collections (was always ineffective)
- [ ] Add queue overflow handling for high-throughput scenarios
- [ ] Test listener behavior under concurrent registration scenarios

---

## 7. Appendix: Code Locations

| File                                                      | Purpose                          |
|-----------------------------------------------------------|----------------------------------|
| `src/main/java/.../McpRegistry.java`                      | Registry + listener management   |
| `src/main/java/.../McpProtocolHandler.java`               | Protocol handler + notifications |
| `src/main/java/.../spi/McpRegistryChangeListener.java`    | Listener interface               |
| `src/main/java/.../spi/McpResourceUpdateListener.java`    | Resource update interface        |
| `src/main/java/.../api/events/QueueOverflowListener.java` | Overflow listener interface      |
