package io.github.vinhphan812.mcp.api.security;

import java.util.Optional;

/** Provider-neutral SPI for bearer-token verification. Implementations verify signature, issuer, expiry, audience and resource binding. */
@FunctionalInterface
public interface TokenVerifier {
    Optional<SecurityContext> verify(String accessToken, TokenVerificationContext context);
}
