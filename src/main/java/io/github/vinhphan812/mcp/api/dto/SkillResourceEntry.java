package io.github.vinhphan812.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A skill file resource entry returned in the {@code resources[]} array of
 * a {@link SkillDTO}. Each entry describes one file with its URI, name,
 * cryptographic digest, and byte size.
 *
 * <p>For static skills, entries are computed once and cached. For dynamic skills,
 * a sentinel entry with {@code uri: "dynamic"} signals that content may vary.
 *
 * @see SkillDTO
 */
public final class SkillResourceEntry {

    /**
     * URI of this resource, relative to the skill root.
     * Special value {@code "dynamic"} indicates a dynamic skill.
     */
    public final String uri;

    /**
     * Display name of this resource (file name without path).
     */
    public final String name;

    /**
     * SHA-256 digest of the file content, formatted as {@code "sha256:<hex>"}.
     * {@code null} for dynamic skills.
     */
    public final String digest;

    /**
     * Exact byte length of the file content.
     * {@code -1} for dynamic skills.
     */
    public final long size;

    public SkillResourceEntry(String uri, String name, String digest, long size) {
        this.uri = uri;
        this.name = name;
        this.digest = digest;
        this.size = size;
    }

    /**
     * Sentinel entry for dynamic skills.
     */
    public static SkillResourceEntry dynamic() {
        return new SkillResourceEntry("dynamic", "dynamic", null, -1);
    }

    /** Returns a JSON-compatible Map representation for JSON-RPC responses. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", uri);
        // The stable Skills wire contract intentionally omits the legacy name field.
        if (digest != null) m.put("digest", digest);
        if (size >= 0) m.put("size", size);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkillResourceEntry that = (SkillResourceEntry) o;
        return size == that.size
                && Objects.equals(uri, that.uri)
                && Objects.equals(name, that.name)
                && Objects.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, name, digest, size);
    }

    @Override
    public String toString() {
        return "SkillResourceEntry{uri=" + uri + ", name=" + name
                + ", digest=" + digest + ", size=" + size + "}";
    }
}
