# Full Source Audit — 2026-09-01

## Scope

This audit covers the Java source, tests, examples, Gradle configuration, and documentation in the standalone MCP Java
SDK. The implementation baseline is MCP `2025-11-25`; MCP `2026-07-28` is comparison reference only and is not claimed
as supported.

## Current package scope

The SDK contains MCP core and a Grizzly Streamable HTTP transport. WebSocket is not included. A robot or application may
use an external WebSocket bridge or adapter, but that integration is outside this package.

## Findings

- Portable Java 8 core contains no Android, AndroidX, Android/ROSA robot application, or private mapper imports.
- Runtime dependencies are Gson `2.11.0` and Grizzly HTTP server `4.0.2`.
- The example demonstrates a tool, exact resource, resource template, prompt, and Grizzly startup.
- Origin allowlisting is configurable through `GrizzlyStreamableServerTransportProvider.allowedOrigins(...)`; the
  default remains local-only.
- Grizzly is JVM/server-oriented. Android runtime compatibility, including Android API 21, has not been verified with a
  device or emulator.
- The live test is an in-process Grizzly HTTP smoke test and does not establish external MCP client interoperability.

## Protocol and transport limitations

The implementation does not claim full MCP parity. Known incomplete or unverified areas include pagination, list-changed
notifications, resumable SSE, binary resource contents, completion, logging, progress/cancellation, sampling,
elicitation, tasks, asynchronous APIs, richer schemas, STDIO, and full external session interoperability.

## License and release status

The repository is distributed under the Apache License 2.0; see the root `LICENSE` file. Dependency license metadata has
not been independently generated in this audit. CI, package, and release workflows are configured, but no tagged release
or package publication has been executed.

## Recommended follow-up

1. Add focused transport rejection and body-limit tests.
2. Review dependency notices and generate an SBOM if required for release.
3. Exercise at least one external MCP client or record the limitation explicitly.
4. Test Grizzly on every target Android runtime before claiming Android support.
