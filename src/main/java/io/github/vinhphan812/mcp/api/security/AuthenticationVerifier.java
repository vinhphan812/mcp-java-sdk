package io.github.vinhphan812.mcp.api.security;

import java.util.Optional;

/**
 * Interface for verifying credentials.
 */
public interface AuthenticationVerifier {
    /**
     * Verifies the credentials from the context.
     * @param context the authentication context
     * @return an Optional containing the authenticated identity, or empty if verification fails.
     */
    Optional<AuthenticatedIdentity> verify(AuthenticationContext context);
}
