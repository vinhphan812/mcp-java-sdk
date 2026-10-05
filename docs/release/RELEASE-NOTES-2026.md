# MCP Java SDK — Release Notes: v1.0.1 to v1.1.0-pre

> These notes cover the behavioural delta between the released **v1.0.1** tag (2026-09-29, sessioned MCP `2025-11-25`)
> and the current **HEAD** on `origin/master` (2026-10-05, MCP `2026-07-28`).
>
> "2025 sessioned" refers to the state shipped in v1.0.1 (commit 897735b / `v1.0.1` tag).
> "2026 final" refers to all commits from v1.0.1 through HEAD (`ea5a9f7`).

---

## Section 1 — Changes Inherited from 2025 Sessioned Work (v1.0.0 → v1.0.1)

The following changes were shipped in [v1.0.1](https://github.com/vinhphan812/mcp-java-sdk/releases/tag/v1.0.1).
They are included here because they set the baseline that 2026 final behaviour builds on.

### 1.1 Transport Layer Renamed (`GrizzlyStreamableServerTransportProvider` → `HttpTransportProvider`)

**Files changed:** `src/main/java/io/github/vinhphan812/mcp/transport/GrizzlyStreamableServerTransportProvider.java` (deleted),
`src/main/java/io/github/vinhphan812/mcp/transport/HttpTransportProvider.java` (added),
`src/main/java/io/github/vinhphan812/mcp/core/McpServer.java`

The Grizzly transport provider class was renamed. `McpServer.getTransport()` now returns `HttpTransportProvider`
(added `HttpTransportProvider.java:1`, deleted `GrizzlyStreamableServerTransportProvider.java`).

**Migration:** Applications that cast or name the transport provider type must update imports.
The transport behaviour is unchanged; this is a naming and packaging change.

Reference: `McpServer.java:64` — `getTransport()` Javadoc updated to remove "Grizzly" in v1.0.1 diff.

### 1.2 `McpServer.stop()` Now Closes All Sessions and Stops the Cleanup Thread

**File changed:** `src/main/java/io/github/vinhphan812/mcp/core/McpServer.java`

Before v1.0.1, `stop()` only called `transport.stop()`. Now it also calls:

```java
// McpServer.java — added in v1.0.1
protocolHandler.shutdown();
protocolHandler.closeAllSessions();
transport.stop();
```

Any test or deployment that relied on `stop()` leaving sessions dangling will now see them cleaned up.
Sessions that had in-flight SSE event queues will drain those queues before the handler thread exits.

### 1.3 `McpServerConfig` — New Security, Rate-Limit, and Queue-Overflow Controls

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java` (+312 lines)

v1.0.1 introduced these new public API surface areas on `McpServerConfig`:

| Added type | Purpose |
|---|---|
| `McpSecurityDefaults` | Canonical source of rate-limit and security defaults |
| `RateLimits` | Configurable per-IP and per-session rate-limit thresholds |
| `CategoryRateLimitController` | Per-session category admission, quotas, destructive-tool caps, abuse scoring |
| `McpServerConfig.maxQueuedEvents` (default: 1000) | Cap on SSE events queued per session before `QueueOverflowException` |
| `McpServerConfig.QueueOverflowListener` SPI | Callback invoked when a session's event queue overflows |
| `McpServerConfig.QueueOverflowException` | Thrown exception type (extends `RuntimeException`) |
| `DefaultApiKeyStore` + `ApiKeyStore` SPI | Pluggable API key storage for request authentication |

These changes ship in the v1.0.1 JAR and were documented in `RELEASE-NOTES.md` at that time.

### 1.4 `TlsConfig` Deprecated

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java`

`TlsConfig`, its constructor, and `TlsConfig.defaults()` are deprecated. TLS termination is the responsibility
of a reverse proxy; this class does not configure in-process TLS. No replacement SDK TLS API is planned.
Removal is reserved for the next explicit major release. See ADR-0014.

### 1.5 Java Runtime Floor: Java 8 → Java 11

**File changed:** `build.gradle`, CI matrix in `.github/workflows/`

The bundled Grizzly 4.0.2 HTTP/SSE transport requires Java 11 (bytecode class version 55).
CI no longer tests Java 8. Applications previously running on Java 8 must upgrade the runtime to Java 11+.

Reference: ADR-0019 (`docs/adr/ADR-0019-java-runtime-floor.md`) documents the decision.

---

## Section 2 — New 2026 Final Behaviour (v1.0.1 → HEAD, MCP `2026-07-28`)

All commits below ship in the next release (target: v1.1.0-pre / MCP 2026 post-v1.0.1 gate).

### 2.1 Dual Protocol Modes: Sessioned `2025-11-25` (default) and Stateless `2026-07-28`

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/config/McpServerConfig.java`

The SDK now operates in one of two mutually exclusive protocol modes set at build time:

| Mode | Protocol version | Session required | Initialize |
|---|---|---|---|
| `SESSIONED` (default) | `2025-11-25` | Yes (UUID per client) | Creates a session |
| `STATELESS` | `2026-07-28` | No | Rejected with `-32601` |

```java
// Sessioned (default — unchanged from v1.0.1)
McpServerConfig config = McpServerConfig.builder().build(); // advertises "1.0.0"

// Stateless 2026-07-28 — protocol version is automatically set
McpServerConfig config = McpServerConfig.builder()
    .protocolMode(ProtocolMode.STATELESS)
    .build(); // advertises "2026-07-28"
```

**Behavioural difference #1:** Calling `initialize` in `STATELESS` mode now returns
`{-32601, "Method not found"}` instead of establishing a session. In `SESSIONED` mode
the behaviour is unchanged from v1.0.1.

Reference: `McpProtocolHandler.java` — `isStatelessProtocol()` gate at line adding
"2026-07-28 wire contract enforcement" comment; wire contract test in
`Mcp2026WireContractTest.java:1` (822-line test class).

### 2.2 New Method: `server/discover` — Sessionless Capability Discovery

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/utils/McpMethodNames.java` (+1 constant)

`server/discover` is a new MCP method defined in the `2026-07-28` specification.
It returns server capability metadata **without** creating an MCP session.

```json
// Request (no Mcp-Session-Id header required in STATELESS mode)
{"jsonrpc": "2.0", "id": 1, "method": "server/discover"}

// Response
{"jsonrpc": "2.0", "id": 1,
 "result": {
   "capabilities": { ... },
   "serverInfo": { "name": "...", "version": "..." },
   "cacheScope": "public"   // 2026-07-28 cache hint
 }}
```

**Behavioural difference #2:** In `SESSIONED` mode, `server/discover` returns `{-32601}`.
In `STATELESS` mode, it returns the above response and **never** creates or echoes a session ID
(`responseSessionId = null` in `McpProtocolHandler.java`).

Reference: `McpProtocolHandler.java` — `handleServerDiscover()` adds `cacheScope: "public"` to result.

### 2.3 New MCP Tasks Extension SPI (`McpTaskExtension`)

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/spi/McpTaskExtension.java` (new, 260 lines)

A new pluggable SPI that allows applications to replace the SDK's built-in task lifecycle:

```
Namespace: io.modelcontextprotocol/tasks
Methods handled: tasks/create, tasks/get, tasks/cancel, tasks/list, tasks/progress, tasks/complete
```

Key design:

```java
public interface McpTaskExtension extends McpExtension {
    boolean supports(String protocolVersion);       // version-gating gate
    Map<String, Object> advertiseCapabilities(String protocolVersion);
    void onRegister(ExtensionRegistry registry);   // lifecycle hook
    void registerTask(String taskId, Object state);
    Object onRequest(String method, Map<String, Object> params, Mcp2026RequestContext ctx);
    void onError(String taskId, Throwable error);
}
```

The extension is registered via `McpRegistry`:

```java
registry.registerExtension(new MyTaskStore()); // auto-advertised in initialize response
```

**Behavioural difference #3:** Without a registered `McpTaskExtension`, the SDK uses its built-in
bounded task store. With one registered and `supports("2026-07-28")` returning `true`, all task
requests are dispatched to the extension instead (short-circuit dispatch when `onRequest()` returns non-null).
Returning `false` from `supports()` causes all task requests to return `{-32601}`.

Reference: `McpProtocolHandler.java` — `dispatchToExtension()` short-circuit;
`DefaultExtensionRegistry.java` — extension registry implementation.

### 2.4 New Protocol Methods: `listens/subscribe` and `listens/unsubscribe`

**File changed:** `src/main/java/io/github/vinhphan812/mcp/transport/McpHttpHandler.java`, `McpProtocolHandler.java`

Two new methods for server-initiated push subscriptions over the SSE stream:

- `listens/subscribe` — registers interest in a named event stream
- `listens/unsubscribe` — deregisters a previously registered subscription

These methods support the MRTR (Machine-to-Machine Request-Response) / elicitation foundation
(ADR-0022). They are gated behind the `2026-07-28` wire contract and return `{-32601}` in `SESSIONED` mode.

### 2.5 Typed Elicitation Models (`Mcp2026RequestContext`)

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/dto/Mcp2026RequestContext.java` (new, 146 lines)

New typed request context for `2026-07-28` protocol requests. Captures the `_meta` object fields
the SDK actually uses at runtime:

```java
public final class Mcp2026RequestContext {
    public final Object progressToken;           // for notifications/progress flow
    public final Map<String, Object> extras;     // any additional _meta keys, silently ignored
}
```

Null values are normalised to absent. This class replaces opaque `Map` access to `_meta` throughout
`McpProtocolHandler` and is immutable and thread-safe.

### 2.6 Extensible Extension Registry (`ExtensionRegistry` SPI)

**File changed:** `src/main/java/io/github/vinhphan812/mcp/api/spi/ExtensionRegistry.java` (new, 59 lines),
`src/main/java/io/github/vinhphan812/mcp/core/DefaultExtensionRegistry.java` (new, 87 lines)

A registry for multiple `McpExtension` implementations, enabling:
- `McpTaskExtension` (Tasks SEP-2663)
- Future extension points (e.g. sampling, prompts)

```java
public interface ExtensionRegistry {
    void register(McpExtension extension);
    <T extends McpExtension> T getExtension(Class<T> type);
    List<McpExtension> getExtensions();
}
```

### 2.7 Strict Wire Contract Enforcement (PR #20, `4d9fb83`)

**File changed:** `McpProtocolHandler.java`, `McpHttpHandler.java`, `McpJsonRpc.java`

PR #20 introduced strict enforcement of the `2026-07-28` wire contract:

| Rule | Behaviour in 2026-07-28 STATELESS mode |
|---|---|
| `initialize` call | Returns `-32601` (method not found) |
| `server/discover` call | Returns capability result with `cacheScope: "public"` |
| Session echoed in response | Always `null` |
| List method pagination | Attaches version-gated `cacheMetadata` |
| Task method routing | Dispatched to `McpTaskExtension` when registered, else `-32601` |

Regression tests: `Mcp2026WireContractTest.java` (822 lines), `Mcp2026WireContractDiagTest.java` (109 lines).

---

## Summary Table

| # | Change | Since | File(s) | Breaking? |
|---|---|---|---|---|
| 1 | Transport class renamed (`GrizzlyStreamableServerTransportProvider` → `HttpTransportProvider`) | v1.0.1 | `McpServer.java`, transport package | Yes — type-level |
| 2 | `McpServer.stop()` closes sessions and cleanup thread | v1.0.1 | `McpServer.java` | Minor — side-effect change |
| 3 | `McpServerConfig` gained security/rate-limit/queue-overflow controls | v1.0.1 | `McpServerConfig.java` | No |
| 4 | `TlsConfig` deprecated | v1.0.1 | `TlsConfig.java` | No — deprecation only |
| 5 | Java runtime floor: 8 → 11 | v1.0.1 | `build.gradle`, CI | Yes — environment |
| 6 | Dual protocol modes: `SESSIONED` (2025-11-25) and `STATELESS` (2026-07-28) | HEAD | `McpServerConfig.java` | No |
| 7 | New method: `server/discover` (sessionless capability discovery) | HEAD | `McpMethodNames.java`, `McpProtocolHandler.java` | No |
| 8 | New SPI: `McpTaskExtension` for pluggable task lifecycle | HEAD | `McpTaskExtension.java`, `DefaultExtensionRegistry.java` | No |
| 9 | New methods: `listens/subscribe` and `listens/unsubscribe` | HEAD | `McpHttpHandler.java`, `McpProtocolHandler.java` | No |
| 10 | New: `Mcp2026RequestContext` typed `_meta` context | HEAD | `Mcp2026RequestContext.java` | No |
| 11 | New: `ExtensionRegistry` SPI for multiple extensions | HEAD | `ExtensionRegistry.java`, `DefaultExtensionRegistry.java` | No |
| 12 | Strict `2026-07-28` wire contract enforcement (PR #20) | HEAD | `McpProtocolHandler.java` | No — additive behaviour |

---

## Upgrade Notes

- **Runtime:** Ensure Java 11+ is available. Java 8 is no longer supported.
- **Transport type:** If your code casts `McpServer.getTransport()` to `GrizzlyStreamableServerTransportProvider`,
  update to `HttpTransportProvider`.
- **TLS:** `TlsConfig` is deprecated. TLS termination must be handled by a reverse proxy.
- **2026 support:** Set `McpServerConfig.builder().protocolMode(ProtocolMode.STATELESS)` to enable
  `2026-07-28` stateless behaviour. The default remains `SESSIONED` for backward compatibility.
- **Tasks:** Applications needing custom task stores can register a `McpTaskExtension` via
  `McpRegistry.registerExtension()`. The built-in task store is used when no extension is registered.
