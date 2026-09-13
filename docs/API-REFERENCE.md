# MCP Java SDK — API Reference

Complete public API surface for `io.github.vinhphan812.mcp`.

## Package overview

```
io.github.vinhphan812.mcp
├── annotations/
│   ├── @McpTool       — tool endpoint
│   ├── @McpResource   — exact resource reader
│   ├── @McpResourceTemplate — parameterized resource reader
│   ├── @McpPrompt     — prompt provider
│   ├── @McpParam      — parameter description (repeatable)
│   ├── @Tools         — marks class as tool provider
│   ├── @Resources     — marks class as resource provider
│   └── @Prompts       — marks class as prompt provider
├── api/
│   ├── spi/                   — registration and lifecycle contracts
│   │   ├── McpRegistrar              — registration SPI
│   │   ├── McpResourceUpdateListener  — resource change events
│   │   └── McpRegistryChangeListener — registration change events
│   ├── handler/               — application-owned behaviour contracts
│   │   ├── McpToolHandler            — tool execution
│   │   ├── McpResourceHandler       — text resource reading
│   │   ├── McpBlobResourceHandler   — binary resource reading
│   │   ├── McpPromptHandler         — prompt generation
│   │   └── McpCompletionProvider     — completion values
│   ├── dto/                   — immutable value objects
│   │   ├── McpTask                — server-managed task snapshot
│   │   └── McpBlobContent        — Base64 blob resource content
│   ├── config/                — server and client metadata
│   │   ├── McpServerConfig        — protocol version, capabilities, info
│   │   └── McpClientCapabilities  — client capability metadata
│   ├── logging/                — logging adapters
│   │   ├── McpLogger              — portable logger interface
│   │   └── JulMcpLogger          — java.util.logging adapter
│   ├── McpReflectionRegistrar   — annotation-based registration utility
│   └── package-info.java         — API overview
├── core/
│   ├── McpServer           — server bootstrap
│   ├── McpRegistry         — tool/resource/prompt/task registry
│   └── McpProtocolHandler  — JSON-RPC protocol dispatcher
└── transport/
    ├── GrizzlyStreamableServerTransportProvider — HTTP server lifecycle
    └── McpGrizzlyHandler   — HTTP request handler

```

## Annotations

### `@McpTool`

Marks a method as an MCP tool. Applied to a method inside a class annotated `@Tools`.

```java
@McpTool(name = "greet", description = "Greets a user by name")
public Map<String, Object> greet(@McpParam(name = "name", required = true) String name) {
    return Map.of("text", "Hello, " + name + "!");
}
```

**Attributes**

| Attribute       | Type     | Required | Default | Description                                                               |
| -------------- | -------- | -------- | ------- | ------------------------------------------------------------------------ |
| `name`         | `String` | no       | method name | Tool identifier advertised to clients                                   |
| `description`  | `String` | no       | `""`    | Human-readable description                                                 |
| `outputSchema` | `String` | no       | `""`    | JSON Schema string describing the tool result shape. Accepted as raw JSON. |

### `@McpParam`

Binds a single method parameter to a named JSON argument. Used inside tool and prompt methods.

```java
@McpTool(name = "calculate")
public Map<String, Object> calculate(
    @McpParam(name = "quantity", type = "integer", required = true) int quantity,
    @McpParam(name = "price", type = "number", required = true) double price) {
    // ...
}
```

**Attributes**

| Attribute   | Type      | Required | Default | Description                                                  |
| ---------- | --------- | -------- | ------- | ------------------------------------------------------------ |
| `name`     | `String`  | yes      | —       | JSON argument name                                            |
| `type`     | `String`  | no       | `"string"` | JSON type: `"string"`, `"boolean"`, `"integer"`, `"number"`, `"object"` |
| `required` | `boolean` | no       | `false`  | Whether the argument must be present                          |

**POJO complex types.** Any user-defined class can be used as a parameter type. The SDK serialises the incoming JSON value through Gson and deserialises it into the declared type. This handles nested objects, lists of objects, and arrays automatically.

```java
public static class Address {
    private final String street;
    private final String city;
    private final String country;
    public Address(String street, String city, String country) { ... }
    public String getStreet()  { return street; }
    public String getCity()    { return city; }
    public String getCountry() { return country; }
}

@McpTool(name = "format-address")
public Map<String, Object> formatAddress(
        @McpParam(name = "address", required = true) Address address) {
    String formatted = address.getStreet() + ", " + address.getCity() + ", " + address.getCountry();
    return Map.of("formatted", formatted);
}
```

### `@McpResource`

Marks a method as an exact-match resource. Applied to a method inside a class annotated `@Resources`.

```java
@McpResource(uri = "demo://readme", mimeType = "text/plain", description = "SDK overview")
public String readReadme() {
    return "This is the MCP Java SDK...";
}
```

**Attributes**

| Attribute     | Type     | Required | Default        | Description                            |
| ------------ | -------- | -------- | -------------- | -------------------------------------- |
| `uri`        | `String` | yes      | —              | Resource URI                            |
| `mimeType`   | `String` | no       | `"text/plain"` | MIME type of the resource content       |
| `description`| `String` | no       | `""`           | Human-readable description              |

### `@McpResourceTemplate`

Marks a method as a resource template with URI variables. Applied to a method inside a class annotated `@Resources`.

```java
@McpResourceTemplate(uri = "demo://users/{userId}", description = "User profile by ID")
public String getUserProfile(String userId) {
    return "User " + userId;
}
```

The method parameter receives the resolved variable value (the part of the URI matched by `{variableName}`).

**Attributes**

| Attribute     | Type     | Required | Default | Description                  |
| ------------ | -------- | -------- | ------- | ---------------------------- |
| `uri`        | `String` | yes      | —       | Template URI with `{var}` slots |
| `description`| `String` | no       | `""``   | Human-readable description    |

### `@McpPrompt`

Marks a method as a prompt. Applied to a method inside a class annotated `@Prompts`.

```java
@McpPrompt(name = "explain-user", description = "Explains a user profile")
public Map<String, Object> explainUser(
        @McpParam(name = "userId", required = true) String userId) {
    return Map.of("role", "user", "content", List.of(
            Map.of("type", "text", "text", "User " + userId + " profile")
    ));
}
```

**Attributes**

| Attribute     | Type     | Required | Default | Description                  |
| ------------ | -------- | -------- | ------- | ---------------------------- |
| `name`       | `String` | no       | method name | Prompt identifier           |
| `description`| `String` | no       | `""`     | Human-readable description    |

### `@Tools`, `@Resources`, `@Prompts`

Class-level markers that enable reflection scanning. Place on a class to register all methods annotated with the corresponding MCP annotation.

```java
@Tools
public class MyTools {
    @McpTool(name = "my-tool") public Map<String, Object> myTool(...) { ... }
}

@Resources
public class MyResources {
    @McpResource(uri = "demo://file") public String readFile(...) { ... }
}

@Prompts
public class MyPrompts {
    @McpPrompt(name = "my-prompt") public Map<String, Object> myPrompt(...) { ... }
}
```

---

## Server

### `McpServer`

Entry point. Use the builder to configure and start the server.

```java
McpServer server = McpServer.builder()
        .config(McpServerConfig.builder()
                .serverName("my-server")
                .serverVersion("1.0.0")
                .protocolVersion("2025-11-25")
                .tools(true)
                .resources(true)
                .prompts(true)
                .build())
        .host("127.0.0.1")
        .port(3011)
        .endpoint("/mcp")
        .build()
        .register(new MyTools())
        .register(new MyResources())
        .register(new MyPrompts());

server.start();
Logger.getLogger("mcp-server").info(server.getUrl());  // http://127.0.0.1:3011/mcp

// shutdown
server.close();
```

**Builder methods**

| Method                      | Description                                                             |
| -------------------------- | ---------------------------------------------------------------------- |
| `config(McpServerConfig)`  | Server capability and metadata configuration                             |
| `host(String)`              | Bind address (default `127.0.0.1`)                                     |
| `port(int)`                 | TCP port (default `3011`; use `0` for ephemeral)                       |
| `endpoint(String)`          | URL path (default `/mcp`; must start with `/`)                        |
| `scheme(String)`             | URL scheme for `getUrl()` output: `"http"` or `"https"` (default `http`) |
| `allowedOrigins(String...)` | Allowed `Origin` header patterns (default local-only)                   |
| `apiKeySupplier(Supplier<String>)` | External Bearer token provider                                   |
| `maxRequestBodySize(long)`  | Maximum POST body size in bytes (default `1 MiB`)                      |
| `build()`                   | Returns a configured `McpServer`                                       |

**Instance methods**

| Method                      | Description                                        |
| -------------------------- | ------------------------------------------------- |
| `register(Object)`         | Register one annotated provider before start       |
| `registerAll(Object...)`    | Register multiple providers                       |
| `start()`                  | Start Grizzly and begin accepting requests        |
| `stop()` / `close()`       | Stop server and close all sessions                |
| `isRunning()`              | `true` if the server is running                  |
| `getUrl()`                 | Returns server URL when running, else `null`      |
| `getTransport()`           | Returns the underlying transport provider          |

### `McpServerConfig`

Immutable configuration for server metadata and capability flags.

```java
McpServerConfig config = McpServerConfig.builder()
        .serverName("my-server")
        .serverVersion("1.0.0")
        .protocolVersion("2025-11-25")
        .tools(true)
        .resources(true)
        .prompts(true)
        .logging(true)
        .completions(true)
        .tasks(true)
        .experimental(Map.of("feature", Map.of("enabled", true)))
        .build();
```

**Builder methods**

| Method                    | Default  | Description                                                       |
| ------------------------ | -------- | ----------------------------------------------------------------- |
| `serverName(String)`      | required | Server name advertised in `initialize` response                   |
| `serverVersion(String)`   | required | Server version string                                             |
| `protocolVersion(String)` | required | Supported MCP protocol version (e.g. `"2025-11-25"`)             |
| `tools(boolean)`          | `false`  | Advertise tools capability                                        |
| `resources(boolean)`      | `false`  | Advertise resources capability                                   |
| `prompts(boolean)`        | `false`  | Advertise prompts capability                                     |
| `logging(boolean)`         | `false`  | Advertise logging capability                                     |
| `completions(boolean)`    | `false`  | Advertise completion capability                                  |
| `tasks(boolean)`          | `false`  | Advertise server-managed tasks capability                         |
| `experimental(Map)`       | empty    | Arbitrary key-value metadata added to capabilities                |

---

## Registration

### `McpRegistrar`

Public SPI for registering capability handlers. All registration methods throw `IllegalArgumentException` on duplicate names or invalid arguments.

```java
registrar.registerTool("my-tool", "A tool",
    Map.of("properties", Map.of("name", Map.of("type", "string"))),
    List.of("name"),
    arguments -> Map.of("result", "ok"));

registrar.registerResource(
    "demo://readme",
    "text/plain",
    "SDK readme",
    uri -> "Readme content...");

registrar.registerPrompt(
    "my-prompt",
    "A prompt",
    arguments -> Map.of("role", "user", "content", "Hello"));
```

**Tool registration**

| Signature | Description |
| -------- | ----------- |
| `registerTool(name, description, inputSchema, required, handler)` | Text/tool only |
| `registerTool(name, description, inputSchema, required, handler, outputSchema)` | With optional output schema |

**Resource registration**

| Signature | Description |
| -------- | ----------- |
| `registerResource(uri, mimeType, description, handler)` | Exact-match resource |
| `registerBlobResource(uri, mimeType, description, handler)` | Binary resource (returns `McpBlobContent`) |
| `registerResourceTemplate(uri, description, handler)` | Template with URI variables |

**Prompt registration**

| Signature | Description |
| -------- | ----------- |
| `registerPrompt(name, description, handler)` | Prompt |

**Listener registration**

| Signature | Description |
| -------- | ----------- |
| `addRegistryChangeListener(listener)` | Called when tools, resources, or prompts change |
| `removeRegistryChangeListener(listener)` | Remove a previously registered listener |
| `setResourceUpdateListener(listener)` | Called when a subscribed resource URI is updated |

### `McpReflectionRegistrar`

Scans annotated provider objects and registers everything through a `McpRegistrar`. Use with `@Tools`, `@Resources`, `@Prompts`.

```java
McpReflectionRegistrar registrar = new McpReflectionRegistrar();
registrar.register(providerObject, myRegistrar);
```

---

## Handlers

### `McpToolHandler`

```java
public interface McpToolHandler {
    Map<String, Object> invoke(Map<String, Object> arguments) throws Exception;
}
```

Receives the JSON arguments map and returns the MCP result. Throw an exception to produce a JSON-RPC error response.

### `McpResourceHandler`

```java
public interface McpResourceHandler {
    String read(String uri) throws Exception;
}
```

Receives the resolved URI and returns the resource content as a `String`.

### `McpBlobResourceHandler`

```java
public interface McpBlobResourceHandler {
    McpBlobContent readBlob(String uri) throws Exception;
}
```

Receives the resolved URI and returns binary content as `McpBlobContent`.

### `McpPromptHandler`

```java
public interface McpPromptHandler {
    Map<String, Object> getPrompt(Map<String, Object> arguments) throws Exception;
}
```

Receives prompt arguments and returns an MCP prompt message (role + content list).

### `McpCompletionProvider`

```java
public interface McpCompletionProvider {
    List<String> getCompletions(String ref, String[] arguments) throws Exception;
}
```

Provides completion candidates for a reference type and partial arguments.

---

## Resources

### `McpBlobContent`

DTO returned by `McpBlobResourceHandler.readBlob()`.

```java
public class McpBlobContent {
    public final String blob;       // Base64-encoded content
    public final String mimeType;   // MIME type (e.g. "image/png")

    public McpBlobContent(String blob, String mimeType) { ... }

    public static boolean isValidBase64(String value) { ... }
}
```

MCP `resources/read` response for a blob:

```json
{
  "contents": [{
    "type": "blob",
    "blob": "<base64>",
    "mimeType": "image/png"
  }]
}
```

---

## Tasks

### `McpTask`

Represents a server-managed task with a bounded lifecycle.

```java
public class McpTask {
    public final String id;
    public final String name;
    public final Map<String, Object> input;
    public Status status;   // PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    public final long createdAt;
    public long completedAt;

    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }
}
```

### Task operations

| Method                    | Description                              |
| ------------------------ | ---------------------------------------- |
| `submitTask(input)`       | Submit a task and return its ID          |
| `getTask(id)`            | Get task state by ID                    |
| `getTaskResult(id)`      | Get task result by ID                   |
| `cancelTask(id)`          | Cancel a pending or processing task      |
| `publishTaskProgress(id, progress)` | Publish progress token to all sessions |
| `completeTask(id, result)` | Mark task completed and notify sessions |
| `failTask(id, error)`     | Mark task failed and notify sessions    |

---

## Logging

### `McpLogger`

Server-side logging interface. Implement to redirect SDK log output.

```java
public interface McpLogger {
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);
}
```

### `McpClientCapabilities`

Carries client capability metadata from the `initialize` request.

```java
McpClientCapabilities caps = handler.getClientCapabilities(sessionId);
Boolean sampling = caps.getSampling();
String prompt = caps.getSamplingPrompt();
```

---

## Transport

### `GrizzlyStreamableServerTransportProvider`

Provides `McpGrizzlyHandler` lifecycle management.

| Method              | Description                                       |
| ------------------ | ------------------------------------------------- |
| `start()`          | Bind and start the HTTP server                    |
| `stop()`           | Stop the HTTP server                              |
| `isRunning()`      | `true` if the server is accepting connections     |
| `getActualPort()`  | Returns the actual port (useful with ephemeral `0`) |
| `getUrl()`         | Returns the server URL when running               |
| `getHandler()`     | Returns the `McpGrizzlyHandler` instance          |

### `McpGrizzlyHandler`

Internal transport handler. Access via `GrizzlyStreamableServerTransportProvider.getHandler()`.

---

## Protocol

### `McpProtocolHandler`

Protocol dispatch layer. Access via `McpServer` or through `McpGrizzlyHandler`.

| Method                           | Description                                    |
| -------------------------------- | --------------------------------------------- |
| `hasSession(id)`                  | `true` if a session exists                   |
| `terminateSession(id)`            | Close a session and notify all subscribers    |
| `sendNotification(method, params)` | Push a JSON-RPC notification to all sessions  |
| `notifyResourceUpdated(uri)`       | Push `resources/updated` to subscribers       |
| `notifyLogMessage(level, logger, data)` | Push a `logging/message` notification |
| `supportsProtocolVersion(v)`       | `true` if the given version is supported     |

### `McpRegistry`

Central registry of registered tools, resources, prompts, and tasks.

| Method                           | Returns                                   |
| -------------------------------- | ----------------------------------------- |
| `getRegisteredTools()`            | `List<Map<String, Object>>` (tools/list) |
| `getRegisteredResources()`        | `List<Map<String, Object>>` (resources/list) |
| `getResourceTemplateHandlers()`   | `Map<String, McpResourceHandler>`         |
| `getBlobResourceHandlers()`      | `Map<String, McpBlobResourceHandler>`     |
| `getRegisteredPrompts()`         | `List<Map<String, Object>>` (prompts/list) |
| `getRegisteredCompletions()`     | `List<McpCompletionProvider>`             |
| `getCompletionCandidates(ref, args)` | `List<String>`                        |
| `getTask(id)`                    | `McpTask` or `null`                      |
| `getAllTasks()`                  | `Collection<McpTask>`                     |

---

## Protocol methods

Supported MCP JSON-RPC methods:

| Method                        | Direction | Capability required |
| ----------------------------- | --------- | ----------------- |
| `initialize`                  | request   | —                 |
| `notifications/initialized`    | notification | —             |
| `tools/list`                  | request   | `tools: true`     |
| `tools/call`                 | request   | `tools: true`     |
| `resources/list`              | request   | `resources: true` |
| `resources/read`              | request   | `resources: true` |
| `resources/templates/list`     | request   | `resources: true` |
| `resources/subscribe`         | request   | `resources: true` |
| `resources/unsubscribe`       | request   | `resources: true` |
| `prompts/list`                | request   | `prompts: true`  |
| `prompts/get`                 | request   | `prompts: true`  |
| `completion/complete`         | request   | `completions: true` |
| `logging/setLevel`            | request   | `logging: true`  |
| `notifications/message`       | notification | `logging: true` |
| `tasks/get`                   | request   | `tasks: true`     |
| `tasks/result`                | request   | `tasks: true`    |
| `tasks/cancel`                | request   | `tasks: true`    |

## HTTP transport

| Endpoint   | Method | Description                                          |
| ---------  | ------ | ---------------------------------------------------- |
| `/mcp`     | POST   | JSON-RPC request or notification                    |
| `/mcp`     | GET    | SSE event stream (session-bound)                    |
| `/mcp`     | DELETE | Session termination                                  |

**Required headers**

| Direction | Header                      | Value                                     |
| -------- | -------------------------- | ----------------------------------------- |
| POST in  | `Content-Type`              | `application/json`                        |
| POST in  | `Accept`                    | `application/json` or `application/json, text/event-stream` |
| POST in  | `Mcp-Protocol-Version`      | Supported protocol version                 |
| POST in  | `Mcp-Session-Id`           | Session ID (after initialize)             |
| POST in  | `Authorization`            | `Bearer <token>` (if configured)         |
| POST out | `Mcp-Session-Id`           | Issued session ID                         |
| GET in   | `Mcp-Session-Id`           | Active session ID                         |
| GET in   | `Last-Event-ID`            | Event ID to resume from                   |

**Security defaults**

- Default bind: `127.0.0.1` (loopback only)
- Default max body: 1 MiB
- Unknown Origin: rejected with `403`
- Bearer token: constant-time comparison via `MessageDigest.isEqual`
- SSE CRLF: sanitised before transmission


---

## Related documentation

| Document | Description |
|---|---|
| [PROJECT-GUIDE.md](PROJECT-GUIDE.md) | Architecture, usage guide, and protocol overview |
| [GRIZZLY-EXAMPLE.md](GRIZZLY-EXAMPLE.md) | Standalone Grizzly example with HTTP request samples |
| [IMPLEMENTATION-STATUS.md](IMPLEMENTATION-STATUS.md) | Completed, incomplete, and unverified areas |
| [MCP-COMPATIBILITY-2026.md](MCP-COMPATIBILITY-2026.md) | MCP baseline and P0/P1/P2 compatibility status |
| [docs/adr/](adr/) | Architecture Decision Records |

# MCP Java SDK — API Reference

Complete public API surface for `io.github.vinhphan812.mcp`.

## Package overview

```
io.github.vinhphan812.mcp
├── annotations/
│   ├── @McpTool       — tool endpoint
│   ├── @McpResource   — exact resource reader
│   ├── @McpResourceTemplate — parameterized resource reader
│   ├── @McpPrompt     — prompt provider
│   ├── @McpParam      — parameter description (repeatable)
│   ├── @Tools         — marks class as tool provider
│   ├── @Resources     — marks class as resource provider
│   └── @Prompts       — marks class as prompt provider
├── api/
│   ├── spi/                   — registration and lifecycle contracts
│   │   ├── McpRegistrar              — registration SPI
│   │   ├── McpResourceUpdateListener  — resource change events
│   │   └── McpRegistryChangeListener — registration change events
│   ├── handler/               — application-owned behaviour contracts
│   │   ├── McpToolHandler            — tool execution
│   │   ├── McpResourceHandler       — text resource reading
│   │   ├── McpBlobResourceHandler   — binary resource reading
│   │   ├── McpPromptHandler         — prompt generation
│   │   └── McpCompletionProvider     — completion values
│   ├── dto/                   — immutable value objects
│   │   ├── McpTask                — server-managed task snapshot
│   │   └── McpBlobContent        — Base64 blob resource content
│   ├── config/                — server and client metadata
│   │   ├── McpServerConfig        — protocol version, capabilities, info
│   │   └── McpClientCapabilities  — client capability metadata
│   ├── logging/                — logging adapters
│   │   ├── McpLogger              — portable logger interface
│   │   └── JulMcpLogger          — java.util.logging adapter
│   ├── McpReflectionRegistrar   — annotation-based registration utility
│   └── package-info.java         — API overview
├── core/
│   ├── McpServer           — server bootstrap
│   ├── McpRegistry         — tool/resource/prompt/task registry
│   └── McpProtocolHandler  — JSON-RPC protocol dispatcher
└── transport/
    ├── GrizzlyStreamableServerTransportProvider — HTTP server lifecycle
    └── McpGrizzlyHandler   — HTTP request handler

```

## Annotations

### `@McpTool`

Marks a method as an MCP tool. Applied to a method inside a class annotated `@Tools`.

```java
@McpTool(name = "greet", description = "Greets a user by name")
public Map<String, Object> greet(@McpParam(name = "name", required = true) String name) {
    return Map.of("text", "Hello, " + name + "!");
}
```

**Attributes**

| Attribute       | Type     | Required | Default | Description                                                               |
| -------------- | -------- | -------- | ------- | ------------------------------------------------------------------------ |
| `name`         | `String` | no       | method name | Tool identifier advertised to clients                                   |
| `description`  | `String` | no       | `""`    | Human-readable description                                                 |
| `outputSchema` | `String` | no       | `""`    | JSON Schema string describing the tool result shape. Accepted as raw JSON. |

### `@McpParam`

Binds a single method parameter to a named JSON argument. Used inside tool and prompt methods.

```java
@McpTool(name = "calculate")
public Map<String, Object> calculate(
    @McpParam(name = "quantity", type = "integer", required = true) int quantity,
    @McpParam(name = "price", type = "number", required = true) double price) {
    // ...
}
```

**Attributes**

| Attribute   | Type      | Required | Default | Description                                                  |
| ---------- | --------- | -------- | ------- | ------------------------------------------------------------ |
| `name`     | `String`  | yes      | —       | JSON argument name                                            |
| `type`     | `String`  | no       | `"string"` | JSON type: `"string"`, `"boolean"`, `"integer"`, `"number"`, `"object"` |
| `required` | `boolean` | no       | `false`  | Whether the argument must be present                          |

The MCP client passes the nested object in `arguments.address`. See `docs/GRIZZLY-EXAMPLE.md` for the full working example with `Address`.

### `@McpResource`

Marks a method as an exact-match resource. Applied to a method inside a class annotated `@Resources`.

```java
@McpResource(uri = "demo://readme", mimeType = "text/plain", description = "SDK overview")
public String readReadme() {
    return "This is the MCP Java SDK...";
}
```

**Attributes**

| Attribute     | Type     | Required | Default        | Description                            |
| ------------ | -------- | -------- | -------------- | -------------------------------------- |
| `uri`        | `String` | yes      | —              | Resource URI                            |
| `mimeType`   | `String` | no       | `"text/plain"` | MIME type of the resource content       |
| `description`| `String` | no       | `""`           | Human-readable description              |

### `@McpResourceTemplate`

Marks a method as a resource template with URI variables. Applied to a method inside a class annotated `@Resources`.

```java
@McpResourceTemplate(uri = "demo://users/{userId}", description = "User profile by ID")
public String getUserProfile(String userId) {
    return "User " + userId;
}
```

The method parameter receives the resolved variable value (the part of the URI matched by `{variableName}`).

**Attributes**

| Attribute     | Type     | Required | Default | Description                  |
| ------------ | -------- | -------- | ------- | ---------------------------- |
| `uri`        | `String` | yes      | —       | Template URI with `{var}` slots |
| `description`| `String` | no       | `""``   | Human-readable description    |

### `@McpPrompt`

Marks a method as a prompt. Applied to a method inside a class annotated `@Prompts`.

```java
@McpPrompt(name = "explain-user", description = "Explains a user profile")
public Map<String, Object> explainUser(
        @McpParam(name = "userId", required = true) String userId) {
    return Map.of("role", "user", "content", List.of(
            Map.of("type", "text", "text", "User " + userId + " profile")
    ));
}
```

**Attributes**

| Attribute     | Type     | Required | Default | Description                  |
| ------------ | -------- | -------- | ------- | ---------------------------- |
| `name`       | `String` | no       | method name | Prompt identifier           |
| `description`| `String` | no       | `""`     | Human-readable description    |

### `@Tools`, `@Resources`, `@Prompts`

Class-level markers that enable reflection scanning. Place on a class to register all methods annotated with the corresponding MCP annotation.

```java
@Tools
public class MyTools {
    @McpTool(name = "my-tool") public Map<String, Object> myTool(...) { ... }
}

@Resources
public class MyResources {
    @McpResource(uri = "demo://file") public String readFile(...) { ... }
}

@Prompts
public class MyPrompts {
    @McpPrompt(name = "my-prompt") public Map<String, Object> myPrompt(...) { ... }
}
```

---

## Server

### `McpServer`

Entry point. Use the builder to configure and start the server.

```java
McpServer server = McpServer.builder()
        .config(McpServerConfig.builder()
                .serverName("my-server")
                .serverVersion("1.0.0")
                .protocolVersion("2025-11-25")
                .tools(true)
                .resources(true)
                .prompts(true)
                .build())
        .host("127.0.0.1")
        .port(3011)
        .endpoint("/mcp")
        .build()
        .register(new MyTools())
        .register(new MyResources())
        .register(new MyPrompts());

server.start();
Logger.getLogger("mcp-server").info(server.getUrl());  // http://127.0.0.1:3011/mcp

// shutdown
server.close();
```

**Builder methods**

| Method                      | Description                                                             |
| -------------------------- | ---------------------------------------------------------------------- |
| `config(McpServerConfig)`  | Server capability and metadata configuration                             |
| `host(String)`              | Bind address (default `127.0.0.1`)                                     |
| `port(int)`                 | TCP port (default `3011`; use `0` for ephemeral)                       |
| `endpoint(String)`          | URL path (default `/mcp`; must start with `/`)                        |
| `scheme(String)`             | URL scheme for `getUrl()` output: `"http"` or `"https"` (default `http`) |
| `allowedOrigins(String...)` | Allowed `Origin` header patterns (default local-only)                   |
| `apiKeySupplier(Supplier<String>)` | External Bearer token provider                                   |
| `maxRequestBodySize(long)`  | Maximum POST body size in bytes (default `1 MiB`)                      |
| `build()`                   | Returns a configured `McpServer`                                       |

**Instance methods**

| Method                      | Description                                        |
| -------------------------- | ------------------------------------------------- |
| `register(Object)`         | Register one annotated provider before start       |
| `registerAll(Object...)`    | Register multiple providers                       |
| `start()`                  | Start Grizzly and begin accepting requests        |
| `stop()` / `close()`       | Stop server and close all sessions                |
| `isRunning()`              | `true` if the server is running                  |
| `getUrl()`                 | Returns server URL when running, else `null`      |
| `getTransport()`           | Returns the underlying transport provider          |

### `McpServerConfig`

Immutable configuration for server metadata and capability flags.

```java
McpServerConfig config = McpServerConfig.builder()
        .serverName("my-server")
        .serverVersion("1.0.0")
        .protocolVersion("2025-11-25")
        .tools(true)
        .resources(true)
        .prompts(true)
        .logging(true)
        .completions(true)
        .tasks(true)
        .experimental(Map.of("feature", Map.of("enabled", true)))
        .build();
```

**Builder methods**

| Method                    | Default  | Description                                                       |
| ------------------------ | -------- | ----------------------------------------------------------------- |
| `serverName(String)`      | required | Server name advertised in `initialize` response                   |
| `serverVersion(String)`   | required | Server version string                                             |
| `protocolVersion(String)` | required | Supported MCP protocol version (e.g. `"2025-11-25"`)             |
| `tools(boolean)`          | `false`  | Advertise tools capability                                        |
| `resources(boolean)`      | `false`  | Advertise resources capability                                   |
| `prompts(boolean)`        | `false`  | Advertise prompts capability                                     |
| `logging(boolean)`         | `false`  | Advertise logging capability                                     |
| `completions(boolean)`    | `false`  | Advertise completion capability                                  |
| `tasks(boolean)`          | `false`  | Advertise server-managed tasks capability                         |
| `experimental(Map)`       | empty    | Arbitrary key-value metadata added to capabilities                |

---

## Registration

### `McpRegistrar`

Public SPI for registering capability handlers. All registration methods throw `IllegalArgumentException` on duplicate names or invalid arguments.

```java
registrar.registerTool("my-tool", "A tool",
    Map.of("properties", Map.of("name", Map.of("type", "string"))),
    List.of("name"),
    arguments -> Map.of("result", "ok"));

registrar.registerResource(
    "demo://readme",
    "text/plain",
    "SDK readme",
    uri -> "Readme content...");

registrar.registerPrompt(
    "my-prompt",
    "A prompt",
    arguments -> Map.of("role", "user", "content", "Hello"));
```

**Tool registration**

| Signature | Description |
| -------- | ----------- |
| `registerTool(name, description, inputSchema, required, handler)` | Text/tool only |
| `registerTool(name, description, inputSchema, required, handler, outputSchema)` | With optional output schema |

**Resource registration**

| Signature | Description |
| -------- | ----------- |
| `registerResource(uri, mimeType, description, handler)` | Exact-match resource |
| `registerBlobResource(uri, mimeType, description, handler)` | Binary resource (returns `McpBlobContent`) |
| `registerResourceTemplate(uri, description, handler)` | Template with URI variables |

**Prompt registration**

| Signature | Description |
| -------- | ----------- |
| `registerPrompt(name, description, handler)` | Prompt |

**Listener registration**

| Signature | Description |
| -------- | ----------- |
| `addRegistryChangeListener(listener)` | Called when tools, resources, or prompts change |
| `removeRegistryChangeListener(listener)` | Remove a previously registered listener |
| `setResourceUpdateListener(listener)` | Called when a subscribed resource URI is updated |

### `McpReflectionRegistrar`

Scans annotated provider objects and registers everything through a `McpRegistrar`. Use with `@Tools`, `@Resources`, `@Prompts`.

```java
McpReflectionRegistrar registrar = new McpReflectionRegistrar();
registrar.register(providerObject, myRegistrar);
```

---

## Handlers

### `McpToolHandler`

```java
public interface McpToolHandler {
    Map<String, Object> invoke(Map<String, Object> arguments) throws Exception;
}
```

Receives the JSON arguments map and returns the MCP result. Throw an exception to produce a JSON-RPC error response.

### `McpResourceHandler`

```java
public interface McpResourceHandler {
    String read(String uri) throws Exception;
}
```

Receives the resolved URI and returns the resource content as a `String`.

### `McpBlobResourceHandler`

```java
public interface McpBlobResourceHandler {
    McpBlobContent readBlob(String uri) throws Exception;
}
```

Receives the resolved URI and returns binary content as `McpBlobContent`.

### `McpPromptHandler`

```java
public interface McpPromptHandler {
    Map<String, Object> getPrompt(Map<String, Object> arguments) throws Exception;
}
```

Receives prompt arguments and returns an MCP prompt message (role + content list).

### `McpCompletionProvider`

```java
public interface McpCompletionProvider {
    List<String> getCompletions(String ref, String[] arguments) throws Exception;
}
```

Provides completion candidates for a reference type and partial arguments.

---

## Resources

### `McpBlobContent`

DTO returned by `McpBlobResourceHandler.readBlob()`.

```java
public class McpBlobContent {
    public final String blob;       // Base64-encoded content
    public final String mimeType;   // MIME type (e.g. "image/png")

    public McpBlobContent(String blob, String mimeType) { ... }

    public static boolean isValidBase64(String value) { ... }
}
```

MCP `resources/read` response for a blob:

```json
{
  "contents": [{
    "type": "blob",
    "blob": "<base64>",
    "mimeType": "image/png"
  }]
}
```

---

## Tasks

### `McpTask`

Represents a server-managed task with a bounded lifecycle.

```java
public class McpTask {
    public final String id;
    public final String name;
    public final Map<String, Object> input;
    public Status status;   // PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    public final long createdAt;
    public long completedAt;

    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }
}
```

### Task operations

| Method                    | Description                              |
| ------------------------ | ---------------------------------------- |
| `submitTask(input)`       | Submit a task and return its ID          |
| `getTask(id)`            | Get task state by ID                    |
| `getTaskResult(id)`      | Get task result by ID                   |
| `cancelTask(id)`          | Cancel a pending or processing task      |
| `publishTaskProgress(id, progress)` | Publish progress token to all sessions |
| `completeTask(id, result)` | Mark task completed and notify sessions |
| `failTask(id, error)`     | Mark task failed and notify sessions    |

---

## Logging

### `McpLogger`

Server-side logging interface. Implement to redirect SDK log output.

```java
public interface McpLogger {
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);
}
```

### `McpClientCapabilities`

Carries client capability metadata from the `initialize` request.

```java
McpClientCapabilities caps = handler.getClientCapabilities(sessionId);
Boolean sampling = caps.getSampling();
String prompt = caps.getSamplingPrompt();
```

---

## Transport

### `GrizzlyStreamableServerTransportProvider`

Provides `McpGrizzlyHandler` lifecycle management.

| Method              | Description                                       |
| ------------------ | ------------------------------------------------- |
| `start()`          | Bind and start the HTTP server                    |
| `stop()`           | Stop the HTTP server                              |
| `isRunning()`      | `true` if the server is accepting connections     |
| `getActualPort()`  | Returns the actual port (useful with ephemeral `0`) |
| `getUrl()`         | Returns the server URL when running               |
| `getHandler()`     | Returns the `McpGrizzlyHandler` instance          |

### `McpGrizzlyHandler`

Internal transport handler. Access via `GrizzlyStreamableServerTransportProvider.getHandler()`.

---

## Protocol

### `McpProtocolHandler`

Protocol dispatch layer. Access via `McpServer` or through `McpGrizzlyHandler`.

| Method                           | Description                                    |
| -------------------------------- | --------------------------------------------- |
| `hasSession(id)`                  | `true` if a session exists                   |
| `terminateSession(id)`            | Close a session and notify all subscribers    |
| `sendNotification(method, params)` | Push a JSON-RPC notification to all sessions  |
| `notifyResourceUpdated(uri)`       | Push `resources/updated` to subscribers       |
| `notifyLogMessage(level, logger, data)` | Push a `logging/message` notification |
| `supportsProtocolVersion(v)`       | `true` if the given version is supported     |

### `McpRegistry`

Central registry of registered tools, resources, prompts, and tasks.

| Method                           | Returns                                   |
| -------------------------------- | ----------------------------------------- |
| `getRegisteredTools()`            | `List<Map<String, Object>>` (tools/list) |
| `getRegisteredResources()`        | `List<Map<String, Object>>` (resources/list) |
| `getResourceTemplateHandlers()`   | `Map<String, McpResourceHandler>`         |
| `getBlobResourceHandlers()`      | `Map<String, McpBlobResourceHandler>`     |
| `getRegisteredPrompts()`         | `List<Map<String, Object>>` (prompts/list) |
| `getRegisteredCompletions()`     | `List<McpCompletionProvider>`             |
| `getCompletionCandidates(ref, args)` | `List<String>`                        |
| `getTask(id)`                    | `McpTask` or `null`                      |
| `getAllTasks()`                  | `Collection<McpTask>`                     |

---

## Protocol methods

Supported MCP JSON-RPC methods:

| Method                        | Direction | Capability required |
| ----------------------------- | --------- | ----------------- |
| `initialize`                  | request   | —                 |
| `notifications/initialized`    | notification | —             |
| `tools/list`                  | request   | `tools: true`     |
| `tools/call`                 | request   | `tools: true`     |
| `resources/list`              | request   | `resources: true` |
| `resources/read`              | request   | `resources: true` |
| `resources/templates/list`     | request   | `resources: true` |
| `resources/subscribe`         | request   | `resources: true` |
| `resources/unsubscribe`       | request   | `resources: true` |
| `prompts/list`                | request   | `prompts: true`  |
| `prompts/get`                 | request   | `prompts: true`  |
| `completion/complete`         | request   | `completions: true` |
| `logging/setLevel`            | request   | `logging: true`  |
| `notifications/message`       | notification | `logging: true` |
| `tasks/get`                   | request   | `tasks: true`     |
| `tasks/result`                | request   | `tasks: true`    |
| `tasks/cancel`                | request   | `tasks: true`    |

## HTTP transport

| Endpoint   | Method | Description                                          |
| ---------  | ------ | ---------------------------------------------------- |
| `/mcp`     | POST   | JSON-RPC request or notification                    |
| `/mcp`     | GET    | SSE event stream (session-bound)                    |
| `/mcp`     | DELETE | Session termination                                  |

**Required headers**

| Direction | Header                      | Value                                     |
| -------- | -------------------------- | ----------------------------------------- |
| POST in  | `Content-Type`              | `application/json`                        |
| POST in  | `Accept`                    | `application/json` or `application/json, text/event-stream` |
| POST in  | `Mcp-Protocol-Version`      | Supported protocol version                 |
| POST in  | `Mcp-Session-Id`           | Session ID (after initialize)             |
| POST in  | `Authorization`            | `Bearer <token>` (if configured)         |
| POST out | `Mcp-Session-Id`           | Issued session ID                         |
| GET in   | `Mcp-Session-Id`           | Active session ID                         |
| GET in   | `Last-Event-ID`            | Event ID to resume from                   |

**Security defaults**

- Default bind: `127.0.0.1` (loopback only)
- Default max body: 1 MiB
- Unknown Origin: rejected with `403`
- Bearer token: constant-time comparison via `MessageDigest.isEqual`
- SSE CRLF: sanitised before transmission
