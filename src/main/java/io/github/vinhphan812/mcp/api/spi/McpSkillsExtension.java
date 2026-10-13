package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.dto.SkillDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension point for MCP Skills.
 */
public interface McpSkillsExtension extends McpExtension {
    String NAMESPACE = "io.modelcontextprotocol/skills";

    @Override
    default String namespace() {
        return NAMESPACE;
    }

    @Override
    default boolean supports(String protocolVersion) {
        return "2026-07-28".equals(protocolVersion);
    }

    /** Returns the capabilities advertisement for the server/info response. */
    default Map<String, Object> advertiseCapabilities(String protocolVersion) {
        if (!supports(protocolVersion)) return null;
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("namespace", NAMESPACE);
        return caps;
    }

    /** Lists available skills. */
    List<SkillDTO> listSkills(int offset, int limit);

    /** Retrieves a skill by URI. */
    SkillDTO getSkill(String uri);

    /**
     * Returns whether this extension supports the {@code resources/directory/read} method.
     * When {@code false} (the default), the server will not advertise the
     * {@code directoryRead} capability and will respond with method-not-found
     * to directory-read requests.
     *
     * @return {@code true} if directory listing is supported
     */
    default boolean supportsDirectoryRead() {
        return false;
    }

    /**
     * Lists directory entries for a skill URI.
     * Called only when {@link #supportsDirectoryRead()} returns {@code true}.
     *
     * @param skillRootUri the skill root URI (e.g. {@code skill://refunds})
     * @return list of directory entries, or empty list if the directory is empty
     */
    default List<DirectoryEntry> listDirectory(String skillRootUri) {
        return List.of();
    }

    /** Serves manifest files. */
    String readManifestFile(String uri, String fileName);

    /** Capabilities for resource directory listing. */
    interface DirectoryEntry {
        String uri();
        String name();
        boolean isDirectory();
    }
}
