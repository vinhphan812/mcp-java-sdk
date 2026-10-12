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

## Static keys remain custom authentication

`apiKey(...)` and `apiKeySupplier(...)` retain their existing constant-time
static Bearer/API-key behavior. They are separate from OAuth token verification;
use `tokenVerifier(...)` with protected-resource metadata for OAuth deployments.
Never commit credentials or tokens.
