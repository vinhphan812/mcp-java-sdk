package io.github.vinhphan812.mcp.api.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Context supplied to a provider-neutral access-token verifier. */
public final class TokenVerificationContext {
    private final String resource;
    private final String requestPath;
    private final Map<String, String[]> headers;
    public TokenVerificationContext(String resource, String requestPath, Map<String, String[]> headers) {
        this.resource = resource;
        this.requestPath = requestPath;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String[]>(headers));
    }
    public String getResource() { return resource; }
    public String getRequestPath() { return requestPath; }
    public Map<String, String[]> getHeaders() { return headers; }
}
