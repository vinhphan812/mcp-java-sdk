# TlsConfig Lifecycle Decision

Status: Proposed amendment to ADR-0014
Date: 2026-09-28
Parent ADR: ADR-0014 — TLS Transport Contract

## Decision

Retain `TlsConfig` as a deprecated, non-functional compatibility surface until the
first explicitly planned major release. Remove it in that major release only after
the release owner has identified the release version and migration notice is part
of the release checklist.

No major-release timing is assumed by this decision. The repository currently has
no committed major-release plan, so deletion is not authorized merely because the
class is unused internally.

The transport contract remains reverse-proxy-only. `TlsConfig` must not be wired
into the transport, advertised as supported in-process TLS, or extended with new
behavior. `scheme("https")` continues to describe the externally visible URL
scheme; it does not enable TLS in the SDK.

## Why this lifecycle

- `TlsConfig` is public API. Its absence from production call sites proves only
  that the repository does not use it; it does not prove that downstream Java
  consumers do not compile against it.
- Deleting it immediately creates a source and binary compatibility break without
  a named major release or a migration window.
- Keeping it indefinitely would preserve an API whose fields imply a capability
  the SDK deliberately does not implement.
- Deprecation makes the incompatibility visible to consumers while preserving the
  current artifact contract until a release with an explicit breaking-change gate.
- ADR-0014's reverse-proxy-only decision remains correct; this amendment changes
  the removal procedure, not the TLS architecture.

## Compatibility and migration policy

### Current and deprecation phase

1. Keep the public class and its current constructor/default factory behavior.
2. Mark the class, constructor, and `defaults()` factory deprecated in a future
   implementation task, with Javadoc stating:
    - the class does not configure TLS;
    - TLS termination is external (reverse proxy/load balancer/ingress);
    - no replacement SDK TLS API is planned;
    - removal is reserved for the next explicitly planned major release.
3. Do not add a `tls(TlsConfig)` builder method or otherwise increase the public
   surface.
4. Add a migration note directing users to configure TLS at the deployment edge
   and to use the transport's externally visible `scheme("https")` setting where
   appropriate.

### Removal phase

At the first named major release approved by the release owner:

1. Verify no repository production, test, example, documentation, or generated API
   reference still requires the class.
2. Delete `src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java`.
3. Remove stale imports, Javadoc, API listings, and examples.
4. Move the removal notice from `Unreleased` into the release's breaking-changes
   notes, correcting the current premature claim in `CHANGELOG.md` if it is still
   present before the release is cut.
5. Publish the reverse-proxy TLS migration guidance alongside the major release.

Downstream consumers using `TlsConfig` must migrate before upgrading: remove the
unused configuration object and configure TLS in their reverse proxy or ingress.
Consumers using only `scheme("https")` are not affected by this lifecycle.

## ADR treatment

A new ADR is not required for a new TLS architecture: ADR-0014 already decides
reverse-proxy-only termination. An amendment is required because ADR-0014 currently
states unconditional removal in the next major release but does not define a
pre-major deprecation phase or require a named release plan.

The amendment should update ADR-0014's Decision and Migration Path sections to
state the two-phase policy above, and should mark its cleanup task as deferred
until a major release is explicitly scheduled. This decision document is the
implementation-ready specification for that amendment; it does not itself alter
source or release metadata.

## Future implementation changes

The future implementation task should be limited to these paths and concerns:

- `src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java`: add the
  deprecation annotation and migration Javadoc; preserve behavior during the
  compatibility phase.
- `docs/adr/ADR-0014-tls-transport-contract.md`: apply the ADR amendment and
  record the no-major-release-plan constraint.
- `docs/transport/TRANSPORT-STREAMABLE-HTTP.md` and the canonical transport/user
  guide: explain reverse-proxy TLS and the meaning of `scheme("https")`.
- `CHANGELOG.md`: keep removal out of a generic `Unreleased` section until the
  major release is actually being prepared; then record the breaking change.
- API/reference inventories and examples: remove only at the major-release
  removal step, after a repository-wide reference audit.

Do not modify Gradle version configuration as part of deprecation. The existing
version is build/release controlled and no major-release version has been
selected.

## Rollback and consumer impact

Deprecation rollback is low risk: remove the deprecation annotation/Javadoc if
compatibility policy changes, without changing runtime behavior. No consumer
migration is forced by the deprecation-only phase.

Removal rollback requires restoring the class in a patch before the major release
is published, or shipping a compatibility artifact if publication has already
occurred. After a major release has shipped, restoring the class would reduce
compatibility harm but would not undo the documented breaking release; therefore
release approval must include an API-diff check before publication.

The principal impact is static tooling noise for consumers that instantiate
`TlsConfig`; the warning is intentional. There is no runtime behavior change,
including no newly enabled TLS.

## Acceptance criteria for the future implementation task

- [ ] ADR-0014 explicitly records deprecate-now/retain-until-named-major/remove-in-that-major policy.
- [ ] `TlsConfig` remains source- and binary-present during the compatibility phase.
- [ ] Deprecation Javadoc explicitly says the class is non-functional and names
  reverse-proxy termination as the supported model.
- [ ] No `tls(TlsConfig)` method or in-process TLS implementation is introduced.
- [ ] `scheme("https")` behavior and documentation remain unchanged.
- [ ] The changelog does not claim removal before the major release is prepared.
- [ ] Before removal, repository references and published API surface are audited;
  the major-release notes contain migration guidance.
- [ ] The removal release passes compilation, tests, Javadoc, and API-diff checks.

## Validation commands

During the deprecation phase:

    ./gradlew clean test
    ./gradlew javadoc
    git grep -n "TlsConfig\|scheme(\"https\")" -- src docs CHANGELOG.md

Before removal:

    ./gradlew clean test javadoc build
    git grep -n "TlsConfig" -- . ':!docs/audits/historical/*'
    ./gradlew publishToMavenLocal -PsdkVersion=<named-major-version>

The final command is a local artifact smoke check only; publication to a remote
repository requires the release workflow and its credentials. The API-diff check
must compare the last supported pre-removal artifact with the major-release
artifact and confirm that the deletion is intentional and documented.
