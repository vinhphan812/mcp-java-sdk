# MCP Java SDK — Project Guide

## 1. Purpose

`mcp-java-sdk` is an independent, portable MCP server SDK targeting Java 8. It is separated from Android, ROSA (Robot
Operating System Android), and application-specific services.

The implementation baseline is MCP `2025-11-25`. MCP `2026-07-28` is used only as a comparison reference; the SDK does
not claim full support for that version.

The SDK currently provides a practical subset:

- JSON-RPC 2.0 request and notification dispatch;
- MCP initialisation and protocol-version validation;
- tools, resources, resource templates, and prompts;
- Grizzly Streamable HTTP transport;
- session headers and basic lifecycle management;
- annotation-based reflection registration;
- a Java 8-compatible server bootstrap.

The SDK is not a full MCP implementation. Unsupported or unverified features are listed in section 8.

The package contains MCP core and Grizzly HTTP transport only. WebSocket is not a package capability; a robot or
application may connect through an external WebSocket bridge or adapter when required.

## 2. Directory structure

```text
src/main/java/io/github/vinhphan812/mcp/
├── annotations/       Runtime annotations for tools, resources, prompts, parameters
├── api/                Public contracts and configuration
│   ├── spi/            Registration SPI and lifecycle listeners
│   │   ├── McpRegistrar, McpResourceUpdateListener, McpRegistryChangeListener
│   ├── handler/        Tool, resource, prompt, completion handler interfaces
│   │   ├── McpToolHandler, McpResourceHandler, McpBlobResourceHandler,
│   │   ├── McpPromptHandler, McpCompletionProvider
│   ├── dto/            Immutable value objects
│   │   ├── McpTask, McpBlobContent
│   ├── config/         Server and client configuration
│   │   ├── McpServerConfig, McpClientCapabilities
│   ├── logging/         Logging adapters
│   │   ├── McpLogger, JulMcpLogger
│   └── McpReflectionRegistrar.java   Annotation-based registration utility
├── core/               Registry, protocol dispatch, server facade
│   ├── McpServer, McpRegistry, McpProtocolHandler
└── transport/          Grizzly HTTP adapter and lifecycle provider
    ├── HttpTransportProvider, McpHttpHandler

src/test/java/io/github/vinhphan812/mcp/
└── Behaviour and live HTTP tests

examples/src/main/java/.../examples/
└── HttpExample.java

docs/
├── guides/
│   ├── PROJECT-GUIDE.md        # (this file, in guides/)
│   ├── API-REFERENCE.md
│   ├── USER_GUIDE.md
│   ├── GRIZZLY-EXAMPLE.md
│   ├── TRANSPORT-SSE.md
│   └── IMPLEMENTATION-STATUS.md
├── architecture/
│   ├── MCP-COMPATIBILITY-2026.md
│   ├── MCP-PORTING-PLAN.md
│   ├── mcp-registry-design-specification.md
│   ├── blob-resource-spi-triage-spec.md
│   └── listener-semantics-spec.md
├── authz/
│   ├── SCOPES-AUTHORIZATION-SPEC.md
│   └── RATE-LIMIT-TRIAGE-SPEC.md
├── adr/                Architecture Decision Records
└── audits/
    ├── AUDIT_STATUS.md
    └── historical/
```

## 3. Package architecture

The package split is intentionally small and clean:

- `annotations/` contains runtime metadata only (`@McpTool`, `@McpParam`, `@McpResource`, `@McpResourceTemplate`,
  `@McpPrompt` and provider markers). It has no transport or registry policy.
- `api/spi/` defines registration contracts (`McpRegistrar`) and listeners that the server fires when registrations or
  resources change.
- `api/handler/` defines the interfaces applications implement to provide tool, resource, prompt, and completion
  behaviour.
- `api/dto/` holds immutable value objects that flow through the protocol (tasks, blob content).
- `api/config/` holds server and client configuration (`McpServerConfig`, `McpClientCapabilities`).
- `api/logging/` provides a portable logger interface and a JDK-logging adapter.
- `api/McpReflectionRegistrar.java` is a runtime utility that scans `@Tools`, `@Resources`, and `@Prompts` providers and
  registers their annotated methods.
- `core/` contains protocol dispatch, registry state, and the server facade. It translates public registrations into MCP
  JSON-RPC behaviour but does not own HTTP-specific request handling.
- `transport/` contains the Grizzly Streamable HTTP adapter and lifecycle provider. Keeping it isolated permits a future
  transport adapter without moving protocol or annotation code.

This arrangement keeps dependency direction clear: annotations describe providers; API exposes extension points; core
owns MCP semantics; transport adapts network I/O. Intentionally excluded are Android application services, robot/domain
models, WebSocket and STDIO implementations, persistence, authentication backends, and broad framework abstractions.
Those belong in consuming applications or separate adapters. The standalone example is under `examples/`, not a
production package.

## 4. Runtime and dependencies

- Java source/target: 8
- Build tool: Gradle Wrapper
- Gson: `2.11.0`
- Grizzly HTTP server: `4.0.2`
- Default bind address: `127.0.0.1`
- Default port: `3011`
- Default endpoint: `/mcp`

Do not put credentials, API keys, tokens, passwords, or connection strings in source, tests, examples, or documentation.
Use `[REDACTED]` for sensitive example values.

## 4. Hosting an MCP server in an Android application

The SDK can run inside an Android application process and expose an MCP server over HTTP. This includes Android phones,
embedded Android devices, and Android-based robots. The application owns the Android Service/lifecycle, network
permissions, bind address, authentication policy, and provider implementations; the SDK supplies the MCP protocol core
and optional Grizzly transport.

Typical Android integration:

1. Add the `mcp-java-sdk:1.0.0` dependency.
2. Create `McpServer` from an Android `Service` or another lifecycle owner.
3. Register application-owned tools, resources, and prompts.
4. Start the server on a selected address and port.
5. Connect an MCP client to the device endpoint.
6. Call `stop()`/`close()` when the Service or application stops.

Use `127.0.0.1` for local-only access. Binding to a LAN address requires authentication, an Origin allowlist, request
limits, network policy, and lifecycle controls. The bundled Grizzly transport is JVM/server-oriented and must be
verified on the target Android API level. If Grizzly is unsuitable for a device runtime, implement or provide another
transport through the public API while retaining the same MCP core.

## 5. Server startup flow

```mermaid
flowchart TB
    subgraph application["Application"]
        Config["McpServerConfig"]
        Builder["McpServer.Builder"]
        Server["McpServer"]
        TransportProvider["HttpTransportProvider"]
    end

    subgraph core["core/ (transport-neutral)"]
        Registry["McpRegistry"]
        Protocol["McpProtocolHandler"]
    end

    subgraph transport["transport/ (Grizzly-specific)"]
        Handler["McpHttpHandler"]
        GrizzlyServer["Grizzly HTTP Server"]
    end

    Config --> Builder
    Builder --> Server
    Server --> Registry
    Server --> Protocol
    Server --> TransportProvider
    TransportProvider --> Handler
    Handler --> Protocol
    Handler --> GrizzlyServer

    style application fill:#e3f2fd,stroke:#1565c0
    style core fill:#e8f5e9,stroke:#2e7d32
    style transport fill:#fff3e0,stroke:#e65100
```

**Two-layer design.** `core/` holds MCP logic (JSON-RPC dispatch, registry, session state) and knows nothing about the
network. `transport/` holds Grizzly-specific HTTP/SSE handling and calls into `core/` for protocol work. This separation
allows the protocol layer to be tested in isolation and permits swapping the transport in the future.

Providers are registered before `start()`:

Providers are registered before `start()`:

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
        .register(new MyResources());

server.start();
Logger.getLogger("mcp-server").info(server.getUrl());
// shutdown: server.close()
```

Use `port(0)` in tests to request an ephemeral port from the operating system. After startup, retrieve the actual port
with `server.getTransport().getActualPort()`.

Lifecycle methods:

- `register(...)`: register a provider before startup;
- `registerAll(...)`: register multiple providers in order;
- `start()`: start Grizzly;
- `isRunning()`: inspect the running state;
- `getUrl()`: obtain the endpoint while the server is running;
- `stop()`/`close()`: stop the server and close sessions.

## 6. Capability registration

The reflection registrar reads these annotations:

- `@Tools` + `@McpTool`;
- `@Resources` + `@McpResource`;
- `@Resources` + `@McpResourceTemplate`;
- `@Prompts` + `@McpPrompt`;
- `@McpParam` for argument metadata, schemas, and direct parameter binding.

The complete runnable catalogue, request payloads, capability settings, run commands, and limitations are documented in
`GRIZZLY-EXAMPLE.md`. It registers six tools, three exact resources, four resource templates, and four prompts.

When a tool or prompt method has one `Map<String, Object>` parameter, the map is passed through unchanged. When
parameters are individually annotated with `@McpParam`, JSON argument values are bound by name and converted to
supported Java 8 scalar types (`String`, boolean, and numeric primitives/wrappers). Required values and types are
checked before invocation. Any remaining type (user-defined POJO, nested object, array, or generic `List`) is
deserialised via Gson round-trip. See `GRIZZLY-EXAMPLE.md` for a full POJO example (`Address`).

Current contracts:

- tool methods return `Map<String, Object>`;
- prompt methods return `Map<String, Object>`;
- resource and resource-template methods return `String`;
- tool and prompt methods accept zero arguments, one compatible `Map<String, Object>` argument, or individually
  annotated direct parameters;
- direct `@McpParam` parameters are bound by name and support String, boolean, numeric primitive/wrapper, Object, and
  Map values;
- resource arguments are normally URI `String` values.

The registrar checks return types and throws `IllegalArgumentException` when a method violates the contract. For large
production deployments, consider a generated registrar instead of reflection to reduce startup cost and improve
determinism.

## 7. Package architecture assessment

The package layout under `io.github.vinhphan812.mcp` is appropriate for the current SDK boundary and should not be
flattened or split further without a new capability requiring it:

- `annotations`: public annotation declarations only; no protocol or transport dependency.
- `api`: public extension contracts, configuration, handlers, logger, completion provider, and task/client metadata
  types.
- `core`: registry, JSON-RPC dispatch, session state, capability advertisement, and server facade; it is
  transport-neutral.
- `transport`: Grizzly HTTP lifecycle, headers, authentication, Origin policy, SSE, and request limits; HTTP-specific
  concerns remain isolated here.

Keeping `core` independent of `transport` allows another transport to use the same protocol handler. Keeping `api`
separate from `core` makes the public integration surface explicit. WebSocket, Android services, robot/application
domain models, persistence, and generated registrars are intentionally outside these packages.

The example application is kept under `examples/.../examples` and is not part of the SDK artifact.

## 8. Protocol flow

The client sends `initialize` using JSON-RPC 2.0:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {"name": "client", "version": "1.0.0"}
  }
}
```

The client then sends this notification:

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

Notifications have no `id` and receive no JSON-RPC response body. Ordinary requests have an `id` and receive a response
or error.

Main methods:

- `initialize`;
- `tools/list`, `tools/call`;
- `resources/list`, `resources/read`;
- `resources/templates/list`;
- `prompts/list`, `prompts/get`.

A resource template does not use a custom route. The client resolves a URI, such as `demo://users/42`, and then calls
`resources/read`.

Protocol failures use JSON-RPC errors, including invalid requests or parameters and unknown methods or resources. The
SDK does not treat an application error as a successful result containing an error string.

## 9. HTTP transport

The endpoint handles:

- `POST /mcp`: JSON-RPC request or notification;
- `GET /mcp`: basic session-bound event stream;
- `DELETE /mcp`: session termination.

Relevant headers:

- POST requires `Content-Type: application/json`;
- `Accept` must allow `application/json` and/or `text/event-stream`;
- protocol header: `Mcp-Protocol-Version: 2025-11-25`;
- session header: `Mcp-Session-Id` after a session is issued;
- optional authentication: `Authorization: Bearer [REDACTED]`.

Security defaults:

- examples bind to loopback;
- an unknown or disallowed Origin is rejected;
- Bearer tokens are compared in constant time;
- the secret is supplied by `Supplier<String>` and must not be written to logs or source.

Production deployment should add a reverse proxy, request limits, TLS, an appropriate Origin allowlist, and a secret
provider outside source control.

## 9. Unsupported or unverified scope

The following features are not implemented or have not been fully demonstrated:

- progress/cancellation notifications (implemented: `notifyToolProgress`, `notifications/cancelled`, `isCancelled` —
  P2);
- sampling (not implemented);
- elicitation request/response flow (not implemented);
- async task orchestration and `tasks/create` (implemented — P2);
- async server API (not implemented);
- STDIO transport;
- typed schema model beyond `@McpTool(outputSchema)`;
- POST event-stream response mode (results returned as JSON);
- complete CORS policy beyond Origin rejection;
- external MCP client interoperability;
- Android/ROSA device or emulator startup evidence.

Do not describe the SDK as having full MCP parity while these limitations remain.

## 9a. Security

The SDK provides built-in security controls (ADR-0011):

- **Owner-based sessions:** `initialize` accepts an optional `ownerId`. One active session per owner.
- **Per-category rate limiting:** read/write/admin burst + sustained + concurrent caps.
- **Destructive tool caps:** lifetime limits + cooldown for `shutdown`, `delete_action`, `delete_prompt`, `upload_file`.
- **Abuse scoring:** weighted signals accumulate; session blocked at threshold.
- **Queue overflow handling:** bounded notification queue (100/session); throws `QueueOverflowException` or notifies
  `QueueOverflowListener`.
- **IP-based rate limiting:** per-client-IP request limits.
- **Max concurrent sessions:** default 10; returns `-32029` when exceeded.
- `McpServerConfig.Builder.rateLimits(...)` — server-specific security and rate-limit overrides
- `RateLimits` is immutable; create a new server/configuration for runtime changes

See [ADR-0011](../adr/ADR-0011-security-rate-limiting.md) for the complete design.

```java
McpServerConfig.builder()
    .serverName("my-server")
    .overflowListener(sessionId -> log.warn("Queue overflow on session: " + sessionId))
    .authorization((scopes, confirmationRequired, args) -> {
        if (Arrays.asList(scopes).contains("admin") && !currentUser.isAdmin()) {
            return "Admin scope required";
        }
        return null;
    })
    .build();
```

Annotate tools with `@McpTool(scopes = {"admin"}, confirmationRequired = true)` to opt in.

## ## 10. Build, tests, and example

The build creates one Maven publication with artifact `io.github.vinhphan812.mcp:mcp-java-sdk` and main, sources, and
Javadoc JARs. CI and release workflows are configured, but no tagged release or publication has been executed.

From the project root:

```bash
./gradlew clean test build --console=plain
```

Current tests cover:

- configuration defaults and validation;
- JSON-RPC dispatch and error/notification behaviour;
- annotation registration and direct binding;
- tasks (get, result, cancel, progress, notFound);
- pagination (cursor-based);
- list-changed notifications;
- in-process Grizzly HTTP smoke, security matrix, and resumability paths;
- Bearer token authentication matrix.

The example is not declared as a separate Gradle source set or application task. Run it with a suitable Gradle classpath
or add a separate build task in a different change.

Detailed request guidance is in `GRIZZLY-EXAMPLE.md`.

## 11. Audit and release checklist

Before release:

1. run `./gradlew clean test build --console=plain`;
2. inspect test reports and the exit code;
3. run a static search for Android/application imports;
4. check for real secrets or credentials;
5. review the existing audit documentation and update its date and scope;
6. review dependency notices or an SBOM as required for the release;
7. check Origin, TLS, body-size limits, authentication, and reverse-proxy configuration;
8. confirm client interoperability with runtime HTTP probes.

## 12. Reference documentation

- `README.md`: quick overview;
- `guides/API-REFERENCE.md`: complete public API surface with all annotations, handlers, and methods;
- `guides/GRIZZLY-EXAMPLE.md`: transport and example walkthrough;
- `architecture/MCP-COMPATIBILITY-2026.md`: compatibility matrix and remediation history;
- `architecture/MCP-PORTING-PLAN.md`: portable extraction plan;
- `guides/IMPLEMENTATION-STATUS.md`: completed, incomplete, and unverified areas with test evidence;
- `guides/USER_GUIDE.md`: quick-start usage guide;
- `guides/TRANSPORT-SSE.md`: SSE transport and reverse-proxy guidance;
- `adr/`: architecture decision records (ADRs) documenting key design choices.
- `audits/`: security and documentation audit findings with historical evidence.
