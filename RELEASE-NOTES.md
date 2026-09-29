# MCP Java SDK v1.0.1

Released: 2026-09-29

## Summary

v1.0.1 is a maintenance release that consolidates protocol and transport internals, adds configurable security and queue controls, improves concurrency and SSE coverage, and corrects the documented Java runtime contract.

## Compatibility

- Full SDK runtime requirement: Java 11 or later.
- The project source and target compatibility settings remain Java 8, but the bundled Grizzly 4.0.2 HTTP/SSE transport uses Java 11 bytecode. Applications using the transport therefore require Java 11+.
- CI validates Java 11 and Java 17.
- The legacy HTTP+SSE transport remains supported. Streamable HTTP support remains documented as an in-progress migration path; see ADR-0018.

## Highlights

### Protocol, transport, and configuration

- Added `CategoryRateLimitController` to centralise stateful per-session category admission, quotas, destructive-tool caps, and abuse scoring.
- Made `McpSecurityDefaults` the canonical source of rate-limit and security defaults.
- Added configurable `McpServerConfig.maxQueuedEvents`, with a default of 1000 queued SSE events per session.
- Added `SseEventFormatter` and consolidated HTTP/JSON-RPC utilities in the transport path.
- Added shared protocol utilities for Gson, JSON-RPC envelopes, HTTP headers, method names, error codes, errors, and exceptions.
- Replaced the obsolete `McpGrizzlyHandler` with `McpHttpHandler` as the transport implementation.

### Security and concurrency

- Added configurable `RateLimits` and destructive-tool policy defaults.
- Added authorization, API-key storage, session-owner binding, rate-limit, queue-overflow, and session-timeout coverage.
- Improved `McpRegistry` concurrent-read safety and notification-listener isolation.
- Added regression coverage for category reservation, SSE connection limits, Last-Event-ID replay, and streamable HTTP behaviour.

### Dependency and build delivery

- Added Guava 33.3.0-jre for approved internal immutable-collection usage; Guava types are not exposed through public API signatures.
- Release workflow now builds the versioned SDK JAR before compiling examples, so examples compile against the exact release artifact.
- Published release artifacts include the binary JAR, sources JAR, and Javadoc JAR.

### Documentation

- Added ADR-0019 documenting the Java 11 runtime floor for the Grizzly transport.
- Reconciled ADR status and current-source inspection classifications.
- Updated public Java compatibility claims and removed token-like credential examples from documentation.

## Upgrade notes

- If an application previously ran the full SDK on Java 8, upgrade the runtime to Java 11+ before using v1.0.1.
- No Guava classes are part of the supported public API surface.
- Review `McpServerConfig` and `RateLimits` if custom queue limits or rate limits are required.

## Verification

Release workflow passed on the v1.0.1 tag:

- SDK build and tests
- Example compilation against `mcp-java-sdk-1.0.1.jar`
- GitHub Packages publication step
- GitHub Release artifact upload

## Artifacts

- `mcp-java-sdk-1.0.1.jar`
- `mcp-java-sdk-1.0.1-sources.jar`
- `mcp-java-sdk-1.0.1-javadoc.jar`

GitHub Release: https://github.com/vinhphan812/mcp-java-sdk/releases/tag/v1.0.1
