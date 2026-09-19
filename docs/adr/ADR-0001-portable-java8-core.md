# ADR-0001 — Portable Java 8 Core Without Android SDK

**Status:** Accepted
**Date:** 2026-09-01
**Authors:** MCP Java SDK team

## Context

The MCP implementation originated inside an Android application project targeting Cruzr/ROSA robots. The application
brought Android SDK, AndroidX, robot-specific models, and application services as transitive dependencies. Extracting
the MCP logic into a standalone library required separating the portable protocol and transport code from all Android
and application-specific code.

## Decision

The portable core under `io.github.vinhphan812.mcp` uses only standard Java SE 8 APIs. No production source file imports
`android.*`, `androidx.*`, or Android/ROSA robot application packages.

The `annotations`, `api`, and `core` packages are dependency-neutral:

- `annotations/`: runtime metadata only (`@McpTool`, `@McpParam`, `@McpResource`, `@McpResourceTemplate`, `@McpPrompt`).
- `api/`: public extension contracts, handlers, configuration, logging, and completion providers.
- `core/`: registry, JSON-RPC dispatch, session state, and server facade — transport-neutral.

The `transport/` package contains Grizzly-specific code and is the only package with non-Java-SE dependencies (Grizzly
HTTP server).

## Consequences

**Positive:**

- The library compiles and runs on any JVM 8+ environment.
- Consuming applications on Android can embed the library if Grizzly and all runtime dependencies are compatible with
  the target Android API level.
- A consumer can provide an alternative transport (e.g. STDIO, Netty) by implementing the public `McpRegistrar` SPI
  without depending on Grizzly.
- No Android-specific code leaks into the core protocol.

**Negative:**

- Android-specific features (preferences storage, Android logging, Android service lifecycle) must be re-implemented in
  the consuming application or a separate adapter.
- The library does not claim Android API 21 compatibility without device or emulator evidence.
- Grizzly is a JVM/server-oriented dependency; its behaviour on Android must be verified before production use.

## Verification

Static source scan:

```bash
grep -rIn "import android\.\|import androidx\." src/main/java/
```

No matches in production source. Test source may use Android packages; production source does not.

## Notes

- The `transport/` package is intentionally Grizzly-specific and isolated from `core/`.
- WebSocket, STDIO, Android services, and robot domain models are outside the portable scope and belong in consuming
  applications or separate adapters.
