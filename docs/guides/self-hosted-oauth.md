# Self-hosted OAuth Protected Resource

The MCP HTTP transport supports provider-neutral, self-hosted bearer authentication.

## Protected Resource Metadata

For an MCP endpoint configured as `/mcp`, the handler exposes:

`GET /mcp/protected-resource-metadata`

The response is JSON containing the protected resource identifier, authorization server, and supported scopes. Clients can discover this URL from the `resource_metadata` parameter in the `WWW-Authenticate` challenge returned for an unauthenticated request.

## Authentication

Configure an `AuthenticationVerifier` with `McpHttpHandler.Builder.verifier(...)`. The verifier receives an `AuthenticationContext` containing the bearer credential, request headers, path, method, and remote address. This SPI intentionally does not depend on a hosted identity provider, and can be implemented with a local JWT verifier, an introspection client, or a static verifier.

The existing `apiKey(...)` and `apiKeySupplier(...)` options remain available for static Bearer/API-key deployments. When authentication fails, the handler returns HTTP 401 with a standards-compatible challenge:

`WWW-Authenticate: Bearer realm="mcp", resource_metadata="/mcp/protected-resource-metadata"`

Applications should perform issuer, expiry, audience/resource binding, and scope checks inside their verifier and return an authenticated identity only when all checks pass.
