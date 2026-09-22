# McpRegistry Technical Analysis: Locking and Metadata Structure

**Task:** t_262422d9  
**Date:** 2026-09-20  
**Workspace:** D:\android\mcp-java-sdk

---

## 1. Current Locking Strategy

### Instance-Level Locks (synchronized on `this`)

All registration methods use instance-level synchronization:

| Method                              | Lock Target                |
|-------------------------------------|----------------------------|
| `registerTool(...)` (3 overloads)   | `synchronized` on instance |
| `registerResource(...)`             | `synchronized` on instance |
| `registerBlobResource(...)`         | `synchronized` on instance |
| `registerResourceTemplate(...)`     | `synchronized` on instance |
| `registerBlobResourceTemplate(...)` | `synchronized` on instance |
| `registerPrompt(...)`               | `synchronized` on instance |

### Class-Level Locks (synchronized on `McpRegistry.class`)

The `findMimeType()` method uses class-level locking:

```java
// McpRegistry.java:466-479
private static String findMimeType(List<Map<String, Object>> definitions, String key, String value) {
    synchronized (McpRegistry.class) {  // CLASS lock, NOT instance
        for (Map<String, Object> definition : definitions) {
            if (value != null && value.equals(definition.get(key))) {
                return (String) definition.get("mimeType");
            }
        }
    }
    return null;
}
```

### List-Level Locks (synchronized on source list)

The `copyDefinitions()` method uses list-level synchronization:

```java
// McpRegistry.java:494-502
private static List<Map<String, Object>> copyDefinitions(List<Map<String, Object>> source) {
    synchronized (source) {  // Lock on the list OBJECT, not instance
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> definition : source) {
            copy.add(copyMap(definition));
        }
        return copy;
    }
}
```

### Thread-Safe Collections

- `ConcurrentHashMap` for handlers: `toolHandlers`, `resourceHandlers`, `resourceTemplateHandlers`,
  `blobResourceTemplateHandlers`, `promptHandlers`, `tasks`, `completionProviders`, `cancelledRequests`
- `CopyOnWriteArrayList` for `changeListeners`
- `volatile` for `notificationTarget`

### Thread-Unsafe Collections

- `ArrayList` for metadata: `registeredTools`, `registeredResources`, `registeredResourceTemplates`, `registeredPrompts`

---

## 2. All Metadata Fields

### Tool Definition Fields (returned by getToolDefinition)

| Field                  | Type    | Mutable | Source                            |
|------------------------|---------|---------|-----------------------------------|
| `name`                 | String  | No      | Set at registration               |
| `description`          | String  | No      | Set at registration               |
| `inputSchema`          | Map     | No      | Deep copied at registration       |
| `required`             | List    | No      | Deep copied at registration       |
| `requiredScopes`       | List    | **YES** | Set conditionally at registration |
| `confirmationRequired` | Boolean | **YES** | Set conditionally at registration |
| `outputSchema`         | Map     | **YES** | Set conditionally at registration |

### Resource Definition Fields

| Field         | Type   | Mutable | Source                     |
|---------------|--------|---------|----------------------------|
| `uri`         | String | No      | Set at registration        |
| `name`        | String | No      | Set at registration        |
| `description` | String | No      | Set at registration        |
| `mimeType`    | String | No      | Normalized at registration |

### Resource Template Fields

| Field         | Type   | Mutable | Source                     |
|---------------|--------|---------|----------------------------|
| `uriTemplate` | String | No      | Set at registration        |
| `name`        | String | No      | Set at registration        |
| `description` | String | No      | Set at registration        |
| `mimeType`    | String | No      | Normalized at registration |

### Prompt Definition Fields

| Field         | Type   | Mutable | Source                      |
|---------------|--------|---------|-----------------------------|
| `name`        | String | No      | Set at registration         |
| `description` | String | No      | Set at registration         |
| `arguments`   | List   | No      | Deep copied at registration |

---

## 3. Mutation Paths

### getToolDefinition - Returns Live Mutable Map (CRITICAL ISSUE)

```java
// McpRegistry.java:405-410
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {  // Iterates live ArrayList
        if (name.equals(tool.get("name"))) return tool;  // Returns ORIGINAL, not copy
    }
    return null;
}
```

**Problem:** Returns direct reference to the mutable map in `registeredTools`. External code can modify:

- `requiredScopes` - authorization scopes
- `confirmationRequired` - security-sensitive flag
- `outputSchema` - tool output schema

No synchronization protects these mutations after registration completes.

### getRegisteredTools/Resources/Prompts - Defensive Copy

```java
// McpRegistry.java:366-368
public List<Map<String, Object>> getRegisteredTools() {
    return copyDefinitions(registeredTools);  // Deep copy - SAFE
}
```

These methods use `copyDefinitions()` which creates deep copies, but note the locking strategy uses
`synchronized(source)` on the list object itself.

---

## 4. Registration Methods and Synchronization

### Fully Synchronized Registration Methods

All registration methods are `synchronized` on the instance:

```java
// McpRegistry.java:62-78 - Example pattern
public synchronized void registerTool(...) {
    requireUnique(name, toolHandlers, "tool");
    Map<String, Object> tool = new LinkedHashMap<>();
    // ... populate tool ...
    registeredTools.add(tool);          // ArrayList.add - thread-safe under synchronized
    toolHandlers.put(name, handler);   // ConcurrentHashMap - thread-safe
    notifyRegistryChanged("tools");    // Notifies listeners
}
```

### Un-Synchronized Read Methods

| Method                                   | Synchronization                   | Issue                            |
|------------------------------------------|-----------------------------------|----------------------------------|
| `getToolHandler()`                       | None (ConcurrentHashMap)          | Safe                             |
| `getResourceHandler()`                   | None (ConcurrentHashMap)          | Safe                             |
| `getPromptHandler()`                     | None (ConcurrentHashMap)          | Safe                             |
| `getToolDefinition()`                    | None                              | **Returns mutable map**          |
| `getResourceMimeType()`                  | `synchronized(McpRegistry.class)` | Class lock differs from instance |
| `getResourceTemplateMimeType()`          | `synchronized(McpRegistry.class)` | Class lock differs from instance |
| `getRegisteredTools/Resources/Prompts()` | `synchronized(source)`            | List lock differs from instance  |

---

## 5. Consumers/Listeners Reading Metadata

### Primary Consumer: McpProtocolHandler

1. **Rate Limiting with Tool Scopes** (line 611):
   ```java
   Map<String, Object> toolDef = registry.getToolDefinition(toolName);
   if (toolDef != null) {
       toolScopes = (List<String>) toolDef.get("requiredScopes");
   }
   ```
   Reads `requiredScopes` for category-based rate limiting.

2. **Tools/List Response** (line 1437):
   ```java
   return paginate("tools", registry.getRegisteredTools(), params);
   ```
   Returns defensive copy - safe.

3. **Tool Call Authorization** (lines 1483-1487):
   ```java
   McpToolHandler handler = registry.getToolHandler(name);
   Map<String, Object> definition = registry.getToolDefinition(name);
   // Uses definition for requiredScopes, confirmationRequired
   ```

### Listeners

1. **McpRegistryChangeListener** (via `addRegistryChangeListener`):
    - `McpProtocolHandler` implements this interface (line 54)
    - Registered at line 397: `registry.addRegistryChangeListener(this)`
    - Callback: `onRegistryChanged(String listType)` - queues notifications to sessions

2. **McpResourceUpdateListener** (via `setNotificationTarget`):
    - `McpProtocolHandler` implements this interface
    - Registered at line 396: `registry.setNotificationTarget(this)`
    - Callback: `notifyResourceUpdated(String uri)` - queues resource update notifications

---

## 6. Identified Concurrency Issues

### Issue #1: getToolDefinition Returns Mutable Reference (CRITICAL)

**Severity:** HIGH  
**Location:** McpRegistry.java:405-410  
**Problem:** Returns direct reference to internal map, allowing unsynchronized mutation.

```java
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {
        if (name.equals(tool.get("name"))) return tool; // Returns live object!
    }
    return null;
}
```

**Impact:** External code can modify `requiredScopes`, `confirmationRequired`, `outputSchema` after registration without
any synchronization. This affects:

- Authorization bypass (modified scopes)
- Security checks bypassed (modified confirmationRequired)
- Rate limiting category changes

**Fix:** Return a defensive copy:

```java
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {
        if (name.equals(tool.get("name"))) return copyMap(tool);
    }
    return null;
}
```

### Issue #2: Inconsistent Locking Strategy

**Severity:** MEDIUM  
**Location:** Throughout McpRegistry.java  
**Problem:** Three different locking strategies used:

| Context         | Lock                              | Potential Issue |
|-----------------|-----------------------------------|-----------------|
| Registration    | `synchronized` (instance)         | ✅ Correct       |
| findMimeType    | `synchronized(McpRegistry.class)` | Different lock  |
| copyDefinitions | `synchronized(source)`            | Different lock  |

**Risk:** While each path is internally consistent, mixing lock types can cause confusion and potential issues if code
is refactored.

### Issue #3: ArrayList for Metadata Storage

**Severity:** LOW (mitigated by synchronization)  
**Location:** Lines 23-26  
**Problem:** `registeredTools` et al. are `ArrayList`, not thread-safe.

```java
private final List<Map<String, Object>> registeredTools = new ArrayList<>();
```

**Mitigation:** All writes happen in synchronized methods, so happens-before is established. However, iteration without
synchronization (in getToolDefinition) could see partially constructed objects if registration is not complete.

### Issue #4: notificationTarget Volatile Write Not Synchronized

**Severity:** LOW  
**Location:** McpRegistry.java:334-336  
**Problem:** `setNotificationTarget` is not synchronized, but `notificationTarget` is volatile.

```java
private volatile McpResourceUpdateListener notificationTarget;

public void setNotificationTarget(McpResourceUpdateListener target) {
    this.notificationTarget = target;  // Not synchronized
}
```

**Impact:** Low - the write happens during server initialization before concurrent access begins.

---

## 7. Summary Table

| Aspect                | Current State             | Risk     |
|-----------------------|---------------------------|----------|
| Registration          | ✅ Synchronized (instance) | None     |
| Handler Maps          | ✅ ConcurrentHashMap       | None     |
| List Getters          | ✅ Defensive copy          | None     |
| **getToolDefinition** | ❌ Returns mutable         | **HIGH** |
| Change Listeners      | ✅ CopyOnWriteArrayList    | None     |
| Notification Target   | ⚠️ volatile, no sync      | Low      |

---

## 8. Recommendations

1. **Immediate:** Fix `getToolDefinition()` to return defensive copy
2. **Consider:** Standardize locking strategy (all instance-level, or all class-level)
3. **Consider:** Make registeredTools a Collections.synchronizedList() for explicit documentation
4. **Documentation:** Document that getToolDefinition returns a copy for API users
