package io.github.vinhphan812.mcp.transport;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CorsOriginPolicy}.
 */
class CorsOriginPolicyTest {

    // ── Default policy ────────────────────────────────────────────────────

    @Test
    void defaultPolicy_acceptsNullOrigin() {
        assertTrue(CorsOriginPolicy.DEFAULT.accepts(null));
    }

    @Test
    void defaultPolicy_acceptsBlankOrigin() {
        assertTrue(CorsOriginPolicy.DEFAULT.accepts(""));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("   "));
    }

    @Test
    void defaultPolicy_acceptsLocalhost() {
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://localhost"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://localhost:3000"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://localhost:5173"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://LOCALHOST"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("Http://Localhost:8080"));
    }

    @Test
    void defaultPolicy_accepts127001() {
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://127.0.0.1"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://127.0.0.1:5173"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://127.0.0.1:3000"));
    }

    @Test
    void defaultPolicy_acceptsHttpsLoopback() {
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("https://localhost"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("https://localhost:443"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("https://127.0.0.1"));
    }

    @Test
    void defaultPolicy_acceptsIpv6Loopback() {
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://[::1]"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("http://[::1]:8080"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("https://[::1]"));
        assertTrue(CorsOriginPolicy.DEFAULT.accepts("https://[::1]:8443"));
    }

    @Test
    void defaultPolicy_rejectsPrivateIp() {
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://192.168.1.1"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://192.168.1.1:8080"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://10.0.0.1"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://172.16.0.1"));
    }

    @Test
    void defaultPolicy_rejectsPublicOrigins() {
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://example.com"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("https://example.com"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("https://app.example.com:8443"));
    }

    @Test
    void defaultPolicy_rejectsZeroZeroZeroZero() {
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://0.0.0.0"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("http://0.0.0.0:8080"));
    }

    // ── Explicit allowlist ─────────────────────────────────────────────────

    @Test
    void explicitAllowlist_acceptsListedNonLoopback() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(
                Collections.singleton("https://app.example.com"));
        assertTrue(policy.accepts("https://app.example.com"));
        assertTrue(policy.accepts("https://APP.EXAMPLE.COM"),
                "Should be case-insensitive");
    }

    @Test
    void explicitAllowlist_alsoAcceptsLoopback() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(
                Collections.singleton("https://app.example.com"));
        assertTrue(policy.accepts("http://localhost"),
                "Loopback should still be accepted alongside explicit list");
        assertTrue(policy.accepts("http://127.0.0.1:5173"),
                "Loopback with port should still be accepted");
    }

    @Test
    void explicitAllowlist_rejectsUnlistedNonLoopback() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(
                Collections.singleton("https://app.example.com"));
        assertFalse(policy.accepts("http://example.com"));
        assertFalse(policy.accepts("https://other.example.com"));
    }

    @Test
    void emptyExplicitAllowlist_onlyAcceptsLoopback() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(Collections.emptySet());
        assertTrue(policy.accepts("http://localhost:3000"));
        assertTrue(policy.accepts("http://[::1]:8080"));
        assertFalse(policy.accepts("http://example.com"));
    }

    @Test
    void ofNormalisesToLowerCase() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(
                new HashSet<>(Arrays.asList("HTTPS://APP.EXAMPLE.COM", "http://Other.COM")));
        assertTrue(policy.explicitOrigins().contains("https://app.example.com"));
        assertTrue(policy.explicitOrigins().contains("http://other.com"));
        assertFalse(policy.explicitOrigins().contains("HTTPS://APP.EXAMPLE.COM"),
                "Original mixed-case should not be present");
    }

    @Test
    void ofTrimsWhitespace() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(
                Collections.singleton("  https://example.com  "));
        assertTrue(policy.explicitOrigins().contains("https://example.com"));
        assertFalse(policy.explicitOrigins().contains("  https://example.com  "));
    }

    @Test
    void ofRejectsNullCollection() {
        assertThrows(IllegalArgumentException.class,
                () -> CorsOriginPolicy.of(null));
    }

    @Test
    void ofRejectsNullElements() {
        assertThrows(IllegalArgumentException.class,
                () -> CorsOriginPolicy.of(Arrays.asList("http://example.com", null)));
    }

    @Test
    void ofRejectsBlankElements() {
        assertThrows(IllegalArgumentException.class,
                () -> CorsOriginPolicy.of(Arrays.asList("http://example.com", "")));
        assertThrows(IllegalArgumentException.class,
                () -> CorsOriginPolicy.of(Arrays.asList("http://example.com", "   ")));
    }

    @Test
    void explicitOriginsIsUnmodifiable() {
        CorsOriginPolicy policy = CorsOriginPolicy.of(
                Collections.singleton("https://example.com"));
        assertThrows(UnsupportedOperationException.class,
                () -> policy.explicitOrigins().add("http://evil.com"));
    }

    // ── Malformed origins ────────────────────────────────────────────────

    @Test
    void acceptsHandlesMalformedOrigins() {
        // Malformed → not loopback → not in allowlist → rejected
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("not-a-url"));
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("localhost")); // missing scheme
        assertFalse(CorsOriginPolicy.DEFAULT.accepts("://example.com")); // missing scheme part
    }

    // ── allowedOriginValue ────────────────────────────────────────────────

    @Test
    void allowedOriginValue_returnsNullForAbsentOrigin() {
        assertNull(CorsOriginPolicy.DEFAULT.allowedOriginValue(null));
        assertNull(CorsOriginPolicy.DEFAULT.allowedOriginValue(""));
        assertNull(CorsOriginPolicy.DEFAULT.allowedOriginValue("   "));
    }

    @Test
    void allowedOriginValue_returnsOriginForAccepted() {
        assertEquals("http://localhost",
                CorsOriginPolicy.DEFAULT.allowedOriginValue("http://localhost"));
        assertEquals("http://localhost:3000",
                CorsOriginPolicy.DEFAULT.allowedOriginValue("http://localhost:3000"));
    }

    @Test
    void allowedOriginValue_returnsNullStringForRejected() {
        assertEquals("null",
                CorsOriginPolicy.DEFAULT.allowedOriginValue("http://example.com"));
    }

    // ── equals / hashCode / toString ─────────────────────────────────────

    @Test
    void equalsIsStructural() {
        CorsOriginPolicy a = CorsOriginPolicy.of(Collections.singleton("http://a.com"));
        CorsOriginPolicy b = CorsOriginPolicy.of(Collections.singleton("http://a.com"));
        CorsOriginPolicy c = CorsOriginPolicy.of(Collections.singleton("http://b.com"));
        CorsOriginPolicy d = CorsOriginPolicy.DEFAULT;

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertEquals(CorsOriginPolicy.DEFAULT, CorsOriginPolicy.of(Collections.emptySet()),
                "DEFAULT and empty-set policy must be equal");
    }

    @Test
    void hashCodeIsConsistent() {
        CorsOriginPolicy a = CorsOriginPolicy.of(Collections.singleton("http://a.com"));
        CorsOriginPolicy b = CorsOriginPolicy.of(Collections.singleton("http://a.com"));
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toStringDescribesPolicy() {
        String s = CorsOriginPolicy.DEFAULT.toString();
        assertTrue(s.contains("CorsOriginPolicy"));
        assertTrue(s.contains("explicitOrigins"));
    }
}
