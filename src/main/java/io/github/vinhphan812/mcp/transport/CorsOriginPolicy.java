package io.github.vinhphan812.mcp.transport;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates HTTP {@code Origin} headers against a loopback-by-default policy.
 *
 * <p>Loopback rule: an origin whose host resolves to a loopback address
 * ({@code 127.0.0.0/8} or {@code ::1}) is always accepted, regardless of
 * port. This covers {@code localhost}, {@code 127.0.0.1}, and {@code [::1]}.
 *
 * <p>Non-loopback origins are accepted only when present in the explicit
 * allowlist. Matching is case-insensitive and exact.
 *
 * <p>An {@code Origin} value that cannot be parsed is treated as non-loopback
 * and tested against the allowlist.
 *
 * <p>This class is immutable and thread-safe.
 */
public final class CorsOriginPolicy {

    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    /**
     * Canonical default policy: loopback origins are accepted; no additional
     * explicit origins.
     */
    public static final CorsOriginPolicy DEFAULT = new CorsOriginPolicy(
            Collections.emptySet());

    /**
     * Immutable set of explicitly allowed non-loopback origins, normalised to
     * lowercase and trimmed. Empty when no non-loopback origins are configured.
     */
    private final Set<String> explicitOrigins;

    private CorsOriginPolicy(Set<String> explicitOrigins) {
        this.explicitOrigins = explicitOrigins;
    }

    /**
     * Builds a policy with the given explicit non-loopback origins.
     *
     * @param explicitOrigins allowed origins beyond the loopback rule; each
     *                        value must be a well-formed {@code scheme://host[:port]}
     *                        string, non-null and non-blank
     * @return a new policy
     * @throws IllegalArgumentException if any element is null or blank
     */
    public static CorsOriginPolicy of(Collection<String> explicitOrigins) {
        if (explicitOrigins == null) {
            throw new IllegalArgumentException("explicitOrigins cannot be null");
        }
        Set<String> normalised = new HashSet<>();
        for (String o : explicitOrigins) {
            if (o == null || o.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "explicitOrigins cannot contain null or blank values");
            }
            normalised.add(o.trim().toLowerCase(Locale.ROOT));
        }
        return new CorsOriginPolicy(Collections.unmodifiableSet(normalised));
    }

    /**
     * Returns whether the given {@code Origin} header value should be accepted.
     *
     * @param origin the {@code Origin} header value, or {@code null} / blank
     * @return {@code true} when the origin is accepted
     */
    public boolean accepts(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            // No origin → non-browser request; accept.
            return true;
        }
        String trimmed = origin.trim();
        // Check loopback rule first (fast path for local dev).
        if (isLoopback(trimmed)) {
            return true;
        }
        // Fall back to explicit allowlist (exact, case-insensitive).
        return explicitOrigins.contains(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the {@code Access-Control-Allow-Origin} value to echo back for a
     * given request origin.
     *
     * @param origin the {@code Origin} header value, or {@code null} / blank
     * @return the allowed origin value, or {@code "null"} when the origin is
     *         rejected and no fallback origin should be cached
     */
    public String allowedOriginValue(String origin) {
        if (origin == null || origin.trim().isEmpty()) {
            return null; // no Vary-based CORS header needed
        }
        return accepts(origin) ? origin.trim() : "null";
    }

    /**
     * Returns the immutable set of configured non-loopback explicit origins.
     *
     * @return unmodifiable set of normalised origins, never {@code null}
     */
    public Set<String> explicitOrigins() {
        return explicitOrigins;
    }

    // ── private helpers ────────────────────────────────────────────────────

    /**
     * Returns true when the origin's host resolves to a loopback address.
     * Supports IPv4 ({@code 127.0.0.0/8}), IPv6 ({@code ::1}), and
     * hostnames that resolve to loopback ({@code localhost}).
     *
     * <p>The port is ignored for loopback matching.
     */
    private boolean isLoopback(String origin) {
        URI uri = parse(origin);
        if (uri == null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress();
        } catch (Exception e) {
            // Unknown host or resolution failure → not loopback.
            return false;
        }
    }

    /**
     * Parses an origin string into a {@link URI}.
     *
     * <p>Tolerates origins without a trailing slash (e.g., {@code http://localhost}).
     *
     * @param origin origin string to parse
     * @return parsed URI, or {@code null} when the string cannot be parsed
     */
    private static URI parse(String origin) {
        // Ensure the URI has a path so that URI(String) does not treat
        // scheme://host:port as an opaque URI (no host component).
        String s = origin;
        if (!origin.contains("/")) {
            s = origin + "/";
        }
        try {
            URI uri = new URI(s);
            // Reject if the URI parser produced an opaque scheme (malformed).
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "CorsOriginPolicy{" +
                "explicitOrigins=" + explicitOrigins +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CorsOriginPolicy)) return false;
        CorsOriginPolicy that = (CorsOriginPolicy) o;
        return Objects.equals(explicitOrigins, that.explicitOrigins);
    }

    @Override
    public int hashCode() {
        return Objects.hash(explicitOrigins);
    }
}
