package io.github.vinhphan812.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for an MCP Skill.
 *
 * <p>Wire shape (SEP-2640 stable spec):
 * <ul>
 *   <li>{@code uri} — skill:// URI pointing to SKILL.md</li>
 *   <li>{@code frontmatter} — object with name, description, title, author, etc.</li>
 *   <li>{@code resources} — list of {@link SkillResourceEntry} objects, or literal "dynamic"</li>
 * </ul>
 *
 * <p>Legacy fields {@code name}, {@code title}, {@code description}, {@code manifest},
 * {@code files} and {@code digests} are accepted in incoming requests for backward
 * compatibility but are never serialised in responses.
 */
public final class SkillDTO {
    /** Stable-spec: skill URI (must end with /SKILL.md for root skills). */
    public final String uri;

    /** Stable-spec: frontmatter object. Required keys: name, description. */
    public final Map<String, Object> frontmatter;

    /**
     * Stable-spec: resources array or literal "dynamic" string.
     * When a dynamic skill, this is the string "dynamic".
     * When a static skill, this is a List of {@link SkillResourceEntry}.
     */
    public final Object resources;

    /**
     * @deprecated Legacy source compatibility. Use {@link #frontmatter} instead.
     * Accepted in incoming requests only; never serialised in responses.
     */
    @Deprecated
    public final String name;

    /**
     * @deprecated Legacy source compatibility. Use {@link #frontmatter} instead.
     * Accepted in incoming requests only; never serialised in responses.
     */
    @Deprecated
    public final String title;

    /**
     * @deprecated Legacy source compatibility. Use {@link #frontmatter} instead.
     * Accepted in incoming requests only; never serialised in responses.
     */
    @Deprecated
    public final String description;

    /**
     * @deprecated Legacy source compatibility. Use {@link #frontmatter} instead.
     * Accepted in incoming requests only; never serialised in responses.
     */
    @Deprecated
    public final Map<String, Object> manifest;

    /**
     * @deprecated Use {@link #resources} instead. Accepted in incoming requests for
     *     backward compatibility only; never serialised in responses.
     */
    @Deprecated
    public final Map<String, String> files;

    /**
     * @deprecated Use {@link #resources} instead. Accepted in incoming requests for
     *     backward compatibility only; never serialised in responses.
     */
    @Deprecated
    public final Map<String, String> digests;

    /**
     * Full constructor with all fields including the new {@code frontmatter} and
     * {@code resources} (which may be a List or the literal "dynamic" string).
     *
     * @param uri        skill URI
     * @param frontmatter skill metadata object (may be null — will be computed from legacy fields)
     * @param resources  list of resource entries, or literal "dynamic" for dynamic skills
     * @param name       (deprecated) skill name — used to build frontmatter if not provided
     * @param title      (deprecated) optional title — used to build frontmatter if not provided
     * @param description (deprecated) optional description — used to build frontmatter if not provided
     * @param manifest   (deprecated) optional manifest — used to build frontmatter if not provided
     */
    public SkillDTO(String uri, Map<String, Object> frontmatter, Object resources,
                    String name, String title, String description, Map<String, Object> manifest) {
        this.uri = uri;
        this.frontmatter = frontmatter != null ? frontmatter : buildFrontmatter(name, title, description, manifest);
        this.resources = resources;
        this.name = name;
        this.title = title;
        this.description = description;
        this.manifest = manifest;
        this.files = null;
        this.digests = null;
    }

    /**
     * Legacy constructor accepting {@code name}, {@code title}, {@code description},
     * {@code manifest}, and resource entry list.
     *
     * @deprecated Use {@link #SkillDTO(String, Map, Object, String, String, String, Map)} instead.
     */
    @Deprecated
    public SkillDTO(String uri, String name, String title, String description,
                    Map<String, Object> manifest, List<SkillResourceEntry> resources) {
        this(uri, null, resources, name, title, description, manifest);
    }

    /**
     * Legacy constructor accepting {@code files} and {@code digests} maps.
     *
     * @deprecated Use {@link #SkillDTO(String, Map, Object, String, String, String, Map)} instead.
     */
    @Deprecated
    public SkillDTO(String uri, String name, String title, String description,
                    Map<String, Object> manifest,
                    Map<String, String> files, Map<String, String> digests) {
        this.uri = uri;
        this.frontmatter = buildFrontmatter(name, title, description, manifest);
        this.resources = null;
        this.name = name;
        this.title = title;
        this.description = description;
        this.manifest = manifest;
        this.files = files;
        this.digests = digests;
    }

    /**
     * Builds frontmatter from legacy fields. Required keys: name, description.
     */
    private static Map<String, Object> buildFrontmatter(String name, String title,
            String description, Map<String, Object> manifest) {
        if (manifest != null && !manifest.isEmpty()) {
            // If manifest is provided, use it as frontmatter but ensure name/description
            Map<String, Object> fm = new LinkedHashMap<>(manifest);
            if (name != null && !fm.containsKey("name")) fm.put("name", name);
            if (title != null && !fm.containsKey("title")) fm.put("title", title);
            if (description != null && !fm.containsKey("description")) fm.put("description", description);
            return fm;
        }
        Map<String, Object> fm = new LinkedHashMap<>();
        if (name != null) fm.put("name", name);
        if (title != null) fm.put("title", title);
        if (description != null) fm.put("description", description);
        return fm.isEmpty() ? null : fm;
    }

    /**
     * Returns a JSON-compatible Map representation for JSON-RPC responses.
     *
     * <p>Wire format (SEP-2640 stable spec):
     * <ul>
     *   <li>uri</li>
     *   <li>frontmatter (object with name, description, etc.)</li>
     *   <li>resources: either a list of entries, or the literal string "dynamic"</li>
     * </ul>
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", uri);

        // Stable spec: frontmatter instead of top-level name/title/description/manifest
        if (frontmatter != null && !frontmatter.isEmpty()) {
            m.put("frontmatter", frontmatter);
        }

        // Stable spec: resources may be List or literal "dynamic" string
        if (resources != null) {
            if (resources instanceof List) {
                List<?> entries = (List<?>) resources;
                // Check if it's a dynamic skill sentinel
                if (entries.size() == 1 && entries.get(0) instanceof SkillResourceEntry) {
                    SkillResourceEntry entry = (SkillResourceEntry) entries.get(0);
                    if ("dynamic".equals(entry.uri)) {
                        // B3: dynamic skill serializes as literal string "dynamic"
                        m.put("resources", "dynamic");
                        return m;
                    }
                }
                // Convert List to wire format (SkillResourceEntry.toMap())
                List<Object> wireEntries = new java.util.ArrayList<>();
                for (Object entry : entries) {
                    if (entry instanceof SkillResourceEntry) {
                        wireEntries.add(((SkillResourceEntry) entry).toMap());
                    } else {
                        wireEntries.add(entry);
                    }
                }
                m.put("resources", wireEntries);
            } else {
                // Already a string (e.g., "dynamic") or other object
                m.put("resources", resources);
            }
        }

        return m;
    }
}
