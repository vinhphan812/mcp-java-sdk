# Metadata Immutability Policy Specification

**Task:** t_a21292c9  
**Date:** 2026-09-20  
**Status:** Specification (Tier 2)

---

## 1. Overview

This document specifies the immutable snapshot policy for `McpRegistry` metadata. It defines how tool, resource,
template, and prompt definitions are exposed to consumers while preventing external mutation of security-sensitive
fields.

---

## 2. Fields Classification

### 2.1 Tool Definition Fields

| Field                  | Type    | Mutability  | Copy Strategy                 |
|------------------------|---------|-------------|-------------------------------|
| `name`                 | String  | Immutable   | Shallow (immutable)           |
| `description`          | String  | Immutable   | Shallow (immutable)           |
| `inputSchema`          | Map     | Immutable   | **Deep** (nested object)      |
| `required`             | List    | Immutable   | **Deep** (nested list)        |
| `requiredScopes`       | List    | **Mutable** | **Deep** (security-sensitive) |
| `confirmationRequired` | Boolean | **Mutable** | Shallow (immutable type)      |
| `outputSchema`         | Map     | **Mutable** | **Deep** (nested object)      |

### 2.2 Resource Definition Fields

| Field         | Type   | Mutability | Copy Strategy |
|---------------|--------|------------|---------------|
| `uri`         | String | Immutable  | Shallow       |
| `name`        | String | Immutable  | Shallow       |
| `description` | String | Immutable  | Shallow       |
| `mimeType`    | String | Immutable  | Shallow       |

### 2.3 Resource Template Fields

| Field         | Type   | Mutability | Copy Strategy |
|---------------|--------|------------|---------------|
| `uriTemplate` | String | Immutable  | Shallow       |
| `name`        | String | Immutable  | Shallow       |
| `description` | String | Immutable  | Shallow       |
| `mimeType`    | String | Immutable  | Shallow       |

### 2.4 Prompt Definition Fields

| Field         | Type   | Mutability | Copy Strategy             |
|---------------|--------|------------|---------------------------|
| `name`        | String | Immutable  | Shallow                   |
| `description` | String | Immutable  | Shallow                   |
| `arguments`   | List   | Immutable  | **Deep** (nested objects) |

---

## 3. Copy Strategy Rationale

### 3.1 Deep Copy Required

**Deep copy** is required for fields that contain:

1. **Nested Maps** (`inputSchema`, `outputSchema`):
    - These are JSON Schema objects with nested properties
    - External code could traverse and mutate nested values
    - Must copy all nested maps and lists recursively

2. **Lists of Strings** (`required`, `requiredScopes`):
    - Lists are mutable references
    - External code could call `list.add()`, `list.remove()`
    - Must create new ArrayList with copied elements

3. **Lists of Maps** (`arguments`):
    - Each argument is a Map with `name`, `description`, `type`, `required`
    - Must deep copy each map in the list

### 3.2 Shallow Copy Acceptable

**Shallow copy** is acceptable for:

1. **Primitives and Immutable Objects** (`name`, `description`, `uri`, `mimeType`, `confirmationRequired`):
    - String is immutable in Java
    - Boolean is immutable
    - No mutable state to protect

---

## 4. Copy-on-Read vs Copy-on-Write

### 4.1 Decision: Copy-on-Read

We adopt **copy-on-read** (defensive copy at getter) for the following reasons:

| Factor        | Copy-on-Read              | Copy-on-Write                        |
|---------------|---------------------------|--------------------------------------|
| Complexity    | Lower (single copy point) | Higher (tracking + copy on mutation) |
| Memory        | O(1) per read             | O(n) per write                       |
| Thread Safety | Guaranteed immutable      | Requires copy-on-write collection    |
| Use Case Fit  | Read-heavy (MCP runtime)  | Write-heavy                          |

### 4.2 Rationale

- **MCP runtime is read-heavy**: Tools/resources are registered once at startup, queried many times per request
- **Simplicity**: Single copy point at getter avoids complex write tracking
- **Memory**: Copy-on-write would duplicate entire registry on each mutation; copy-on-read only copies what's requested
- **Existing infrastructure**: `copyMap()` and `copyList()` utilities already exist in `McpRegistry`

---

## 5. Defensive Copying Boundaries

### 5.1 Public API Boundary

All public getter methods must return defensive copies:

```
┌─────────────────────────────────────────────────────┐
│                   McpRegistry                        │
│  ┌─────────────────────────────────────────────┐   │
│  │  Internal Storage (private)                 │   │
│  │  - ArrayList / CopyOnWriteArrayList         │   │
│  │  - ConcurrentHashMap                        │   │
│  └─────────────────────────────────────────────┘   │
│                       │                             │
│               copyMap() / copyList()               │
│                       │                             │
│  ┌─────────────────────────────────────────────┐   │
│  │  Public API (returns defensive copy)       │   │
│  │  - getToolDefinition()                      │   │
│  │  - getRegisteredTools()                     │   │
│  │  - getRegisteredResources()                 │   │
│  │  - getRegisteredPrompts()                   │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 5.2 Nested Collection Boundaries

For nested structures, the copy must be recursive:

```java
// Deep copy must handle:
Map<String, Object>  →  Map<String, Object>  (recursive)
List<Object>        →  List<Object>        (recursive)
```

### 5.3 Handler Maps

Handler maps (`toolHandlers`, `resourceHandlers`, etc.) return references to handler objects. These are **not copied**
because:

- Handlers are implementation objects, not metadata
- They are inherently mutable (stateful)
- Consumers need the actual handler instance to invoke

---

## 6. Custom Metadata Types

### 6.1 Current State

Currently, all metadata uses `Map<String, Object>` and `List<Object>` from the JDK. No custom metadata types exist.

### 6.2 Future Extension Policy

If custom metadata types are introduced:

| Type                         | Handling                                    |
|------------------------------|---------------------------------------------|
| **Implementing `Cloneable`** | Use `clone()` for deep copy                 |
| **Immutable wrapper**        | Shallow copy acceptable                     |
| **Mutable POJO**             | Require custom deep copy method or builder  |
| **Third-party types**        | Wrap in unmodifiable view or create adapter |

### 6.3 Registration API Extension

If registration accepts custom metadata:

```java
// Policy: Custom metadata must be provided as immutable at registration
public interface MetadataSnapshot {
    Map<String, Object> toMap();  // Defensive copy on call
}
```

---

## 7. API Changes

### 7.1 New Methods

| Method                                   | Description                                                                          |
|------------------------------------------|--------------------------------------------------------------------------------------|
| `getToolDefinitionSnapshot(String name)` | Alias for `getToolDefinition()` with documented immutability (optional, for clarity) |

### 7.2 Deprecated Methods

| Method                           | Deprecation Reason                        | Replacement                                                      |
|----------------------------------|-------------------------------------------|------------------------------------------------------------------|
| `getToolDefinition(String name)` | Returns mutable reference (security risk) | `getToolDefinitionSnapshot(String name)` or fixed implementation |

### 7.3 Implementation Requirement

**The existing `getToolDefinition()` method must be fixed** to return a defensive copy:

```java
// CURRENT (INSECURE):
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {
        if (name.equals(tool.get("name"))) return tool;  // Returns live object!
    }
    return null;
}

// REQUIRED (SECURE):
public Map<String, Object> getToolDefinition(String name) {
    for (Map<String, Object> tool : registeredTools) {
        if (name.equals(tool.get("name"))) return copyMap(tool);  // Defensive copy
    }
    return null;
}
```

### 7.4 Javadoc Requirements

All public getter methods must document their immutability contract:

```java
/**
 * Returns a defensive copy of the tool definition.
 * The returned map is immutable and independent of internal state.
 *
 * @param name tool name
 * @return immutable tool definition, or null if not found
 */
public Map<String, Object> getToolDefinition(String name) {
    // ...
}
```

---

## 8. Performance Considerations

### 8.1 Copy Cost

| Operation              | Complexity | Notes                     |
|------------------------|------------|---------------------------|
| `copyMap()`            | O(n)       | n = total nested fields   |
| `copyList()`           | O(n)       | n = elements in list      |
| `getToolDefinition()`  | O(n)       | Linear search + copy      |
| `getRegisteredTools()` | O(n*m)     | n = tools, m = avg fields |

### 8.2 Optimization (Optional)

ADR-0015 proposes adding a `ConcurrentHashMap` index for O(1) lookups:

```java
private final ConcurrentHashMap<String, Map<String, Object>> toolDefinitionsByName;
```

This reduces lookup from O(n) to O(1), while still returning a defensive copy.

---

## 9. Migration Path

### Phase 1: Fix Critical Issue (Immediate)

1. Fix `getToolDefinition()` to return defensive copy
2. Add immutability documentation to Javadocs

### Phase 2: Collection Upgrade (ADR-0015)

1. Replace `ArrayList` with `CopyOnWriteArrayList`
2. Add `ConcurrentHashMap` index for O(1) lookups
3. Remove unnecessary synchronization

### Phase 3: API Deprecation (Breaking Change)

1. Mark old `getToolDefinition()` as `@Deprecated`
2. Introduce `getToolDefinitionSnapshot()` with clear naming
3. Update consumers

---

## 10. Summary

| Decision       | Value                                                   |
|----------------|---------------------------------------------------------|
| Copy Strategy  | Deep copy for nested Maps/Lists; shallow for primitives |
| Copy Mechanism | Copy-on-read (defensive copy at getter)                 |
| Boundary       | All public getter methods                               |
| Custom Types   | Require immutable or provide deep-copy mechanism        |
| API Change     | Fix `getToolDefinition()`; add deprecation notices      |

---

## 11. References

- McpRegistry-Technical-Analysis.md
- ADR-0015: Concurrent Collection Strategy
- ADR-0011: Security Rate Limiting (scope usage context)
- Java Concurrency in Practice: Defensive Copies
