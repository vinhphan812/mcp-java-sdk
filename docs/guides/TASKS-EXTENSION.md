# Tasks Extension Usage Guide

> This document covers the MCP Tasks capability as implemented by the `mcp-java-sdk`
> server-side SDK. For the protocol specification, see the [MCP Tasks
> RFC](https://modelcontextprotocol.io/specification). For architectural decisions,
> see [ADR-0022](../architecture/ADR-0022-server-to-client-mrtr-elicitation-foundation.md).

**Source files:**
`src/main/java/io/github/vinhphan812/mcp/api/spi/McpTaskExtension.java`
`src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java`
`src/test/java/io/github/vinhphan812/mcp/McpTaskExtensionTest.java`

---

## 1. Overview

The **MCP Tasks capability** allows an MCP server to expose long-running background
tasks to clients. The server can create tasks, report their progress, and deliver
results once they complete. Clients can poll for status, cancel tasks, and retrieve
the final result.

`mcp-java-sdk` implements this capability at two levels:

| Layer                      | Description                                                                                                          |
|----------------------------|----------------------------------------------------------------------------------------------------------------------|
| **Built-in task store**    | `tasks/create`, `tasks/get`, `tasks/cancel`, `tasks/result` with an in-memory task registry                          |
| **`McpTaskExtension` SPI** | Pluggable replacement for the built-in store; enables custom task backends, external schedulers, or MRTR elicitation |

The SPI (extension) layer is entirely optional. If no extension is configured, the
built-in store handles all task requests. An extension can observe or intercept
built-in requests via `onRequest` / `onError` hooks without fully replacing the store.

The canonical namespace for this capability is `io.modelcontextprotocol/tasks`.

---

## 2. Protocol Negotiation

### Capability advertisement

The server advertises the `tasks` capability in the `initialize` response:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2026-07-28",
    "capabilities": {
      "tasks": {}
    },
    "serverInfo": {
      "name": "my-server",
      "version": "1.0"
    }
  }
}
```

The capability is present when:

- `config.tasks == true` **AND**
- either no extension is configured, **OR** an extension is configured and its
  `supports(negotiatedVersion)` returns `true`

### Extension version gating

`supports(String protocolVersion)` is called with the **client-requested**
protocol version (from the `initialize` `params.protocolVersion` field).

```java
public interface McpTaskExtension {
    boolean supports(String protocolVersion);
    // ...
}
```

| `supports` return | Effect                                                                                |
|-------------------|---------------------------------------------------------------------------------------|
| `true`            | Tasks capability is advertised; task methods are dispatched to this extension         |
| `false`           | Tasks capability is **not** advertised; task methods return `-32601 Method not found` |

This means an extension can limit itself to specific protocol versions. For example,
an extension that only supports `2026-07-28` will not advertise tasks to a `2025-11-25`
client, and any task method calls from that client will receive `-32601`.

### `advertiseCapabilities` -- capability metadata

When an extension is active, the server calls `advertiseCapabilities(protocolVersion)`
to fill the `tasks` capability object:

```java
// Default implementation (used when no extension overrides it):
public Map<String, Object> advertiseCapabilities(String protocolVersion) {
    Map<String, Object> cap = new LinkedHashMap<>();
    cap.put("listChanged", false);
    return cap;
}
```

The negotiated version is passed so the metadata can be version-specific.

### Top-level `extensions` array (MCP 2026-07-28)

When an extension's `advertiseExtension(String protocolVersion)` returns a non-null
map, that map is placed in the top-level `extensions` array of the `initialize` /
`server/discover` response:

```json
{
  "result": {
    "capabilities": {
      "tasks": {}
    },
    "extensions": [
      {
        "name": "io.modelcontextprotocol/tasks",
        "version": "1.0"
      }
    ],
    "serverInfo": {
      "name": "my-server",
      "version": "1.0"
    }
  }
}
```

The array is absent when no extension is registered, when every registered
extension returns `null`, or when no extension supports the negotiated version.

---

## 3. Server-Side Registration

### Enabling tasks

Enable tasks in the server config:

```java
McpServerConfig config = McpServerConfig.builder()
        .serverName("my-server")
        .serverVersion("1.0")
        .tasks(true)            // required to advertise the capability
        // .tasksExtension(ext)  // optional -- see below
        .build();
```

Setting `tasks(false)` suppresses the capability even when an extension is configured.

### Registering a custom extension

```java
McpTaskExtension ext = new McpTaskExtension() {
    @Override
    public boolean supports(String protocolVersion) {
        return "2026-07-28".equals(protocolVersion)
                || "2025-11-25".equals(protocolVersion);
    }

    @Override
    public Map<String, Object> advertiseExtension(String protocolVersion) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", McpTaskExtension.NAMESPACE); // "io.modelcontextprotocol/tasks"
        meta.put("version", "1.0");
        return meta;
    }

    @Override
    public RequestResult onRequest(String method, Map<String, Object> params, String session) {
        // Intercept task requests; return non-null to short-circuit built-in handling.
        return null; // pass through to built-in handler
    }

    @Override
    public RequestResult onError(String method, Map<String, Object> params,
                                 String session, int errorCode, String message) {
        // Transform or suppress errors from the built-in handler.
        return null; // preserve original error
    }

    @Override
    public void register(TaskRegistry taskRegistry) {
        // Lifecycle: extension is being registered.
        // Use taskRegistry to register snapshots or perform one-time setup.
    }

    @Override
    public void unregister() {
        // Lifecycle: extension is being deregistered. Release resources.
    }
};

McpServerConfig config = McpServerConfig.builder()
        .serverName("my-server")
        .serverVersion("1.0")
        .tasks(true)
        .tasksExtension(ext)
        .build();
```

### The `TaskRegistry` bridge

The `TaskRegistry` interface exposed to extensions is a minimal, safe subset of the
internal registry:

```java
interface TaskRegistry {
    void registerTask(String taskId, String status);
    // status must be one of: "working", "completed", "failed", "cancelled"

    String getTaskStatus(String taskId);
    // returns the current status, or null if the task is unknown
}
```

`registerTask` maps the string status to an internal `McpTask.Status` enum value.
Invalid inputs (null taskId, blank taskId, unknown status) throw
`IllegalArgumentException`.

### `RequestResult` -- return type for hooks

Both `onRequest` and `onError` return a `RequestResult`:

```java
RequestResult.success(Map<String, Object> data)   // non-null data -> short-circuits with result
RequestResult.

success()                          // empty result
RequestResult.

error(int code, String message)   // short-circuits with error
null                                            // pass through to built-in handler
```

---

## 4. Client-Side Usage

### Built-in task methods

| Method         | Params                                                 | Returns                        |
|----------------|--------------------------------------------------------|--------------------------------|
| `tasks/create` | `{name: string, input?: object, inputSchema?: object}` | `{task: {...}, token: string}` |
| `tasks/get`    | `{taskId: string}`                                     | flat task object               |
| `tasks/cancel` | `{taskId: string}`                                     | flat task object               |
| `tasks/result` | `{taskId: string}`                                     | `{taskId, result?, error?}`    |

> **Note:** `tasks/edit` and `tasks/delete` are **not** implemented in the current
> version. Calls to these methods receive `-32601 Method not found`. `tasks/list`
> with cursor-based pagination is not yet implemented; see the compatibility matrix.

### `tasks/create` -- creating a task

```json
// Request
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tasks/create",
  "params": {
    "name": "my-task",
    "input": {
      "arg": "value"
    },
    "inputSchema": {
      "type": "object"
    }
  }
}

// Response
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "task": {
      "taskId": "task_abc123",
      "status": "working",
      "name": "my-task",
      "input": {
        "arg": "value"
      },
      "inputSchema": {
        "type": "object"
      }
    },
    "token": "task_abc123"
  }
}
```

The server also emits a `tasks/task` notification to the client so it can begin
tracking progress immediately:

```json
{
  "jsonrpc": "2.0",
  "method": "tasks/task",
  "params": {
    "task": {
      "taskId": "task_abc123",
      "status": "working",
      "name": "my-task"
    },
    "token": "task_abc123"
  }
}
```

### `tasks/get` -- polling for status

```json
// Request
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tasks/get",
  "params": {
    "taskId": "task_abc123"
  }
}

// Response (task still working)
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "taskId": "task_abc123",
    "status": "working"
  }
}
```

The `tasks/get` response is a **flat** object (no `task` wrapper), unlike the
`tasks/create` response.

### `tasks/cancel` -- cancelling a task

```json
// Request
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tasks/cancel",
  "params": {
    "taskId": "task_abc123"
  }
}

// Response (cancelled)
{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "taskId": "task_abc123",
    "status": "cancelled"
  }
}
```

Cancelling an already-terminal task (completed / failed / cancelled) returns
`-32002 RESULT_ALREADY_TERMINAL`.

### `tasks/result` -- fetching the result

```json
// Request
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tasks/result",
  "params": {
    "taskId": "task_abc123"
  }
}

// Response (completed)
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "taskId": "task_abc123",
    "result": {
      "output": "done"
    }
  }
}

// Response (still working -- error)
{
  "jsonrpc": "2.0",
  "id": 5,
  "error": {
    "code": -32001,
    "message": "Task not complete"
  }
}

// Response (failed)
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "taskId": "task_abc123",
    "error": "boom"
  }
}
```

Calling `tasks/result` on a working task returns `-32001 RESULT_NOT_COMPLETE`.
Calling `tasks/result` on an unknown task returns `-32602 INVALID_PARAMS`.

---

## 5. Version Gating

### What happens when a client does not advertise the tasks extension

The server handles each mismatch case as follows:

| Scenario                                         | Server behaviour                                                                                          |
|--------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| No extension configured, `tasks=true`            | Built-in store handles all task methods; returns `-32601` only for unknown task methods not in the switch |
| Extension registered, `supports(version)=false`  | Tasks capability not advertised; task method calls return `-32601 Method not found`                       |
| Extension registered, `onRequest` returns `null` | Built-in handler processes the request                                                                    |
| Extension registered, `onError` returns `null`   | Original error propagates to the client                                                                   |

### Error codes

| Code     | Name                      | Condition                                                  |
|----------|---------------------------|------------------------------------------------------------|
| `-32001` | `RESULT_NOT_COMPLETE`     | `tasks/result` called on a working task                    |
| `-32002` | `RESULT_ALREADY_TERMINAL` | `tasks/cancel` called on an already-terminal task          |
| `-32601` | `METHOD_NOT_FOUND`        | Extension does not support the negotiated protocol version |
| `-32602` | `INVALID_PARAMS`          | Missing or unknown `taskId`                                |

---

## 6. Architectural Decisions

See [ADR-0022: Server-to-Client MRTR Elicitation Foundation](../architecture/ADR-0022-server-to-client-mrtr-elicitation-foundation.md)
for:

- The rationale for the `extensions` top-level array in `initialize` / `server/discover`
- The `advertiseExtension` contract and its relationship to `supports`
- Forward-compatibility rules for adding new extension slots
- MRTR (Message Routing / Request-Response Tracking) elicitation architecture

---

## 7. Example JSON-RPC Exchanges

### Full session: initialize + tasks/create + tasks/result

```
Client -> Server: initialize
Server -> Client: capability advertisement

Client -> Server: notifications/initialized  (client ready)

Client -> Server: tasks/create
Server -> Client: task created + tasks/task notification

[... background work ...]

Client -> Server: tasks/result
Server -> Client: result (or RESULT_NOT_COMPLETE if still working)
```

#### `initialize` (2026-07-28, stateless)

```
-> {
     "jsonrpc": "2.0",
     "id": 1,
     "method": "initialize",
     "params": {
       "protocolVersion": "2026-07-28",
       "capabilities": {},
       "clientInfo": { "name": "my-client", "version": "1.0" }
     }
   }

<- {
     "jsonrpc": "2.0",
     "id": 1,
     "result": {
       "protocolVersion": "2026-07-28",
       "capabilities": { "tasks": {} },
       "serverInfo": { "name": "my-server", "version": "1.0" }
     }
   }
```

#### `tasks/create`

```
-> {
     "jsonrpc": "2.0",
     "id": 2,
     "method": "tasks/create",
     "params": {
       "name": "process-file",
       "input": { "fileId": "f_001" }
     }
   }

<- {
     "jsonrpc": "2.0",
     "id": 2,
     "result": {
       "task": {
         "taskId": "task_001",
         "status": "working",
         "name": "process-file",
         "input": { "fileId": "f_001" }
       },
       "token": "task_001"
     }
   }
```

Server also sends a `tasks/task` notification (no id):

```
<- {
     "jsonrpc": "2.0",
     "method": "tasks/task",
     "params": {
       "task": { "taskId": "task_001", "status": "working", "name": "process-file" },
       "token": "task_001"
     }
   }
```

#### `tasks/edit` (not implemented -- returns -32601)

```
-> {
     "jsonrpc": "2.0",
     "id": 3,
     "method": "tasks/edit",
     "params": { "taskId": "task_001", "status": "completed" }
   }

<- {
     "jsonrpc": "2.0",
     "id": 3,
     "error": { "code": -32601, "message": "Method not found: tasks/edit" }
   }
```

#### `tasks/delete` (not implemented -- returns -32601)

```
-> {
     "jsonrpc": "2.0",
     "id": 4,
     "method": "tasks/delete",
     "params": { "taskId": "task_001" }
   }

<- {
     "jsonrpc": "2.0",
     "id": 4,
     "error": { "code": -32601, "message": "Method not found: tasks/delete" }
   }
```

#### `tasks/list` (cursor pagination -- not implemented)

The server does not currently implement `tasks/list`. This is a gap tracked in the
implementation status. Calling it returns `-32601 Method not found`.

#### `tasks/get`

```
-> {
     "jsonrpc": "2.0",
     "id": 5,
     "method": "tasks/get",
     "params": { "taskId": "task_001" }
   }

<- {
     "jsonrpc": "2.0",
     "id": 5,
     "result": {
       "taskId": "task_001",
       "status": "working"
     }
   }
```

#### `tasks/result` -- still working (error)

```
-> {
     "jsonrpc": "2.0",
     "id": 6,
     "method": "tasks/result",
     "params": { "taskId": "task_001" }
   }

<- {
     "jsonrpc": "2.0",
     "id": 6,
     "error": { "code": -32001, "message": "Task not complete" }
   }
```

#### `tasks/cancel`

```
-> {
     "jsonrpc": "2.0",
     "id": 7,
     "method": "tasks/cancel",
     "params": { "taskId": "task_001" }
   }

<- {
     "jsonrpc": "2.0",
     "id": 7,
     "result": {
       "taskId": "task_001",
       "status": "cancelled"
     }
   }
```

---

## See Also

- [ADR-0022 -- Server-to-Client MRTR Elicitation Foundation](../architecture/ADR-0022-server-to-client-mrtr-elicitation-foundation.md)
- [MCP Compatibility Matrix](../guides/MCP-COMPATIBILITY-2026.md)
- [Implementation Status](../guides/IMPLEMENTATION-STATUS.md)
- `McpTaskExtension.java` -- SPI interface with full Javadoc
- `McpTaskExtensionTest.java` -- 22 tests covering negotiation, dispatch, error transformation, lifecycle, and
  regression guards
