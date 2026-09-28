# Security Triage: Context Propagation, Key Custody, Reflection, IP Binding

**Task:** t_b7cea50c
**Date:** 2026-09-21
**Author:** dev-architect (Hermes Kanban)
**Scope:** Analysis-only — no source modifications, no commits.

---

## Area 1: AuthenticationContext Propagation in `McpAuthorization.denial()`

### Findings

**1a. Interface signature**

`McpAuthorization.java:70–71` declares:

```java
String denial(String[] requiredScopes, boolean confirmationRequired,
              Map<String, Object> arguments);
```

`AuthenticationContext.java` is a standalone interface (`api/security/AuthenticationContext.java:8`) with methods:

| Method               | Purpose                                         |
|----------------------|-------------------------------------------------|
| `getApiKey()`        | Raw credential used to authenticate the request |
| `getHeaders()`       | HTTP headers (multi-value map)                  |
| `getRemoteAddress()` | Client `host:port` string                       |
| `getRequestPath()`   | URL path (no query/fragment)                    |
| `getMethod()`        | HTTP verb                                       |

**1b. Caller evidence**

`McpProtocolHandler.java:1515–1526` is the sole invocation site:

```java
// Authorization check
if (authorization != null) {
    String[] scopes = requiredScopes == null ? new String[0]
            : requiredScopes.toArray(new String[0]);
    String denial = authorization.denial(
            scopes,
            definition != null && Boolean.TRUE.equals(definition.get("confirmationRequired")),
            arguments);   // ← tool-input arguments, NOT request context
    if (denial != null) {
        throw new McpErrorException(-32029, "Authorization denied: " + denial);
    }
}
```

`arguments` here is the **tool invocation input** (`Map<String, Object>`), not the HTTP request context.
`AuthenticationContext` is never constructed or passed to `denial()`.

**1c. Bearer validation is separate from `McpAuthorization`**

Bearer validation lives in `McpGrizzlyHandler.java:413–421`:

```java
private boolean isUnauthorized(Request request) {
    String configured = apiKeySupplier == null ? null : apiKeySupplier.get();
    if (configured == null || configured.trim().isEmpty()) return false;
    String header = request.getHeader(AUTH_HEADER);  // "Authorization"
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return true;
    byte[] expected = configured.trim().getBytes(StandardCharsets.UTF_8);
    byte[] actual = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
    return !MessageDigest.isEqual(expected, actual);  // constant-time comparison
}
```

`MessageDigest.isEqual` is timing-safe. The bearer token is checked at the transport layer (HTTP 401 on failure) before
`McpProtocolHandler` is reached.

**1d. API compatibility analysis**

Adding `AuthenticationContext` as a new mandatory parameter would break all existing `McpAuthorization`
implementations (every lambda/class implementing `denial(...)`).

Safe API migration path:

```
Current:  String denial(String[], boolean, Map<String,Object>)
Future:   String denial(String[], boolean, Map<String,Object>, AuthenticationContext)
          // Added as 4th parameter with default: pass null for backward compat
```

This preserves source compatibility for implementations that don't need the context.

**1e. What AuthenticationContext would enable**

If propagated, implementors could write authorization logic that:

- Extracts the authenticated API key via `getApiKey()` to correlate with user/tenant identity
- Reads request headers (e.g. `X-User-Id`, `X-Tenant-ID`) forwarded by a gateway
- Uses `getRemoteAddress()` for IP-allowlisting or anomaly detection
- Reads `getRequestPath()` for path-based rules

Currently none of this is accessible to `McpAuthorization` implementors.

### Decision

|                              |                                                                                                                                                                                                                                         |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Recommended action**       | Add `AuthenticationContext` as a 4th parameter to `McpAuthorization.denial()`, defaulting to `null` for backward compatibility. Update `McpProtocolHandler` to construct and pass an `AuthenticationContext` instance at the call site. |
| **Priority**                 | Medium — unblocks meaningful authorization use cases (tenant isolation, IP allowlisting)                                                                                                                                                |
| **Risk of adding parameter** | Low — existing implementations continue to compile and work; null is a safe default                                                                                                                                                     |

### Open Questions

- Does `AuthenticationContext` need to be constructed at the `McpProtocolHandler` level (which has no HTTP-level
  awareness), or should it be constructed at the transport layer and threaded through? Current design has transport know
  about `McpProtocolHandler` but not vice versa.
- Should `AuthenticationContext` be part of a separate `McpSecurityContext` or integrated into an extended
  `McpAuthorization` contract?

---

## Area 2: Secret Custody and Persistence in `DefaultApiKeyStore`

### Findings

**2a. Plaintext file persistence**

`DefaultApiKeyStore.java:65–75`:

```java
private void saveToDisk() {
    if (storagePath != null) {
        Properties props = new Properties();
        props.setProperty("activeKey", activeKey.get());
        try (OutputStream os = Files.newOutputStream(storagePath)) {
            props.store(os, "API Key Store");  // ← plaintext on disk
        } catch (IOException e) {
            // Log and ignore or throw
        }
    }
}
```

`props.store()` writes the key in cleartext with a comment header:

```
#API Key Store
#Mon Sep 21 12:00:00 UTC 2026
activeKey=550e8400-e29b-41d4-a716-446655440000
```

The key lives on the filesystem with OS-level file permissions only.

**2b. Swallowed IO failures**

Both `loadFromDisk()` (`DefaultApiKeyStore.java:59–61`) and `saveToDisk()` (`DefaultApiKeyStore.java:71–73`) catch
`IOException` with empty catch blocks:

```java
} catch (IOException e) {
    // Log and ignore or throw
}
```

The TODO comment confirms this was intentional at time of writing but creates silent failures:

- If disk write fails, the rotated key is lost after JVM restart
- If disk read fails, the stale persisted key is silently ignored
- Applications cannot detect or recover from these failures

**2c. `ApiKeyStoreExample` credential boundary**

`examples/ApiKeyStoreExample.java:11`:

```java
DefaultApiKeyStore apiKeyStore = new DefaultApiKeyStore("initial-api-key", "auth.json", 3600);
```

The example hard-codes the initial key `"initial-api-key"` and the path `"auth.json"` — both must be replaced before
deployment. The comment on `apiKey()` in `GrizzlyStreamableServerTransportProvider.java:124–128` is explicit: *"Keep the
key outside source control."* No such warning exists on `DefaultApiKeyStore`.

**2d. Bearer validation in Grizzly transport**

`McpGrizzlyHandler.java:417–420` shows bearer validation does NOT go through `DefaultApiKeyStore`:

```java
String configured = apiKeySupplier == null ? null : apiKeySupplier.get();
if (configured == null || configured.trim().isEmpty()) return false;
String header = request.getHeader(AUTH_HEADER);
if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return true;
byte[] expected = configured.trim().getBytes(StandardCharsets.UTF_8);
byte[] actual = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
return !MessageDigest.isEqual(expected, actual);
```

Bearer validation uses the `Supplier<String>` (configured via `apiKey` or `apiKeySupplier`), NOT
`DefaultApiKeyStore.isValid()`. These are two independent paths:

- `DefaultApiKeyStore` manages rotating keys for use by application code via SPI
- `McpGrizzlyHandler.isUnauthorized()` uses the `apiKeySupplier` directly

There is no integration between `DefaultApiKeyStore` and the Grizzly transport's bearer validation.

**2e. Key rotation and invalidation**

`DefaultApiKeyStore.rotateKey()` (`DefaultApiKeyStore.java:83–92`) generates a `UUID.randomUUID()`, updates `activeKey`,
and saves to disk. The scheduler uses `scheduleAtFixedRate` with no jitter. If a rotation happens mid-request,
concurrent validation may use the old key until the next request sees the new one (due to `AtomicReference`).

### Decision

| Gap                           | Severity | Recommended action                                                                                                                                 |
|-------------------------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Plaintext key on disk         | HIGH     | Document clearly; recommend encrypted filesystem (dm-crypt, EFS) or secret store (Vault, AWS SSM). DefaultApiKeyStore should note this in Javadoc. |
| Swallowed IO failures         | MEDIUM   | Replace empty catch blocks with logging (`McpLogger`) and surface failures via a caller-supplied `Consumer<IOException>` callback                  |
| No integration with transport | INFO     | Document that `DefaultApiKeyStore` is for application-level key management, not transport authentication                                           |
| Rotation race                 | LOW      | Document the eventual-consistency property; consider a short grace window for in-flight requests                                                   |

### Open Questions

- Should `DefaultApiKeyStore` integrate with the Grizzly transport's `apiKeySupplier` so that key rotation automatically
  propagates to transport validation?
- Should the file store be encrypted (e.g., JCEKS keystore) instead of plaintext properties?

---

## Area 3: Reflection Behavior Policy — `McpReflectionRegistrar.setAccessible(true)`

### Findings

**3a. Source evidence**

`McpReflectionRegistrar.java:38`:

```java
for (Method method : type.getDeclaredMethods()) {
    method.setAccessible(true);  // ← unconditional, every declared method
    if (method.isAnnotationPresent(McpTool.class) && ...) { ... }
    if (method.isAnnotationPresent(McpResource.class) && ...) { ... }
    if (method.isAnnotationPresent(McpResourceTemplate.class) && ...) { ... }
    if (method.isAnnotationPresent(McpPrompt.class) && ...) { ... }
}
```

`setAccessible(true)` is called unconditionally on **every** declared method in the provider class, regardless of
whether it is annotated. The annotated methods are then invoked via reflection.

**3b. Java 8 behavior**

In Java 8 (`java.lang.reflect.AccessibleObject`):

- `setAccessible(true)` is a permissions check: the JVM's security manager (if enabled) verifies
  `ReflectPermission("suppressAccessChecks")`.
- Without a security manager (the common case), it succeeds silently.
- Private methods, fields, and constructors become callable.

**3c. Java 9+ behavior**

JEP 411 introduced module system restrictions. `setAccessible(true)` in Java 9+ checks:

1. The caller's module: if the module does not open the target package, an `InaccessibleObjectException` is thrown.
2. If `--add-opens` is specified for the module/package pair, the check is bypassed.
3. `setAccessible(true)` may be denied even when `--add-opens` is not needed if a security manager is active.

Starting Java 16: `setAccessible(true)` without `--add-opens` throws `InaccessibleObjectException` by default for
non-open packages.

**3d. Android behavior**

Android runtime (both Dalvik and ART) has historically not enforced Java reflection access checks in the same way as
desktop JVM. However:

- `setAccessible` on framework methods may be restricted on newer Android versions
- Private SDK methods are not accessible without `setAccessible`
- Android 12+ (API 31+) introduced stricter access checks via `StrictMode`

**3e. Policy gap**

The codebase ships `McpReflectionRegistrar` as a production feature ("small optional runtime registrar for tests and
projects that cannot enable annotation processing") while calling `setAccessible(true)` unconditionally with no guard,
no module-opens flags in build config, and no documentation of this requirement.

Javadoc at `McpReflectionRegistrar.java:15–17`:
> "Production Android applications should prefer a generated registrar so discovery is deterministic and startup is
> cheaper."

This recommendation exists but is advisory only — the reflection registrar still functions on Android without
`--add-opens` in typical configurations.

### Decision

|                        |                                                                                                                                                                                                                                                                       |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Recommended action** | Add a try/catch around `setAccessible` and document the `--add-opens` requirement for Java 9+ module environments. Consider guarding with a runtime check for Android compatibility. Document the advisory note in `McpReflectionRegistrar` Javadoc more prominently. |
| **Priority**           | LOW for Android (works today), MEDIUM for Java 9+ module-path deployments                                                                                                                                                                                             |
| **Note**               | The tool metadata gap (`scopes`/`confirmationRequired` dropped on reflection path) is already documented in `docs/authz/SCOPES-AUTHORIZATION-SPEC.md` — separate from this reflection policy                                                                          |

### Open Questions

- Should `--add-opens` JVM flags be documented in a `README` or `McpReflectionRegistrar` Javadoc?
- Should a generated-registrar alternative (annotation processor) be the recommended path with the reflection registrar
  formally deprecated for production?

---

## Area 4: IP Binding Decision — `bindSessionToIp` Config Exposure

### Findings

**4a. Config exposure in McpServerConfig**

`McpServerConfig.java:61–73`:

```java
/**
 * When {@code true}, each MCP session is bound to the client IP address recorded at
 * {@code initialize} time. Subsequent requests arriving from a different IP are rejected
 * with error -32602 (Invalid params). Default is {@code false}.
 *
 * <p>Addresses AUTH-03 / session-fixation prevention: binding the session to the
 * originating IP makes it significantly harder for an attacker who steals a session token
 * to use it from a different network location.
 *
 * <p><strong>Note:</strong> when the server is behind a reverse proxy you should also
 * set {@code trustXForwardedFor = true} so the real client IP is used instead of the
 * proxy address.
 */
public final boolean bindSessionToIp;
```

**4b. Implementation absence**

Grepping the entire `src/` tree for `bindSessionToIp` references finds only:

- `McpServerConfig.java:73, 109, 313–316` — declaration and builder assignment
- `GrizzlyStreamableServerTransportProvider.java:157` — passed to `McpGrizzlyHandler` constructor
- `McpGrizzlyHandler.java:85` — field declaration

No code path reads `bindSessionToIp` to perform any IP comparison. There is no check in `McpProtocolHandler` or
`McpGrizzlyHandler` that compares the current request's IP against the session's initial IP.

**4c. `trustXForwardedFor` implementation**

`McpGrizzlyHandler.java:64–77`:

```java
private String getClientIp(Request request) {
    if (trustXForwardedFor) {
        String xff = request.getHeader(X_FORWARDED_FOR_HEADER);
        if (xff != null && !xff.trim().isEmpty()) {
            String firstIp = xff.split(",")[0].trim();
            if (!firstIp.isEmpty()) { return firstIp; }
        }
    }
    return request.getRemoteAddr();
}
```

`trustXForwardedFor` IS implemented. It is also passed from `McpServerConfig` through
`GrizzlyStreamableServerTransportProvider` to `McpGrizzlyHandler` at
`GrizzlyStreamableServerTransportProvider.java:157`.

**4d. Misleading public config**

`McpServerConfig.Builder.bindSessionToIp(boolean)` (`McpServerConfig.java:313–316`) exposes a public builder method with
a detailed Javadoc that describes behavior ("rejected with error -32602") that does not exist in code. This is a
misleading API: consumers who enable it will believe they are protected from session-fixation attacks when they are not.

### Decision

|                        |                                                                                                                                                                                                                                                                                                                |
|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Recommended action** | **Deprecate and remove** `bindSessionToIp` from `McpServerConfig` (field, builder method, and the field in `GrizzlyStreamableServerTransportProvider`). The feature is unimplemented and its presence creates a false sense of security. If IP binding is needed, implement it properly first, then expose it. |
| **Priority**           | HIGH — misleading security config is worse than no config                                                                                                                                                                                                                                                      |
| **Risk**               | Removing a config option is breaking but low-risk since the feature never worked                                                                                                                                                                                                                               |

`trustXForwardedFor` should be **retained** — it is implemented and working.

### Open Questions

- If IP binding is desired in the future, should it live in the transport layer (rejecting requests before they reach
  `McpProtocolHandler`) or in the protocol layer (as part of session state validation)?
- Should the fix be implemented as part of this triage, or deferred to a follow-up card?

---

## Area 5: Migration Compatibility and QA Acceptance Tests

### Findings

**5a. Failure/error contracts**

Current error contract for authorization failures:

- `McpProtocolHandler.java:1524`: throws `McpErrorException(-32029, "Authorization denied: " + denial)`
- `McpGrizzlyHandler.java:401`: returns HTTP 401 for missing/invalid bearer token

No other error codes are defined for authorization-specific failures. The `McpAuthorization.denial()` return value is
free-form string.

**5b. Implementation slices needing migration compatibility**

| Area                         | Current state                 | Compatibility concern                                    |
|------------------------------|-------------------------------|----------------------------------------------------------|
| `McpAuthorization` interface | 3-param method                | Adding 4th param requires default or overload            |
| `DefaultApiKeyStore`         | Plaintext file                | Migration to encrypted store needs migration path        |
| `bindSessionToIp`            | Config exposed, unimplemented | Removing breaks any consumer who set it (no-op behavior) |
| `McpReflectionRegistrar`     | Works on Java 8/Android       | Java 9+ module path needs `--add-opens`                  |

**5c. Missing test coverage**

Existing tests (`McpAuthorizationTest.java`):

- Verifies `authorization == null` → all tools allowed
- Verifies denial message returned as `-32029`
- Verifies `denial()` is called and `null` return allows tool

Missing:

- No test for `AuthenticationContext` (not yet supported)
- No test for concurrent key rotation race in `DefaultApiKeyStore`
- No test for swallowed IO in `DefaultApiKeyStore.loadFromDisk`/`saveToDisk`
- No test for `bindSessionToIp` behavior (not implemented)
- No test for `trustXForwardedFor` IP extraction

### Recommended QA Acceptance Tests

| # | Feature                                   | Test case                                                                                                                                         |
|---|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | `AuthenticationContext` propagation       | After implementation: authorizer receives non-null context with correct `getRemoteAddress()`, `getHeaders()`, `getApiKey()`                       |
| 2 | `DefaultApiKeyStore` plaintext warning    | Documented/acknowledged; no runtime test needed                                                                                                   |
| 3 | `DefaultApiKeyStore` IO failure logging   | Mock filesystem; verify `Consumer<IOException>` callback is invoked on read/write failure                                                         |
| 4 | `DefaultApiKeyStore` rotation consistency | Concurrent `isValid()` calls during `rotateKey()`: no valid key should be accepted twice                                                          |
| 5 | `setAccessible` on Java 9+                | Run reflection registrar tests with `--add-opens io.github.vinhphan812.mcp=ALL-UNNAMED` absent; verify graceful failure or documented requirement |
| 6 | `trustXForwardedFor` IP extraction        | Mock request with `X-Forwarded-For: 203.0.113.1, 10.0.0.1`; verify `getClientIp()` returns `203.0.113.1`                                          |
| 7 | `bindSessionToIp` removal                 | After removal: confirm no references to `bindSessionToIp` remain in source                                                                        |
| 8 | `McpAuthorization` backward compat        | Implement 3-param lambda; confirm it still compiles and works after adding 4th param                                                              |
| 9 | Bearer token constant-time comparison     | Code review confirmation of `MessageDigest.isEqual` usage; no regression path for timing attacks                                                  |

### Decision

|                        |                                                                                                                                                                                                                                                                                                                                                                     |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Recommended action** | Define a migration compatibility policy: (1) `McpAuthorization` changes must maintain binary backward compatibility via default parameter; (2) `DefaultApiKeyStore` must add failure callback before plaintext storage is removed; (3) `bindSessionToIp` removal is breaking but low-risk; (4) Document Java 9+ `--add-opens` requirement for reflection registrar. |
| **Priority**           | Medium — needed before shipping a release with the above changes                                                                                                                                                                                                                                                                                                    |

### Open Questions

- Should `McpAuthorization.denial()` return type be changed from `String` to a structured `AuthorizationResult` object
  for richer error codes?
- What is the target Java version for this SDK? (Java 8 confirmed per ADR-0001 — Android API 22 compatible.)

---

## Summary of Decisions

| # | Area                                | Recommendation                                                                        | Priority |
|---|-------------------------------------|---------------------------------------------------------------------------------------|----------|
| 1 | `AuthenticationContext` propagation | Add as 4th parameter to `McpAuthorization.denial()` with `null` default               | Medium   |
| 2 | `DefaultApiKeyStore` custody        | Document plaintext scope; add failure callbacks; no integration with transport needed | Medium   |
| 3 | `setAccessible(true)` policy        | Document `--add-opens` requirement; add graceful fallback                             | Medium   |
| 4 | `bindSessionToIp`                   | **Deprecate and remove** — unimplemented misleading config                            | **High** |
| 5 | Migration compatibility             | Define policy + 9 acceptance tests                                                    | Medium   |

---

## Source Evidence Index

| File                                                                | Lines                                 | Used for                                            |
|---------------------------------------------------------------------|---------------------------------------|-----------------------------------------------------|
| `api/spi/McpAuthorization.java`                                     | 70–71                                 | denial() signature                                  |
| `api/security/AuthenticationContext.java`                           | 1–44                                  | AuthenticationContext interface                     |
| `core/McpProtocolHandler.java`                                      | 1515–1526                             | denial() invocation site                            |
| `core/McpProtocolHandler.java`                                      | 50–51                                 | ADR-0011 features list                              |
| `transport/McpGrizzlyHandler.java`                                  | 413–421                               | isUnauthorized() bearer validation                  |
| `transport/McpGrizzlyHandler.java`                                  | 64–77                                 | trustXForwardedFor IP extraction                    |
| `transport/McpGrizzlyHandler.java`                                  | 36, 85                                | AUTH_HEADER, trustXForwardedFor field               |
| `transport/GrizzlyStreamableServerTransportProvider.java`           | 124–147                               | apiKey / apiKeySupplier Javadoc                     |
| `transport/GrizzlyStreamableServerTransportProvider.java`           | 157                                   | trustXForwardedFor passed to handler                |
| `security/DefaultApiKeyStore.java`                                  | 52–75                                 | loadFromDisk / saveToDisk (plaintext, swallowed IO) |
| `security/DefaultApiKeyStore.java`                                  | 59–61, 71–73                          | Empty catch blocks                                  |
| `security/DefaultApiKeyStore.java`                                  | 83–92                                 | rotateKey() implementation                          |
| `api/McpReflectionRegistrar.java`                                   | 37–38                                 | Unconditional setAccessible(true)                   |
| `api/McpReflectionRegistrar.java`                                   | 15–17                                 | Production Android advisory                         |
| `api/config/McpServerConfig.java`                                   | 50, 87–89, 106, 143, 265–275, 293–296 | authorization config                                |
| `api/config/McpServerConfig.java`                                   | 58, 61–73, 108, 145, 293              | trustXForwardedFor config                           |
| `api/config/McpServerConfig.java`                                   | 73, 109, 146, 313–316                 | bindSessionToIp config (unimplemented)              |
| `examples/ApiKeyStoreExample.java`                                  | 11                                    | Hard-coded key and path                             |
| `examples/AuthorizationExample.java`                                | 8                                     | 3-param denial lambda                               |
| `docs/adr/ADR-0006-security-model.md`                               | 1–95                                  | Existing security model documentation               |
| `docs/adr/ADR-0011-security-rate-limiting.md`                       | 1–248                                 | Security/rate-limiting ADR                          |
| `docs/authz/SCOPES-AUTHORIZATION-SPEC.md`                           | 1–50                                  | Reflection metadata gap                             |
| `src/test/java/io/github/vinhphan812/mcp/McpAuthorizationTest.java` | 1–98                                  | Authorization test coverage                         |
