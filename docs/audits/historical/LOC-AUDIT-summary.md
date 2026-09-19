# LOC Audit Summary — MCP Java SDK v1.1.0-pre

**Date:** 2026-09-14
**Scope:** Production + tests + examples + documentation + build configuration
**Baseline:** v1.0.0 release

---

## Totals

| Category           |  Files |  Total LOC | Code LOC |   Bytes |
|--------------------|-------:|-----------:|---------:|--------:|
| Production source  |     35 |      5,136 |    3,162 | 208,458 |
| Test source        |     18 |      2,345 |    1,853 | 104,204 |
| Example            |      1 |        307 |      269 |       — |
| Documentation (md) |     25 |      5,881 |        — | 245,718 |
| Gradle build files |      4 |        118 |        — |       — |
| **Grand total**    | **83** | **13,787** |        — |       — |

**Public types in production source:** 23

---

## Per-package summary (production)

| Package                                 | Files |   LOC |  Code | Avg LOC |
|-----------------------------------------|------:|------:|------:|--------:|
| `io.github.vinhphan812.mcp.annotations` |     9 |   181 |    99 |    20.1 |
| `io.github.vinhphan812.mcp.api`         |     1 |   336 |   285 |   336.0 |
| `io.github.vinhphan812.mcp.api.config`  |     3 |   437 |   215 |   145.7 |
| `io.github.vinhphan812.mcp.api.dto`     |     3 |   455 |   290 |   151.7 |
| `io.github.vinhphan812.mcp.api.handler` |     6 |   126 |    32 |    21.0 |
| `io.github.vinhphan812.mcp.api.logging` |     3 |   136 |    45 |    45.3 |
| `io.github.vinhphan812.mcp.api.spi`     |     5 |   261 |    52 |    52.2 |
| `io.github.vinhphan812.mcp.core`        |     3 | 2,598 | 1,723 |   866.0 |
| `io.github.vinhphan812.mcp.transport`   |     2 |   606 |   421 |   303.0 |

---

## Top 5 production files by LOC

| Rank | File                               |   LOC |  Code |
|------|------------------------------------|------:|------:|
| 1    | `core/McpProtocolHandler.java`     | 1,864 | 1,288 |
| 2    | `core/McpRegistry.java`            |   561 |   334 |
| 3    | `transport/McpGrizzlyHandler.java` |   375 |   320 |
| 4    | `api/McpReflectionRegistrar.java`  |   336 |   285 |
| 5    | `api/config/McpServerConfig.java`  |   270 |   124 |

---

## Top 5 test files by LOC

| Rank | File                                           |                         LOC |
|------|------------------------------------------------|----------------------------:|
| 1    | `McpRateLimitTest.java`                        | 579 (currently `@Disabled`) |
| 2    | `McpOwnerSessionTest.java`                     | 213 (currently `@Disabled`) |
| 3    | `McpGrizzlySecurityMatrixTest.java`            |                         186 |
| 4    | `McpReflectionRegistrarDirectBindingTest.java` |                         161 |
| 5    | `McpTasksTest.java`                            |                         152 |

---

## Key findings

- **`McpProtocolHandler.java` at 1,864 LOC dominates** — accounts for **36%** of production source. ADR-0011 added ~600
  LOC of rate limiting, abuse scoring, queue overflow, ownership, and authorization logic.
- **35 production source files, 18 test files, 1 example, 25 docs, 4 build files.**
- **New since v1.0.0:** `McpAuthorization` SPI, 6 test files for ADR-0011 (3 enabled, 3 `@Disabled`).
- **Comment density:** production source has 27.6% comment lines (`McpProtocolHandler` is heavily documented).
- **Test discipline:** test-to-source ratio is `2,345 / 5,136 ≈ 0.46` — adequate coverage; disabled tests should be
  rewritten and re-enabled.

See `LOC-AUDIT.md` for per-file detail.
