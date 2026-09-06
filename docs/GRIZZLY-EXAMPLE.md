# Grizzly MCP server example

Standalone Java 8 server using `McpServer`, `McpServerConfig`, and Grizzly Streamable HTTP.

Source: `examples/src/main/java/io/github/vinhphan812/mcp/examples/GrizzlyExample.java`

Example registers:

- Tool `greet`
- Exact resource `demo://readme`
- Resource template `demo://users/{userId}`
- Prompt `explain-user`

The annotated provider classes use `@Tools`, `@Resources`, and `@Prompts`. Methods use `@McpTool`, `@McpResource`, `@McpResourceTemplate`, `@McpPrompt`, and `@McpParam`.

`McpResource` and `McpResourceTemplate` methods return resource text. Tool and prompt methods return `Map<String, Object>` matching MCP result shapes.

`McpServer.register(...)` is chainable. `registerAll(...)` can register multiple providers in one call.

For current completed and incomplete areas, see `docs/IMPLEMENTATION-STATUS.md`.

Example MCP flow after startup:

1. POST `initialize` to `/mcp`.
2. Save response `Mcp-Session-Id`.
3. POST `notifications/initialized` with that header (a notification has no response body).
4. POST `resources/list`, `resources/read`, and `resources/templates/list`. Resolve a template URI such as `demo://users/42`, then call `resources/read`; there is no `resources/templates/get` method.
5. POST `prompts/list`, then `prompts/get` with `name` and `arguments`.
6. POST `tools/list`, then `tools/call` with `name` and `arguments`.

Do not expose or commit API keys, passwords, or connection strings.

## JSON-RPC request examples

Replace `<SESSION_ID>` with session ID returned by `initialize`.

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
```

```json
{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"demo://readme"}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"demo://users/42"}}
```

```json
{"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"explain-user","arguments":{"userId":"42"}}}
```

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"greet","arguments":{"name":"Ada"}}}
```

With curl, retain session header between calls:

```text
curl -i -X POST http://127.0.0.1:3011/mcp -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
```


## Run

Build SDK:

```text
./gradlew clean build
```

Run example from IDE with project classes and runtime dependencies on classpath.

Endpoint:

```text
http://127.0.0.1:3011/mcp
```

## Bootstrap

```java
McpServer server = McpServer.builder()
    .config(McpServerConfig.builder()
        .serverName("grizzly-example")
        .serverVersion("1.0.0")
        .tools(true)
        .resources(false)
        .prompts(false)
        .build())
    .host("127.0.0.1")
    .port(3011)
    .endpoint("/mcp")
    .build()
    .register(new DemoTools());

server.start();
```

`McpServerConfig` controls protocol metadata and advertised capabilities. `port(0)` selects ephemeral port; call `server.getUrl()` after startup.

For authentication, load secret from external environment/secret store and pass through `apiKey(...)`. Never commit credentials.

Transport supports POST JSON-RPC, GET event stream, DELETE session, `Mcp-Session-Id`, configurable Origin allowlist, configurable request body limit, and optional Bearer authentication.

POST requests must include `Content-Type: application/json` and `Accept: application/json, text/event-stream`. The server validates `Origin`, `Mcp-Protocol-Version`, and `Mcp-Session-Id`. Notifications receive HTTP 202 with no body.

The default protocol version is `2025-11-25`.
