# Tasks Extension Guide

> This guide documents the MCP Tasks capability extension point (SEP-2663).

## Overview

The Tasks extension (`io.modelcontextprotocol/tasks`) provides a pluggable mechanism for managing long-running, multi-step operations. The server ships with a built-in task store; applications can replace it with a custom implementation by registering an `McpTaskExtension` via the server configuration builder.

## Extension Registry Architecture

The SDK uses an **extensible extension registry** (`ExtensionRegistry` / `DefaultExtensionRegistry`) that supports registering multiple extension types by namespace. The `McpTaskExtension` is the first registered extension.

```
┌─────────────────────────────────────────────────────────┐
│                 ExtensionRegistry                        │
│   "io.modelcontextprotocol/tasks"  →  McpTaskExtension  │
│   (future: other namespaces...)                          │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              McpProtocolHandler                          │
│  • handleInitialize()    — loops registry, advertises   │
│  • handleServerDiscover() — loops registry               │
│  • dispatchTaskRequest()  — routes via registry          │
└─────────────────────────────────────────────────────────┘
```

## Quick Start

### Registering a Tasks Extension

```java
McpServerConfig config = McpServerConfig.builder()
    .serverName("my-server")
    .tasks(true)                        // must be true to enable the capability
    .tasksExtension(new MyTaskExtension())
    .build();
```

### Implementing McpTaskExtension

```java
public class MyTaskExtension implements McpTaskExtension {

    @Override
    public boolean supports(String protocolVersion) {
        // Advertise and handle task methods only for these versions
        return "2025-11-25".equals(protocolVersion)
            || "2026-07-28".equals(protocolVersion);
    }

    @Override
    public Map<String, Object> advertiseCapabilities(String protocolVersion) {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("listChanged", false);
        return cap;
    }

    @Override
    public void onRegister(TaskRegistry taskRegistry) {
        // Access the task registry to register task snapshots
    }

    @Override
    public RequestResult onRequest(String method, Map<String, Object> params, String session) {
        // Return non-null to short-circuit the built-in handler
        // Return null to fall through to built-in logic
        return null;
    }
}
```

## Lifecycle Hooks

| Hook | When called | Typical use |
|---|---|---|
| `register(ExtensionRegistry)` | After extension is registered in the registry | Cross-extension coordination |
| `register(TaskRegistry)` | After extension is wired to the server (backward-compat overload) | Initialise task snapshots |
| `onRegister(TaskRegistry)` | After `register(TaskRegistry)`, before first request | Access the task registry for startup tasks |
| `unregister()` | When the extension is replaced or the server shuts down | Release resources |

## Version Gating

The `supports(String protocolVersion)` method controls two things:

1. **Capability advertisement** — if it returns `false` for the negotiated version, the `tasks` capability key is not included in the `initialize` / `server/discover` response.
2. **Request dispatch** — if it returns `false` when a task request arrives, the request returns `-32601 Method not found`.

This means you can advertise task support only for `2026-07-28` clients:

```java
@Override
public boolean supports(String protocolVersion) {
    return "2026-07-28".equals(protocolVersion);
}
```

Legacy clients on `2025-11-25` will not see the tasks capability and will receive `-32601` for any task method calls.

## Request Interception

Override `onRequest` to intercept task methods before the built-in handler runs:

```java
@Override
public RequestResult onRequest(String method, Map<String, Object> params, String session) {
    if ("tasks/create".equals(method)) {
        // Custom task creation logic
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", UUID.randomUUID().toString());
        return RequestResult.success(result);
    }
    return null; // fall through to built-in
}
```

To transform errors from the built-in handler, override `onError`:

```java
@Override
public RequestResult onError(String method, Map<String, Object> params, String session,
                              int errorCode, String message) {
    if (errorCode == McpErrorCodes.RESULT_NOT_COMPLETE) {
        return RequestResult.error(McpErrorCodes.INTERNAL_ERROR, "Custom error: " + message);
    }
    return null; // preserve original error
}
```

## Built-in Task Store

When no extension is configured (`tasksExtension = null`) the server uses its built-in task store. This store supports all four standard task methods: `tasks/create`, `tasks/get`, `tasks/cancel`, `tasks/result`.

## Multiple Extension Support

The registry is designed to support future extension types. To add a second extension type, create a new interface extending `McpExtension` with its own namespace constant, then register it alongside `McpTaskExtension` via the builder (once a multi-extension builder API is added).

## Error Codes

| Code | Constant | Meaning |
|---|---|---|
| -32001 | `RESULT_NOT_COMPLETE` | `tasks/result` called on a non-terminal task |
| -32002 | `RESULT_ALREADY_TERMINAL` | `tasks/cancel` called on an already-terminal task |
| -32021 | `MISSING_REQUIRED_CLIENT_CAPABILITY` | Client did not declare the extension in `clientCapabilities._meta` |
| -32601 | `METHOD_NOT_FOUND` | Version gating rejected the request, or capability disabled |
| -32602 | `INVALID_PARAMS` | Missing or invalid request parameters |
