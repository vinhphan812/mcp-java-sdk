# MCP Core Porting Plan

## Objective

Port portable MCP core from `CruzrEnglishAssistant` into this standalone Java 8 library under `io.github.vinhphan812.mcp`.

## Current state

Source MCP module contains:

- `annotations`: runtime annotations for tools, resources, prompts, and parameters.
- `api`: registration SPI, handlers, server configuration, reflection registrar.
- `core`: registry and JSON-RPC protocol handler.
- `transport`, `security`, and `cruzr`: Android, Grizzly, persistence, and robot/application integrations.

Target project is plain Gradle Java 8. No Android runtime or application utilities available.

## Scope

Port only dependency-neutral core:

```text
io.github.vinhphan812.mcp.annotations
io.github.vinhphan812.mcp.api
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
- `mcp/cruzr`: robot-specific providers, managers, and definitions.
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
com.sparkcore.cruzr.englishassistant.modules.mcp
```

with:

```text
io.github.vinhphan812.mcp
```

## Acceptance criteria

1. No portable source imports `android.*`, `androidx.*`, or Cruzr application packages.
2. Target compiles with Java 8 compatibility.
3. Unit tests cover registration, reflection, sessions, JSON-RPC routing, capability rejection, and resource templates.
4. `./gradlew test` passes.
5. `./gradlew build` passes and produces sources/Javadoc artifacts.
6. README documents portable scope and excluded integrations.
7. Existing target files remain intact except required build, docs, source, and test changes.

## Execution status

Implemented and verified. The target currently has no test sources, so Gradle reports `:test` as `NO-SOURCE`; behavior-level tests remain a release prerequisite.

## Risks and limits

- This is a core library port, not a drop-in Android server replacement.
- HTTP transport and credential storage require separate adapters.
- Source copyright/license text must be reviewed before public release; current source header states personal/educational-only use and is not an open-source license.
