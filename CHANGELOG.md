# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

### Changed

### Fixed

---

Release-note PRs: #96, #95

## [v1.1.3] — 2026-10-14

> Tag: `13ac3d6` (full SHA: `13ac3d6f7ff047b149a9d9fe5b2d4ed0e054aa6b`)

### Added

- **Cursor-based pagination** — `paginate(req, cursor)` helper walks `nextCursor` tokens
  transparently. `DirectoryItem` now exposes `pageSize` and `nextCursor`. Streaming
  directory listings respect the `pageSize` hint from the MCP client.
- **Companion file serving** — `McpDirectoryResource` registers a
  `classpath:/META-INF/mcp/companion/` resource tree alongside real files. Both
  paths are browsable and serve `Content-Type` based on file extension (falls back
  to `application/octet-stream`).
- **Capability negotiation** — `supports("2026-07-28")` is now wired into every
  protocol handler so 2026-era clients receive SEP-2575 subscriptions and
  server-initiated requests even without an explicit `capabilities` block.

### Changed

- **MIME type handling** — Raw request/response bodies are now correctly
  serialized with `application/json`; streamed SSE events use `text/event-stream`.
  Binary bodies fall back to `application/octet-stream`.
- **`directory/list` returns `DirectoryItem` instead of raw `Resource`** —
  Breaking: `McpDirectoryResource.listItems()` now returns `List<DirectoryItem>`
  so cursor and page-size fields are available at the protocol layer.

### Fixed

- **Null-safety in `InputSchemaBuilder`** — `nullable` flag and `default` value
  are now correctly propagated through the builder chain.
- **SSE `Last-Event-ID` replay isolation** — Each session tracks its own replay
  cursor; concurrent sessions no longer share replay state.

---

Release-note PRs: none

## [v1.1.2] — 2026-10-13

### Fixed

- **SEP-2322 MRTR same-request retry** — `inputResponses` now forwarded to tool/prompt/resource handlers. `Mcp2026RequestContext` exposes `inputResponses` and `requestState` fields. Token validation and round counting integrated.
- **JSON Schema 2020-12 root-type bug** — `type` keyword no longer required at top level. Combinator-only, conditional, and `$defs` schemas validate correctly.
- **SchemaValidator dialect policy** — Explicitly documented: supported keywords, excluded keywords (format, prefixItems, dependentRequired, unevaluatedProperties, remote $ref), and pass-through semantics for unsupported vocabulary.
- **Skills capability advertisement** — `McpSkillsExtension.supports(2026-07-28)` returns `true`, enabling `skills/list` and `skills/get` protocol dispatch.

### Changed

- **README updated** — Version examples updated to 1.1.1, test count corrected to 546, stale elicitation claim removed.
- **ELICITATION.md** — Updated to show SEP-2322 same-request retry as primary protocol path.
- **ADR-0022** — Updated MRTR architecture to reflect same-request retry semantics.

---

## [v1.1.0] — 2026-10-12

### Added

- **MCP 2026-07-28 full conformance** — Official `@modelcontextprotocol/conformance@alpha` CI gate
  with 41/41 scenarios passing. All required core scenarios verified: tools, resources,
  prompts, completions, SSE, DNS rebinding protection.
  See [docs/guides/IMPLEMENTATION-STATUS.md](docs/guides/IMPLEMENTATION-STATUS.md).

- **Subscriptions streaming (SEP-2575)** — `subscriptions/listen` as a long-lived POST
  streaming response. First frame delivers `notifications/subscriptions/acknowledged`
  with `subscriptionId`. Notifications carry `_meta["io.modelcontextprotocol/subscriptionId"]`
  for correlation. HTTP cancellation closes the stream; no `unsubscribe` RPC needed.
  Shutdown sends `resultType: "complete"`.

- **Skills extension (SEP-2640)** — `io.modelcontextprotocol/skills` via
  ExtensionRegistry SPI. Skills discovery (`skills/list`), skills retrieval
  (`skills/get`), manifest serving via `resources/read` with `skill://` URIs,
  and `directoryRead` capability gating.

- **RFC 9728 OAuth 2.0 Protected Resource** — Self-hosted bearer-token
  protection. RFC 9728 metadata at `/.well-known/oauth-protected-resource/mcp`,
  `WWW-Authenticate: Bearer` challenges on 401, provider-neutral pluggable
  `TokenVerifier` SPI, and immutable `McpSecurityContext`.

- **MRTR / Elicitation foundation** — Server-to-client request infrastructure
  (ADR-0022). `ElicitConfirmation` and `ElicitInput` handlers, SSE event
  correlation, HTTP transport wiring for server-initiated requests, and 2026
  capability gating with era boundary.

- **ExtensionRegistry SPI** — `io.github.vinhphan812.mcp.api.spi.ExtensionRegistry`
  and `McpExtension` base interface for extensible MCP protocol support.

- **JSON Schema 2020-12 enforcement** — Tool input schemas validated against
  draft-07+ vocabulary; malformed schemas fail fast at registration.

- **`server/discover`** — MCP 2026 service discovery endpoint returning
  supported protocol versions and capability manifest.

- **Routing headers** — `mcp-protocol-version`, `mcp-originating-router`
  forwarded on stateless requests.

- **CORS loopback origin policy** — Configurable allowlist for browser-based
  clients (ADR-0021).

### Fixed

- **Conformance wire contract** — `resultType: "complete"` on all list,
  read, and tool-response envelopes. SSE progress notifications now carry
  `jsonrpc` field and flush correctly in stateless mode.

- **Resource template resolution** — URI path segment extraction corrected
  for templated resources.

- **Completion envelope** — `completion/complete` wrapped in the correct
  MCP result envelope with `resultType`.

- **CI artifact isolation** — Build matrix jobs upload to unique artifact
  names to prevent collision between Java 11 and Java 17.

- **Legacy `resources/subscribe`** — Isolated behind an era boundary; 2025
  clients receive synchronous JSON-RPC responses, 2026 clients use SEP-2575.

- **Grype SARIF reporting** — CI SARIF upload corrected to use
  `github/codeql-action/upload-sarif@v3`.

### Deprecated

- `TlsConfig` class, constructor, and `defaults()` factory are deprecated.
  TLS termination is handled by a reverse proxy. No replacement SDK TLS API is
  planned. Removal is reserved for the next explicitly planned major release.
  See [ADR-0014](docs/adr/ADR-0014-tls-transport-contract.md).

- Sampling, Roots, and Logging are deprecated in MCP 2026-07-28.
  See [ADR-0023](docs/adr/ADR-0023-deprecation-sampling-roots-logging.md).

### Documentation

- `scheme("https")` is documented as an external TLS indicator (reverse-proxy
  termination), not in-process TLS.
  See [TRANSPORT-STREAMABLE-HTTP.md](docs/transport/TRANSPORT-STREAMABLE-HTTP.md).
- `docs/guides/SKILL.md` — Skills extension usage guide.
- `docs/guides/LISTEN-SUBSCRIPTIONS.md` — SEP-2575 streaming guide.
- `docs/guides/self-hosted-oauth.md` — OAuth Protected Resource setup.
- `docs/guides/ELICITATION.md` — MRTR / elicitation usage guide.

---

## [v1.0.1] — 2026-09-29

See [RELEASE-NOTES.md](RELEASE-NOTES.md).

