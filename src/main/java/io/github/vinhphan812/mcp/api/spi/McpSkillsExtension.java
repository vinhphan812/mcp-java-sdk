package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.dto.SkillDTO;
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
        return false;
    }

    /** Lists available skills. */
    List<SkillDTO> listSkills(int offset, int limit);

    /** Retrieves a skill by URI. */
    SkillDTO getSkill(String uri);

    /** Serves manifest files. */
    String readManifestFile(String uri, String fileName);

    /** Capabilities for resource directory listing. */
    interface DirectoryEntry {
        String uri();
        String name();
        boolean isDirectory();
    }
}
