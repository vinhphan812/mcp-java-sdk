package io.github.vinhphan812.mcp.api.security;

import java.util.Map;
import java.util.Set;

/**
 * Verified user/token identity after authentication.
 */
public interface SecurityContext {
    /**
     * Returns the subject identifier (e.g., username, user ID, client ID).
     */
    String getSubject();

    /**
     * Returns the authorized scopes for this context.
     */
    Set<String> getScopes();

    /**
     * Returns arbitrary metadata claims associated with this context.
     */
    Map<String, Object> getClaims();
}
