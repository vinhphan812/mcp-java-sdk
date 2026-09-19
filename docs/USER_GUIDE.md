# MCP Java SDK — User Guide

## 1. Getting Started (5 minutes)

`mcp-java-sdk` is an independent, portable MCP server SDK for Java 8. It provides a lightweight framework to expose tools, resources, and prompts over HTTP using an annotation-based API.

### Quick Setup

Add the dependency to your `build.gradle` (using Gradle):
```gradle
dependencies {
    implementation 'io.github.vinhphan812:mcp-java-sdk:1.0-SNAPSHOT'
}
```

## 2. Basic Usage

To build a basic MCP server, use the `McpServer.Builder`.

```java
import io.github.vinhphan812.mcp.core.McpServer;
import io.github.vinhphan812.mcp.api.config.McpServerConfig;

// ...

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
        .register(new MyResources());

server.start();
```

## 3. Advanced Usage

### Rate Limits
Configure rate limits to prevent server overloading:

```java
import io.github.vinhphan812.mcp.api.config.RateLimits;

RateLimits limits = RateLimits.builder()
        .maxConcurrentSessions(10)
        .maxRequestsPerIpPerMinute(60)
        .read(60, 200, 5) // burst, sustainable, concurrent
        .write(30, 100, 3)
        .admin(5, 15, 1)
        .build();

McpServerConfig config = McpServerConfig.builder()
        .rateLimits(limits)
        .build();
```

### Authentication and authorization
The bundled Grizzly transport supports optional HTTP Bearer authentication. Configure the expected token through
`McpServer.Builder.apiKeySupplier`; the supplier is evaluated for every request, so it can read a rotating secret from
an environment variable or secret manager. Do not hard-code a token in source code.

```java
McpServer server = McpServer.builder()
        .apiKeySupplier(() -> System.getenv("MCP_API_KEY"))
        // other builder settings
        .build();
```

When the supplier is absent or returns `null`/blank, Bearer authentication is disabled. When it supplies a nonblank
value, POST, GET, and DELETE requests require a valid HTTP Bearer credential; the transport compares it with
`MessageDigest.isEqual` and rejects missing or invalid credentials with HTTP 401.

Bearer authentication identifies a request but does not provide user scopes. To control tool access, configure
`McpServerConfig.authorization(McpAuthorization)`. The authorization callback runs for `tools/call` after the request
and tool rate-limit checks, but before the tool handler. Return `null` to allow the call or a denial message to return
an MCP tool error result. Resource and prompt calls are not passed through this callback.

```java
McpServerConfig config = McpServerConfig.builder()
        .authorization((scopes, confirmationRequired, arguments) -> {
            if (Arrays.asList(scopes).contains("admin")) {
                return "Admin scope required";
            }
            return null;
        })
        .build();
```

There is no `ApiKeyStore` or `apiKeyMiddleware` configuration on `McpServerConfig`. Applications that need credential
storage, identity mapping, or richer authentication must implement it outside this SDK (for example at a reverse proxy
or in a custom transport).

## 4. API Reference

For detailed documentation of all classes, methods, and annotations, refer to `API-REFERENCE.md`.

## 5. Migration Guide

For detailed information on porting or migrating MCP projects, see `MCP-PORTING-PLAN.md`.

## 6. Troubleshooting

- **Compilation Errors:** Ensure all annotation processors are configured in your `build.gradle`.
- **HTTP Connection Issues:** Verify firewall settings and bind address (127.0.0.1 vs 0.0.0.0).
- **Protocol Errors:** Check if the `protocolVersion` in the client request matches the server config.

## 7. FAQ

**Q: Does it support WebSockets?**
A: No, the SDK directly supports HTTP streamable transport only. WebSocket connectivity requires an external bridge.

**Q: Can I run this on Android?**
A: The SDK is designed to be portable and independent, and can be used on Android by owning the lifecycle within a `Service`, but must be verified on the target Android API level.

See `PROJECT-GUIDE.md` for project scope details and implementation architecture.
