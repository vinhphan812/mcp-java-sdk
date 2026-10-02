# ADR-0019 — Java Runtime Floor: Java 11 Required for Grizzly Transport

**Status:** Accepted
**Date:** 2026-09-29
**Author:** dev-architect
**Implementation Status:** Completed — decision recorded; no source changes required

## Context

The MCP Java SDK publishes one Gradle Java component whose current source and target compatibility are Java 11:

| Layer       | Packages                                      | `sourceCompatibility` | Bytecode version | Intended runtime |
|-------------|-----------------------------------------------|-----------------------|------------------|------------------|
| SDK classes | `annotations/`, `api/`, `core/`, `transport/` | Java 11               | **v55**          | JVM 11+          |
| Examples    | `examples/`                                   | Java 11               | **v55**          | JVM 11+          |

`build.gradle` declares `sourceCompatibility = JavaVersion.VERSION_11` and
`targetCompatibility = JavaVersion.VERSION_11`. Grizzly 4.0.2 dependencies are also compiled for v55 (Java 11).

The Java 11 floor therefore applies to the published SDK and its Grizzly HTTP/SSE transport. This ADR establishes the
documented bytecode and runtime contract and the rationale for the choice.

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
SDK own classes:  79 classes  bytecode v55 (Java 11)  ✓
Guava 33.3.0-jre: 2017 classes bytecode v52 (Java 8)  ✓
```

The SDK's own code is Java 11 bytecode. Guava remains Java 8 bytecode but does not lower the SDK's runtime floor.
Grizzly is also v55.

### No Java-8-compatible Grizzly exists

Grizzly 2.4.x was the last series targeting Java 8 bytecode. It is unmaintained (last release 2021) and has known CVEs
including [CVE-2024-45687](https://nvd.nist.gov/vuln/detail/CVE-2024-45687). No maintained fork has emerged.

## Options

### Option A — Java 11+ runtime floor (selected)

Adopt Java 11 as the published SDK's source, target, bytecode, and runtime floor. The root `build.gradle` and examples
build both target Java 11, matching the Grizzly v55 dependency graph.

**Pros:**

- No dual-artifact or split-transport maintenance burden.
- Grizzly 4.0.x is actively maintained (Eclipse EE4J).
- All modern Java runtimes (11–23 LTS) are supported.

**Cons:**

- Pure-Java-8 JVM environments (some embedded/IoT targets) cannot run the transport layer.
- The portable package design does not provide a separately published Java 8 artifact and is only tested on JVM 11+.

### Option B — Find or build a Java-8-compatible transport

Investigate alternatives such as embedded Jetty, Undertow, or a custom NIO server.

**Rejected:** No actively maintained Java 8 HTTP server has feature parity with Grizzly (HTTP/SSE, session management,
CORS). Building a custom transport is a significant project outside this SDK's scope.

### Option C — Split transport artifacts

Publish separate artifacts only if a future Java 8-compatible transport is intentionally supported.

**Rejected for now:** The current published component is consistently Java 11; introducing a Java 8 core artifact would
require an explicit publication design and compatibility tests.

## Decision

**Option A — Java 11+ source, bytecode, and runtime floor.**

The Gradle Java component and examples target Java 11, and the Grizzly 4.0.x transport is compiled for Java 11 bytecode.
The published SDK therefore requires JVM 11+. A separately published Java 8 core is not part of the current artifact
contract.

## Runtime Contract

| Configuration                                                 | Minimum JVM                |
|---------------------------------------------------------------|----------------------------|
| Published SDK (`annotations/`, `api/`, `core/`, `transport/`) | **Java 11 (bytecode v55)** |
| Examples                                                      | **Java 11 (bytecode v55)** |
| CI test matrix                                                | Java 11, Java 17           |
| Release build                                                 | Java 17                    |

The root and examples Gradle builds both declare Java 11 source and target compatibility. A Java 8-compatible core is
not currently published or supported by this artifact.

## Affected Documentation

The following files previously claimed "Java 8" in their compatibility statements. They have been updated to reflect the
Java 11 source/target and runtime contract:

- `CONTRIBUTING.md`, `RELEASE-NOTES.md`, and user/project/transport guides — current Java compatibility statements
- `docs/architecture/guava-dependency-policy.md` — published artifact and validation contract
- `examples/build.gradle` — example source/target compatibility

## References

- [Eclipse Grizzly GitHub](https://github.com/eclipse-ee4j/grizzly)
- Grizzly 4.0.2 Maven: `org.glassfish.grizzly:grizzly-http-server:4.0.2`
- [CVE-2024-45687 (Grizzly 2.4.x)](https://nvd.nist.gov/vuln/detail/CVE-2024-45687)
- ADR-0001: Portable Java 8 core without Android SDK
- ADR-0002: Grizzly transport isolation
