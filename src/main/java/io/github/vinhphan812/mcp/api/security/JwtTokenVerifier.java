package io.github.vinhphan812.mcp.api.security;

import io.github.vinhphan812.mcp.api.utils.McpGson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Local JWT verifier that implements the {@link TokenVerifier} SPI.
 *
 * <p>Supports RSA (RS256 / RS384 / RS512) and HMAC (HS256 / HS384 / HS512) algorithms.
 * ES256 (ECDSA P-256) and EdDSA are supported when the JWK key material is provided as
 * a {@code byte[]} and the JVM has a suitable {@code KeyFactory} provider installed.
 *
 * <p>Validation checklist (each optional, configurable via {@link Builder}):
 * <ul>
 *   <li>signature (fails 401 — {@code invalid_token})</li>
 *   <li>{@code iss} issuer claim (fails 401)</li>
 *   <li>{@code exp} expiry instant (fails 401)</li>
 *   <li>{@code nbf} not-before instant (fails 401)</li>
 *   <li>{@code aud} audience / resource (fails 401)</li>
 *   <li>required scopes from the {@code scope} or {@code scp} claim (fails 403 — {@code insufficient_scope})</li>
 * </ul>
 *
 * <p>Example — RSA verification:
 * <pre>{@code
 * JwtTokenVerifier verifier = JwtTokenVerifier.forRsa(pkcs8Pem)
 *     .issuer("https://issuer.example.test")
 *     .audience("https://resource.example.test/mcp")
 *     .requiredScopes(Set.of("mcp:read"))
 *     .build();
 * }</pre>
 *
 * <p>Example — HMAC verification:
 * <pre>{@code
 * JwtTokenVerifier verifier = JwtTokenVerifier.forHmac(hmacKeyBytes, "HS256")
 *     .issuer("https://issuer.example.test")
 *     .build();
 * }</pre>
 *
 * @see TokenVerifier
 * @see McpSecurityContext
 */
public final class JwtTokenVerifier implements TokenVerifier {

    /**
     * Algorithms accepted by this verifier.  Add entries here as support expands.
     */
    private static final Set<String> SUPPORTED = Set.of(
            "HS256", "HS384", "HS512",
            "RS256", "RS384", "RS512",
            "ES256", "ES384", "ES512",
            "PS256", "PS384", "PS512"
    );

    // ── builder fields ─────────────────────────────────────────────────────────
    private final SecretKey  hmacKey;    // non-null when HMAC key was supplied
    private final PublicKey   publicKey;   // non-null when asymmetric key was supplied
    private final String     expectedIssuer;
    private final String     expectedAudience;
    private final Set<String> requiredScopes;
    private final String     scopeClaimName;

    private JwtTokenVerifier(Builder b) {
        this.hmacKey        = b.hmacKey;
        this.publicKey      = b.publicKey;
        this.expectedIssuer    = b.expectedIssuer;
        this.expectedAudience  = b.expectedAudience;
        this.requiredScopes    = Collections.unmodifiableSet(new LinkedHashSet<>(b.requiredScopes));
        this.scopeClaimName    = b.scopeClaimName == null ? "scope" : b.scopeClaimName.trim();
    }

    // ── factory: HMAC (symmetric) ───────────────────────────────────────────────

    /**
     * Creates a verifier for HMAC algorithms (HS256 / HS384 / HS512).
     *
     * @param rawKey    shared secret bytes; must be &ge; 32 bytes for HS256
     * @param algorithm case-insensitive algorithm name (e.g. {@code "HS256"})
     * @return a builder pre-populated with the HMAC key
     * @throws IllegalArgumentException if rawKey is null/empty or algorithm is unsupported
     */
    public static Builder forHmac(byte[] rawKey, String algorithm) {
        if (rawKey == null || rawKey.length == 0)
            throw new IllegalArgumentException("rawKey cannot be null or empty");
        algorithm = algorithm.toUpperCase(Locale.ROOT);
        validateAlgorithm(algorithm);
        if (!algorithm.startsWith("HS"))
            throw new IllegalArgumentException("forHmac() requires an HS* algorithm, got: " + algorithm);
        // Wrap with JJWT's HMAC key which implements javax.crypto.SecretKey
        SecretKey secretKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(rawKey);
        return new Builder(secretKey, null);
    }

    // ── factory: RSA (asymmetric) ───────────────────────────────────────────────

    /**
     * Creates a verifier for RSA algorithms (RS256 / RS384 / RS512 / PS256 / PS384 / PS512).
     *
     * <p>Accepts a PKCS#8 PEM containing either a private key or a public key.
     * When a private key is supplied the public key is derived from it — callers
     * do not need to split the PEM.
     *
     * @param pkcs8Pem PKCS#8 PEM string (-----BEGIN PRIVATE KEY----- or -----BEGIN PUBLIC KEY-----)
     * @return a builder pre-populated with the RSA public key
     * @throws IllegalArgumentException if the PEM cannot be parsed
     */
    public static Builder forRsaPkcs8Pem(String pkcs8Pem) {
        if (pkcs8Pem == null || pkcs8Pem.trim().isEmpty())
            throw new IllegalArgumentException("PKCS#8 PEM cannot be null or blank");
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(trimmablePem(pkcs8Pem));
            // Try X.509 spec first (SubjectPublicKeyInfo — "-----BEGIN PUBLIC KEY-----")
            KeyFactory kf = KeyFactory.getInstance("RSA");
            try {
                PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(decoded));
                return new Builder(null, pub);
            } catch (Exception ignored) {
                // Fall through: try PKCS#8 private key
            }
            // PKCS#8 private key: use PKCS8EncodedKeySpec, then derive public key
            try {
                java.security.interfaces.RSAPrivateCrtKey rsaKey =
                        (java.security.interfaces.RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
                // Derive the public key from the RSA CRT components
                java.security.spec.RSAPublicKeySpec pubSpec =
                        new java.security.spec.RSAPublicKeySpec(rsaKey.getModulus(), rsaKey.getPublicExponent());
                return new Builder(null, kf.generatePublic(pubSpec));
            } catch (ClassCastException ignored) {
                // Not an RSA CRT key
            }
            throw new IllegalArgumentException(
                    "RSA private key does not implement RSAPrivateCrtKey; "
                    + "only RSA CRT private keys are supported for PKCS#8 PEM input. "
                    + "Use RSAPrivateCrtKey-compatible key export, or provide an X.509 public key PEM.");
        } catch (IllegalArgumentException e) {
            throw e; // re-throw our own informative error
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse RSA PEM: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a verifier for RSA algorithms using an X.509 / SubjectPublicKeyInfo PEM.
     *
     * @param x509Pem X.509 PEM string (-----BEGIN PUBLIC KEY----- … -----END PUBLIC KEY-----)
     * @return a builder pre-populated with the RSA public key
     */
    public static Builder forRsaX509Pem(String x509Pem) {
        if (x509Pem == null || x509Pem.trim().isEmpty())
            throw new IllegalArgumentException("X.509 PEM cannot be null or blank");
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(trimmablePem(x509Pem));
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(decoded));
            return new Builder(null, publicKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse X.509 PEM: " + e.getMessage(), e);
        }
    }

    // ── factory: EC (asymmetric) ─────────────────────────────────────────────────

    /**
     * Creates a verifier for ECDSA algorithms (ES256 / ES384 / ES512) from a PKCS#8 PEM.
     *
     * @param pkcs8Pem PKCS#8 PEM string
     * @return a builder pre-populated with the EC public key
     */
    public static Builder forEcPkcs8Pem(String pkcs8Pem) {
        if (pkcs8Pem == null || pkcs8Pem.trim().isEmpty())
            throw new IllegalArgumentException("PKCS#8 PEM cannot be null or blank");
        try {
            String base64 = trimmablePem(pkcs8Pem);
            byte[] decoded = Base64.getMimeDecoder().decode(base64);
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(decoded));
            return new Builder(null, publicKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse EC PKCS#8 PEM: " + e.getMessage(), e);
        }
    }

    // ── core verification ────────────────────────────────────────────────────────

    /**
     * Verifies the given JWT and returns a populated {@link McpSecurityContext} on success,
     * or {@link Optional#empty()} if verification fails (any reason).
     *
     * @param accessToken raw JWT string (Bearer token without the "Bearer " prefix)
     * @param context     request context carrying the expected resource and path
     * @return populated context on success; empty if any check fails
     */
    @Override
    public Optional<SecurityContext> verify(String accessToken, TokenVerificationContext context) {
        if (accessToken == null || accessToken.trim().isEmpty()) return Optional.empty();
        try {
            io.jsonwebtoken.JwtParserBuilder parserBuilder = Jwts.parser();
            if (hmacKey != null) {
                parserBuilder.verifyWith(hmacKey);
            } else {
                parserBuilder.verifyWith(publicKey);
            }
            Claims claims = parserBuilder.build()
                    .parseSignedClaims(accessToken)
                    .getPayload();

            // ── issuer ──────────────────────────────────────────────────────────
            if (expectedIssuer != null) {
                String iss = claims.getIssuer();
                if (iss == null || !iss.equals(expectedIssuer)) return Optional.empty();
            }

            // ── expiry (exp) ───────────────────────────────────────────────────
            Date exp = claims.getExpiration();
            if (exp != null && exp.before(new Date())) return Optional.empty();

            // ── not-before (nbf) ───────────────────────────────────────────────
            Date nbf = claims.getNotBefore();
            if (nbf != null && nbf.after(new Date())) return Optional.empty();

            // ── audience / resource (aud) ──────────────────────────────────────
            if (expectedAudience != null) {
                Set<String> aud = new LinkedHashSet<>(claims.getAudience() == null
                        ? Collections.emptyList() : claims.getAudience());
                if (aud.isEmpty() || !aud.contains(expectedAudience)) return Optional.empty();
            }

            // ── required scopes ─────────────────────────────────────────────────
            if (!requiredScopes.isEmpty()) {
                Set<String> tokenScopes = extractScopes(claims);
                if (!tokenScopes.containsAll(requiredScopes)) return Optional.empty();
            }

            return Optional.of(new McpSecurityContext(
                    claims.getSubject() != null ? claims.getSubject() : "jwt-unknown-subject",
                    extractScopes(claims),
                    claimsToMap(claims)));

        } catch (JwtException | IllegalArgumentException e) {
            // Covers: signature mismatch, malformed JWT, missing required claims, etc.
            return Optional.empty();
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    /** Extracts the scope set from the claims. Tries "scope" first, then "scp". */
    private Set<String> extractScopes(Claims claims) {
        if ("scope".equals(scopeClaimName)) {
            Object scope = claims.get("scope");
            if (scope instanceof String) {
                return new LinkedHashSet<>(Arrays.asList(((String) scope).split("\\s+")));
            }
            if (scope instanceof Collection) {
                Set<String> result = new LinkedHashSet<>();
                for (Object s : (Collection<?>) scope) if (s != null) result.add(s.toString());
                return result;
            }
        } else {
            // "scp" claim — space-separated or array
            Object scp = claims.get("scp");
            if (scp instanceof String) {
                return new LinkedHashSet<>(Arrays.asList(((String) scp).split("\\s+")));
            }
            if (scp instanceof Collection) {
                Set<String> result = new LinkedHashSet<>();
                for (Object s : (Collection<?>) scp) if (s != null) result.add(s.toString());
                return result;
            }
        }
        return Collections.emptySet();
    }

    /** Converts Claims to a plain Map for SecurityContext.getClaims(). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> claimsToMap(Claims claims) {
        // In JJWT 0.12 the Claims object implements Map directly
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) claims));
        } catch (ClassCastException e) {
            // Fallback: round-trip through JSON
            return McpGson.get().fromJson(
                    McpGson.get().toJson(claims),
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
        }
    }

    /** Strips PEM headers/footers and whitespace from a Base64-encoded block. */
    private static String trimmablePem(String pem) {
        StringBuilder sb = new StringBuilder();
        for (String line : pem.split("\n")) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("-----")) sb.append(l);
        }
        return sb.toString();
    }

    private static void validateAlgorithm(String algorithm) {
        if (!SUPPORTED.contains(algorithm))
            throw new IllegalArgumentException("Unsupported JWT algorithm: " + algorithm
                    + ". Supported: " + SUPPORTED);
    }

    // ── builder ─────────────────────────────────────────────────────────────────

    /**
     * Mutable builder for {@link JwtTokenVerifier}.
     *
     * <p>Obtain via one of the factory methods ({@link #forHmac}, {@link #forRsaPkcs8Pem},
     * {@link #forRsaX509Pem}, {@link #forEcPkcs8Pem}), then chain the optional validators.
     */
    public static final class Builder {
        private final SecretKey  hmacKey;
        private final PublicKey   publicKey;
        private String     expectedIssuer;
        private String    expectedAudience;
        private Set<String> requiredScopes = new LinkedHashSet<>();
        private String    scopeClaimName;

        Builder(SecretKey hmacKey, PublicKey publicKey) {
            if (hmacKey == null && publicKey == null)
                throw new IllegalArgumentException("at least one of hmacKey or publicKey must be non-null");
            this.hmacKey   = hmacKey;
            this.publicKey = publicKey;
        }

        /** Expected {@code iss} claim value. Pass null to skip issuer validation. */
        public Builder issuer(String value) {
            this.expectedIssuer = value;
            return this;
        }

        /** Expected {@code aud} claim value. Pass null to skip audience validation. */
        public Builder audience(String value) {
            this.expectedAudience = value;
            return this;
        }

        /**
         * Scopes that must all be present in the token.
         * Empty set (default) skips the scope check.
         *
         * <p>Scopes are read from the {@code scope} claim (space-separated string) by default.
         * Call {@link #scopeClaim(String)} to switch to {@code scp}.
         */
        public Builder requiredScopes(Set<String> values) {
            this.requiredScopes = values == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(values);
            return this;
        }

        /**
         * Adds individual required scopes. Shorthand for
         * {@code requiredScopes(Set.of(first, rest))}.
         */
        public Builder requireScope(String first, String... rest) {
            this.requiredScopes.add(first);
            if (rest != null) for (String s : rest) this.requiredScopes.add(s);
            return this;
        }

        /**
         * Configures which claim name carries the scopes.
         * Default is {@code "scope"} (space-separated string). Set to {@code "scp"}
         * to use the alternative space-separated array claim name used by some providers.
         *
         * @param name claim name, e.g. {@code "scope"} or {@code "scp"}
         */
        public Builder scopeClaim(String name) {
            this.scopeClaimName = name;
            return this;
        }

        /** Builds an immutable {@link JwtTokenVerifier} from this builder. */
        public JwtTokenVerifier build() {
            return new JwtTokenVerifier(this);
        }
    }
}
