package io.github.vinhphan812.mcp.api.spi;

import io.github.vinhphan812.mcp.api.dto.DirectoryPage;
import io.github.vinhphan812.mcp.api.dto.ListSkillsResult;
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

    default Map<String, Object> advertiseCapabilities(String protocolVersion) {
        if (!supports(protocolVersion)) return null;
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("namespace", NAMESPACE);
        return caps;
    }

    ListSkillsResult listSkills(String cursor, int limit);

    @Deprecated
    default List<SkillDTO> listSkills(int offset, int limit) {
        String cursor = offset == 0 ? null : String.valueOf(offset);
        ListSkillsResult r = listSkills(cursor, limit);
        return r.skills;
    }

    SkillDTO getSkill(String uri);

    default boolean supportsDirectoryRead() {
        return false;
    }

    default List<DirectoryEntry> listDirectory(String skillRootUri) {
        return List.of();
    }

    String readManifestFile(String uri, String fileName);

    interface DirectoryEntry {
        String uri();
        String name();
        boolean isDirectory();

        default String mimeType() {
            if (isDirectory()) return "inode/directory";
            String name = name();
            if (name == null) return "text/plain";
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) return "text/plain";
            String ext = name.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "md":  return "text/markdown";
                case "txt": return "text/plain";
                case "json": return "application/json";
                case "yaml": case "yml": return "text/yaml";
                case "xml": return "application/xml";
                case "sh": return "text/x-shellscript";
                case "py": return "text/x-python";
                case "java": return "text/x-java";
                case "js": return "application/javascript";
                case "css": return "text/css";
                case "html": return "text/html";
                case "png": return "image/png";
                case "jpg": case "jpeg": return "image/jpeg";
                case "gif": return "image/gif";
                case "svg": return "image/svg+xml";
                case "pdf": return "application/pdf";
                case "zip": return "application/zip";
                case "tar": return "application/x-tar";
                case "gz": case "gzip": return "application/gzip";
                default: return "application/octet-stream";
            }
        }
    }

    interface PaginatedSkillsExtension {
        DirectoryPage listDirectoryPage(String directoryUri, String cursor, int limit);
    }
}
