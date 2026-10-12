# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

