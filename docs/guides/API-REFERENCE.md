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
│   │   ├── McpRegistryChangeListener — registration change events
│   │   └── McpTaskExtension          — Tasks capability SPI (version-gated)
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
    ├── HttpTransportProvider — HTTP server lifecycle
    └── McpHttpHandler   — HTTP request handler

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

| Attribute      | Type     | Required | Default     | Description                                                                |
|----------------|----------|----------|-------------|----------------------------------------------------------------------------|
| `name`         | `String` | no       | method name | Tool identifier advertised to clients                                      |
| `description`  | `String` | no       | `""`        | Human-readable description                                                 |
| `outputSchema` | `String` | no       | `""`        | JSON Schema string describing the tool result shape. Accepted as raw JSON. |

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

| Attribute  | Type      | Required | Default    | Description                                                             |
|------------|-----------|----------|------------|-------------------------------------------------------------------------|
| `name`     | `String`  | yes      | —          | JSON argument name                                                      |
| `type`     | `String`  | no       | `"string"` | JSON type: `"string"`, `"boolean"`, `"integer"`, `"number"`, `"object"` |
| `required` | `boolean` | no       | `false`    | Whether the argument must be present                                    |

**POJO complex types.** Any user-defined class can be used as a parameter type. The SDK serialises the incoming JSON
value through Gson and deserialises it into the declared type. This handles nested objects, lists of objects, and arrays
automatically. Use `@SerializedName` on each field to make the JSON key explicit:

```java
import com.google.gson.annotations.SerializedName;

public static class Address {
    @SerializedName("street")
    private final String street;
    @SerializedName("city")
    private final String city;
    @SerializedName("country")
    private final String country;

    public Address(String street, String city, String country) { ...}

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getCountry() {
        return country;
    }
}

@McpTool(name = "format-address")
public Map<String, Object> formatAddress(
        @McpParam(name = "address", required = true) Address address) {
    String formatted = address.getStreet() + ", " + address.getCity() + ", " + address.getCountry();
    return Map.of("formatted", formatted);
}
```

If a JSON key differs from the Java field name (e.g. `"user_id"` → `userId`), annotate the field with
`@SerializedName("user_id")`. Gson uses the annotation over the field name. See `HTTP-TRANSPORT-EXAMPLE.md` for the full
working example with `Address`.

### `@McpResource`

Marks a method as an exact-match resource. Applied to a method inside a class annotated `@Resources`.

```java

@McpResource(uri = "demo://readme", mimeType = "text/plain", description = "SDK overview")
public String readReadme() {
    return "This is the MCP Java SDK...";
}
```

**Attributes**

| Attribute     | Type     | Required | Default        | Description                       |
|---------------|----------|----------|----------------|-----------------------------------|
| `uri`         | `String` | yes      | —              | Resource URI                      |
| `mimeType`    | `String` | no       | `"text/plain"` | MIME type of the resource content |
| `description` | `String` | no       | `""`           | Human-readable description        |

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

| Attribute     | Type     | Required | Default | Description                     |
|---------------|----------|----------|---------|---------------------------------|
| `uri`         | `String` | yes      | —       | Template URI with `{var}` slots |
| `description` | `String` | no       | `""``   | Human-readable description      |

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

| Attribute     | Type     | Required | Default     | Description                |
|---------------|----------|----------|-------------|----------------------------|
| `name`        | `String` | no       | method name | Prompt identifier          |
| `description` | `String` | no       | `""`        | Human-readable description |

### `@Tools`, `@Resources`, `@Prompts`

Class-level markers that enable reflection scanning. Place on a class to register all methods annotated with the
corresponding MCP annotation.

```java

@Tools
public class MyTools {
    @McpTool(name = "my-tool")
    public Map<String, Object> myTool(...) { ...}
}

@Resources
public class MyResources {
    @McpResource(uri = "demo://file")
    public String readFile(...) { ...}
}

@Prompts
public class MyPrompts {
    @McpPrompt(name = "my-prompt")
    public Map<String, Object> myPrompt(...) { ...}
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
        .apiKeySupplier(() -> System.getenv("MCP_API_KEY"))
        .build()
        .register(new MyTools())
        .register(new MyResources())
        .register(new MyPrompts());

server.start();
System.out.println(server.getUrl());  // http://127.0.0.1:3011/mcp

// later — stop gracefully
server.close();
```

#### `McpServer.Builder` — transport-level configuration

Controls the HTTP transport (bind address, port, authentication, CORS).

| Method | Default | Description |
|--------|---------|-------------|
| `registry(McpRegistry)` | auto-created | Pre-configured registry instance |
| `config(McpServerConfig)` | auto-created | Protocol and capability configuration |
| `host(String)` | `"127.0.0.1"` | TCP bind address. `"127.0.0.1"` = loopback only; `"0.0.0.0"` = all interfaces (network-exposed; use with firewall/TLS) |
| `port(int)` | `3011` | TCP port. `0` = OS ephemeral; retrieve via `server.getUrl()` after `start()` |
| `endpoint(String)` | `"/mcp"` | HTTP path for JSON-RPC requests |
| `apiKey(String)` | disabled | Fixed Bearer token (prefer `apiKeySupplier`) |
| `apiKeySupplier(Supplier<String>)` | disabled | Token supplier called on every request; use for rotating credentials |
| `allowedOrigins(Set<String>)` | loopback-only | Additional non-loopback allowed origins; see CORS note below |
| `build()` | — | Returns the configured `McpServer` (not started) |

**CORS / Origin note:** The SDK accepts all `127.0.0.0/8` and `::1` origins regardless of port without any configuration. `allowedOrigins` is only needed for non-loopback origins such as `https://my-app.example.com`. The origin policy is entirely independent of the bind address and of Bearer authentication. See ADR-0021.

**0.0.0.0 note:** Binding to `0.0.0.0` exposes the server on every IPv4 interface. The SDK does not perform TLS in-process; for production, bind to `127.0.0.1` behind a reverse proxy that terminates TLS.

#### `McpServerConfig.Builder` — protocol-level configuration

Controls MCP protocol version, capabilities, sessions, and rate limits.

**Metadata**

| Method | Default | Description |
|--------|---------|-------------|
| `protocolMode(ProtocolMode)` | `SESSIONED` | `SESSIONED` = stateful sessions (default); `STATELESS` = per-request (`2026-07-28`) |
| `protocolVersion(String)` | `"2025-11-25"` | Advertised protocol version string |
| `serverName(String)` | `"mcp-server"` | Server name in `initialize` response |
| `serverVersion(String)` | `"1.0.0"` | Server version string |
| `experimental(Map<String,Object>)` | empty | Arbitrary metadata added to capabilities |

**Capabilities**

| Method | Default | Description |
|--------|---------|-------------|
| `tools(boolean)` | `true` | Advertise tools capability |
| `resources(boolean)` | `true` | Advertise resources capability |
| `resourceSubscriptions(boolean)` | `true` | Advertise resource subscriptions (requires `resources`) |
| `prompts(boolean)` | `true` | Advertise prompts capability |
| `logging(boolean)` | `false` | Advertise logging capability |
| `completions(boolean)` | `false` | Advertise completion capability |
| `tasks(boolean)` | `false` | Advertise tasks capability |
| `tasksExtension(McpTaskExtension)` | null | Pluggable tasks extension; controls version-gated task method dispatch |
| `elicitation(boolean)` | `false` | Advertise elicitation capability (requires `protocolMode(STATELESS)` and `protocolVersion("2026-07-28")`) |
| `elicitationTimeoutMs(long)` | `60000` | Timeout for elicitation requests in milliseconds |

**Sessions and streaming**

| Method | Default | Description |
|--------|---------|-------------|
| `streaming(boolean)` | `true` | Advertise `streaming: {}` capability in `initialize` response |
| `streamableHttp(boolean)` | `true` | **No runtime effect in this release.** Transport mode is set exclusively via `McpServer.builder().transportMode(TransportMode.STREAMABLE_HTTP)`. This flag is reserved for a future release. |

**Trusted-proxy and session security**

| Method | Default | Description |
|--------|---------|-------------|
| `trustXForwardedFor(boolean)` | `false` | Extract client IP from `X-Forwarded-For` instead of socket address |
| `bindSessionToIp(boolean)` | `false` | Lock session to client IP at `initialize` time (AUTH-03 / session-fixation mitigation). Requires `trustXForwardedFor` when behind a reverse proxy |

**Rate limits and authorization**

| Method | Default | Description |
|--------|---------|-------------|
| `rateLimits(RateLimits)` | `McpSecurityDefaults` | Full rate-limit and security tunables (see `RateLimits`) |
| `authorization(McpAuthorization)` | null | Tool authorisation handler; `null` = allow all |
| `pageSize(int)` | `50` | Maximum items per paginated response |
| `maxQueuedEvents(int)` | `1000` | Max SSE events held in session queue before oldest evicted |
| `overflowListener(QueueOverflowListener)` | null | Called when notification queue overflows; `null` throws `QueueOverflowException` |
| `logger(McpLogger)` | JDK logger | Application logger adapter |

---

## Registration

### `McpRegistrar`

Public SPI for registering capability handlers. All registration methods throw `IllegalArgumentException` on duplicate
names or invalid arguments.

```java
registrar.registerTool("my-tool","A tool",
                       Map.of("properties", Map.of("name", Map.of("type", "string"))),
        List.

of("name"),

arguments ->Map.

of("result","ok"));

        registrar.

registerResource(
    "demo://readme",
            "text/plain",
            "SDK readme",
    uri ->"Readme content...");

        registrar.

registerPrompt(
    "my-prompt",
            "A prompt",
    arguments ->Map.

of("role","user","content","Hello"));
```

**Tool registration**

| Signature                                                                       | Description                 |
|---------------------------------------------------------------------------------|-----------------------------|
| `registerTool(name, description, inputSchema, required, handler)`               | Text/tool only              |
| `registerTool(name, description, inputSchema, required, handler, outputSchema)` | With optional output schema |

**Resource registration**

| Signature                                                   | Description                                |
|-------------------------------------------------------------|--------------------------------------------|
| `registerResource(uri, mimeType, description, handler)`     | Exact-match resource                       |
| `registerBlobResource(uri, mimeType, description, handler)` | Binary resource (returns `McpBlobContent`) |
| `registerResourceTemplate(uri, description, handler)`       | Template with URI variables                |

**Prompt registration**

| Signature                                    | Description |
|----------------------------------------------|-------------|
| `registerPrompt(name, description, handler)` | Prompt      |

**Listener registration**

| Signature                                | Description                                      |
|------------------------------------------|--------------------------------------------------|
| `addRegistryChangeListener(listener)`    | Called when tools, resources, or prompts change  |
| `removeRegistryChangeListener(listener)` | Remove a previously registered listener          |
| `setResourceUpdateListener(listener)`    | Called when a subscribed resource URI is updated |

### `McpReflectionRegistrar`

Scans annotated provider objects and registers everything through a `McpRegistrar`. Use with `@Tools`, `@Resources`,
`@Prompts`.

```java
McpReflectionRegistrar registrar = new McpReflectionRegistrar();
registrar.

register(providerObject, myRegistrar);
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

    public McpBlobContent(String blob, String mimeType) { ...}

    public static boolean isValidBase64(String value) { ...}
}
```

MCP `resources/read` response for a blob:

```json
{
  "contents": [
    {
      "type": "blob",
      "blob": "<base64>",
      "mimeType": "image/png"
    }
  ]
}
```

---

## Tasks

### `McpTask`

Immutable snapshot of a bounded server task. Created via `McpProtocolHandler.createTask()` or `tasks/create`.

```java
public final class McpTask {
    public enum Status { WORKING, COMPLETED, FAILED, CANCELLED }

    String getTaskId();
    Status getStatus();
    long getCreatedAt();
    long getLastUpdatedAt();
    Object getResult();       // non-null when COMPLETED
    String getError();         // non-null when FAILED or CANCELLED

    // Factory
    static McpTask create();
    static McpTask create(String name, String sessionId, String requestId,
                           Map<String, Object> input, Map<String, Object> inputSchema);

    // State transition — produces a new immutable snapshot
    McpTask transition(Status nextStatus, Object nextResult, String nextError);

    // JSON serialization
    Map<String, Object> toMap();
}
```

### `McpTaskExtension`

Pluggable SPI for the `io.modelcontextprotocol/tasks` capability.  See
**[TASKS-EXTENSION.md](TASKS-EXTENSION.md)** for the full guide.

```java
McpServerConfig config = McpServerConfig.builder()
    .tasks(true)
    .tasksExtension(new MyTaskExtension())
    .build();
```

### Task lifecycle

| MCP method               | Description                                       |
|--------------------------|---------------------------------------------------|
| `tasks/create`           | Create a named deferred task                      |
| `tasks/get`              | Fetch current task snapshot                       |
| `tasks/cancel`           | Cancel a working task and emit `notifications/cancelled` |
| `tasks/result`           | Fetch the final result (COMPLETED) or error (FAILED/CANCELLED) |

Tasks created programmatically (no MCP wire call):

| `McpProtocolHandler` method | Description                                      |
|------------------------------|--------------------------------------------------|
| `createTask()`               | Create a bounded task in WORKING state           |
| `completeTask(id, result)`   | Transition to COMPLETED                          |
| `failTask(id, error)`        | Transition to FAILED                             |

### Error codes

| Code  | Constant                      | When                                      |
|-------|-------------------------------|-------------------------------------------|
| -32001 | `RESULT_NOT_COMPLETE`          | `tasks/result` called on a WORKING task    |
| -32002 | `RESULT_ALREADY_TERMINAL`      | `tasks/cancel` called on a terminal task  |

---

## Elicitation

Elicitation enables the server to prompt the client for confirmation or text input during tool execution.
All methods return `CompletableFuture` — the call is non-blocking.

See [ELICITATION.md](ELICITATION.md) for the full guide, including async pipeline examples.

### Configuration

Enable in `McpServerConfig`:

```java
McpServerConfig.builder()
        .protocolVersion("2026-07-28")
        .protocolMode(ProtocolMode.STATELESS)
        .elicitation(true)
        .elicitationTimeoutMs(60_000)  // default: 60 s
        .build();
```

### API — `McpProtocolHandler`

| Method | Description |
|--------|-------------|
| `elicitConfirmation(sessionId, message, actions, timeoutMs)` | Prompt with labelled actions; resolves to selected action label; throws `McpElicitationException` on decline or timeout |
| `elicitInput(sessionId, message, defaultValue, timeoutMs)` | Prompt for free-form text; returns `defaultValue` on decline; throws on timeout |

### Error codes

| Code | Constant | When |
|------|----------|------|
| `-32003` | `SERVER_REQUEST_TIMEOUT` | Client did not respond within timeout |
| `-32004` | `ELICITATION_REJECTED` | Client declined or dismissed |
| `-32601` | `METHOD_NOT_FOUND` | Transport does not support elicitation (e.g., STDIO) |

### Key types

| Type | Package | Purpose |
|------|---------|---------|
| `ElicitAction` | `api.dto` | Labelled action (label + optional description) |
| `ElicitRequest` | `api.dto` | Immutable elicitation request (builder API) |
| `ElicitationResult` | `api.dto` | Parsed client response; distinguishes action, value, and decline |
| `McpElicitationException` | `api.utils` | Thrown on timeout, decline, or transport error; extends `RuntimeException` |

Transport: **HTTP/SSE only** — not available over STDIO or Streamable HTTP POST responses.

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

### `HttpTransportProvider`

Provides `McpHttpHandler` lifecycle management.

| Method            | Description                                         |
|-------------------|-----------------------------------------------------|
| `start()`         | Bind and start the HTTP server                      |
| `stop()`          | Stop the HTTP server                                |
| `isRunning()`     | `true` if the server is accepting connections       |
| `getActualPort()` | Returns the actual port (useful with ephemeral `0`) |
| `getUrl()`        | Returns the server URL when running                 |
| `getHandler()`    | Returns the `McpHttpHandler` instance               |

### `McpHttpHandler`

Internal transport handler. Access via `HttpTransportProvider.getHandler()`.

---

## Protocol

### `McpProtocolHandler`

Protocol dispatch layer. Access via `McpServer` or through `McpHttpHandler`.

| Method                                  | Description                                  |
|-----------------------------------------|----------------------------------------------|
| `hasSession(id)`                        | `true` if a session exists                   |
| `terminateSession(id)`                  | Close a session and notify all subscribers   |
| `sendNotification(method, params)`      | Push a JSON-RPC notification to all sessions |
| `notifyResourceUpdated(uri)`            | Push `resources/updated` to subscribers      |
| `notifyLogMessage(level, logger, data)` | Push a `logging/message` notification        |
| `supportsProtocolVersion(v)`            | `true` if the given version is supported     |

### `McpRegistry`

Central registry of registered tools, resources, prompts, and tasks.

| Method                               | Returns                                      |
|--------------------------------------|----------------------------------------------|
| `getRegisteredTools()`               | `List<Map<String, Object>>` (tools/list)     |
| `getRegisteredResources()`           | `List<Map<String, Object>>` (resources/list) |
| `getResourceTemplateHandlers()`      | `Map<String, McpResourceHandler>`            |
| `getBlobResourceHandlers()`          | `Map<String, McpBlobResourceHandler>`        |
| `getRegisteredPrompts()`             | `List<Map<String, Object>>` (prompts/list)   |
| `getRegisteredCompletions()`         | `List<McpCompletionProvider>`                |
| `getCompletionCandidates(ref, args)` | `List<String>`                               |
| `getTask(id)`                        | `McpTask` or `null`                          |
| `getAllTasks()`                      | `Collection<McpTask>`                        |

---

## Protocol methods

Supported MCP JSON-RPC methods:

| Method                      | Direction    | Capability required |
|-----------------------------|--------------|---------------------|
| `initialize`                | request      | —                   |
| `notifications/initialized` | notification | —                   |
| `tools/list`                | request      | `tools: true`       |
| `tools/call`                | request      | `tools: true`       |
| `resources/list`            | request      | `resources: true`   |
| `resources/read`            | request      | `resources: true`   |
| `resources/templates/list`  | request      | `resources: true`   |
| `resources/subscribe`       | request      | `resources: true`   |
| `resources/unsubscribe`     | request      | `resources: true`   |
| `prompts/list`              | request      | `prompts: true`     |
| `prompts/get`               | request      | `prompts: true`     |
| `completion/complete`       | request      | `completions: true` |
| `logging/setLevel`          | request      | `logging: true`     |
| `notifications/message`     | notification | `logging: true`     |
| `tasks/get`                 | request      | `tasks: true`       |
| `tasks/result`              | request      | `tasks: true`       |
| `tasks/cancel`              | request      | `tasks: true`       |
| `elicitation/create`        | server-initiated request | `elicitation: true` (HTTP/SSE only) |

## HTTP transport

| Endpoint | Method | Description                      |
|----------|--------|----------------------------------|
| `/mcp`   | POST   | JSON-RPC request or notification |
| `/mcp`   | GET    | SSE event stream (session-bound) |
| `/mcp`   | DELETE | Session termination              |

**Required headers**

| Direction | Header                 | Value                                                       |
|-----------|------------------------|-------------------------------------------------------------|
| POST in   | `Content-Type`         | `application/json`                                          |
| POST in   | `Accept`               | `application/json` or `application/json, text/event-stream` |
| POST in   | `Mcp-Protocol-Version` | Supported protocol version                                  |
| POST in   | `Mcp-Session-Id`       | Session ID (after initialize)                               |
| POST in   | `Authorization`        | `Bearer <token>` (if configured)                            |
| POST out  | `Mcp-Session-Id`       | Issued session ID                                           |
| GET in    | `Mcp-Session-Id`       | Active session ID                                           |
| GET in    | `Last-Event-ID`        | Event ID to resume from                                     |

**Security defaults**

- Default bind: `127.0.0.1` (loopback only)
- Default max body: 1 MiB
- Unknown Origin (non-loopback, not in explicit allowlist): rejected with HTTP `403`
- Bearer token: constant-time comparison via `MessageDigest.isEqual`
- SSE CRLF: sanitised before transmission
- CORS: loopback origins (`127.0.0.0/8`, `::1`, any port) accepted automatically; non-loopback origins require explicit allowlist entry via `McpServer.Builder.allowedOrigins(Set)`. Origin policy is independent of bind address and Bearer auth (see [ADR-0021](../adr/ADR-0021-cors-loopback-origin-policy.md))

---

## Related documentation

| Document | Description |
|---|---|
| [Project Guide](PROJECT-GUIDE.md) | Architecture, usage guide, and protocol overview |
| [HTTP Transport Example](HTTP-TRANSPORT-EXAMPLE.md) | Standalone HTTP/SSE transport example with request samples |
| [Transport — Streamable HTTP](../transport/TRANSPORT-STREAMABLE-HTTP.md) | SSE connection flow, TLS reverse-proxy topology |
| [ADR-0021 — CORS Loopback Origin Policy](../adr/ADR-0021-cors-loopback-origin-policy.md) | Full CORS policy: loopback rule, allowlist, preflight, auth independence |
| [ADR-0018 — Transport Contract](../adr/ADR-0018-transport-contract-http-sse-vs-streamable-http.md) | Legacy HTTP+SSE vs modern Streamable HTTP comparison |
| [Implementation Status](IMPLEMENTATION-STATUS.md) | Completed, incomplete, and unverified areas |
| [MCP Compatibility](../architecture/MCP-COMPATIBILITY-2026.md) | MCP baseline and P0/P1/P2 compatibility status |
| [docs/adr/](../adr/) | Architecture Decision Records |

