package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.security.SecurityContext;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for pluggable token verification (e.g., JWT, OAuth2, or static tokens).
 */
public interface AuthenticationVerifier {
    /**
     * Verifies the provided credentials and returns a populated security context if valid.
     *
     * @param apiKey  the raw credential (e.g., Bearer token)
     * @param headers the full request headers to allow for contextual verification
     * @return an optional security context if credentials are authenticated
     */
    Optional<SecurityContext> verify(String apiKey, Map<String, String[]> headers);
}
