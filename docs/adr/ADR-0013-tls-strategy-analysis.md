# ADR-0013 — TLS Strategy: Reverse-Proxy-Only vs In-Process TLS

**Status:** Accepted
**Date:** 2026-09-20
**Authors:** MCP Java SDK team

## Context

The MCP Java SDK provides a `TlsConfig` class and a `scheme()` method that suggest TLS support, but neither is
functional:

- `TlsConfig` (src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java) defines `keystorePath`,
  `keystorePassword`, and `protocol` fields but has zero references in the codebase.
- `StreamableServerTransportProvider` creates a plain `NetworkListener` at line 154 with no TLS configuration:
  `new NetworkListener(LISTENER_NAME, host, port)`.
- The `scheme("https")` method (lines 86-90) only modifies the URL string returned by `getUrl()` — it does not enable
  TLS on the server.

This ADR evaluates two approaches: (1) reverse-proxy-only TLS termination, or (2) implementing full in-process TLS via
Grizzly.

---

## Option A: Reverse-Proxy-Only TLS

### Description

Deprecate and eventually remove `TlsConfig`. Document that TLS is handled externally by a reverse proxy (nginx, Apache,
cloud load balancer). The `scheme()` method remains as documentation of the external termination.

### Threat Model Assumptions

- The application server handles only HTTP.
- TLS/SSL is terminated at the reverse proxy, which provides certificate management.
- The internal network between proxy and app server is trusted (localhost or VPC).
- Attackers cannot intercept traffic between proxy and app server.

### Deployment Complexity

| Task                                            | Complexity                    |
|-------------------------------------------------|-------------------------------|
| Remove TlsConfig                                | Low — delete unused class     |
| Update StreamableServerTransportProvider | None — already plain HTTP     |
| Documentation                                   | Low — clarify TLS is external |
| Release notes                                   | Low                           |

### Compatibility/Migration Impact

- **Breaking change** for any code referencing `TlsConfig` (though none exists currently).
- Users relying on "built-in TLS" must configure a reverse proxy.
- No runtime migration needed — no functional change.

### Release Notes Needed

```
## [X.Y.0] - YYYY-MM-DD

### Removed
- `TlsConfig` class removed. TLS termination should be handled by a reverse proxy.
  If you need TLS, configure it at the proxy level (nginx, Apache, cloud LB).

### Documentation
- Added guidance on TLS setup via reverse proxy in the transport documentation.
```

### https-advertising Semantics (scheme() behavior)

- `scheme("https")` remains valid — it documents that TLS is terminated upstream.
- `getUrl()` returns `https://host:port/endpoint` when scheme is set, even though the server listens on plain HTTP.
- This is intentional: the URL advertised to clients should reflect the external endpoint, not the internal transport.

### Team Validation Capacity

- No TLS implementation needed.
- Documentation review sufficient.
- No additional test infrastructure required.

---

## Option B: In-Process TLS via Grizzly

### Description

Implement full TLS support in `StreamableServerTransportProvider` using Grizzly's `SSLContextConfigurator` and
`SSLEngineConfigurator`. Wire `TlsConfig` into the transport provider.

### Implementation Requirements

#### 1. Wire TlsConfig into StreamableServerTransportProvider

Add a `tls(TlsConfig)` method to the builder chain:

```java
public StreamableServerTransportProvider tls(TlsConfig config) {
    this.tlsConfig = config;
    return this;
}
```

#### 2. Create SSLContext from TlsConfig

Use Grizzly's `SSLContextConfigurator` (available in grizzly-http-server:4.0.2):

```java
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;

private SSLEngineConfigurator createSSLEngineConfigurator(TlsConfig tls) {
    SSLContextConfigurator sslContextConfig = new SSLContextConfigurator();
    sslContextConfig.setKeyStoreFile(tls.keystorePath);
    sslContextConfig.setKeyStorePass(tls.keystorePassword);
    sslContextConfig.setKeyStoreType("JKS"); // or PKCS12
    sslContextConfig.setSecurityProtocol(tls.protocol != null ? tls.protocol : "TLSv1.3");

    SSLContext sslContext = sslContextConfig.createSSLContext(true);
    return new SSLEngineConfigurator(sslContext)
        .setClientMode(false)
        .setNeedClientAuth(false); // Optionally make configurable
}
```

#### 3. Enable TLS on NetworkListener

Modify the `start()` method to configure SSL:

```java
@Override
public synchronized void start() throws IOExceptionUnchecked {
    if (isRunning()) return;
    try {
        McpGrizzlyHandler httpHandler = new McpGrizzlyHandler(...);
        server = new HttpServer();
        NetworkListener listener = new NetworkListener(LISTENER_NAME, host, port);

        if (tlsConfig != null) {
            SSLEngineConfigurator sslConfig = createSSLEngineConfigurator(tlsConfig);
            listener.setSSLEngineConfigurator(sslConfig);
        }

        server.addListener(listener);
        server.getServerConfiguration().addHttpHandler(httpHandler, endpoint);
        server.start();
    } catch (Exception e) {
        // error handling
    }
}
```

#### 4. Keystore Management

- Users must provide a JKS or PKCS#12 keystore file.
- Keystore must contain a private key and certificate.
- Password may be provided directly or via environment variable/secret supplier (security best practice).
- Documentation must include instructions for generating a self-signed certificate:
  ```bash
  keytool -genkeypair -alias mcp-server -keyalg RSA -keysize 2048 \
    -storetype PKCS12 -keystore server.p12 -storepass secret \
    -validity 365 -ext SAN=dns:localhost,ip:127.0.0.1
  ```

#### 5. Dual HTTP/HTTPS Support (Optional Enhancement)

Optionally support both HTTP and HTTPS on different ports:

```java
// HTTP on 3011, HTTPS on 3012
NetworkListener http = new NetworkListener(LISTENER_NAME + "-http", host, httpPort);
NetworkListener https = new NetworkListener(LISTENER_NAME + "-https", host, httpsPort);
https.setSSLEngineConfigurator(sslConfig);
server.addListener(http);
server.addListener(https);
```

### Threat Model Assumptions

- The application handles TLS directly.
- Private keys reside in the application process memory (or filesystem accessible by the app).
- Attackers with filesystem access can extract keystore and password.
- TLS provides encryption and server authentication end-to-end.

### Deployment Complexity

| Task                               | Complexity                           |
|------------------------------------|--------------------------------------|
| Wire TlsConfig into builder        | Medium — add method, store field     |
| Create SSLContextConfigurator      | Medium — map fields correctly        |
| Configure NetworkListener with SSL | Medium — Grizzly API usage           |
| Add tests (unit + integration)     | High — TLS handshake tests           |
| Documentation                      | Medium — keystore generation, config |
| Release notes                      | Medium — new feature                 |

### Compatibility/Migration Impact

- **Backward compatible** — adding TLS does not break existing HTTP-only deployments.
- Users can gradually adopt TLS by adding `.tls(TlsConfig.defaults())` (with keystore).
- No migration required for existing HTTP users.

### Release Notes Needed

```
## [X.Y.0] - YYYY-MM-DD

### Added
- In-process TLS support via `TlsConfig`. Configure TLS on the transport:

  ```java
  StreamableServerTransportProvider transport = new StreamableServerTransportProvider(handler)
      .tls(new TlsConfig("/path/to/keystore.p12", "password", "TLSv1.3"))
      .scheme("https")
      .start();
  ```

- The `scheme("https")` method now correctly configures HTTPS on the server.
- Added `StreamableServerTransportProvider.tls(TlsConfig)` builder method.

### Documentation

- Added TLS configuration guide in transport documentation.
- Added keystore generation instructions for local development.

```

### https-advertising Semantics (scheme() behavior)
- `scheme("https")` should enable TLS on the server AND set the advertised URL scheme.
- `getUrl()` returns `https://host:port/endpoint` — accurate for both internal and external clients.

### Team Validation Capacity
- **Medium effort** — implement TLS configuration, add unit tests for SSLContext creation.
- **Integration test** — create a test that starts the server with TLS, connects via HTTPS, and verifies the handshake succeeds.
- May need: test keystore in test resources, mock or test HTTP client that trusts the test certificate.

---

## Comparison Matrix

| Criteria | Reverse-Proxy-Only | In-Process TLS |
|----------|-------------------|-----------------|
| Implementation effort | 1 day | 3-5 days |
| TLS handshake testing | Not needed | Required |
| Keystore management | External | User-provided |
| Private key location | Proxy filesystem | App filesystem |
| Documentation updates | Minor | Medium |
| Release note impact | Removal (breaking) | Addition (backward compatible) |
| User migration required | No (no change) | Optional |
| External dependencies | None | None (Grizzly includes SSL) |
| Complexity | Low | Medium |

---

## Recommendation

**Recommended: Option A (Reverse-Proxy-Only)**

Rationale:
1. **Current usage is zero** — `TlsConfig` has no references; removing it has no user impact.
2. **Security best practice** — reverse-proxy TLS is the recommended deployment pattern for JVM services; it separates concerns and centralizes certificate management.
3. **Reduced maintenance** — no TLS handshake code to test, no keystore handling documentation to maintain.
4. **ADR-0006 already documents** that production deployment requires TLS termination at a reverse proxy — this aligns with the existing security model.

**However**, if there is demand for in-process TLS from users (e.g., for single-container deployments, IoT, or edge cases), implement Option B as a future enhancement. The `TlsConfig` class can remain for that purpose (not removed), but should be documented as "planned" rather than functional.

---

**Recommended: Option A (Reverse-Proxy-Only)** — see [ADR-0014](ADR-0014-tls-transport-contract.md) for the accepted decision.

## Decision

- Remove `TlsConfig` from the public API in the next major release (see ADR-0014).
- Document the reverse-proxy TLS pattern in transport documentation (see ADR-0014).
- Keep `scheme("https")` as it correctly reflects the external URL scheme.
- Track in-process TLS as a potential future enhancement if user demand materializes.

---

## Open Questions

1. Should `TlsConfig` be deprecated now and removed in a future major version, rather than removed immediately?
2. Should a `tls(TlsConfig)` method be added to the builder for future use, even if not wired to Grizzly yet (to avoid API churn later)?
3. Is there user demand for in-process TLS that would justify the implementation effort?

---

## References

- Grizzly SSLContextConfigurator: https://github.com/javaee/grizzly/blob/master/modules/grizzly/src/main/java/org/glassfish/grizzly/ssl/SSLContextConfigurator.java
- Grizzly with SSL (OX Documentation): https://documentation.open-xchange.com/7.10.4/middleware/security_and_encryption/grizzly_with_ssl.html
- ADR-0006 Security Model: ADR-0006-security-model.md
- TlsConfig source: src/main/java/io/github/vinhphan812/mcp/api/config/TlsConfig.java
- StreamableServerTransportProvider source: src/main/java/io/github/vinhphan812/mcp/transport/StreamableServerTransportProvider.java
