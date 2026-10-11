package io.github.vinhphan812.mcp.transport;

import io.github.vinhphan812.mcp.api.security.AuthenticationContext;
import org.glassfish.grizzly.http.server.Request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestContext implements AuthenticationContext {
    private final Request request;

    public RequestContext(Request request) {
        this.request = request;
    }

    @Override
    public String getApiKey() {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return "";
    }

    @Override
    public Map<String, String[]> getHeaders() {
        Map<String, String[]> headers = new HashMap<>();
        for (String name : request.getHeaderNames()) {
            List<String> list = new ArrayList<>();
            for (String value : request.getHeaders(name)) {
                list.add(value);
            }
            headers.put(name, list.toArray(new String[0]));
        }
        return headers;
    }

    @Override
    public String getRemoteAddress() {
        return request.getRemoteAddr();
    }

    @Override
    public String getRequestPath() {
        return request.getRequestURI();
    }

    @Override
    public String getMethod() {
        return request.getMethod().toString();
    }
}
