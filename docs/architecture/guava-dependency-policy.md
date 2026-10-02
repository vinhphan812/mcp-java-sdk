# Guava Dependency and Public-Boundary Policy

**Status:** Accepted
**Date:** 2026-09-29
**Scope:** `com.google.guava:guava:33.3.0-jre`
**Related decisions:
** [ADR-0001](../adr/ADR-0001-portable-java8-core.md), [ADR-0002](../adr/ADR-0002-grizzly-transport-isolation.md)

## Context

The SDK build declares Guava 33.3.0-jre while the production source currently has no
Guava imports. The SDK is compiled for Java 11 and describes its published `annotations/`, `api/`, `core/`, and
`transport/` component as a Java 11 artifact. It also has JVM/Grizzly transport code and Android/Cruzr-adjacent
consumers.

Guava is an implementation dependency, not an SDK contract. The JRE artifact is
appropriate for the supported JVM distribution, but it is not evidence that the
complete distribution is an Android library. Android consumers must use a separately
validated Android-compatible dependency graph or an Android-specific adapter/variant;
this policy does not expand the Android support claim in ADR-0001.

## Decision

1. Guava is permitted only as an internal implementation dependency in `core/`,
   `transport/`, and private implementation code under `api/`. It may be used for
   local algorithms, internal immutable snapshots, and internal helpers where it
   does not change protocol or API semantics.
2. Guava types must not cross a public or protected SDK boundary. Public and
   protected signatures, fields, return values, thrown types, callback contracts,
   SPI interfaces, and serialized values continue to use JDK types and SDK types.
   In particular, use `Map`, `List`, and `Set` (and JDK/SDK interfaces) at boundaries.
3. The published JVM artifact targets Java 11 bytecode and requires a Java 11+ runtime. It uses
   `com.google.guava:guava:33.3.0-jre`. Do not substitute `-android` in the JVM
   publication. An Android variant, if needed, requires a separate dependency
   graph and device/API-level validation; it is not implied by this decision.
4. Guava must remain an implementation dependency (`implementation`), never `api`,
   unless a new ADR explicitly changes the public-boundary policy and compatibility
   claim.

## Approved and prohibited APIs

| API/pattern                                                                             | Policy                                  | Rationale                                                                                                                                    |
|-----------------------------------------------------------------------------------------|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `ImmutableSet`, `ImmutableList`, `ImmutableMap`                                         | Approved internally only                | Useful for defensive internal state; convert/copy to JDK collection types at boundaries. Do not return or accept these Guava types publicly. |
| Guava `HttpHeaders`                                                                     | Not approved for migration              | Keep existing SDK/transport header constants and JDK/Grizzly contracts. Do not introduce Guava header constants merely to replace literals.  |
| Guava `MediaType`                                                                       | Not approved for migration              | Keep the existing transport content-type representation and avoid a Guava type in transport/public contracts.                                |
| Guava `Charsets` or deprecated Guava constants                                          | Prohibited when a JDK equivalent exists | Use `StandardCharsets.UTF_8` and current JDK APIs. Do not add APIs deprecated in Guava 33.3.0-jre.                                           |
| Guava collections in public signatures                                                  | Prohibited                              | Prevents dependency leakage, preserves consumer compatibility, and keeps the API portable.                                                   |
| Guava use in annotations, SPI contracts, DTOs, JSON/protocol models, or serialized data | Prohibited                              | These are compatibility boundaries.                                                                                                          |
| Broad replacement of existing JDK collections/constants with Guava                      | Prohibited                              | Dependency adoption must not create behaviour-changing or churn-only migration.                                                              |

Internal Guava collections must not be exposed through mutable aliases. When an
internal value is returned through a JDK boundary, return an appropriate defensive
JDK copy or unmodifiable JDK view and preserve the existing null/order/mutability
semantics.

## Safe scope and prohibited churn

Safe changes are isolated, behaviour-preserving internal improvements with tests,
such as replacing a private defensive collection construction with an internal
immutable value while preserving iteration order and null rejection behaviour.
Do not migrate `McpSecurityDefaults.DESTRUCTIVE_TOOLS`, `StandardCharsets.UTF_8`,
HTTP header literals, or content-type literals solely because Guava has a similar
API. Do not refactor unrelated code, alter wire output, or make Guava a prerequisite
for callers implementing the public SPI.

## Version, provenance, and security review

The pinned coordinate is resolved from Maven Central by Gradle:

```
com.google.guava:guava:33.3.0-jre
```

Its observed runtime graph is Guava plus `failureaccess:1.0.2`, the empty
`listenablefuture` compatibility artifact, `jsr305:3.0.2`,
`checker-qual:3.43.0`, and `error_prone_annotations:2.28.0`. Release review must
re-run the dependency report, inspect Maven Central metadata/licensing, and check
Gradle dependency updates plus the project's vulnerability scanner. Upgrade only
through a reviewed version change, with Java 11 compilation and the supported JVM
runtime test suite as gates. Reassess Android compatibility for every dependency
or variant change; do not infer it from the JVM graph.

## Consequences for implementation tasks

Child migration tasks must:

- keep Guava out of all public/protected signatures and keep the dependency as
  `implementation`;
- use only the approved internal APIs and add focused regression tests for any
  changed collection semantics;
- preserve Java 11 bytecode/source compatibility and existing wire/API behaviour;
- avoid `HttpHeaders`, `MediaType`, `Charsets`, deprecated Guava constants, and
  churn-only replacements;
- report the changed package, boundary review, dependency graph, and Java 11
  compile/test results;
- stop and request an ADR if a change requires Guava in a public contract or
  requires changing the Android support claim.

## Validation record

At decision time, `./gradlew dependencies --configuration runtimeClasspath`
completed successfully. It resolved `guava:33.3.0-jre` and the transitive graph
listed above. `build.gradle` confirms `sourceCompatibility` and
`targetCompatibility` are both `JavaVersion.VERSION_11`. A source scan found no
production `com.google.common` imports before migration.
