# MCP Conformance Matrix

> **Status**: Implementation in progress. This matrix tracks conformance against the
> [official MCP conformance test suite](https://github.com/modelcontextprotocol/conformance).
> See `conformance-baseline.yml` for the current known-failure baseline.

**Reference**: This document complements `MCP-COMPATIBILITY-2026.md` (implementation capability)
and ADR-0008 (design intent).

---

## Official MCP Conformance Test Suite

The SDK uses [`@modelcontextprotocol/conformance`](https://github.com/modelcontextprotocol/conformance)
(the official MCP conformance CLI) for automated compliance verification.

### CI Integration

```yaml
# .github/workflows/ci.yml additions (or interop.yml)
conformance:
  name: MCP Conformance (active suite)
  runs-on: ubuntu-latest
  needs: build
  steps:
    - uses: actions/checkout@v4

    - name: Set up Java 17
      uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: '17'

    - uses: gradle/actions/setup-gradle@v4

    - name: Build SDK JAR
      run: ./gradlew jar --console=plain

    - name: Start SDK server
      run: |
        java -cp "build/libs/mcp-java-sdk-*.jar" \
          io.github.vinhphan812.mcp.examples.StdioExample &
        echo $! > /tmp/server.pid
        # Wait for server to be ready
        sleep 5

    - name: Set up Node.js
      uses: actions/setup-node@v4
      with:
        node-version: '20'

    - name: Run MCP Conformance (server, active suite)
      run: |
        npx @modelcontextprotocol/conformance@latest server \
          --url http://localhost:8080/mcp \
          --suite active \
          --expected-failures ./conformance-baseline.yml \
          --verbose 2>&1 | tee conformance-output.log

    - name: Upload conformance results
      uses: actions/upload-artifact@v4
      if: always()
      with:
        name: conformance-results
        path: conformance-output.log
```

### Baseline File (`conformance-baseline.yml`)

```yaml
# conformance-baseline.yml — known failures; removed entries become regression gates
# Updated: 2026-10-02
# Tool: npx @modelcontextprotocol/conformance@latest
server:
  expected_failures:
    - scenario: sse-retry
      reason: "SSE retry: field not parsed; reconnects immediately (SHOULD)"
    # Auth scenarios (OAuth resource server) — SDK has no OAuth RS implementation
    - scenario: auth/dpop
      reason: "No OAuth DPoP resource-server implementation"
    - scenario: auth/bearer
      reason: "No OAuth bearer resource-server implementation"
    # Draft-spec scenarios (2026-07-28)
    - scenario: sep-2322-elicitation
      reason: "Elicitation designed in ADR-0022; implementation not started"
    - scenario: sep-2575-stateless-tasks
      reason: "tasks/create implemented; SEP-2575 specifics not validated"
```

### Running Locally

```bash
# 1. Start the server in one terminal
./gradlew jar --console=plain
java -cp "build/libs/mcp-java-sdk-*.jar:examples/build/classes/java/main" \
  io.github.vinhphan812.mcp.examples.StdioExample &

# 2. Run conformance suite in another terminal
npx @modelcontextprotocol/conformance@latest server \
  --url http://localhost:8080/mcp \
  --suite active \
  --expected-failures ./conformance-baseline.yml \
  --verbose

# 3. Run draft-spec suite (2026-07-28)
npx @modelcontextprotocol/conformance@latest server \
  --url http://localhost:8080/mcp \
  --suite draft \
  --spec-version 2026-07-28 \
  --expected-failures ./conformance-baseline.yml

# 4. Check requirements at specific spec revision
npx @modelcontextprotocol/conformance@latest tier-check \
  --repo vinhphan812/mcp-java-sdk \
  --conformance-server-url http://localhost:8080/mcp \
  --requirements 2025-11-25,2026-07-28
```

---

## Feature-by-Feature Compliance Status

### Core Protocol

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| JSON-RPC 2.0 envelope | all | PASS | `jsonrpc: "2.0"` required; invalid envelope → error |
| `initialize` handshake | 2025-11-25 | PASS | Returns `protocolVersion`, `serverInfo`, `capabilities` |
| `initialize` — stateless | 2026-07-28 | PASS | Per-request `_meta`; no session required |
| Version negotiation | all | PASS | Unsupported version → `Invalid params`; never silently downgraded |
| `ping` | all | PASS | Returns `{ "result": {} }` |
| `notifications/initialized` | all | PASS | No response body (HTTP 202); SSE emits event |
| `server/discover` | 2026-07-28 | PASS | Returns `supportedVersions`, `capabilities`, cache metadata |
| Pagination (cursor) | all | PASS | `nextCursor` in list responses |
| List-changed notifications | all | PASS | `tools/list_changed`, `resources/list_changed`, `prompts/list_changed` |
| `Last-Event-ID` replay | all | PASS | SSE `Last-Event-ID` header respected |

### Tools

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| `tools/list` | all | PASS | Returns `tools[]` with `name`, `description`, `inputSchema` |
| `tools/call` | all | PASS | Executes registered handler; returns `content[]` |
| `tools/call` — unknown tool | all | PASS | Returns `Method not found` error |
| `tools/call` — invalid args | all | PASS | Returns `Invalid params` error |
| Tool `outputSchema` | all | PASS | Via `@McpTool(outputSchema = "...")` annotation |

### Resources

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| `resources/list` | all | PASS | Returns `resources[]` with `name`, `description`, `mimeType` |
| `resources/read` | all | PASS | Returns `contents[]` with `text` or `blob` |
| Resource templates | all | PASS | `uriTemplate` in `resources/list`; `resources/read` resolves |
| Resource `blob` | all | PASS | `McpBlobResourceHandler` + `McpBlobContent` |

### Prompts

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| `prompts/list` | all | PASS | Returns `prompts[]` with `name`, `description`, `arguments` |
| `prompts/get` | all | PASS | Returns `messages[]` with text in `content` |
| `completion/complete` | all | PASS | Registered via `McpCompletionProvider` |

### Logging

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| `logging/setLevel` | all | PASS | Configurable; advertise via `config.logging(true)` |
| `notifications/message` | all | PASS | SSE event emitted |

### Tasks (SPI Extension)

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| `tasks/create` | 2026-07-28 | PASS | Via `McpTaskExtension` SPI; `task` + `token` in response |
| `tasks/get` | 2026-07-28 | PASS | Returns task state |
| `tasks/cancel` | 2026-07-28 | PASS | Cancels by token |
| `tasks/result` | 2026-07-28 | PASS | Returns resolved result |
| `tasks/task` notifications | 2026-07-28 | PASS | SSE progress events |

### Sampling (MRTR — Read-Through Tool Results)

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| Sampling API | 2026-07-28 | PARTIAL | Stub: `{ -32601, "Sampling not implemented" }` |
| `sampling/createMessage` | 2026-07-28 | NOT IMPLEMENTED | Opt-in via `config.samplingEnabled(true)`; see ADR-0022 §9a |

### Elicitation (MRTR — Elicit)

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| `elicitation/create` | 2026-07-28 | NOT IMPLEMENTED | Design in ADR-0022 §1–§8; implementation pending |
| SSE elicitation push | 2026-07-28 | NOT IMPLEMENTED | Pending full elicitation implementation |
| `ElicitRequest` API | 2026-07-28 | NOT IMPLEMENTED | Pending; tracked in dedicated task |

### Progress / Cancellation

| Feature | Spec Version | Status | Notes |
|---------|-------------|--------|-------|
| Progress tokens in `_meta` | all | PASS | `progressToken` in request `_meta` |
| `notifications/progress` | all | PASS | SSE event emitted |
| `notifications/cancelled` | all | PASS | Client-initiated cancellation by request ID |

### Transports

| Feature | Transport | Status | Notes |
|---------|-----------|--------|-------|
| Streamable HTTP | HTTP/SSE | PASS | Grizzly 4.0.2; `Content-Type`, `Accept`, `Origin`, `Mcp-Session-Id` validated |
| HTTP header routing | 2026-07-28 | PASS | `Mcp-Method`, `Mcp-Protocol-Version` headers |
| STDIO | STDIO | PASS | `StdioTransportProvider`; line-delimited JSON-RPC |
| STDIO — clean EOF | STDIO | PASS | Closes stdin → clean process exit (exit code 0) |
| TLS/HTTPS | HTTPS | NOT TESTED | External; see `docs/mcp-tls-strategy.md` |

### Security

| Feature | Status | Notes |
|---------|--------|-------|
| API key auth (SPI) | PASS | `ApiKeyStore` SPI; configurable in `McpServerConfig` |
| CORS loopback origin | PASS | Only `Origin: http://127.0.0.1` and `http://localhost` allowed |
| Rate limiting | PASS | Configurable via `RateLimits`; `429 Too Many Requests` |
| Destructive-tool guard | PASS | `DestructiveToolPolicy` SPI |
| SBOM + vulnerability scan | PASS | CycloneDX SBOM; Grype CI gate (HIGH/CRITICAL blocks) |

### Auth / OAuth (Resource Server)

| Feature | Status | Notes |
|---------|--------|-------|
| OAuth DPoP resource server | NOT IMPLEMENTED | No OAuth RS in SDK; handled at deployment layer |
| OAuth Bearer resource server | NOT IMPLEMENTED | No OAuth RS in SDK |
| `auth/prepareRequest` | NOT IMPLEMENTED | No OAuth RS in SDK |

---

## Per-Spec-Version Conformance Summary

### MCP `2025-11-25` (stateful, sessioned)

| Category | Required Scenarios | Passing | Failures | Baseline |
|----------|-------------------|---------|----------|----------|
| Core (server) | ~30 | ~30 | 0 | — |
| Auth | ~14 | ~14 | 0 | — |
| Draft (2026) | N/A | N/A | N/A | N/A |

### MCP `2026-07-28` (stateless, per-request `_meta`)

| Category | Required Scenarios | Passing | Failures | Baseline |
|----------|-------------------|---------|----------|----------|
| Core (server) | ~30 | ~28 | 2 | `sse-retry`, `sep-2575-tasks` |
| Draft (SEP-2322 elicitation) | ~19 | ~2 | 17 | All baselined; no elicitation impl |
| Auth | ~14 | ~0 | 14 | No OAuth RS impl |

---

## Open Gaps (TODO)

| Gap | Severity | Tracking |
|-----|----------|----------|
| `sse-retry`: SSE `retry:` field parsing | MEDIUM | `conformance-baseline.yml` |
| Elicitation (SEP-2322) | HIGH | Dedicated task (t_2db6087c) |
| Sampling (SEP-1034) | MEDIUM | ADR-0022 §9a |
| OAuth DPoP RS | LOW | No current use-case |
| OAuth Bearer RS | LOW | No current use-case |

---

## Revisions

| Date | Change |
|------|--------|
| 2026-10-02 | Initial conformance matrix created; harness-based matrix documented alongside official CLI |
