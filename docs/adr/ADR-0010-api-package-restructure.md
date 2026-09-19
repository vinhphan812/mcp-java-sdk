# ADR-0010 — `api/` Package Restructure by Category

**Status:** Accepted
**Date:** 2026-09-11
**Authors:** MCP Java SDK team

## Context

After the P0/P1 implementation was completed, `io.github.vinhphan812.mcp.api` contained
16 unrelated types in a single flat package:

- Registration interfaces (`McpRegistrar`, listeners)
- Handler interfaces (`McpToolHandler`, `McpResourceHandler`, `McpPromptHandler`, `McpCompletionProvider`,
  `McpBlobResourceHandler`)
- Immutable value objects (`McpTask`, `McpBlobContent`)
- Configuration types (`McpServerConfig`, `McpClientCapabilities`)
- Logging interfaces and adapters (`McpLogger`, `JulMcpLogger`)
- Utility classes (`McpReflectionRegistrar`)

Discoverability was poor: a developer looking for the tool handler interface
had to scan 16 files in one directory. Package-private and intra-package
imports were also harder to audit.

## Decision

Reorganize `api/` into five subpackages grouped by category:

| Subpackage     | Contents                                                                                                      | Purpose                                                                                |
|----------------|---------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|
| `api/spi/`     | `McpRegistrar`, `McpResourceUpdateListener`, `McpRegistryChangeListener`                                      | Service-provider interfaces and lifecycle listeners                                    |
| `api/handler/` | `McpToolHandler`, `McpResourceHandler`, `McpBlobResourceHandler`, `McpPromptHandler`, `McpCompletionProvider` | Behaviour contracts applications implement                                             |
| `api/dto/`     | `McpTask`, `McpBlobContent`                                                                                   | Immutable value objects passed through the protocol                                    |
| `api/config/`  | `McpServerConfig`, `McpClientCapabilities`                                                                    | Configuration and capability metadata                                                  |
| `api/logging/` | `McpLogger`, `JulMcpLogger`                                                                                   | Portable logger interface and adapters                                                 |
| `api/` (root)  | `McpReflectionRegistrar`                                                                                      | Standalone utility — uses types from every subpackage, so it stays at the package root |

Each subpackage receives a `package-info.java` describing its contents.

## Migration steps

1. Create subdirectories under `src/main/java/io/github/vinhphan812/mcp/api/`.
2. Move files into their categories.
3. Update each file's `package` declaration to match its new directory.
4. Update all import statements in `core/` and the rest of the project.
5. Run `./gradlew.bat --no-daemon clean test --console=plain`.

## Backward compatibility

This is a breaking change for any consumer that imports from `io.github.vinhphan812.mcp.api.*`
directly. Application consumers must update imports from, for example:

```java
import io.github.vinhphan812.mcp.api.McpToolHandler;
```

to:

```java
import io.github.vinhphan812.mcp.api.handler.McpToolHandler;
```

No public type was renamed, removed, or had its signature changed.

## Alternatives considered

**Status quo (flat package):** Rejected — discoverability was poor; common patterns
like "find every handler interface" required a directory listing rather than a
package scan.

**Per-domain packages (tools/, resources/, prompts/):** Rejected — over-fragmentation
for a portable SDK. The current `handler/` subpackage keeps related interfaces together
without forcing a hierarchy that mirrors the protocol.

**Single deep package `io.github.vinhphan812.mcp.public/`:** Rejected — breaks the
existing API surface area and requires a major version bump.

## Verification

```bash
./gradlew.bat --no-daemon clean test build javadoc --console=plain
BUILD SUCCESSFUL
```

```bash
cd examples && ../gradlew.bat --no-daemon compileJava "-PsdkJar=../build/libs/mcp-java-sdk-1.0-SNAPSHOT.jar"
BUILD SUCCESSFUL
```

46 tests pass, no compiler warnings, javadoc complete.
