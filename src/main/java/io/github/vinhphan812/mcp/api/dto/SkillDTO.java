package io.github.vinhphan812.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for an MCP Skill.
 *
 * <p>Wire shape (SEP-2640 stable spec):
 * <ul>
 *   <li>{@code uri} — skill:// URI</li>
 *   <li>{@code name} — skill identifier</li>
 *   <li>{@code title} — optional display title</li>
 *   <li>{@code description} — optional description</li>
 *   <li>{@code manifest} — optional skill manifest</li>
 *   <li>{@code resources} — list of {@link SkillResourceEntry} objects</li>
 * </ul>
 *
 * <p>Legacy fields {@code files} and {@code digests} are accepted in incoming requests
 * for backward compatibility but are never serialised in responses.
 */
public final class SkillDTO {
    public final String uri;
    public final String name;
    public final String title;
    public final String description;
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
    /** Ordered list of resource entries; never null. */
    public final List<SkillResourceEntry> resources;

    /**
     * Full constructor with all fields including the new {@code resources} array.
     *
     * @param uri       skill URI
     * @param name      skill name
     * @param title     optional title (may be null)
     * @param description optional description (may be null)
     * @param manifest  optional manifest (may be null)
     * @param resources list of resource entries (may be null — treated as empty)
     */
    public SkillDTO(String uri, String name, String title, String description,
                    Map<String, Object> manifest, List<SkillResourceEntry> resources) {
        this.uri = uri;
        this.name = name;
        this.title = title;
        this.description = description;
        this.manifest = manifest;
        this.files = null;
        this.digests = null;
        this.resources = resources;
    }

    /**
     * Legacy constructor accepting {@code files} and {@code digests} maps.
     * The maps are stored for backward compatibility but are NOT serialised
     * in {@link #toMap()}.
     *
     * @deprecated Use {@link #SkillDTO(String, String, String, String, Map, List)} instead.
     */
    @Deprecated
    public SkillDTO(String uri, String name, String title, String description,
                    Map<String, Object> manifest,
                    Map<String, String> files, Map<String, String> digests) {
        this.uri = uri;
        this.name = name;
        this.title = title;
        this.description = description;
        this.manifest = manifest;
        this.files = files;
        this.digests = digests;
        this.resources = null;
    }

    /** Returns a JSON-compatible Map representation for JSON-RPC responses. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", uri);
        m.put("name", name);
        if (title != null) m.put("title", title);
        if (description != null) m.put("description", description);
        if (manifest != null) m.put("manifest", manifest);
        // Stable spec: always use resources[]; never serialise legacy files/digests
        if (resources != null) m.put("resources", resources);
        return m;
    }
}
