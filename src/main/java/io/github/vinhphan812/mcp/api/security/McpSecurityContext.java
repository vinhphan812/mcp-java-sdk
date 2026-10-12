package io.github.vinhphan812.mcp.api.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Immutable verified identity for an MCP bearer token. */
public final class McpSecurityContext implements SecurityContext {
    private final String subject;
    private final Set<String> scopes;
    private final Map<String, Object> claims;
    public McpSecurityContext(String subject, Set<String> scopes, Map<String, Object> claims) {
        if (subject == null || subject.trim().isEmpty()) throw new IllegalArgumentException("subject cannot be blank");
        this.subject = subject;
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<String>(scopes));
        this.claims = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(claims));
    }
    @Override public String getSubject() { return subject; }
    @Override public Set<String> getScopes() { return scopes; }
    @Override public Map<String, Object> getClaims() { return claims; }
}
