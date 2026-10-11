package io.github.vinhphan812.mcp.api.security;

import java.util.Optional;
import java.util.Set;

public class StaticAuthenticationVerifier implements AuthenticationVerifier {
    private final String expectedToken;
    private final Set<String> allowedScopes;

    public StaticAuthenticationVerifier(String token, Set<String> scopes) {
        this.expectedToken = token;
        this.allowedScopes = scopes;
    }

    @Override
    public Optional<AuthenticatedIdentity> verify(AuthenticationContext context) {
        String token = context.getApiKey();
        if (token != null && expectedToken.equals(token)) {
            return Optional.of(new AuthenticatedIdentity() {
                @Override public String getId() { return "static-user"; }
                @Override public Set<String> getScopes() { return allowedScopes; }
            });
        }
        return Optional.empty();
    }
}
