# MCP Java SDK

Portable Java 8 MCP core extracted from the Cruzr application.

## Scope

This library contains the transport-neutral MCP JSON-RPC core:

- annotations for tools, prompts, resources, and parameters;
- handler interfaces and server configuration;
- reflection-based registration;
- concurrent registry;
- MCP protocol request/response handling and session state.

Android, Cruzr robot, application services, and credential storage are intentionally excluded. An optional Grizzly Streamable HTTP transport and server bootstrap are included for standalone Java applications. Applications can also provide their own transport through the public API.

## Build

```bash
./gradlew clean test build
```

The build targets Java 8 and produces the main JAR, sources JAR, and Javadoc JAR under `build/libs`.

## Dependency and license

JSON parsing uses Gson `2.11.0`, resolved from Maven Central. Gson is Apache License 2.0. Check `./gradlew dependencies` and the upstream license before redistribution.

The portable source retains the original copyright/license notice where present. Review and replace the project license header before publishing a public release; the current source notice is not an OSI-approved open-source license by itself.

## Usage outline

1. Create an `McpRegistry`.
2. Register `McpToolHandler`, `McpPromptHandler`, or `McpResourceHandler` instances, or use `McpReflectionRegistrar`.
3. Construct `McpProtocolHandler` with the registry and optional `McpServerConfig`.
4. Pass JSON-RPC request bodies to `handleRequest` from an application-owned HTTP, SSE, or stdio adapter.

Documentation:

- `docs/PROJECT-GUIDE.md` — detailed architecture, API, protocol, transport, testing, and release guide.
- `docs/IMPLEMENTATION-STATUS.md` — current completed, incomplete, and unverified areas.
- `docs/GRIZZLY-EXAMPLE.md` — standalone Grizzly example and HTTP requests.
- `docs/MCP-PORTING-PLAN.md` — scope, exclusions, and acceptance criteria.
- `docs/MCP-COMPATIBILITY-2026.md` — MCP baseline and compatibility status.
- `docs/audits/2026-09-01-full-source-audit.md` — full source, security, license, and compatibility audit.

The implementation is a tested MCP subset for baseline `2025-11-25`, not full MCP parity.
