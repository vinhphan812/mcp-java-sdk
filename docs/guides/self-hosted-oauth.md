# Self-hosted OAuth Protected Resource

The HTTP transport can act as an OAuth protected resource without choosing an
identity provider. It publishes RFC 9728 metadata and delegates access-token
verification to application code.

## Configuration

```java
ProtectedResourceMetadata metadata = new ProtectedResourceMetadata(
    "https://mcp.example.test/mcp",
    Collections.singletonList("https://issuer.example.test"),
    Arrays.asList("mcp:read", "mcp:write"));

new HttpTransportProvider(protocolHandler)
    .protectedResourceMetadata(metadata)
    .requiredBearerScopes(Collections.singleton("mcp:read"))
    .tokenVerifier((token, context) -> {
        // Verify locally: signature, issuer, expiry, audience and resource.
        return Optional.of(new McpSecurityContext("subject", scopes, claims));
    });
```

`TokenVerifier` is provider-neutral: it receives the raw Bearer token, the
configured resource identifier, request path, and request headers. The SDK does
not perform network calls or embed any third-party identity-provider dependency.
Verifier implementations must enforce signature, issuer, expiry, audience, and
resource binding before returning a `SecurityContext`.

## RFC 9728 discovery and challenges

For an endpoint at `/mcp`, metadata is served at:

```
GET /.well-known/oauth-protected-resource/mcp
```

The JSON document contains `resource`, `authorization_servers`, and, when
configured, `scopes_supported`. Missing or invalid Bearer tokens receive a 401
with `WWW-Authenticate: Bearer error="invalid_token"` and a `resource_metadata`
parameter that points to that path. A verified token lacking a required scope
receives 403 with `error="insufficient_scope"` and the required `scope`.

## Using JwtTokenVerifier

The SDK ships `JwtTokenVerifier implements TokenVerifier` so you can validate
JWTs without any external OAuth provider. Keys are generated locally; signing
and verification are fully offline.

### RSA (RS256 / RS384 / RS512)

```java
// Build a verifier from a PKCS#8 PEM (private or public key)
JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
    .issuer("https://issuer.example.test")
    .audience("https://mcp.example.test/mcp")
    .requiredScopes(Set.of("mcp:read"))
    .build();

HttpTransportProvider transport = new HttpTransportProvider(handler)
    .port(8080)
    .protectedResourceMetadata(metadata)
    .tokenVerifier(verifier);
```

You can also start from an X.509 PEM with `forRsaX509Pem(x509PublicKeyPem)`.

### HMAC (HS256 / HS384 / HS512)

```java
JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(hmacKeyBytes, "HS256")
    .issuer("https://issuer.example.test")
    .build();
```

### EC (ES256 / ES384 / ES512)

```java
JwtTokenVerifier verifier = JwtTokenVerifier.forEcPkcs8Pem(ecPrivateKeyPem)
    .issuer("https://issuer.example.test")
    .build();
```

### Key generation for tests

```java
KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
kpg.initialize(2048);
KeyPair pair = kpg.generateKeyPair();
// pair.getPrivate() → sign with jjwt
// pair.getPublic()  → verify with JwtTokenVerifier
```

All three key types (RSA, HMAC, EC) are covered by the integration test
`JwtTokenVerifierIntegrationTest` which generates keys in-memory, signs tokens,
and verifies the full acceptance criteria: valid JWT authorises, expired/bad-
signature/wrong-issuer/wrong-audience return 401, insufficient scope returns 403.

Run the JWT tests with:

```
./gradlew test --tests JwtTokenVerifierIntegrationTest
```

## Static keys remain custom authentication

`apiKey(...)` and `apiKeySupplier(...)` retain their existing constant-time
static Bearer/API-key behavior. They are separate from OAuth token verification;
use `tokenVerifier(...)` with protected-resource metadata for OAuth deployments.
Never commit credentials or tokens.
