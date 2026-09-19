package io.github.vinhphan812.mcp.api.security;

import java.util.Map;

/**
 * Request context for authentication.
 */
public interface AuthenticationContext {
    String getApiKey();
    Map<String, String[]> getHeaders();
    String getRemoteAddress();
    String getRequestPath();
    String getMethod();
}
