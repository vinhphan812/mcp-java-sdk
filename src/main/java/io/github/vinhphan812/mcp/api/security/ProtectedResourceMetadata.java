package io.github.vinhphan812.mcp.api.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** RFC 9728 protected-resource metadata. */
public final class ProtectedResourceMetadata {
    private final String resource;
    private final List<String> authorizationServers;
    private final List<String> scopesSupported;
    public ProtectedResourceMetadata(String resource, List<String> authorizationServers, List<String> scopesSupported) {
        if (resource == null || resource.trim().isEmpty()) throw new IllegalArgumentException("resource cannot be blank");
        if (authorizationServers == null || authorizationServers.isEmpty()) throw new IllegalArgumentException("authorizationServers cannot be empty");
        this.resource = resource;
        this.authorizationServers = Collections.unmodifiableList(new ArrayList<String>(authorizationServers));
        this.scopesSupported = Collections.unmodifiableList(new ArrayList<String>(scopesSupported == null ? Collections.<String>emptyList() : scopesSupported));
    }
    public String getResource() { return resource; }
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("resource", resource);
        value.put("authorization_servers", authorizationServers);
        if (!scopesSupported.isEmpty()) value.put("scopes_supported", scopesSupported);
        return value;
    }
}
