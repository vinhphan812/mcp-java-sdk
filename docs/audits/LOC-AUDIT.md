# LOC Audit — MCP Java SDK v1.1.0-pre

**Date:** 2026-09-14
**Scope:** Production source, test source, example, documentation, build
**Baseline:** v1.0.0 release tag

---

## Executive Summary

- **35** production source files (5136 LOC, 208,458 bytes)
- **18** test source files (2345 LOC, 104,204 bytes)
- **1** example file (307 LOC)
- **25** Markdown documentation files (5881 lines, 245,718 bytes)
- **4** Gradle build files (118 lines)
- **23** public top-level types (classes/interfaces/enums) in production

**Grand total LOC:** 13787 across **83** files.

**Production comment density:** 1418/5136 = 27.6% comment.

---

## Production Source Detail

| File | Total | Code | Blank | Comment | Bytes | Types |
|------|------:|------:|------:|--------:|------:|------:|
| `src/main/java/io/github/vinhphan812/mcp/annotations/McpParam.java` | 22 | 11 | 6 | 5 | 578 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/McpParams.java` | 15 | 10 | 3 | 2 | 436 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/McpPrompt.java` | 18 | 11 | 4 | 3 | 571 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/McpResource.java` | 24 | 13 | 6 | 5 | 729 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/McpResourceTemplate.java` | 24 | 13 | 6 | 5 | 778 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/McpTool.java` | 39 | 14 | 7 | 18 | 1,373 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/Prompts.java` | 13 | 9 | 3 | 1 | 370 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/Resources.java` | 13 | 9 | 3 | 1 | 374 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/annotations/Tools.java` | 13 | 9 | 3 | 1 | 366 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java` | 336 | 285 | 30 | 21 | 16,189 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/config/McpClientCapabilities.java` | 152 | 80 | 19 | 53 | 4,907 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` | 271 | 134 | 27 | 110 | 9,835 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/config/package-info.java` | 14 | 1 | 1 | 12 | 484 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/api/dto/McpBlobContent.java` | 218 | 149 | 34 | 35 | 7,177 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/dto/McpTask.java` | 225 | 140 | 17 | 68 | 9,029 | 2 |
| `src/main/java/io/github/vinhphan812/mcp/api/dto/package-info.java` | 12 | 1 | 1 | 10 | 380 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/McpBlobResourceHandler.java` | 30 | 5 | 4 | 21 | 1,142 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/McpCompletionProvider.java` | 15 | 6 | 3 | 6 | 575 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/McpPromptHandler.java` | 25 | 8 | 4 | 13 | 769 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/McpResourceHandler.java` | 14 | 4 | 2 | 8 | 395 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/McpToolHandler.java` | 26 | 8 | 4 | 14 | 728 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/handler/package-info.java` | 16 | 1 | 1 | 14 | 786 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/api/logging/JulMcpLogger.java` | 50 | 28 | 9 | 13 | 1,488 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/logging/McpLogger.java` | 74 | 16 | 8 | 50 | 1,576 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/logging/package-info.java` | 12 | 1 | 1 | 10 | 384 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/api/spi/McpAuthorization.java` | 73 | 9 | 8 | 56 | 2,734 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/spi/McpRegistrar.java` | 151 | 34 | 15 | 102 | 5,998 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/spi/McpRegistryChangeListener.java` | 13 | 4 | 2 | 7 | 480 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/spi/McpResourceUpdateListener.java` | 10 | 4 | 2 | 4 | 312 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/api/spi/package-info.java` | 14 | 1 | 1 | 12 | 683 | 0 |
| `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 1864 | 1288 | 197 | 379 | 82,482 | 2 |
| `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java` | 561 | 334 | 53 | 174 | 24,741 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/core/McpServer.java` | 173 | 101 | 25 | 47 | 5,799 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/transport/GrizzlyStreamableServerTransportProvider.java` | 231 | 101 | 21 | 109 | 7,753 | 1 |
| `src/main/java/io/github/vinhphan812/mcp/transport/McpGrizzlyHandler.java` | 375 | 320 | 26 | 29 | 16,057 | 1 |

---

## Test Source Detail

| File | Total | Code | Blank | Comment | Bytes |
|------|------:|------:|------:|--------:|------:|
| `src/test/java/io/github/vinhphan812/mcp/McpAuthorizationTest.java` | 139 | 112 | 23 | 4 | 5,692 |
| `src/test/java/io/github/vinhphan812/mcp/McpClientCapabilitiesTest.java` | 107 | 91 | 16 | 0 | 4,502 |
| `src/test/java/io/github/vinhphan812/mcp/McpExampleRegistrationTest.java` | 63 | 52 | 10 | 1 | 2,099 |
| `src/test/java/io/github/vinhphan812/mcp/McpGrizzlyLiveTest.java` | 77 | 67 | 9 | 1 | 3,569 |
| `src/test/java/io/github/vinhphan812/mcp/McpGrizzlySecurityMatrixTest.java` | 187 | 161 | 25 | 1 | 9,247 |
| `src/test/java/io/github/vinhphan812/mcp/McpListChangedNotificationTest.java` | 51 | 43 | 8 | 0 | 2,616 |
| `src/test/java/io/github/vinhphan812/mcp/McpOwnerSessionTest.java` | 214 | 138 | 43 | 33 | 8,783 |
| `src/test/java/io/github/vinhphan812/mcp/McpPaginationTest.java` | 58 | 49 | 9 | 0 | 2,687 |
| `src/test/java/io/github/vinhphan812/mcp/McpProgressAndCancellationTest.java` | 117 | 95 | 19 | 3 | 5,659 |
| `src/test/java/io/github/vinhphan812/mcp/McpProtocolHandlerTest.java` | 137 | 119 | 18 | 0 | 7,826 |
| `src/test/java/io/github/vinhphan812/mcp/McpQueueOverflowTest.java` | 114 | 86 | 20 | 8 | 4,755 |
| `src/test/java/io/github/vinhphan812/mcp/McpRateLimitTest.java` | 580 | 431 | 89 | 60 | 25,838 |
| `src/test/java/io/github/vinhphan812/mcp/McpReflectionRegistrarDirectBindingTest.java` | 162 | 136 | 25 | 1 | 6,713 |
| `src/test/java/io/github/vinhphan812/mcp/McpSecurityConfigTest.java` | 46 | 35 | 8 | 3 | 1,441 |
| `src/test/java/io/github/vinhphan812/mcp/McpServerConfigTest.java` | 34 | 28 | 6 | 0 | 1,019 |
| `src/test/java/io/github/vinhphan812/mcp/McpSessionTimeoutTest.java` | 76 | 53 | 18 | 5 | 2,867 |
| `src/test/java/io/github/vinhphan812/mcp/McpTasksTest.java` | 153 | 133 | 20 | 0 | 7,848 |
| `src/test/java/io/github/vinhphan812/mcp/transport/McpGrizzlyResumabilityTest.java` | 30 | 24 | 6 | 0 | 1,043 |

---

## Example Detail

| File | Total | Code | Bytes |
|------|------:|------:|------:|
| `examples/src/main/java/io/github/vinhphan812/mcp/examples/GrizzlyExample.java` | 307 | 269 | 15,073 |

---

## Per-Package Summary (production source)

| Package | Files | LOC | Code | Avg LOC | Comment |
|---------|------:|----:|-----:|--------:|--------:|
| `io.github.vinhphan812.io.github.vinhphan812.mcp.annotations` | 9 | 181 | 99 | 20.1 | 41 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.api` | 1 | 336 | 285 | 336.0 | 21 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.api.config` | 3 | 437 | 215 | 145.7 | 175 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.api.dto` | 3 | 455 | 290 | 151.7 | 113 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.api.handler` | 6 | 126 | 32 | 21.0 | 76 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.api.logging` | 3 | 136 | 45 | 45.3 | 73 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.api.spi` | 5 | 261 | 52 | 52.2 | 181 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.core` | 3 | 2598 | 1723 | 866.0 | 600 |
| `io.github.vinhphan812.io.github.vinhphan812.mcp.transport` | 2 | 606 | 421 | 303.0 | 138 |

---

## Top Lists

### Top 5 production files by LOC

| Rank | File | LOC |
|------|------|---:|
| 1 | `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 1864 |
| 2 | `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java` | 561 |
| 3 | `src/main/java/io/github/vinhphan812/mcp/transport/McpGrizzlyHandler.java` | 375 |
| 4 | `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java` | 336 |
| 5 | `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` | 271 |

### Top 5 production files by Bytes

| Rank | File | Bytes |
|------|------|------:|
| 1 | `src/main/java/io/github/vinhphan812/mcp/core/McpProtocolHandler.java` | 82,482 |
| 2 | `src/main/java/io/github/vinhphan812/mcp/core/McpRegistry.java` | 24,741 |
| 3 | `src/main/java/io/github/vinhphan812/mcp/api/McpReflectionRegistrar.java` | 16,189 |
| 4 | `src/main/java/io/github/vinhphan812/mcp/transport/McpGrizzlyHandler.java` | 16,057 |
| 5 | `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` | 9,835 |

### Top 5 test files by LOC

| Rank | File | LOC |
|------|------|---:|
| 1 | `src/test/java/io/github/vinhphan812/mcp/McpRateLimitTest.java` | 580 |
| 2 | `src/test/java/io/github/vinhphan812/mcp/McpOwnerSessionTest.java` | 214 |
| 3 | `src/test/java/io/github/vinhphan812/mcp/McpGrizzlySecurityMatrixTest.java` | 187 |
| 4 | `src/test/java/io/github/vinhphan812/mcp/McpReflectionRegistrarDirectBindingTest.java` | 162 |
| 5 | `src/test/java/io/github/vinhphan812/mcp/McpTasksTest.java` | 153 |

---

## Documentation Detail

| File | Lines | Bytes |
|------|------:|------:|
| `CONTRIBUTING.md` | 55 | 1,775 |
| `README.md` | 492 | 16,136 |
| `docs/API-REFERENCE.md` | 1219 | 51,237 |
| `docs/GRIZZLY-EXAMPLE.md` | 356 | 14,196 |
| `docs/IMPLEMENTATION-STATUS.md` | 281 | 14,425 |
| `docs/MCP-COMPATIBILITY-2026.md` | 83 | 4,512 |
| `docs/MCP-PORTING-PLAN.md` | 96 | 3,585 |
| `docs/PROJECT-GUIDE.md` | 419 | 18,330 |
| `docs/adr/ADR-0001-portable-java8-core.md` | 60 | 2,562 |
| `docs/adr/ADR-0002-grizzly-transport-isolation.md` | 129 | 5,046 |
| `docs/adr/ADR-0003-json-rpc-envelope-protocol-versioning.md` | 75 | 3,026 |
| `docs/adr/ADR-0004-session-management.md` | 76 | 2,998 |
| `docs/adr/ADR-0005-sse-notifications-event-queue.md` | 68 | 2,505 |
| `docs/adr/ADR-0006-security-model.md` | 95 | 3,363 |
| `docs/adr/ADR-0007-annotation-registration.md` | 89 | 3,166 |
| `docs/adr/ADR-0008-protocol-baseline-compatibility.md` | 72 | 3,807 |
| `docs/adr/ADR-0009-code-audit-2026-09-11.md` | 100 | 4,148 |
| `docs/adr/ADR-0010-api-package-restructure.md` | 89 | 4,440 |
| `docs/adr/ADR-0011-implementation-plan.md` | 1020 | 36,864 |
| `docs/adr/ADR-0011-security-rate-limiting.md` | 214 | 7,859 |
| `docs/adr/README.md` | 79 | 4,486 |
| `docs/audits/2026-09-01-full-source-audit.md` | 42 | 2,298 |
| `docs/audits/2026-09-12-audit-supplement.md` | 255 | 14,409 |
| `docs/audits/2026-09-12-full-source-audit.md` | 311 | 15,530 |
| `docs/inspect/FINDINGS-CLASSIFICATION.md` | 106 | 5,015 |

---

## Build Configuration

- `build.gradle` — 82 lines
- `examples/build.gradle` — 27 lines
- `examples/settings.gradle` — 7 lines
- `settings.gradle` — 2 lines

---

## Findings

### Largest files

`McpProtocolHandler.java` at 1864 LOC accounts for **36%** of production source. ADR-0011 added ~600 LOC of rate limiting, abuse scoring, queue overflow, ownership, and authorization logic.

### Newly added since v1.0.0

- `McpAuthorization.java` — new SPI
- 6 test files for ADR-0011 features (3 currently enabled, 3 `@Disabled`)
- ADR-0011 implementation plan (1020 lines markdown)

### Comment density

Production source has 1418 comment lines (27.6%). Docstrings on public methods are present.

### Risk markers

- `McpProtocolHandler.handleToolsCall` now exceeds 120 lines and embeds schema validation, rate-limit, authorization, concurrent-counter logic; candidates for extraction in future refactor.
- 3 test files (`McpRateLimitTest`, `McpOwnerSessionTest`, `McpSessionTimeoutTest`) are `@Disabled` from subagent creation; should be rewritten against `McpProtocolHandler.getSessionIdForOwner`, `getOwnerIdForSession`, `isSessionBlocked` helpers.

---

*Generated from local analysis. Sync data is at `build/loc-audit-data.json` (not committed).*