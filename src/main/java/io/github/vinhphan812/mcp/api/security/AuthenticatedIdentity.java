package io.github.vinhphan812.mcp.api.security;

import java.util.Set;

/**
 * Represents an authenticated identity.
 */
public interface AuthenticatedIdentity {
    String getId();
    Set<String> getScopes();
}
