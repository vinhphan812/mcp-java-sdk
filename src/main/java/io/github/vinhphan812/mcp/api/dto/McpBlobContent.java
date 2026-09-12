package io.github.vinhphan812.mcp.api.dto;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable blob resource content that serialises to the MCP {@code type=blob}
 * content shape: {@code {"type":"blob","blob":"<base64>","mimeType":"<type>"}}.
 *
 * <p>Instances are constructed via {@link #of(String, String, String)}; the
 * constructor validates that {@code base64Data} is a valid Base64-encoded
 * string before storing it. This prevents silent corruption of binary data.
 *
 * <p>The class implements {@link Map} so it can be serialised directly by
 * Gson as a plain object without requiring a custom serializer.
 */
public final class McpBlobContent implements Map<String, Object> {

    /**
     * Constructs a validated blob content record.
     *
     * @param uri         resource URI
     * @param mimeType    MIME type, never {@code null}
     * @param base64Data  valid Base64-encoded bytes
     * @return a new {@code McpBlobContent}
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code base64Data} is not valid Base64
     */
    public static McpBlobContent of(String uri, String mimeType, String base64Data) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(base64Data, "base64Data");
        validateBase64(base64Data);
        return new McpBlobContent(uri, mimeType, base64Data);
    }

    /**
     * Returns {@code true} if the supplied string is valid Base64-encoded data.
     * An empty string is treated as valid (it encodes zero bytes).
     *
     * @param value the string to check
     * @return {@code true} if valid Base64, {@code false} otherwise
     */
    public static boolean isBase64(String value) {
        if (value == null) return false;
        try {
            Base64.getDecoder().decode(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void validateBase64(String base64Data) {
        if (!isBase64(base64Data)) {
            throw new IllegalArgumentException(
                    "blob data is not valid Base64: " + truncate(base64Data));
        }
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 64 ? s : s.substring(0, 64) + "…";
    }

    // ── Map<String,Object> implementation ────────────────────────────────────────

    /** Always returns {@code false}. */
    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public int size() {
        return 4;
    }

    @Override
    public boolean containsKey(Object key) {
        return "type".equals(key) || "blob".equals(key) || "mimeType".equals(key) || "uri".equals(key);
    }

    /** Always returns {@code false} (no support for value-based operations). */
    @Override
    public boolean containsValue(Object value) {
        return false;
    }

    @Override
    public Object get(Object key) {
        if ("type".equals(key)) return "blob";
        if ("blob".equals(key)) return base64Data;
        if ("mimeType".equals(key)) return mimeType;
        if ("uri".equals(key)) return uri;
        return null;
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("McpBlobContent is immutable");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("McpBlobContent is immutable");
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        throw new UnsupportedOperationException("McpBlobContent is immutable");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("McpBlobContent is immutable");
    }

    @Override
    public java.util.Set<String> keySet() {
        // Return a snapshot so the map remains effectively immutable.
        java.util.Set<String> snap = new java.util.LinkedHashSet<>();
        snap.add("type");
        snap.add("blob");
        snap.add("mimeType");
        snap.add("uri");
        return snap;
    }

    @Override
    public java.util.Collection<Object> values() {
        java.util.List<Object> snap = new java.util.ArrayList<>();
        snap.add("blob");
        snap.add(base64Data);
        snap.add(mimeType);
        snap.add(uri);
        return snap;
    }

    @Override
    public java.util.Set<Entry<String, Object>> entrySet() {
        java.util.Set<Entry<String, Object>> snap = new java.util.LinkedHashSet<>();
        snap.add(new SimpleEntry<>("type", "blob"));
        snap.add(new SimpleEntry<>("blob", base64Data));
        snap.add(new SimpleEntry<>("mimeType", mimeType));
        snap.add(new SimpleEntry<>("uri", uri));
        return snap;
    }

    // ── Object contract ──────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof McpBlobContent)) return false;
        McpBlobContent that = (McpBlobContent) o;
        return base64Data.equals(that.base64Data)
                && Objects.equals(this.mimeType, that.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base64Data, mimeType);
    }

    @Override
    public String toString() {
        return "McpBlobContent{mimeType=" + mimeType
                + ", blob=<" + base64Data.length() + " bytes base64>}";
    }

    // ── private ──────────────────────────────────────────────────────────────────

    private final String uri;
    private final String base64Data;
    private final String mimeType;

    private McpBlobContent(String uri, String mimeType, String base64Data) {
        this.uri = uri;
        this.base64Data = base64Data;
        this.mimeType = mimeType;
    }

    // Minimal Entry implementation for entrySet()
    private static final class SimpleEntry<K, V> implements Entry<K, V> {
        private final K key;
        private final V value;

        SimpleEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        public V setValue(V v) {
            throw new UnsupportedOperationException();
        }

        public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry<?, ?> e = (Entry<?, ?>) o;
            return Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        public int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }
    }
}
