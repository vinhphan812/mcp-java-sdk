package io.github.vinhphan812.mcp.api.security;

import java.util.Map;

/**
 * Request context for authentication.
 */
public interface AuthenticationContext {
    /**
     * Returns the raw API key credential from the current request.
     *
     * @return raw API key, never null
     */
    String getApiKey();

    /**
     * Returns all HTTP headers from the current request.
     *
     * @return header name to values map
     */
    Map<String, String[]> getHeaders();

    /**
     * Returns the remote IP address of the client.
     *
     * @return IP address string, never null
     */
    String getRemoteAddress();

    /**
     * Returns the request path component of the URI.
     *
     * @return request path, never null
     */
    String getRequestPath();

    /**
     * Returns the HTTP method of the current request.
     *
     * @return method name (e.g. "GET", "POST")
     */
    String getMethod();
}
