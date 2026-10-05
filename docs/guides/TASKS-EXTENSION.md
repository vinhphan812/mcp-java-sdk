# Tasks Extension Guide

> **Spec reference:** `io.modelcontextprotocol/tasks`
> **MCP 2026-07-28** — Tasks advertise and dispatch exclusively through extension negotiation.

The MCP Tasks capability is pluggable. Instead of a single hard-coded slot, the server
exposes `McpTaskExtension` as a `ServiceProviderInterface` (SPI): applications can
supply a custom implementation to replace or augment the built-in task store.

---

## Quick start

```java
import io.github.vinhphan812.mcp.api.config.McpServerConfig;
import io.github.vinhphan812.mcp.api.spi.McpTaskExtension;

// 1 — implement the extension
McpTaskExtension ext = new McpTaskExtension() {
    @Override
    public boolean supports(String protocolVersion) {
        return "2026-07-28".equals(protocolVersion); // claim 2026 mode
    }
};

// 2 — wire it in
McpServerConfig config = McpServerConfig.builder()
    .serverName("my-server")
    .serverVersion("1.0.0")
    .tasks(true)
    .tasksExtension(ext)
    .build();
```

The server will advertise `tasks` in its `initialize` response **only** when
`supports(negotiatedVersion)` returns `true`.

---

## Extension lifecycle

```
register(TaskRegistry)  ← called once when the extension is set
onRequest(method, …)    ← called for every tasks/* request (if supports=true)
onError(method, …)      ← called when a built-in handler throws
unregister()            ← called when the handler is shut down
```

### `register(TaskRegistry)`

Called once when the protocol handler is constructed.  Use this to open connections,
load persisted state, or register initial tasks.

```java
@Override
public void register(McpTaskExtension.TaskRegistry taskRegistry) {
    this.taskRegistry = taskRegistry;
    taskRegistry.registerTask("init-task", "working");
}
```

### `unregister()`

Called when `McpProtocolHandler.shutdown()` is invoked.  Release resources here.

---

## Version gating

`supports(String protocolVersion)` is the gate for both capability advertisement and
request dispatch.  Return `true` only for versions this implementation understands.

| Client version | `supports()` | Behaviour |
|---|---|---|
| `2025-11-25` | `true` | Tasks advertised; all task methods dispatched |
| `2025-11-25` | `false` | Tasks **not** advertised; unknown-method `-32601` on task methods |
| `2026-07-28` | `true` | Tasks advertised (stateless mode); dispatch active |
| `2026-07-28` | `false` | Tasks **not** advertised; unknown-method `-32601` |

```java
@Override
public boolean supports(String protocolVersion) {
    return "2026-07-28".equals(protocolVersion);
}
```

Returning `false` for a version the client expects is safe: the server returns
`-32601 Method not found`, never leaking the extension's existence.

---

## Capability advertisement

`advertiseCapabilities(String protocolVersion)` returns the metadata placed in the
`initialize` response `capabilities.tasks` object.

```java
@Override
public Map<String, Object> advertiseCapabilities(String protocolVersion) {
    Map<String, Object> cap = new LinkedHashMap<>();
    cap.put("listChanged", false);
    return cap;
}
```

The negotiated protocol version is passed so the same extension can return
different metadata for different versions.

---

## Request dispatch

For each `tasks/*` request, the protocol handler calls:

```java
RequestResult onRequest(String method, Map<String, Object> params, String session)
```

| Return value | Effect |
|---|---|
| non-null success | Short-circuits built-in handler; returned as JSON-RPC result |
| non-null error | Short-circuits; returns JSON-RPC error |
| `null` | Falls through to built-in task handler |

```java
@Override
public McpTaskExtension.RequestResult onRequest(
        String method, Map<String, Object> params, String session) {

    if ("tasks/create".equals(method)) {
        String name = (String) params.get("name");
        MyTask myTask = myStore.create(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", myTask.toMap());
        result.put("token", myTask.getId());
        return McpTaskExtension.RequestResult.success(result);
    }

    return null; // delegate to built-in
}
```

### `tasks=false` suppresses capability even with extension

The `tasks(false)` builder flag acts as a master switch.  When `false`, the
`tasks` capability is omitted from `initialize` responses even if a non-null
extension is configured and `supports()` would return `true`.

---

## Error transformation

When the built-in handler throws an `McpException`, the extension can transform
or suppress the error via `onError`:

```java
@Override
public McpTaskExtension.RequestResult onError(
        String method, Map<String, Object> params, String session,
        int errorCode, String message) {

    // Replace -32001 (RESULT_NOT_COMPLETE) with a custom code
    if (errorCode == McpErrorCodes.RESULT_NOT_COMPLETE) {
        return McpTaskExtension.RequestResult.error(
                -32003, "Task is still running, please retry later");
    }
    return null; // propagate original error
}
```

Returning `null` passes the original error unchanged.

---

## Task registry bridge

`TaskRegistry` is a minimal interface exposed to extensions.  It hides the full
`McpRegistry` surface to prevent unknown extensions from interfering with other
server state.

| Method | Behaviour |
|---|---|
| `registerTask(taskId, status)` | Register a task snapshot. Status: `working`, `completed`, `failed`, `cancelled` |
| `getTaskStatus(taskId)` | Returns current status string or `null` if unknown |

```java
@Override
public void register(McpTaskExtension.TaskRegistry taskRegistry) {
    taskRegistry.registerTask("my-task-1", "working");
    String s = taskRegistry.getTaskStatus("my-task-1"); // "working"
}
```

Input validation throws `IllegalArgumentException` for null/blank `taskId`,
null/blank `status`, or unknown status strings.

---

## RequestResult type

```java
RequestResult.success(Map<String, Object> data)   // 200 OK
RequestResult.success()                           // empty success
RequestResult.error(int code, String message)    // JSON-RPC error
result.isSuccess()                                // true on success
```

---

## Canonical namespace

The extension's canonical namespace constant is:

```java
String NAMESPACE = "io.modelcontextprotocol/tasks";
```

This is the value advertised in the MCP spec for capability negotiation.
No action is required by implementors — the constant is provided for reference.

---

## Unknown extension safety

**Null extension** — `tasksExtension(null)` is accepted by the builder and produces
a server that uses only the built-in task store.  The `tasks(true)` flag still
controls capability advertisement independently.

**Unsupported version** — When an extension is configured but `supports(version)` is
`false` for the client's version, the server returns `-32601 Method not found`
for all task methods.  No capability is advertised.  The client sees a normal
server that does not support tasks.

**Extension failure** — If `onRequest` throws, the protocol handler catches it
and falls through to built-in handling (if extension returned `null` before
throwing) or returns the appropriate error.  Extension exceptions never crash
the server.

---

## Minimal complete example

```java
McpTaskExtension ext = new McpTaskExtension() {
    private volatile TaskRegistry registry;

    @Override
    public boolean supports(String protocolVersion) {
        return "2026-07-28".equals(protocolVersion);
    }

    @Override
    public void register(TaskRegistry taskRegistry) {
        this.registry = taskRegistry;
    }

    @Override
    public RequestResult onRequest(String method, Map<String, Object> params,
                                   String session) {
        if ("tasks/create".equals(method)) {
            String name = params.get("name") instanceof String
                    ? (String) params.get("name") : "unnamed";
            String id = UUID.randomUUID().toString();
            registry.registerTask(id, "working");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("task", Map.of("taskId", id, "status", "working",
                    "createdAt", System.currentTimeMillis()));
            r.put("token", id);
            return RequestResult.success(r);
        }
        return null; // fall through
    }

    @Override
    public void unregister() {
        this.registry = null;
    }
};

McpServerConfig config = McpServerConfig.builder()
    .serverName("task-server")
    .tasks(true)
    .tasksExtension(ext)
    .build();
```

---

## SEP-2663 Tasks Extension (2026-07-28)

This section documents features added for SEP-2663 compliance.

### Task statuses

The `McpTask.Status` enum has five values:

| Status | Terminal? | Notes |
|--------|-----------|-------|
| `WORKING` | No | Task is in progress |
| `INPUT_REQUIRED` | No | Task needs client input (SEP-2663 MRTR) |
| `COMPLETED` | Yes | Task finished with a result |
| `FAILED` | Yes | Task failed with a JSON-RPC error |
| `CANCELLED` | Yes | Task was cancelled |

### SEP-2663 Task shape

Tasks serialised via `McpTask.toMap()` include these SEP-2663 fields:

| Field | Type | Description |
|-------|------|-------------|
| `taskId` | `String` | Stable task identifier |
| `status` | `String` | One of the five status values (lowercase) |
| `statusMessage` | `String?` | Human-readable status description |
| `createdAt` | `String` | ISO-8601 UTC timestamp |
| `lastUpdatedAt` | `String` | ISO-8601 UTC timestamp |
| `ttlMs` | `Integer?` | Task TTL in milliseconds |
| `pollIntervalMs` | `Integer?` | Suggested polling interval |
| `inputRequests` | `Map?` | Pending MRTR requests (when `INPUT_REQUIRED`) |
| `result` | `Object?` | Final result (when `COMPLETED`) |
| `error` | `String?` | Error description (when `FAILED`/`CANCELLED`) |

### Creating an input-required task

Use `McpProtocolHandler.transitionTaskToInputRequired()` to move a `WORKING` task to `INPUT_REQUIRED`:

```java
Map<String, Object> inputRequests = new LinkedHashMap<>();
inputRequests.put("req-1", Map.of(
    "method", "elicitation/create",
    "message", "Do you approve this operation?"
));

McpTask t = handler.transitionTaskToInputRequired(
    taskId,
    "Awaiting your approval",   // statusMessage
    60000L,                    // ttlMs (1 minute)
    5000,                      // pollIntervalMs (5 seconds)
    inputRequests              // MRTR inputRequests map
);
```

### tasks/get response shape

`tasks/get` now returns `resultType: "complete"` as the discriminator per SEP-2663 §Task Polling. The response always includes the full task object.

### tasks/update

Clients call `tasks/update` to provide `inputResponses` for an `INPUT_REQUIRED` task:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tasks/update",
  "params": {
    "taskId": "786512e2-9e0d-44bd-8f29-789f820fe840",
    "inputResponses": {
      "req-1": { "action": "approved" }
    }
  }
}
```

On success the server returns an empty acknowledgement `{}`. On failure it returns a JSON-RPC error.

The built-in `handleTasksUpdate` delegates to the extension if present, allowing the extension to process the input responses out-of-band. If no extension is configured, an empty acknowledgement is returned — the application is responsible for calling `transitionTaskToInputRequired()` or `completeTask()` after processing the responses.

### tasks/cancel on INPUT_REQUIRED

Both `WORKING` and `INPUT_REQUIRED` tasks can be cancelled. Attempting to cancel a terminal task returns `-32002 RESULT_ALREADY_TERMINAL`.

### Error codes

| Code | Constant | When |
|------|---------|-------|
| `-32001` | `RESULT_NOT_COMPLETE` | `tasks/result` called on `WORKING` or `INPUT_REQUIRED` |
| `-32002` | `RESULT_ALREADY_TERMINAL` | `tasks/cancel` called twice, or on a terminal task |
| `-32021` | `MISSING_REQUIRED_CLIENT_CAPABILITY` | Client did not declare the tasks extension in per-request capabilities |

`-32021` is returned via `McpError.missingRequiredClientCapability()`:

```java
McpError.missingRequiredClientCapability("io.modelcontextprotocol/tasks")
// Produces:
// { "code": -32021, "message": "Missing required client capability",
//   "data": { "requiredCapabilities": { "extensions": { "io.modelcontextprotocol/tasks": {} } } } }
```

### McpTask factory methods

```java
// Simple bounded task
McpTask t = McpTask.create();

// Named task (used by tasks/create)
McpTask t = McpTask.create(name, sessionId, requestId, input, inputSchema);

// Named task with SEP-2663 metadata
McpTask t = McpTask.create(name, sessionId, requestId, input, inputSchema,
        "Working on it", 3600000L, 5000);

// INPUT_REQUIRED task (SEP-2663 MRTR)
McpTask t = McpTask.createInputRequired(name, sessionId, requestId,
        input, inputSchema, "Awaiting your input",
        60000L, 3000, inputRequests);
```

### ISO-8601 timestamps

`McpTask.toMap()` emits `createdAt` and `lastUpdatedAt` as ISO-8601 strings (e.g. `"2025-11-25T10:30:00.000Z"`) per SEP-2663 §Task. Numeric timestamps were used in the legacy API and are no longer produced.

### McpTaskExtension: `tasks/update` dispatch

The extension's `onRequest` is called for `tasks/update` when the extension supports the negotiated protocol version. Return a `RequestResult` to short-circuit the built-in handler:

```java
@Override
public RequestResult onRequest(String method, Map<String, Object> params, String session) {
    if ("tasks/update".equals(method)) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputResponses =
            (Map<String, Object>) params.get("inputResponses");
        processInputResponses(inputResponses);
        return RequestResult.success(); // empty ack
    }
    return null;
}
```
