# ADR-0014 — TLS Transport Contract

**Status:** Accepted
**Date:** 2026-09-20
**Authors:** MCP Java SDK team
**Parent:** ADR-0013 (TLS Strategy Analysis)

## Title

TLS Transport Contract: Reverse-Proxy-Only Termination

## Context

The MCP Java SDK includes a `TlsConfig` class and `scheme()` method that suggest TLS support, but these are
non-functional:

- `TlsConfig` (keystorePath, keystorePassword, protocol) has zero code references
- `StreamableServerTransportProvider` creates plain HTTP listeners only
- `scheme("https")` only modifies the URL string, not the actual transport

This ADR establishes the official position on TLS transport and defines the contract for `scheme()` semantics.

## Decision

**The MCP Java SDK will use reverse-proxy-only TLS termination.** The `scheme()` method remains as a documentation
mechanism for externally-terminated TLS.

`TlsConfig` follows a two-phase removal lifecycle:

### Phase 1 — Deprecation (current)

`TlsConfig` is marked `@Deprecated` (class, constructor, and `defaults()` factory). The Javadoc states:

- The class does not configure TLS.
- TLS termination is external (reverse proxy/load balancer/ingress).
- No replacement SDK TLS API is planned.
- Removal is reserved for the next explicitly planned major release.

The class and its current behavior are preserved unchanged during this phase. No `tls(TlsConfig)` builder method
or in-process TLS implementation may be added.

### Phase 2 — Removal (major release only)

The class is deleted only when a major release is explicitly approved and scheduled. Before deletion:

1. A repository-wide reference audit confirms no production, test, example, documentation, or generated API reference
   still requires the class.
2. The `Unreleased` section of `CHANGELOG.md` is updated to record the removal as a breaking change for that release.
3. Migration guidance is published alongside the major release.

`scheme("https")` behavior and documentation remain unchanged throughout both phases.

### Threat / Deployment Assumptions

1. **Reverse proxy handles TLS** — nginx, Apache, cloud load balancer, or Kubernetes ingress terminates TLS
2. **Internal network is trusted** — traffic between proxy and application travels over localhost or VPC (not exposed)
3. **Certificate management is external** — users manage certificates at the proxy level, not in the SDK
4. **No in-process key material** — the application never handles private keys or keystores

This model aligns with ADR-0006 (Security Model), which already documents production deployment requires TLS termination
at a reverse proxy.

## Consequences

### API Changes

| Component               | Change                                                           | Breaking?            |
|-------------------------|------------------------------------------------------------------|----------------------|
| `TlsConfig` class       | Deprecated now; removed in next named major release               | Yes (but unused)     |
| `scheme(String)` method | Retained, documents external TLS | No                   |
| `getUrl()`              | Returns scheme from `scheme()` call | No               |

### Migration Path

**Phase 1 (current — deprecation):** No migration required. `TlsConfig` and `scheme("https")` work as before.
`TlsConfig` now produces a compile-time deprecation warning. Consumers may remove unused `TlsConfig` objects
or ignore the warning until the major release.

**Phase 2 (major release):** Remove `TlsConfig` objects and any associated configuration. TLS continues to be
handled by the reverse proxy. `scheme("https")` remains available.

### https-advertising Semantics

The `scheme()` method defines the URL scheme that clients should use to connect:

- `scheme("http")` (default) — `getUrl()` returns `http://host:port/endpoint`
- `scheme("https")` — `getUrl()` returns `https://host:port/endpoint`

**This does NOT enable TLS on the server.** The returned URL reflects the external endpoint scheme, which may be
terminated by a reverse proxy.

Rationale: Clients need to know the correct URL scheme to connect, regardless of how TLS is terminated internally.

### Cleanup Tasks (deferred to the next named major release)

> CLOSED FOR THE CURRENT RELEASE: these are future-work items, not outstanding implementation defects.
> They apply only when a major release is explicitly approved and scheduled. They must not be
> started before a named major version is selected and a migration window is planned.

1. Delete `src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java`
2. Update any Javadoc references to TLS configuration
3. Update `CHANGELOG.md`: move the removal notice from `Unreleased` into the release's breaking-changes notes
4. Publish migration guidance alongside the major release

### Implementation Tasks (if choosing in-process TLS) — NOT CHOSEN

> These tasks are documented for reference only, as the reverse-proxy option was selected.

1. Wire `TlsConfig` into `StreamableServerTransportProvider` builder
2. Implement `SSLContextConfigurator` creation from TlsConfig fields
3. Configure `NetworkListener` with `SSLEngineConfigurator`
4. Add unit tests for SSLContext creation
5. Add integration test for TLS handshake
6. Document keystore generation for local development

### Validation Approach

For the chosen path (reverse-proxy-only):

- No TLS handshake testing required — transport is plain HTTP
- Verify `scheme()` correctly reflects the URL scheme in `getUrl()`
- Documentation review confirms TLS is documented as external

For in-process TLS (not chosen):

- TLS handshake test: start server with test keystore, connect via HTTPS, verify handshake succeeds
- Unit test `SSLContextConfigurator` creation with various TlsConfig values

## Consequences Summary

### Positive

- Zero maintenance burden for TLS handshake code
- Aligns with security best practice (reverse-proxy termination)
- No test infrastructure needed for TLS
- Certificate management centralized at deployment level
- No breaking changes for existing users (TlsConfig has no references)

### Negative

- Users cannot run the SDK with built-in TLS (must use reverse proxy)
- Single-container deployments require sidecar or reverse proxy
- Documentation must clearly explain the external TLS pattern

### Trade-offs

| Scenario               | Reverse-Proxy-Only | In-Process TLS |
|------------------------|--------------------|----------------|
| Implementation effort  | 1 day              | 3-5 days       |
| Maintenance burden     | Low                | Medium         |
| Deployment flexibility | Requires proxy     | Standalone     |
| Private key security   | Proxy-managed      | App-managed    |

## References

- ADR-0006 Security Model
- ADR-0013 TLS Strategy Analysis
- TLSCONFIG-LIFECYCLE-DECISION.md — Detailed two-phase deprecation/removal lifecycle specification
- TlsConfig source: `src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java`
- Transport provider: `src/main/java/io/github/vinhphan812/mcp/transport/StreamableServerTransportProvider.java`
