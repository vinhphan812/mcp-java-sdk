package io.github.vinhphan812.mcp.api.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data Transfer Object for an MCP Skill.
 */
public final class SkillDTO {
    public final String uri;
    public final String name;
    public final String title;
    public final String description;
    public final Map<String, Object> manifest;
    public final Map<String, String> files;
    public final Map<String, String> digests;

    public SkillDTO(String uri, String name, String title, String description,
                    Map<String, Object> manifest, Map<String, String> files, Map<String, String> digests) {
        this.uri = uri;
        this.name = name;
        this.title = title;
        this.description = description;
        this.manifest = manifest;
        this.files = files;
        this.digests = digests;
    }

    /** Returns a JSON-compatible Map representation for JSON-RPC responses. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", uri);
        m.put("name", name);
        if (title != null) m.put("title", title);
        if (description != null) m.put("description", description);
        if (manifest != null) m.put("manifest", manifest);
        if (files != null) m.put("files", files);
        if (digests != null) m.put("digests", digests);
        return m;
    }
}
