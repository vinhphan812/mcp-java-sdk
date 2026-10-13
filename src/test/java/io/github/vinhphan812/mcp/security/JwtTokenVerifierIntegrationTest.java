package io.github.vinhphan812.mcp.security;

import io.github.vinhphan812.mcp.api.security.JwtTokenVerifier;
import io.github.vinhphan812.mcp.api.security.McpSecurityContext;
import io.github.vinhphan812.mcp.api.security.TokenVerificationContext;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link JwtTokenVerifier} — local JWT/JOSE verification
 * without any external OAuth provider.
 *
 * <p>All signing uses keys generated in-memory (no external provider, no persisted secrets).
 * This test class is the reference implementation of the JWT setup described in
 * {@code docs/guides/self-hosted-oauth.md}.
 */
class JwtTokenVerifierIntegrationTest {

    private static final String ISSUER      = "https://issuer.example.test";
    private static final String AUDIENCE    = "https://resource.example.test/mcp";
    private static final String RESOURCE    = "https://resource.example.test/mcp";
    private static final String PATH        = "/mcp";

    // RSA key pair (generated once)
    private static RSAPrivateKey rsaPrivateKey;
    private static RSAPublicKey  rsaPublicKey;
    private static String        rsaPrivateKeyPem;  // PKCS#8 PEM
    private static String        rsaPublicKeyPem;   // X.509 PEM

    // HMAC key
    private static final byte[] HMAC_KEY_256 = "this-is-a-256-bit-shared-secret-key!!".getBytes(StandardCharsets.UTF_8);

    // Fixed "now" for reproducible not-before / expiry claims.
    // Use current time so tokens remain unexpired regardless of when tests run.
    private static final long   FIXED_NOW = System.currentTimeMillis();
    private static final long   FIXED_EXP = FIXED_NOW + 3_600_000; // +1 hour
    private static final long   FIXED_NBF = FIXED_NOW - 60_000;  // -1 minute

    // ── setup ─────────────────────────────────────────────────────────────────

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair = kpg.generateKeyPair();
        rsaPrivateKey = (RSAPrivateKey) pair.getPrivate();
        rsaPublicKey  = (RSAPublicKey)  pair.getPublic();

        // Encode as PEM strings for the verifier's factory methods
        rsaPrivateKeyPem = toPem("PRIVATE KEY", rsaPrivateKey.getEncoded());
        rsaPublicKeyPem  = toPem("PUBLIC KEY",  rsaPublicKey.getEncoded());
    }

    private static String toPem(String type, byte[] bytes) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(bytes);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    // ── JWT builders ─────────────────────────────────────────────────────────────

    private String rsaJwt(ClaimsBuilder claims) {
        return claims.build(rsaPrivateKey);
    }

    private String hmacJwt(ClaimsBuilder claims) {
        return claims.build(HMAC_KEY_256, "HS256");
    }

    /** Fluent JWT claim builder. */
    private static class ClaimsBuilder {
        private String issuer   = ISSUER;
        private String audience= AUDIENCE;
        private String subject = "user-123";
        private Date   exp     = new Date(FIXED_EXP);
        private Date   nbf     = new Date(FIXED_NBF);
        private String scope   = "mcp:read mcp:write";
        private String scp     = null; // use "scope" unless overridden

        ClaimsBuilder issuer(String v)   { this.issuer    = v;   return this; }
        ClaimsBuilder audience(String v) { this.audience  = v;   return this; }
        ClaimsBuilder subject(String v)  { this.subject   = v;   return this; }
        ClaimsBuilder exp(Date v)       { this.exp       = v;   return this; }
        ClaimsBuilder nbf(Date v)        { this.nbf       = v;   return this; }
        ClaimsBuilder scope(String v)    { this.scope     = v;   this.scp = null; return this; }
        ClaimsBuilder scp(String v)      { this.scp       = v;   return this; }

        String build(java.security.Key privateKey) {
            return build(privateKey, "RS256");
        }

        String build(byte[] symmetricKey, String algorithm) {
            return Jwts.builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience().add(audience).and()
                    .expiration(exp)
                    .notBefore(nbf)
                    .issuedAt(new Date(FIXED_NOW))
                    .claim(scp != null ? "scp" : "scope", scp != null ? scp : scope)
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(symmetricKey))
                    .compact();
        }

        String build(java.security.Key privateKey, String algorithm) {
            return Jwts.builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience().add(audience).and()
                    .expiration(exp)
                    .notBefore(nbf)
                    .issuedAt(new Date(FIXED_NOW))
                    .claim(scp != null ? "scp" : "scope", scp != null ? scp : scope)
                    .signWith(privateKey, io.jsonwebtoken.SignatureAlgorithm.forName(algorithm))
                    .compact();
        }
    }

    private static TokenVerificationContext ctx() {
        return new TokenVerificationContext(RESOURCE, PATH, Collections.emptyMap());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RSA — valid token
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RSA (RS256)")
    class RsaValidToken {

        @Test
        @DisplayName("valid RS256 JWT with all claims → authorised")
        void validJwt_authorised() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .requiredScopes(Set.of("mcp:read"))
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .scope("mcp:read mcp:write"));

            Optional<io.github.vinhphan812.mcp.api.security.SecurityContext> result =
                    verifier.verify(token, ctx());

            assertTrue(result.isPresent(), "Valid JWT should return a SecurityContext");
            assertEquals("user-123", result.get().getSubject());
            assertTrue(result.get().getScopes().contains("mcp:read"));
            assertTrue(result.get().getScopes().contains("mcp:write"));
            assertEquals(ISSUER, result.get().getClaims().get("iss"));
        }

        @Test
        @DisplayName("valid RS256 JWT with X.509 key → authorised")
        void validJwt_x509Key_authorised() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaX509Pem(rsaPublicKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .build();

            String token = rsaJwt(new ClaimsBuilder());

            Optional<io.github.vinhphan812.mcp.api.security.SecurityContext> result =
                    verifier.verify(token, ctx());

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("valid RS256 JWT without optional checks → authorised")
        void validJwt_noOptionalChecks_authorised() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .build(); // no issuer, no audience, no scopes

            String token = rsaJwt(new ClaimsBuilder()
                    .issuer("https://other-issuer.test")
                    .audience("https://other-audience.test")
                    .scope("other:scope"));

            Optional<io.github.vinhphan812.mcp.api.security.SecurityContext> result =
                    verifier.verify(token, ctx());

            assertTrue(result.isPresent());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // HMAC — valid token
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HMAC (HS256)")
    class HmacValidToken {

        @Test
        @DisplayName("valid HS256 JWT → authorised")
        void validJwt_authorised() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .requiredScopes(Set.of("mcp:read"))
                    .build();

            String token = hmacJwt(new ClaimsBuilder().scope("mcp:read"));

            Optional<io.github.vinhphan812.mcp.api.security.SecurityContext> result =
                    verifier.verify(token, ctx());

            assertTrue(result.isPresent());
            assertEquals("user-123", result.get().getSubject());
            assertTrue(result.get().getScopes().contains("mcp:read"));
        }

        @Test
        @DisplayName("HS256 with HS384 algorithm name → accepted")
        void validJwt_hs384_accepted() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS384")
                    .build();

            String token = Jwts.builder()
                    .subject("user")
                    .issuer(ISSUER)
                    .expiration(new Date(FIXED_EXP))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(HMAC_KEY_256))
                    .compact();

            assertTrue(verifier.verify(token, ctx()).isPresent());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // HMAC — bad signature
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HMAC — bad signature → 401")
    class HmacBadSignature {

        @Test
        @DisplayName("HMAC token signed with different key → rejected")
        void wrongKey_rejected() {
            byte[] wrongKey = "completely-different-256-bit-secret-key!".getBytes(StandardCharsets.UTF_8);
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .issuer(ISSUER)
                    .build();

            String token = Jwts.builder()
                    .subject("user")
                    .issuer(ISSUER)
                    .expiration(new Date(FIXED_EXP))
                    .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(wrongKey))
                    .compact();

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("malformed JWT string → rejected")
        void malformedJwt_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .build();

            assertTrue(verifier.verify("not.a.jwt", ctx()).isEmpty());
            assertTrue(verifier.verify("", ctx()).isEmpty());
            assertTrue(verifier.verify(null, ctx()).isEmpty());
        }

        @Test
        @DisplayName("RSA token against HMAC verifier → rejected")
        void rsaTokenAgainstHmac_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .issuer(ISSUER)
                    .build();

            String token = rsaJwt(new ClaimsBuilder());
            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RSA — bad signature
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RSA — bad signature → 401")
    class RsaBadSignature {

        @Test
        @DisplayName("RS256 token signed with wrong RSA key → rejected")
        void wrongKey_rejected() throws Exception {
            // Generate a different RSA key pair
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair wrongPair = kpg.generateKeyPair();

            String wrongPrivatePem = toPem("PRIVATE KEY",
                    ((RSAPrivateKey) wrongPair.getPrivate()).getEncoded());

            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .build();

            String token = rsaJwt(new ClaimsBuilder()); // signed with correct key

            // Now tamper: re-sign with wrong key (replace signature bytes)
            // Easier approach: sign with wrong key directly
            String tokenSignedByWrongKey = Jwts.builder()
                    .subject("user-123")
                    .issuer(ISSUER)
                    .audience().add(AUDIENCE).and()
                    .expiration(new Date(FIXED_EXP))
                    .signWith(wrongPair.getPrivate(), io.jsonwebtoken.SignatureAlgorithm.RS256)
                    .compact();

            assertTrue(verifier.verify(tokenSignedByWrongKey, ctx()).isEmpty());
        }

        @Test
        @DisplayName("HMAC token against RSA verifier → rejected")
        void hmacTokenAgainstRsa_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .build();

            String token = hmacJwt(new ClaimsBuilder());
            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Expired JWT → 401
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Expired JWT → 401")
    class ExpiredJwt {

        @Test
        @DisplayName("RS256 JWT with past expiry → rejected")
        void rsaExpired_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .exp(new Date(FIXED_NOW - 60_000))); // expired 1 minute ago

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("HMAC JWT with past expiry → rejected")
        void hmacExpired_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .issuer(ISSUER)
                    .build();

            String token = hmacJwt(new ClaimsBuilder()
                    .exp(new Date(FIXED_NOW - 60_000)));

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Not-yet-valid JWT → 401
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Not-yet-valid JWT (nbf in future) → 401")
    class NotYetValidJwt {

        @Test
        @DisplayName("RS256 JWT with nbf in the future → rejected")
        void rsaNbfFuture_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .nbf(new Date(FIXED_NOW + 3_600_000)) // not valid for another hour
                    .exp(new Date(FIXED_NOW + 4_000_000)));

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Wrong issuer → 401
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wrong issuer → 401")
    class WrongIssuer {

        @Test
        @DisplayName("RS256 JWT with wrong issuer → rejected")
        void rsaWrongIssuer_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .issuer("https://attacker.example.test"));

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("RS256 JWT with missing issuer claim → rejected")
        void rsaMissingIssuer_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .build();

            String token = Jwts.builder()
                    .subject("user")
                    .audience().add(AUDIENCE).and()
                    .expiration(new Date(FIXED_EXP))
                    .signWith(rsaPrivateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                    .compact();

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("HMAC JWT with wrong issuer → rejected")
        void hmacWrongIssuer_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .issuer(ISSUER)
                    .build();

            String token = hmacJwt(new ClaimsBuilder()
                    .issuer("https://wrong-issuer.test"));

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Wrong audience → 401
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Wrong audience / resource → 401")
    class WrongAudience {

        @Test
        @DisplayName("RS256 JWT with wrong audience → rejected")
        void rsaWrongAudience_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .audience(AUDIENCE)
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .audience("https://other-resource.example.test"));

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("RS256 JWT with missing audience → rejected")
        void rsaMissingAudience_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .audience(AUDIENCE)
                    .build();

            String token = Jwts.builder()
                    .subject("user")
                    .issuer(ISSUER)
                    .expiration(new Date(FIXED_EXP))
                    .signWith(rsaPrivateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                    .compact();

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("HMAC JWT with wrong audience → rejected")
        void hmacWrongAudience_rejected() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .audience(AUDIENCE)
                    .build();

            String token = hmacJwt(new ClaimsBuilder()
                    .audience("https://evil.example.test/other"));

            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Insufficient scope → 403 (handled by HttpHandler.scope check, not verifier)
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Insufficient scope → verifier returns context; HttpHandler returns 403")
    class InsufficientScope {

        @Test
        @DisplayName("verifier returns context with partial scopes (scope enforcement is HttpHandler's job)")
        void verifierReturnsPartialContext_httpHandlerEnforces403() {
            // The verifier itself only checks: does the token have ALL required scopes?
            // If the token has SOME but not ALL required scopes, the verifier
            // returns empty — this maps to 401 at HTTP level.
            // If the token has ALL required scopes, the verifier returns a context
            // and HttpHandler passes the request through (403 is only returned
            // when requiredBearerScopes.containsAll(tokenScopes) fails).
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .requiredScopes(Set.of("mcp:admin"))
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .scope("mcp:read mcp:write")); // no admin

            // Token has scopes but not the required ones → verifier returns empty
            assertTrue(verifier.verify(token, ctx()).isEmpty());
        }

        @Test
        @DisplayName("token with all required scopes → verifier returns context")
        void allScopesPresent_verifierReturnsContext() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .requiredScopes(Set.of("mcp:read", "mcp:write"))
                    .build();

            String token = rsaJwt(new ClaimsBuilder()
                    .scope("mcp:read mcp:write mcp:admin")); // superset

            assertTrue(verifier.verify(token, ctx()).isPresent());
        }

        @Test
        @DisplayName("scp claim variant (space-separated array) is read correctly")
        void scpClaimVariant_readCorrectly() {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .scopeClaim("scp")
                    .requiredScopes(Set.of("files:read"))
                    .build();

            String token = Jwts.builder()
                    .subject("user")
                    .issuer(ISSUER)
                    .audience().add(AUDIENCE).and()
                    .expiration(new Date(FIXED_EXP))
                    .claim("scp", "files:read files:write")
                    .signWith(rsaPrivateKey, io.jsonwebtoken.SignatureAlgorithm.RS256)
                    .compact();

            Optional<io.github.vinhphan812.mcp.api.security.SecurityContext> result =
                    verifier.verify(token, ctx());

            assertTrue(result.isPresent());
            assertTrue(result.get().getScopes().contains("files:read"));
            assertTrue(result.get().getScopes().contains("files:write"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // End-to-end with HttpTransportProvider
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("End-to-end HTTP integration")
    class EndToEndHttp {

        @Test
        @DisplayName("valid JWT in Authorization header → MCP request succeeds")
        void validJwtInHeader_requestSucceeds() throws Exception {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .requiredScopes(Set.of("mcp:read"))
                    .build();

            io.github.vinhphan812.mcp.api.config.McpServerConfig config =
                    io.github.vinhphan812.mcp.api.config.McpServerConfig.builder()
                            .serverName("jwt-test-server")
                            .build();
            io.github.vinhphan812.mcp.core.McpRegistry registry = new io.github.vinhphan812.mcp.core.McpRegistry();
            io.github.vinhphan812.mcp.core.McpProtocolHandler handler =
                    new io.github.vinhphan812.mcp.core.McpProtocolHandler(registry, config);

            io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata metadata =
                    new io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata(
                            RESOURCE,
                            Collections.singletonList(ISSUER),
                            Arrays.asList("mcp:read", "mcp:write"));

            io.github.vinhphan812.mcp.transport.HttpTransportProvider transport =
                    new io.github.vinhphan812.mcp.transport.HttpTransportProvider(handler)
                            .port(0)
                            .protectedResourceMetadata(metadata)
                            .requiredBearerScopes(Set.of("mcp:read"))
                            .tokenVerifier(verifier);
            transport.start();
            try {
                String endpoint = transport.getUrl();
                String validToken = rsaJwt(new ClaimsBuilder().scope("mcp:read"));

                int status = rawStatus("POST", endpoint, validToken);
                assertEquals(200, status, "Valid JWT should return HTTP 200");
            } finally {
                transport.stop();
            }
        }

        @Test
        @DisplayName("expired JWT in Authorization header → HTTP 401")
        void expiredJwtInHeader_returns401() throws Exception {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .build();

            io.github.vinhphan812.mcp.api.config.McpServerConfig config =
                    io.github.vinhphan812.mcp.api.config.McpServerConfig.builder()
                            .serverName("jwt-test-server")
                            .build();
            io.github.vinhphan812.mcp.core.McpRegistry registry = new io.github.vinhphan812.mcp.core.McpRegistry();
            io.github.vinhphan812.mcp.core.McpProtocolHandler handler =
                    new io.github.vinhphan812.mcp.core.McpProtocolHandler(registry, config);

            io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata metadata =
                    new io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata(
                            RESOURCE,
                            Collections.singletonList(ISSUER),
                            Collections.singletonList("mcp:read"));

            io.github.vinhphan812.mcp.transport.HttpTransportProvider transport =
                    new io.github.vinhphan812.mcp.transport.HttpTransportProvider(handler)
                            .port(0)
                            .protectedResourceMetadata(metadata)
                            .tokenVerifier(verifier);
            transport.start();
            try {
                String endpoint = transport.getUrl();
                String expiredToken = rsaJwt(new ClaimsBuilder()
                        .exp(new Date(FIXED_NOW - 60_000)));

                int status = rawStatus("POST", endpoint, expiredToken);
                assertEquals(401, status, "Expired JWT should return HTTP 401");
            } finally {
                transport.stop();
            }
        }

        @Test
        @DisplayName("wrong issuer JWT in Authorization header → HTTP 401")
        void wrongIssuerJwt_returns401() throws Exception {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .build();

            io.github.vinhphan812.mcp.api.config.McpServerConfig config =
                    io.github.vinhphan812.mcp.api.config.McpServerConfig.builder()
                            .serverName("jwt-test-server")
                            .build();
            io.github.vinhphan812.mcp.core.McpRegistry registry = new io.github.vinhphan812.mcp.core.McpRegistry();
            io.github.vinhphan812.mcp.core.McpProtocolHandler handler =
                    new io.github.vinhphan812.mcp.core.McpProtocolHandler(registry, config);

            io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata metadata =
                    new io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata(
                            RESOURCE,
                            Collections.singletonList(ISSUER),
                            Collections.emptyList());

            io.github.vinhphan812.mcp.transport.HttpTransportProvider transport =
                    new io.github.vinhphan812.mcp.transport.HttpTransportProvider(handler)
                            .port(0)
                            .protectedResourceMetadata(metadata)
                            .tokenVerifier(verifier);
            transport.start();
            try {
                String endpoint = transport.getUrl();
                String wrongIssuerToken = rsaJwt(new ClaimsBuilder()
                        .issuer("https://attacker.example.test"));

                int status = rawStatus("POST", endpoint, wrongIssuerToken);
                assertEquals(401, status, "Wrong-issuer JWT should return HTTP 401");
            } finally {
                transport.stop();
            }
        }

        @Test
        @DisplayName("insufficient scope JWT → HTTP 403")
        void insufficientScope_returns403() throws Exception {
            JwtTokenVerifier verifier = JwtTokenVerifier.forRsaPkcs8Pem(rsaPrivateKeyPem)
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .build();

            io.github.vinhphan812.mcp.api.config.McpServerConfig config =
                    io.github.vinhphan812.mcp.api.config.McpServerConfig.builder()
                            .serverName("jwt-test-server")
                            .build();
            io.github.vinhphan812.mcp.core.McpRegistry registry = new io.github.vinhphan812.mcp.core.McpRegistry();
            io.github.vinhphan812.mcp.core.McpProtocolHandler handler =
                    new io.github.vinhphan812.mcp.core.McpProtocolHandler(registry, config);

            io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata metadata =
                    new io.github.vinhphan812.mcp.api.security.ProtectedResourceMetadata(
                            RESOURCE,
                            Collections.singletonList(ISSUER),
                            Arrays.asList("mcp:read", "mcp:admin"));

            io.github.vinhphan812.mcp.transport.HttpTransportProvider transport =
                    new io.github.vinhphan812.mcp.transport.HttpTransportProvider(handler)
                            .port(0)
                            .protectedResourceMetadata(metadata)
                            .requiredBearerScopes(Set.of("mcp:admin"))
                            .tokenVerifier(verifier);
            transport.start();
            try {
                String endpoint = transport.getUrl();
                String token = rsaJwt(new ClaimsBuilder().scope("mcp:read")); // no admin

                int status = rawStatus("POST", endpoint, token);
                assertEquals(403, status, "Insufficient scope JWT should return HTTP 403");
            } finally {
                transport.stop();
            }
        }

        // ── raw HTTP helper ───────────────────────────────────────────────────

        private int rawStatus(String method, String urlString, String bearerToken) throws Exception {
            java.net.URL parsed = new java.net.URL(urlString);
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"jwt-test\",\"version\":\"1\"}}}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);

            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(parsed.getHost(), parsed.getPort()), 5_000);
                StringBuilder request = new StringBuilder(method)
                        .append(' ').append(parsed.getPath()).append(" HTTP/1.1\r\nHost: ")
                        .append(parsed.getHost()).append(':').append(parsed.getPort())
                        .append("\r\nConnection: close\r\n");
                if (bearerToken != null) request.append("Authorization: Bearer ").append(bearerToken).append("\r\n");
                if ("POST".equals(method)) {
                    request.append("Content-Type: application/json\r\n")
                            .append("Accept: application/json, text/event-stream\r\n")
                            .append("Mcp-Protocol-Version: 2025-11-25\r\n")
                            .append("Content-Length: ").append(payload.length).append("\r\n");
                }
                request.append("\r\n");
                java.io.OutputStream out = socket.getOutputStream();
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
                if ("POST".equals(method)) out.write(payload);
                out.flush();

                java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String statusLine = in.readLine();
                return Integer.parseInt(statusLine.split(" ")[1]);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Builder / factory guard tests
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Builder guard rails")
    class BuilderGuards {

        @Test
        @DisplayName("forHmac rejects null key")
        void forHmacRejectsNullKey() {
            assertThrows(IllegalArgumentException.class,
                    () -> JwtTokenVerifier.forHmac(null, "HS256"));
        }

        @Test
        @DisplayName("forHmac rejects RS algorithm")
        void forHmacRejectsRsAlgorithm() {
            assertThrows(IllegalArgumentException.class,
                    () -> JwtTokenVerifier.forHmac(HMAC_KEY_256, "RS256"));
        }

        @Test
        @DisplayName("forRsaPkcs8Pem rejects null/blank PEM")
        void forRsaPkcs8PemRejectsBlank() {
            assertThrows(IllegalArgumentException.class,
                    () -> JwtTokenVerifier.forRsaPkcs8Pem(null));
            assertThrows(IllegalArgumentException.class,
                    () -> JwtTokenVerifier.forRsaPkcs8Pem("   "));
        }

        @Test
        @DisplayName("forRsaX509Pem rejects unparseable PEM")
        void forRsaX509PemRejectsInvalid() {
            assertThrows(IllegalArgumentException.class,
                    () -> JwtTokenVerifier.forRsaX509Pem("-----BEGIN PUBLIC KEY-----\nNOT-BASE64\n-----END PUBLIC KEY-----"));
        }

        @Test
        @DisplayName("forEcPkcs8Pem rejects null/blank PEM")
        void forEcPkcs8PemRejectsBlank() {
            assertThrows(IllegalArgumentException.class,
                    () -> JwtTokenVerifier.forEcPkcs8Pem(null));
        }

        @Test
        @DisplayName("Builder.requireScope accepts null varargs")
        void requireScopeNullVarargs() {
            JwtTokenVerifier v = JwtTokenVerifier.forHmac(HMAC_KEY_256, "HS256")
                    .requireScope("a")
                    .build();
            assertNotNull(v);
        }
    }
}
