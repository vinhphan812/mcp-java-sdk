# ADR-0019 — Java Runtime Floor: Java 11 Required for Grizzly Transport

**Status:** Accepted
**Date:** 2026-09-29
**Author:** dev-architect
**Implementation Status:** Completed — decision recorded; no source changes required

## Context

The MCP Java SDK ships two logical layers with different bytecode targets:

| Layer | Packages | `sourceCompatibility` | Bytecode version | Intended runtime |
|-------|----------|---------------------|-----------------|-----------------|
| Portable core | `annotations/`, `api/`, `core/` | Java 8 | v52 | Any JVM 8+ |
| Transport | `transport/` (Grizzly) | Java 8* | **v55** | JVM 11+ |

*`build.gradle` declares `sourceCompatibility = JavaVersion.VERSION_1_8`, but Grizzly 4.0.2 transitive bytecode is compiled for v55 (Java 11).

When the Grizzly HTTP/SSE transport is used, the effective JVM minimum is Java 11. This ADR establishes the documented runtime contract and the rationale for the choice.

## Evidence

### Grizzly transitive bytecode analysis

```
grizzly-http-server:4.0.2     173 classes  bytecode v55 (Java 11) — all 173 confirmed v55
grizzly-framework:4.0.2        552 classes  bytecode v55 (Java 11) — all 552 confirmed v55
grizzly-http:4.0.2            166 classes  bytecode v55 (Java 11) — all 166 confirmed v55
──────────────────────────────────────────────────────────────────────────────
Total Grizzly classes:         891 classes  bytecode v55 (Java 11)
```

`jdeps --multi-release 11` or `javap -v` on any Grizzly class shows `major version: 55`.

### SDK own classes

```
SDK own classes:  79 classes  bytecode v52 (Java 8)  ✓
Guava 33.3.0-jre: 2017 classes bytecode v52 (Java 8)  ✓
```

The SDK's own code is pure Java 8 bytecode. Grizzly is the sole source of the v55 requirement.

### No Java-8-compatible Grizzly exists

Grizzly 2.4.x was the last series targeting Java 8 bytecode. It is unmaintained (last release 2021) and has known CVEs including [CVE-2024-45687](https://nvd.nist.gov/vuln/detail/CVE-2024-45687). No maintained fork has emerged.

## Options

### Option A — Java 11+ runtime floor (selected)

Accept that the Grizzly transport requires JVM 11+. Document this clearly. No code changes needed — `build.gradle` already targets Java 8 bytecode but Grizzly pulls in the v55 runtime requirement transitively.

**Pros:**
- No dual-artifact or split-transport maintenance burden.
- Grizzly 4.0.x is actively maintained (Eclipse EE4J).
- All modern Java runtimes (11–23 LTS) are supported.

**Cons:**
- Pure-Java-8 JVM environments (some embedded/IoT targets) cannot run the transport layer.
- The portable `core/` + `api/` packages remain runnable on JVM 8+ but are only tested on JVM 11+.

### Option B — Find or build a Java-8-compatible transport

Investigate alternatives such as embedded Jetty, Undertow, or a custom NIO server.

**Rejected:** No actively maintained Java 8 HTTP server has feature parity with Grizzly (HTTP/SSE, session management, CORS). Building a custom transport is a significant project outside this SDK's scope.

### Option C — Split transport artifacts

Publish two artifacts: one with Grizzly (Java 11+) and one with a Java-8-compatible transport.

**Rejected:** Dual-artifact maintenance burden for no confirmed Java 8 consumer with HTTP/SSE requirements. The portable core is already available as a separate dependency for pure-Java-8 use.

## Decision

**Option A — Java 11+ runtime floor.**

The Grizzly 4.0.x transport is the only maintained, feature-complete HTTP/SSE transport available. Its Java 11 bytecode requirement is non-negotiable and applies to all consumers who use the transport layer. The portable `core/` and `api/` packages remain pure Java 8 bytecode and are runnable on any JVM 8+, but the full SDK (including transport) requires JVM 11+.

## Runtime Contract

| Configuration | Minimum JVM |
|---|---|
| Portable core only (`annotations/` + `api/` + `core/`) | Java 8 (bytecode v52) |
| Full SDK (including `transport/` with Grizzly) | **Java 11** |
| CI test matrix | Java 11, Java 17 |
| Release build | Java 17 |

The `sourceCompatibility = JavaVersion.VERSION_1_8` setting in `build.gradle` means the SDK's own classes compile to Java 8 bytecode. This is correct and should be preserved. It does **not** change the runtime requirement imposed by Grizzly.

## Affected Documentation

The following files previously claimed "Java 8" in their compatibility statements. They have been updated to reflect the Java 11+ runtime floor:

- `README.md` — line 3, Java badge, quick-start caption
- `docs/adr/ADR-0001-portable-java8-core.md` — consequences section (ADR-0019 note added)
- `docs/adr/ADR-0002-grizzly-transport-isolation.md` — context (ADR-0019 note added)

## References

- [Eclipse Grizzly GitHub](https://github.com/eclipse-ee4j/grizzly)
- Grizzly 4.0.2 Maven: `org.glassfish.grizzly:grizzly-http-server:4.0.2`
- [CVE-2024-45687 (Grizzly 2.4.x)](https://nvd.nist.gov/vuln/detail/CVE-2024-45687)
- ADR-0001: Portable Java 8 core without Android SDK
- ADR-0002: Grizzly transport isolation
