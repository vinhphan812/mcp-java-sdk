# MCP Core Porting Plan

## Objective

Port portable MCP core into a standalone Java 8 library under `io.github.vinhphan812.mcp`.

## Current state

Source MCP module contains:

- `annotations`: runtime annotations for tools, resources, prompts, and parameters.
- `api`: registration SPI, handlers, server configuration, reflection registrar.
- `core`: registry and JSON-RPC protocol handler.
- `transport`, `security`, and robot integrations: Android, Grizzly, persistence, and application-specific integrations.

Target project is plain Gradle Java 8. No Android runtime or application utilities available.

## Scope

Port only dependency-neutral core:

```text
io.github.vinhphan812.mcp.annotations
io.github.vinhphan812.mcp.api.spi
io.github.vinhphan812.mcp.api.handler
io.github.vinhphan812.mcp.api.dto
io.github.vinhphan812.mcp.api.config
io.github.vinhphan812.mcp.api.logging
io.github.vinhphan812.mcp.api  (McpReflectionRegistrar)
io.github.vinhphan812.mcp.core
```

Included files:

- all files under source `mcp/annotations`
- all files under source `mcp/api`
- `McpRegistry.java`
- `McpProtocolHandler.java`

Excluded from portable library:

- `mcp/transport`: Android preferences, Android logging, and Grizzly HTTP dependencies.
- `mcp/security`: Android `Context`, `SharedPreferences`, and app storage utilities.
- `mcp/cruzr`: robot-specific providers, managers, and definitions for Android/ROSA robots.
- `RobotMcpService.java`: Android service and application integration.

## Dependency plan

Use Gson directly for JSON serialization in portable core. Add:

```gradle
implementation 'com.google.code.gson:gson:2.11.0'
```

Do not copy Android logging or `GsonObjectMapper`; replace protocol logging with JDK logging. Keep Java 8-compatible collection and reflection APIs.

## Package conversion

Replace source prefix:

```text
io.github.vinhphan812.mcp   # original prefix was specific to the source Android/ROSA robot project
```

with:

```text
io.github.vinhphan812.mcp   # new portable package
```

## Acceptance criteria

1. No portable source imports `android.*`, `androidx.*`, or Android/ROSA robot application packages.
2. Target compiles with Java 8 compatibility.
3. Unit tests cover registration, reflection, sessions, JSON-RPC routing, capability rejection, and resource templates.
4. `./gradlew test` passes.
5. `./gradlew build` passes and produces sources/Javadoc artifacts.
6. README documents portable scope and excluded integrations.
7. Existing target files remain intact except required build, docs, source, and test changes.

## Execution status

Implemented baseline verified locally. The repository contains four JUnit 5 test classes with nine passing tests. These tests provide basic behaviour coverage; transport rejection, broader protocol coverage, and external-client interoperability remain release prerequisites.

## Risks and limits

- This is a core library port with a Grizzly HTTP transport, not a drop-in Android server replacement.
- Android runtime compatibility, including Android API 21, has not been verified with a device or emulator.
- WebSocket is outside this package; robots and applications must use an external bridge or adapter if they require WebSocket integration.
- Credential storage remains the responsibility of the consuming application or an external adapter.
- The repository is distributed under the Apache License 2.0; see the root `LICENSE` file. No tagged release or package publication has been executed.
